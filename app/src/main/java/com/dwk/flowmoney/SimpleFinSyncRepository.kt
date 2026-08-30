package com.dwk.flowmoney

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal const val MIN_AUTOMATIC_SYNCS_PER_DAY = 1
internal const val MAX_AUTOMATIC_SYNCS_PER_DAY = 12
internal val SIMPLEFIN_RETRY_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(2)
internal val SIMPLEFIN_SYNC_WINDOW_SECONDS = TimeUnit.DAYS.toSeconds(45)
internal val SIMPLEFIN_REQUEST_WINDOW_SECONDS =
    SIMPLEFIN_SYNC_WINDOW_SECONDS - TimeUnit.HOURS.toSeconds(1)

internal fun isValidAutomaticSyncsPerDay(count: Int) = count in MIN_AUTOMATIC_SYNCS_PER_DAY..MAX_AUTOMATIC_SYNCS_PER_DAY

internal fun automaticSyncIntervalMillis(count: Int): Long {
    require(isValidAutomaticSyncsPerDay(count))
    val day = TimeUnit.DAYS.toMillis(1)
    return (day + count - 1) / count
}

internal fun isSimpleFinSyncEligible(
    profile: SimpleFinProfileEntity,
    time: Long,
): Boolean {
    val attempt = profile.lastSyncAttemptAtEpochMillis
    val success = profile.lastSuccessfulSyncAtEpochMillis
    val lastAttemptSucceeded = success != null && (attempt == null || success >= attempt)
    val anchor = if (lastAttemptSucceeded) success else attempt ?: return true
    if (anchor > time) return false
    val age =
        try {
            Math.subtractExact(time, anchor)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    val interval =
        if (lastAttemptSucceeded) {
            automaticSyncIntervalMillis(profile.automaticSyncsPerDay)
        } else {
            SIMPLEFIN_RETRY_INTERVAL_MILLIS
        }
    return age >= interval
}

internal class SimpleFinAutomaticSchedulingException(
    cause: Throwable,
) : Exception(
        "Automatic sync frequency was saved, but scheduling could not be updated",
        cause,
    )

internal class SimpleFinSyncFunctions(
    val claim: suspend (String) -> String,
    val accounts: suspend (String, Long, Long) -> SimpleFinAccountsResult,
    val saveCredential: suspend (String, String) -> Unit,
    val readCredential: suspend (String) -> String?,
    val deleteCredential: suspend () -> Unit,
    val stagePendingCredential: suspend (String, String) -> Unit,
    val readPendingCredential: suspend () -> SimpleFinPendingCredential?,
    val promotePendingCredential: suspend (String, String?, String?) -> Unit,
    val restoreRollbackCredential: suspend (String) -> Boolean,
    val deletePendingCredential: suspend () -> Unit,
    val deleteRollbackCredential: suspend () -> Unit,
    val scheduleWork: suspend (Int) -> Unit,
    val cancelWork: suspend () -> Unit,
    val now: () -> Long,
    val autoCategorize: suspend () -> Unit = {},
)

class SimpleFinSyncRepository internal constructor(
    private val db: FlowMoneyDatabase,
    private val functions: SimpleFinSyncFunctions,
) {
    constructor(context: Context) : this(FlowMoneyDatabase.get(context), productionFunctions(context))

    private val mutablePendingConnectionState = MutableStateFlow(SimpleFinPendingConnectionState.UNKNOWN)

    val profile = db.simpleFinDao().observeProfile()
    val pendingConnectionState: StateFlow<SimpleFinPendingConnectionState> = mutablePendingConnectionState.asStateFlow()

    suspend fun recoverPendingConnection(): SimpleFinPendingConnectionState =
        lifecycleMutex.withLock {
            try {
                val pending = functions.readPendingCredential()
                val publishedProfile = db.simpleFinDao().getProfile()
                if (pending == null) {
                    publishedProfile?.let { restorePublishedCredential(it) }
                    functions.deleteRollbackCredential()
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
                } else if (publishedProfile?.connectionId == pending.connectionId) {
                    // Publication completed before a crash; only recovery artifacts remain.
                    deletePublishedConnectionRecoveryArtifacts()
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
                } else {
                    // Promotion can overwrite the active slot before Room publishes the new identity.
                    publishedProfile?.let { restorePublishedCredential(it) }
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.RETRY_AVAILABLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutablePendingConnectionState.value = SimpleFinPendingConnectionState.UNKNOWN
            }
            mutablePendingConnectionState.value
        }

    suspend fun connect(setupToken: String): SimpleFinSyncResult {
        val connectionId = UUID.randomUUID().toString()
        beginConnection(connectionId)?.let { return it }
        return try {
            val accessUrl =
                try {
                    functions.claim(setupToken).also(SimpleFinClient::validateAccessUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    return failure.toConnectFailure(pendingRetryAvailable = false)
                }

            val previous =
                try {
                    lifecycleMutex.withLock {
                        check(activeConnectionId == connectionId) { "SimpleFIN connection was cancelled" }
                        val snapshot = previousConnection()
                        functions.stagePendingCredential(connectionId, accessUrl)
                        mutablePendingConnectionState.value = SimpleFinPendingConnectionState.RETRY_AVAILABLE
                        snapshot
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    return failure.toConnectFailure()
                }
            finishPendingConnection(connectionId, accessUrl, previous).alsoAutoCategorize()
        } finally {
            clearActiveConnection(connectionId)
        }
    }

    suspend fun retryPendingConnection(): SimpleFinSyncResult {
        var pending: SimpleFinPendingCredential? = null
        var previous: PreviousConnection? = null
        val beginFailure =
            lifecycleMutex.withLock {
                if (activeConnectionId != null) return@withLock connectionInProgressFailure()
                pending =
                    try {
                        functions.readPendingCredential()
                    } catch (failure: Throwable) {
                        return@withLock failure.toConnectFailure()
                    }
                if (pending == null) {
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
                    return@withLock noPendingConnectionFailure()
                }
                activeConnectionId = pending!!.connectionId
                mutablePendingConnectionState.value = SimpleFinPendingConnectionState.RETRY_AVAILABLE
                previous =
                    try {
                        previousConnection()
                    } catch (failure: Throwable) {
                        activeConnectionId = null
                        return@withLock failure.toConnectFailure(pendingRetryAvailable = true)
                    }
                null
            }
        beginFailure?.let { return it }

        val staged = checkNotNull(pending)
        return try {
            try {
                SimpleFinClient.validateAccessUrl(staged.accessUrl)
            } catch (failure: Throwable) {
                deleteRejectedPendingConnection(staged.connectionId)
                return failure.toConnectFailure()
            }
            finishPendingConnection(staged.connectionId, staged.accessUrl, checkNotNull(previous)).alsoAutoCategorize()
        } finally {
            clearActiveConnection(staged.connectionId)
        }
    }

    suspend fun cancelPendingConnection() {
        lifecycleMutex.withLock {
            withContext(NonCancellable) {
                activeConnectionId = null
                val pending = functions.readPendingCredential()
                val publishedProfile = db.simpleFinDao().getProfile()
                when {
                    publishedProfile == null -> {
                        // A first-time promotion may have reached the active slot before publication.
                        functions.deleteCredential()
                        functions.cancelWork()
                    }

                    pending == null || publishedProfile.connectionId != pending.connectionId -> {
                        restorePublishedCredential(publishedProfile)
                    }
                }
                deleteCanceledConnectionRecoveryArtifacts()
                mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
            }
        }
    }

    suspend fun startOverPendingConnection() {
        cancelPendingConnection()
    }

    suspend fun syncNow(): SimpleFinSyncResult = syncMutex.withLock { syncNowLocked(bypassEligibility = false) }

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
            val start = end - SIMPLEFIN_REQUEST_WINDOW_SECONDS
            val result = functions.accounts(accessUrl, start, end)
            val providerErrors = partitionProviderErrors(result.errors)
            if (providerErrors.fatal.isNotEmpty()) throw SimpleFinException("SimpleFIN response reported account errors")
            val origin = SimpleFinServerOrigin.fromAccessUrl(accessUrl)
            val mapped = SimpleFinMapper.map(origin, result.accounts)
            val payloadOccurrences = mapped.payloadOccurrences()
            val error =
                (
                    mapped.warnings +
                        listOfNotNull(
                            nonUsdMessage(result.accounts),
                            joinAdvisoryErrors(providerErrors.advisory).ifBlank { null },
                        )
                ).joinToString("; ")
                    .ifBlank { null }
            var writeResult = SyncedTransactionWriteResult(0, 0, 0)
            db.withTransaction {
                val identityDao = db.simpleFinIdentityDao()
                val reconciliationRequired = identityDao.isReconciliationComplete() != true
                if (reconciliationRequired) {
                    SimpleFinIdentityReconciler.reconcile(identityDao, origin, payloadOccurrences)
                }
                val transactionDao = db.transactionDao()
                writeResult =
                    transactionDao.upsertSyncedTransactionsIgnoringTombstones(
                        transactions = mapped.transactions,
                        merchantRules = transactionDao.getMerchantRules(),
                        reviewedAtEpochMillis = time,
                    )
                dao.upsertAccounts(mapped.accounts)
                check(dao.recordSuccess(profile.connectionId, now(), error) == 1) {
                    "SimpleFIN connection changed during sync"
                }
                if (reconciliationRequired) {
                    identityDao.upsertState(SimpleFinIdentityStateEntity(reconciliationComplete = true))
                }
            }
            autoCategorizeAfterCommit()
            SimpleFinSyncResult.Success(writeResult.inserted, writeResult.updated, writeResult.skipped)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val result = failure.toSyncFailure()
            if (failure.failureKind() == SimpleFinFailureKind.AUTHENTICATION) {
                markReconnectRequired(profile.connectionId)
            } else if (dao.recordFailure(profile.connectionId, result.message) != 1) {
                return result
            }
            result
        }
    }

    suspend fun syncIfStale(): SimpleFinSyncResult = syncMutex.withLock { syncNowLocked(bypassEligibility = false) }

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
                activeConnectionId = null
                db.withTransaction {
                    db.simpleFinDao().clearProfile()
                    db.simpleFinDao().clearAccounts()
                }
                functions.deleteCredential()
                functions.deleteRollbackCredential()
                functions.deletePendingCredential()
                mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
                functions.cancelWork()
            }
        }
    }

    private suspend fun beginConnection(connectionId: String): SimpleFinSyncResult.Failure? =
        lifecycleMutex.withLock {
            if (activeConnectionId != null) return@withLock connectionInProgressFailure()
            val pending =
                try {
                    functions.readPendingCredential()
                } catch (failure: Throwable) {
                    return@withLock failure.toConnectFailure()
                }
            if (pending != null) {
                mutablePendingConnectionState.value = SimpleFinPendingConnectionState.RETRY_AVAILABLE
                return@withLock pendingConnectionExistsFailure()
            }
            activeConnectionId = connectionId
            mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
            null
        }

    private suspend fun finishPendingConnection(
        connectionId: String,
        accessUrl: String,
        previous: PreviousConnection,
    ): SimpleFinSyncResult =
        try {
            val fetchedAt = now()
            val end = TimeUnit.MILLISECONDS.toSeconds(fetchedAt)
            val initial = functions.accounts(accessUrl, end - SIMPLEFIN_REQUEST_WINDOW_SECONDS, end)
            val providerErrors = partitionProviderErrors(initial.errors)
            if (providerErrors.fatal.isNotEmpty()) throw SimpleFinException("SimpleFIN response reported account errors")
            val origin = SimpleFinServerOrigin.fromAccessUrl(accessUrl)
            val mapped = SimpleFinMapper.map(origin, initial.accounts)
            val payloadOccurrences = mapped.payloadOccurrences()
            val warning =
                (
                    mapped.warnings +
                        listOfNotNull(
                            nonUsdMessage(initial.accounts),
                            joinAdvisoryErrors(providerErrors.advisory).ifBlank { null },
                        )
                ).joinToString("; ")
                    .ifBlank { null }
            var writeResult = SyncedTransactionWriteResult(0, 0, 0)
            lifecycleMutex.withLock {
                check(activeConnectionId == connectionId) { "SimpleFIN connection was cancelled" }
                val staged = functions.readPendingCredential()
                check(staged?.connectionId == connectionId && staged.accessUrl == accessUrl) {
                    "SimpleFIN pending credential changed"
                }
                functions.promotePendingCredential(
                    connectionId,
                    previous.profile?.connectionId,
                    previous.credential,
                )
                check(functions.readCredential(connectionId) == accessUrl) {
                    "SimpleFIN credential promotion failed verification"
                }
                val profile =
                    SimpleFinProfileEntity(
                        connectionId = connectionId,
                        connectedAtEpochMillis = fetchedAt,
                        lastSyncAttemptAtEpochMillis = fetchedAt,
                        lastSuccessfulSyncAtEpochMillis = fetchedAt,
                        lastError = warning,
                    )
                functions.scheduleWork(profile.automaticSyncsPerDay)
                db.withTransaction {
                    val identityDao = db.simpleFinIdentityDao()
                    val reconciliationRequired = identityDao.isReconciliationComplete() != true
                    if (reconciliationRequired) {
                        SimpleFinIdentityReconciler.reconcile(identityDao, origin, payloadOccurrences)
                    }
                    db.simpleFinDao().clearProfile()
                    db.simpleFinDao().clearAccounts()
                    val transactionDao = db.transactionDao()
                    writeResult =
                        transactionDao.upsertSyncedTransactionsIgnoringTombstones(
                            transactions = mapped.transactions,
                            merchantRules = transactionDao.getMerchantRules(),
                            reviewedAtEpochMillis = fetchedAt,
                        )
                    db.simpleFinDao().upsertAccounts(mapped.accounts)
                    db.simpleFinDao().upsertProfile(profile)
                    if (reconciliationRequired) {
                        identityDao.upsertState(SimpleFinIdentityStateEntity(reconciliationComplete = true))
                    }
                }

                // Publication is complete. A cleanup failure is recovered on the next repository start.
                try {
                    deletePublishedConnectionRecoveryArtifacts()
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.NONE
                } catch (_: Throwable) {
                    mutablePendingConnectionState.value = SimpleFinPendingConnectionState.UNKNOWN
                }
            }
            SimpleFinSyncResult.Success(writeResult.inserted, writeResult.updated, writeResult.skipped)
        } catch (cancelled: CancellationException) {
            compensateConnect(connectionId, previous)
            throw cancelled
        } catch (failure: Throwable) {
            compensateConnect(connectionId, previous)
            if (failure.failureKind() == SimpleFinFailureKind.AUTHENTICATION) {
                deleteRejectedPendingConnection(connectionId)
            }
            failure.toConnectFailure(pendingRetryAvailable = true)
        }

    private suspend fun previousConnection(): PreviousConnection {
        val oldProfile = db.simpleFinDao().getProfile()
        return PreviousConnection(
            profile = oldProfile,
            accounts = db.simpleFinDao().observeAccounts().first(),
            credential = oldProfile?.let { functions.readCredential(it.connectionId) },
        )
    }

    private suspend fun readCredential(connectionId: String): String {
        val accessUrl =
            try {
                functions.readCredential(connectionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        if (accessUrl.isNullOrBlank()) throw SimpleFinReconnectException()
        return try {
            SimpleFinClient.validateAccessUrl(accessUrl)
            accessUrl
        } catch (_: Throwable) {
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

    private suspend fun compensateConnect(
        connectionId: String,
        previous: PreviousConnection,
    ) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (activeConnectionId != connectionId) return@withLock
            val stateRestored =
                runCatching {
                    db.withTransaction {
                        db.simpleFinDao().clearProfile()
                        db.simpleFinDao().clearAccounts()
                        previous.profile?.let { db.simpleFinDao().upsertProfile(it) }
                        previous.accounts.takeIf { it.isNotEmpty() }?.let { db.simpleFinDao().upsertAccounts(it) }
                        true
                    }
                }.getOrDefault(false)
            if (!stateRestored) return@withLock
            runCatching { functions.deleteCredential() }
            if (previous.profile != null && previous.credential != null) {
                runCatching { functions.saveCredential(previous.profile.connectionId, previous.credential) }
                runCatching { functions.scheduleWork(previous.profile.automaticSyncsPerDay) }
            } else {
                runCatching { functions.cancelWork() }
            }
            mutablePendingConnectionState.value =
                if (runCatching { functions.readPendingCredential() }.getOrNull() == null) {
                    SimpleFinPendingConnectionState.NONE
                } else {
                    SimpleFinPendingConnectionState.RETRY_AVAILABLE
                }
        }
    }

    private suspend fun deleteRejectedPendingConnection(connectionId: String) =
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                val pending = runCatching { functions.readPendingCredential() }.getOrNull()
                if (pending?.connectionId != connectionId) return@withLock
                val cleaned =
                    runCatching {
                        db.simpleFinDao().getProfile()?.let { publishedProfile ->
                            if (publishedProfile.connectionId != connectionId) {
                                restorePublishedCredential(publishedProfile)
                            }
                        }
                        deleteCanceledConnectionRecoveryArtifacts()
                    }.isSuccess
                mutablePendingConnectionState.value =
                    if (cleaned) SimpleFinPendingConnectionState.NONE else SimpleFinPendingConnectionState.UNKNOWN
            }
        }

    private suspend fun restorePublishedCredential(profile: SimpleFinProfileEntity) {
        if (functions.restoreRollbackCredential(profile.connectionId)) {
            functions.scheduleWork(profile.automaticSyncsPerDay)
        }
    }

    private suspend fun deletePublishedConnectionRecoveryArtifacts() {
        // Delete rollback first so a pending record always remains if success cleanup is interrupted.
        functions.deleteRollbackCredential()
        functions.deletePendingCredential()
    }

    private suspend fun deleteCanceledConnectionRecoveryArtifacts() {
        // The published credential is already restored, so remove the rejected stage before its backup.
        functions.deletePendingCredential()
        functions.deleteRollbackCredential()
    }

    private suspend fun clearActiveConnection(connectionId: String) =
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (activeConnectionId == connectionId) activeConnectionId = null
            }
        }

    private data class PreviousConnection(
        val profile: SimpleFinProfileEntity?,
        val accounts: List<SimpleFinAccountEntity>,
        val credential: String?,
    )

    private fun nonUsdMessage(accounts: List<SimpleFinAccount>): String? =
        accounts
            .filterNot { it.currency.equals("USD", ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString { "Skipped ${it.name}: ${it.currency ?: "missing currency"}" }

    private fun Throwable.toConnectFailure(pendingRetryAvailable: Boolean = false): SimpleFinSyncResult.Failure {
        val classified = classifyFailure()
        val message =
            when (classified.kind) {
                SimpleFinFailureKind.MALFORMED_TOKEN -> {
                    "This SimpleFIN setup token is invalid. Create a new setup token and try again."
                }

                SimpleFinFailureKind.AUTHENTICATION -> {
                    "SimpleFIN rejected this connection. Start over with a new setup token."
                }

                SimpleFinFailureKind.TIMEOUT -> {
                    if (pendingRetryAvailable) {
                        "SimpleFIN timed out while connecting. Retry the pending connection."
                    } else {
                        "SimpleFIN timed out while claiming the setup token. Create a new setup token and try again."
                    }
                }

                SimpleFinFailureKind.NETWORK -> {
                    if (pendingRetryAvailable) {
                        "SimpleFIN could not be reached while connecting. Retry the pending connection."
                    } else {
                        "SimpleFIN could not claim the setup token. Create a new setup token and try again."
                    }
                }

                SimpleFinFailureKind.TLS -> {
                    "SimpleFIN could not establish a secure connection. Start over or try again later."
                }

                SimpleFinFailureKind.RATE_LIMIT -> {
                    if (pendingRetryAvailable) {
                        "SimpleFIN is temporarily limiting connections. Retry the pending connection later."
                    } else {
                        "SimpleFIN could not claim the setup token. Create a new setup token and try again later."
                    }
                }

                SimpleFinFailureKind.PROVIDER_5XX -> {
                    if (pendingRetryAvailable) {
                        "SimpleFIN is temporarily unavailable. Retry the pending connection."
                    } else {
                        "SimpleFIN could not claim the setup token. Create a new setup token and try again later."
                    }
                }

                SimpleFinFailureKind.PROTOCOL -> {
                    "SimpleFIN returned an invalid connection response. Start over or try again later."
                }

                SimpleFinFailureKind.UNKNOWN -> {
                    "SimpleFIN could not finish connecting. Start over or try again."
                }
            }
        return SimpleFinSyncResult.Failure(
            message = message,
            retryable = classified.retryable && pendingRetryAvailable,
            kind = classified.kind,
        )
    }

    private fun Throwable.toSyncFailure(): SimpleFinSyncResult.Failure {
        val classified = classifyFailure()
        val message =
            when (classified.kind) {
                SimpleFinFailureKind.MALFORMED_TOKEN -> "SimpleFIN credentials are invalid. Reconnect SimpleFIN."
                SimpleFinFailureKind.AUTHENTICATION -> "Reconnect SimpleFIN to sync."
                SimpleFinFailureKind.TIMEOUT -> "SimpleFIN sync timed out."
                SimpleFinFailureKind.NETWORK -> "SimpleFIN could not be reached."
                SimpleFinFailureKind.TLS -> "SimpleFIN could not establish a secure connection."
                SimpleFinFailureKind.RATE_LIMIT -> "SimpleFIN is temporarily limiting sync requests."
                SimpleFinFailureKind.PROVIDER_5XX -> "SimpleFIN is temporarily unavailable."
                SimpleFinFailureKind.PROTOCOL -> "SimpleFIN returned an invalid sync response."
                SimpleFinFailureKind.UNKNOWN -> "SimpleFIN sync failed."
            }
        return SimpleFinSyncResult.Failure(message, classified.retryable, classified.kind)
    }

    private fun Throwable.failureKind(): SimpleFinFailureKind = classifyFailure().kind

    private fun Throwable.classifyFailure(): ClassifiedFailure =
        when (this) {
            is SimpleFinException -> ClassifiedFailure(kind, retryable)
            is SocketTimeoutException -> ClassifiedFailure(SimpleFinFailureKind.TIMEOUT, retryable = true)
            is SSLException -> ClassifiedFailure(SimpleFinFailureKind.TLS, retryable = false)
            is IOException -> ClassifiedFailure(SimpleFinFailureKind.NETWORK, retryable = true)
            else -> ClassifiedFailure(SimpleFinFailureKind.UNKNOWN, retryable = false)
        }

    private data class ClassifiedFailure(
        val kind: SimpleFinFailureKind,
        val retryable: Boolean,
    )

    private fun connectionInProgressFailure() =
        SimpleFinSyncResult.Failure(
            message = "A SimpleFIN connection is already in progress.",
            kind = SimpleFinFailureKind.PROTOCOL,
        )

    private fun pendingConnectionExistsFailure() =
        SimpleFinSyncResult.Failure(
            message = "A SimpleFIN connection is waiting to retry. Retry it or start over.",
            kind = SimpleFinFailureKind.PROTOCOL,
        )

    private fun noPendingConnectionFailure() =
        SimpleFinSyncResult.Failure(
            message = "There is no pending SimpleFIN connection to retry.",
            kind = SimpleFinFailureKind.PROTOCOL,
        )

    private suspend fun autoCategorizeAfterCommit() {
        try {
            functions.autoCategorize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The sync is already committed; categorization retries on the next sync.
        }
    }

    private suspend fun SimpleFinSyncResult.alsoAutoCategorize(): SimpleFinSyncResult =
        also { if (it is SimpleFinSyncResult.Success) autoCategorizeAfterCommit() }

    private fun now() = functions.now()

    private fun SimpleFinMappingResult.payloadOccurrences(): List<SimpleFinPayloadOccurrence> =
        transactions.map { transaction ->
            SimpleFinPayloadOccurrence(
                transactionId = transaction.id,
                occurredAtEpochMillis = transaction.occurredAtEpochMillis,
            )
        }

    private companion object {
        val lifecycleMutex = Mutex()
        val syncMutex = Mutex()
        var activeConnectionId: String? = null

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
                stagePendingCredential = { connectionId, accessUrl ->
                    withContext(Dispatchers.IO) { credentialStore.stage(connectionId, accessUrl) }
                },
                readPendingCredential = {
                    withContext(Dispatchers.IO) { credentialStore.readPending() }
                },
                promotePendingCredential = { connectionId, previousConnectionId, previousAccessUrl ->
                    withContext(Dispatchers.IO) {
                        credentialStore.promotePending(connectionId, previousConnectionId, previousAccessUrl)
                    }
                },
                restoreRollbackCredential = { connectionId ->
                    withContext(Dispatchers.IO) { credentialStore.restoreRollback(connectionId) }
                },
                deletePendingCredential = {
                    withContext(Dispatchers.IO) { credentialStore.deletePending() }
                },
                deleteRollbackCredential = {
                    withContext(Dispatchers.IO) { credentialStore.deleteRollback() }
                },
                scheduleWork = { SimpleFinSyncWorker.schedule(appContext, it) },
                cancelWork = { SimpleFinSyncWorker.cancel(appContext) },
                now = System::currentTimeMillis,
                autoCategorize = { GeminiAutoCategorizer(appContext).categorizeOneChunk() },
            )
        }
    }
}
