package com.dwk.flowmoney

import android.content.Context
import androidx.room.withTransaction
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal const val MIN_AUTOMATIC_SYNCS_PER_DAY = 1
internal const val MAX_AUTOMATIC_SYNCS_PER_DAY = 12
internal val SIMPLEFIN_RETRY_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(2)

internal fun isValidAutomaticSyncsPerDay(count: Int) = count in MIN_AUTOMATIC_SYNCS_PER_DAY..MAX_AUTOMATIC_SYNCS_PER_DAY

internal fun automaticSyncIntervalMillis(count: Int): Long {
    require(isValidAutomaticSyncsPerDay(count))
    val day = TimeUnit.DAYS.toMillis(1)
    return (day + count - 1) / count
}

internal fun isSimpleFinSyncEligible(profile: SimpleFinProfileEntity, time: Long): Boolean {
    val attempt = profile.lastSyncAttemptAtEpochMillis
    val success = profile.lastSuccessfulSyncAtEpochMillis
    val lastAttemptSucceeded = success != null && (attempt == null || success >= attempt)
    val anchor = if (lastAttemptSucceeded) success else attempt ?: return true
    if (anchor > time) return false
    val age = try {
        Math.subtractExact(time, anchor)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    val interval = if (lastAttemptSucceeded) {
        automaticSyncIntervalMillis(profile.automaticSyncsPerDay)
    } else {
        SIMPLEFIN_RETRY_INTERVAL_MILLIS
    }
    return age >= interval
}

internal class SimpleFinAutomaticSchedulingException(cause: Throwable) : Exception(
    "Automatic sync frequency was saved, but scheduling could not be updated",
    cause,
)

internal class SimpleFinSyncFunctions(
    val claim: suspend (String) -> String,
    val accounts: suspend (String, Long, Long) -> SimpleFinAccountsResult,
    val saveCredential: suspend (String, String) -> Unit,
    val readCredential: suspend (String) -> String?,
    val deleteCredential: suspend () -> Unit,
    val scheduleWork: suspend (Int) -> Unit,
    val cancelWork: suspend () -> Unit,
    val now: () -> Long,
)

class SimpleFinSyncRepository internal constructor(
    private val db: FlowMoneyDatabase,
    private val functions: SimpleFinSyncFunctions,
) {
    constructor(context: Context) : this(FlowMoneyDatabase.get(context), productionFunctions(context))

    val profile = db.simpleFinDao().observeProfile()

    suspend fun connect(setupToken: String): SimpleFinSyncResult {
        val connectionId = UUID.randomUUID().toString()
        var previous: PreviousConnection? = null
        lifecycleMutex.withLock { pendingConnectionId = connectionId }
        return try {
            val accessUrl = try {
                functions.claim(setupToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return SimpleFinSyncResult.Failure(e.userMessage(), e.retryable())
            }
            try {
                SimpleFinClient.validateAccessUrl(accessUrl)
                lifecycleMutex.withLock {
                    check(pendingConnectionId == connectionId) { "SimpleFIN connection was cancelled" }
                    val oldProfile = db.simpleFinDao().getProfile()
                    previous = PreviousConnection(
                        oldProfile,
                        db.simpleFinDao().observeAccounts().first(),
                        oldProfile?.let { functions.readCredential(it.connectionId) },
                    )
                }
                val fetchedAt = now()
                val end = TimeUnit.MILLISECONDS.toSeconds(fetchedAt)
                val initial = functions.accounts(accessUrl, end - TimeUnit.DAYS.toSeconds(90), end)
                if (initial.errors.isNotEmpty()) throw SimpleFinException(initial.errors.joinToString("; "))
                val mapped = SimpleFinMapper.map(connectionId, initial.accounts)
                val warning = (mapped.warnings + listOfNotNull(nonUsdMessage(initial.accounts)))
                    .joinToString("; ")
                    .ifBlank { null }
                var writeResult = SyncedTransactionWriteResult(0, 0, 0)
                lifecycleMutex.withLock {
                    check(pendingConnectionId == connectionId) { "SimpleFIN connection was cancelled" }
                    functions.saveCredential(connectionId, accessUrl)
                    check(functions.readCredential(connectionId) == accessUrl) { "SimpleFIN credential save failed verification" }
                    val profile = SimpleFinProfileEntity(
                        connectionId = connectionId,
                        connectedAtEpochMillis = fetchedAt,
                        lastSyncAttemptAtEpochMillis = fetchedAt,
                        lastSuccessfulSyncAtEpochMillis = fetchedAt,
                        lastError = warning,
                    )
                    functions.scheduleWork(profile.automaticSyncsPerDay)
                    db.withTransaction {
                        db.simpleFinDao().clearProfile()
                        db.simpleFinDao().clearAccounts()
                        writeResult = db.transactionDao().upsertSyncedTransactionsIgnoringTombstones(mapped.transactions)
                        db.simpleFinDao().upsertAccounts(mapped.accounts)
                        db.simpleFinDao().upsertProfile(profile)
                    }
                }
                SimpleFinSyncResult.Success(writeResult.inserted, writeResult.updated, writeResult.skipped)
            } catch (e: CancellationException) {
                compensateConnect(connectionId, previous)
                throw e
            } catch (e: Throwable) {
                compensateConnect(connectionId, previous)
                SimpleFinSyncResult.Failure(e.userMessage(), e.retryable())
            }
        } finally {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (pendingConnectionId == connectionId) pendingConnectionId = null
                }
            }
        }
    }

    suspend fun syncNow(): SimpleFinSyncResult {
        return syncMutex.withLock { syncNowLocked(bypassEligibility = false) }
    }

    private suspend fun syncNowLocked(bypassEligibility: Boolean): SimpleFinSyncResult {
        val dao = db.simpleFinDao()
        val profile = dao.getProfile() ?: return SimpleFinSyncResult.Failure("SimpleFIN is not connected")
        if (profile.isPaused) return SimpleFinSyncResult.Failure("SimpleFIN is not connected")
        val time = now()
        if (!bypassEligibility && !isSimpleFinSyncEligible(profile, time)) {
            return SimpleFinSyncResult.Throttled
        }
        if (dao.recordAttempt(profile.connectionId, time) != 1) {
            return SimpleFinSyncResult.Failure("SimpleFIN is not connected")
        }
        return try {
            val accessUrl = readCredential(profile.connectionId)
            val end = TimeUnit.MILLISECONDS.toSeconds(time)
            val start = end - TimeUnit.DAYS.toSeconds(90)
            val result = functions.accounts(accessUrl, start, end)
            if (result.errors.isNotEmpty()) throw SimpleFinException(result.errors.joinToString("; "))
            val mapped = SimpleFinMapper.map(profile.connectionId, result.accounts)
            val error = (mapped.warnings + listOfNotNull(nonUsdMessage(result.accounts)))
                .joinToString("; ")
                .ifBlank { null }
            var writeResult = SyncedTransactionWriteResult(0, 0, 0)
            db.withTransaction {
                writeResult = db.transactionDao().upsertSyncedTransactionsIgnoringTombstones(mapped.transactions)
                dao.upsertAccounts(mapped.accounts)
                check(dao.recordSuccess(profile.connectionId, now(), error) == 1) { "SimpleFIN connection changed during sync" }
            }
            SimpleFinSyncResult.Success(writeResult.inserted, writeResult.updated, writeResult.skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val failure = SimpleFinSyncResult.Failure(e.userMessage(), e.retryable())
            if (e is SimpleFinReconnectException) {
                markReconnectRequired(profile.connectionId)
            } else {
                if (dao.recordFailure(profile.connectionId, e.userMessage()) != 1) return failure
            }
            failure
        }
    }

    suspend fun syncIfStale(): SimpleFinSyncResult {
        return syncMutex.withLock { syncNowLocked(bypassEligibility = false) }
    }

    suspend fun updateAutomaticSyncsPerDay(count: Int) {
        require(isValidAutomaticSyncsPerDay(count)) { "Automatic syncs per day must be between 1 and 12" }
        lifecycleMutex.withLock {
            val dao = db.simpleFinDao()
            val profile = dao.getProfile() ?: error("SimpleFIN is not connected")
            check(dao.updateAutomaticSyncsPerDay(profile.connectionId, count) == 1) {
                "SimpleFIN connection changed while updating automatic syncs"
            }
            try {
                functions.scheduleWork(count)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                throw SimpleFinAutomaticSchedulingException(failure)
            }
        }
    }

    suspend fun disconnect() {
        lifecycleMutex.withLock {
            withContext(NonCancellable) {
                pendingConnectionId = null
                db.withTransaction {
                    db.simpleFinDao().clearProfile()
                    db.simpleFinDao().clearAccounts()
                }
                functions.deleteCredential()
                functions.cancelWork()
            }
        }
    }

    private suspend fun readCredential(connectionId: String): String {
        val accessUrl = try {
            functions.readCredential(connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (accessUrl.isNullOrBlank()) {
            throw SimpleFinReconnectException()
        }
        return runCatching {
            SimpleFinClient.validateAccessUrl(accessUrl)
            accessUrl
        }.getOrElse {
            throw SimpleFinReconnectException()
        }
    }

    private suspend fun markReconnectRequired(connectionId: String) {
        lifecycleMutex.withLock {
            if (db.simpleFinDao().pauseForReconnect(connectionId) != 1) return
            functions.deleteCredential()
            functions.cancelWork()
        }
    }

    private suspend fun compensateConnect(connectionId: String, previous: PreviousConnection?) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (pendingConnectionId != connectionId) return@withLock
            val stateRestored = runCatching {
                db.withTransaction {
                    db.simpleFinDao().clearProfile()
                    db.simpleFinDao().clearAccounts()
                    previous?.profile?.let { db.simpleFinDao().upsertProfile(it) }
                    previous?.accounts?.takeIf { it.isNotEmpty() }?.let { db.simpleFinDao().upsertAccounts(it) }
                    true
                }
            }.getOrDefault(false)
            if (!stateRestored) return@withLock
            runCatching { functions.deleteCredential() }
            if (previous?.profile != null && previous.credential != null) {
                runCatching { functions.saveCredential(previous.profile.connectionId, previous.credential) }
                runCatching { functions.scheduleWork(previous.profile.automaticSyncsPerDay) }
            } else {
                runCatching { functions.cancelWork() }
            }
        }
    }

    private data class PreviousConnection(
        val profile: SimpleFinProfileEntity?,
        val accounts: List<SimpleFinAccountEntity>,
        val credential: String?,
    )

    private fun nonUsdMessage(accounts: List<SimpleFinAccount>): String? =
        accounts.filterNot { it.currency.equals("USD", ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString { "Skipped ${it.name}: ${it.currency ?: "missing currency"}" }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleFinTransientException -> message ?: "SimpleFIN sync failed"
        is SimpleFinReconnectException -> message ?: "SimpleFIN reconnect required"
        is SimpleFinQuotaException -> message ?: "SimpleFIN payment or quota issue"
        is SimpleFinException -> message ?: "SimpleFIN error"
        else -> "SimpleFIN sync failed"
    }

    private fun Throwable.retryable(): Boolean = this is SimpleFinTransientException || this is IOException

    private fun now() = functions.now()

    private companion object {
        val lifecycleMutex = Mutex()
        val syncMutex = Mutex()
        var pendingConnectionId: String? = null

        fun productionFunctions(context: Context): SimpleFinSyncFunctions {
            val appContext = context.applicationContext
            val client = SimpleFinClient()
            val credentialStore = SimpleFinCredentialStore(appContext)
            return SimpleFinSyncFunctions(
                claim = client::claim,
                accounts = client::accounts,
                saveCredential = { connectionId, accessUrl ->
                    withContext(Dispatchers.IO) { credentialStore.save(connectionId, accessUrl) }
                },
                readCredential = { connectionId ->
                    withContext(Dispatchers.IO) { credentialStore.read(connectionId) }
                },
                deleteCredential = { withContext(Dispatchers.IO) { credentialStore.delete() } },
                scheduleWork = { SimpleFinSyncWorker.schedule(appContext, it) },
                cancelWork = { SimpleFinSyncWorker.cancel(appContext) },
                now = System::currentTimeMillis,
            )
        }
    }
}
