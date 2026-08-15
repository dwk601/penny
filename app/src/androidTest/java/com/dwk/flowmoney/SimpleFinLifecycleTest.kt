package com.dwk.flowmoney

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

@RunWith(AndroidJUnit4::class)
class SimpleFinLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun connectPublishesDefaultCadenceAndImmediatelySyncsAccounts() =
        runBlocking {
            withRepository { db, fake, repository ->
                var accountRequests = 0
                fake.accounts = { _, _, _ ->
                    accountRequests++
                    SimpleFinAccountsResult(emptyList())
                }

                assertTrue(repository.connect("setup-token") is SimpleFinSyncResult.Success)

                assertEquals(1, db.simpleFinDao().getProfile()!!.automaticSyncsPerDay)
                assertEquals(1, accountRequests)
                assertEquals(listOf(1), fake.scheduledCounts)
            }
        }

    @Test
    fun claimIsConsumedOnceWhenInitialAccountsTimeoutAndPendingRetrySucceeds() =
        runBlocking {
            withRepository { _, fake, repository ->
                var claims = 0
                var accountRequests = 0
                fake.claim = { token ->
                    assertEquals("synthetic-setup-token", token)
                    claims++
                    NEW_URL
                }
                fake.accounts = { _, _, _ ->
                    accountRequests++
                    if (accountRequests == 1) {
                        throw SocketTimeoutException("secret timeout at $NEW_URL with provider-body")
                    }
                    SimpleFinAccountsResult(emptyList())
                }

                val first = repository.connect("synthetic-setup-token") as SimpleFinSyncResult.Failure

                assertEquals(SimpleFinFailureKind.TIMEOUT, first.kind)
                assertTrue(first.retryable)
                assertEquals("SimpleFIN timed out while connecting. Retry the pending connection.", first.message)
                assertFalse(first.message.contains("synthetic-setup-token"))
                assertFalse(first.message.contains(NEW_URL))
                assertFalse(first.message.contains("provider-body"))
                assertEquals(1, claims)
                assertTrue(fake.pendingCredential != null)
                assertEquals(SimpleFinPendingConnectionState.RETRY_AVAILABLE, repository.pendingConnectionState.value)

                assertTrue(repository.retryPendingConnection() is SimpleFinSyncResult.Success)
                assertEquals(1, claims)
                assertEquals(2, accountRequests)
                assertNull(fake.pendingCredential)
                assertTrue(fake.credential?.second == NEW_URL)
                assertEquals(SimpleFinPendingConnectionState.NONE, repository.pendingConnectionState.value)
            }
        }

    @Test
    fun retryPendingConnectionRequestsRecommendedWindow() =
        runBlocking {
            withRepository { _, fake, repository ->
                fake.currentTime = 1_700_000_000_000L
                fake.pendingCredential = SimpleFinPendingCredential("pending-window", NEW_URL)
                var requestedWindow: Pair<Long, Long>? = null
                fake.accounts = { _, start, end ->
                    requestedWindow = start to end
                    SimpleFinAccountsResult(emptyList())
                }

                assertTrue(repository.retryPendingConnection() is SimpleFinSyncResult.Success)

                val (start, end) = checkNotNull(requestedWindow)
                assertEquals(TimeUnit.MILLISECONDS.toSeconds(fake.currentTime), end)
                assertEquals(TimeUnit.DAYS.toSeconds(45), end - start)
            }
        }

    @Test
    fun ambiguousClaimFailureDoesNotReplayTokenOrOfferPendingRetry() =
        runBlocking {
            withRepository { _, fake, repository ->
                var claims = 0
                fake.claim = {
                    claims++
                    throw SocketTimeoutException("secret claim timeout for synthetic-one-use-token")
                }

                val result = repository.connect("synthetic-one-use-token") as SimpleFinSyncResult.Failure

                assertEquals(1, claims)
                assertEquals(SimpleFinFailureKind.TIMEOUT, result.kind)
                assertFalse(result.retryable)
                assertTrue(result.message.contains("Create a new setup token"))
                assertFalse(result.message.contains("synthetic-one-use-token"))
                assertNull(fake.pendingCredential)
                assertEquals(SimpleFinPendingConnectionState.NONE, repository.pendingConnectionState.value)
            }
        }

    @Test
    fun pendingConnectionRecoversAfterRepositoryRecreationWithoutReclaiming() =
        runBlocking {
            withRepository { db, fake, repository ->
                var claims = 0
                fake.claim = {
                    claims++
                    NEW_URL
                }
                fake.accounts = { _, _, _ -> throw SimpleFinTransientException("secret provider response") }

                val first = repository.connect("synthetic-restart-token") as SimpleFinSyncResult.Failure
                assertEquals(SimpleFinFailureKind.PROVIDER_5XX, first.kind)
                assertTrue(fake.pendingCredential != null)

                val recreated = SimpleFinSyncRepository(db, fake.bundle())
                assertEquals(SimpleFinPendingConnectionState.UNKNOWN, recreated.pendingConnectionState.value)
                assertEquals(SimpleFinPendingConnectionState.RETRY_AVAILABLE, recreated.recoverPendingConnection())
                fake.accounts = { _, _, _ -> SimpleFinAccountsResult(emptyList()) }

                assertTrue(recreated.retryPendingConnection() is SimpleFinSyncResult.Success)
                assertEquals(1, claims)
                assertNull(fake.pendingCredential)
            }
        }

    @Test
    fun promotedReplacementRecoversPublishedCredentialAcrossRepositoryRecreation() =
        runBlocking {
            withRepository { db, fake, _ ->
                val published = SimpleFinProfileEntity(connectionId = "published", automaticSyncsPerDay = 4)
                db.simpleFinDao().upsertProfile(published)
                fake.pendingCredential = SimpleFinPendingCredential("pending", NEW_URL)
                fake.rollbackCredential = "published" to OLD_URL
                fake.credential = "pending" to NEW_URL

                val recreated = SimpleFinSyncRepository(db, fake.bundle())
                assertEquals(SimpleFinPendingConnectionState.RETRY_AVAILABLE, recreated.recoverPendingConnection())
                assertEquals(published, db.simpleFinDao().getProfile())
                assertEquals("published" to OLD_URL, fake.credential)
                assertEquals(SimpleFinPendingCredential("pending", NEW_URL), fake.pendingCredential)
                assertEquals(listOf(4), fake.scheduledCounts)
                assertTrue(recreated.syncNow() is SimpleFinSyncResult.Success)

                fake.accounts = { _, _, _ -> throw SimpleFinTransientException("synthetic provider failure") }
                val retry = recreated.retryPendingConnection() as SimpleFinSyncResult.Failure

                assertEquals(SimpleFinFailureKind.PROVIDER_5XX, retry.kind)
                assertEquals("published", db.simpleFinDao().getProfile()!!.connectionId)
                assertEquals("published" to OLD_URL, fake.credential)
                assertEquals("published" to OLD_URL, fake.rollbackCredential)
                assertEquals(SimpleFinPendingCredential("pending", NEW_URL), fake.pendingCredential)
                assertEquals(SimpleFinPendingConnectionState.RETRY_AVAILABLE, recreated.pendingConnectionState.value)
            }
        }

    @Test
    fun viewModelRecoveryPublishesPendingStateAndRoutesRetryAndStartOver() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.pendingCredential = SimpleFinPendingCredential("pending-view-model", NEW_URL)
                val store = ViewModelStore()
                try {
                    withContext(Dispatchers.Main) {
                        val viewModel =
                            ViewModelProvider(
                                store,
                                MainViewModel.Factory(EmptyGateway(), repository, db.simpleFinDao().observeAccounts()),
                            )[MainViewModel::class.java]
                        viewModel.reportInitializationComplete()

                        val recovered = viewModel.uiState.first { !it.isLoading && it.simpleFin.isConnectionPending }
                        assertTrue(recovered.simpleFin.isConnectionPending)
                        assertTrue(viewModel.retryPendingSimpleFinConnection() is SimpleFinSyncResult.Success)
                        assertEquals(0, fake.claimCount)
                        assertFalse(
                            viewModel.uiState
                                .first { !it.simpleFin.isConnectionPending }
                                .simpleFin.isConnectionPending,
                        )

                        fake.accounts = { _, _, _ -> throw IOException("synthetic network failure") }
                        assertTrue(viewModel.connectSimpleFin("synthetic-new-token") is SimpleFinSyncResult.Failure)
                        assertTrue(
                            viewModel.uiState
                                .first { it.simpleFin.isConnectionPending }
                                .simpleFin.isConnectionPending,
                        )
                        assertEquals(1, fake.claimCount)

                        viewModel.cancelPendingSimpleFinConnection()
                        assertFalse(
                            viewModel.uiState
                                .first { !it.simpleFin.isConnectionPending }
                                .simpleFin.isConnectionPending,
                        )
                        assertNull(fake.pendingCredential)
                        assertNull(fake.rollbackCredential)
                    }
                } finally {
                    store.clear()
                }
            }
        }

    @Test
    fun authenticationRejectionAndExplicitStartOverDeleteStaging() =
        runBlocking {
            withRepository { _, fake, repository ->
                fake.accounts = { _, _, _ -> throw SimpleFinReconnectException() }

                val rejected = repository.connect("synthetic-rejected-token") as SimpleFinSyncResult.Failure

                assertEquals(SimpleFinFailureKind.AUTHENTICATION, rejected.kind)
                assertFalse(rejected.retryable)
                assertNull(fake.pendingCredential)
                assertEquals(SimpleFinPendingConnectionState.NONE, repository.pendingConnectionState.value)

                fake.accounts = { _, _, _ -> throw IOException("secret network failure at $NEW_URL") }
                val pending = repository.connect("synthetic-cancel-token") as SimpleFinSyncResult.Failure
                assertEquals(SimpleFinFailureKind.NETWORK, pending.kind)
                assertTrue(fake.pendingCredential != null)

                repository.startOverPendingConnection()

                assertNull(fake.pendingCredential)
                assertEquals(SimpleFinPendingConnectionState.NONE, repository.pendingConnectionState.value)
                assertTrue(fake.pendingDeleteCount >= 2)
            }
        }

    @Test
    fun automaticSyncCadenceValidatesRangeAndCeilingBoundaries() {
        assertFalse(isValidAutomaticSyncsPerDay(0))
        assertTrue(isValidAutomaticSyncsPerDay(1))
        assertTrue(isValidAutomaticSyncsPerDay(12))
        assertFalse(isValidAutomaticSyncsPerDay(13))
        assertEquals(TimeUnit.HOURS.toMillis(24), automaticSyncIntervalMillis(1))
        assertEquals(TimeUnit.HOURS.toMillis(2), automaticSyncIntervalMillis(12))
        assertEquals(12_342_858L, automaticSyncIntervalMillis(7))
    }

    @Test
    fun invalidAutomaticSyncPreferenceChangesAndSchedulesNothing() =
        runBlocking {
            withRepository { db, fake, repository ->
                val original = SimpleFinProfileEntity(connectionId = "current", automaticSyncsPerDay = 4)
                db.simpleFinDao().upsertProfile(original)

                assertTrue(runCatching { repository.updateAutomaticSyncsPerDay(0) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { repository.updateAutomaticSyncsPerDay(13) }.exceptionOrNull() is IllegalArgumentException)

                assertEquals(original, db.simpleFinDao().getProfile())
                assertTrue(fake.scheduledCounts.isEmpty())
            }
        }

    @Test
    fun automaticSyncPreferencePreservesProfileAndPersistsBeforeScheduling() =
        runBlocking {
            withRepository { db, fake, repository ->
                val original =
                    SimpleFinProfileEntity(
                        connectionId = "current",
                        connectedAtEpochMillis = 11,
                        lastSyncAttemptAtEpochMillis = 22,
                        lastSuccessfulSyncAtEpochMillis = 33,
                        lastError = "kept",
                        isPaused = true,
                        automaticSyncsPerDay = 2,
                    )
                db.simpleFinDao().upsertProfile(original)
                fake.schedule = { count ->
                    assertEquals(count, db.simpleFinDao().getProfile()!!.automaticSyncsPerDay)
                    fake.scheduledCounts += count
                }

                repository.updateAutomaticSyncsPerDay(7)

                assertEquals(original.copy(automaticSyncsPerDay = 7), db.simpleFinDao().getProfile())
                assertEquals(listOf(7), fake.scheduledCounts)
            }
        }

    @Test
    fun automaticSyncSchedulingFailureIsSurfacedAfterPersistence() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                fake.schedule = { throw IllegalStateException("schedule failed") }

                val failure = runCatching { repository.updateAutomaticSyncsPerDay(6) }.exceptionOrNull()

                assertTrue(failure is SimpleFinAutomaticSchedulingException)
                assertEquals("schedule failed", failure?.cause?.message)
                assertEquals(6, db.simpleFinDao().getProfile()!!.automaticSyncsPerDay)
            }
        }

    @Test
    fun disconnectedQueuedPreferenceUpdateDoesNotSchedule() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                val disconnectEntered = CompletableDeferred<Unit>()
                val releaseDisconnect = CompletableDeferred<Unit>()
                fake.beforeDeleteCredential = {
                    disconnectEntered.complete(Unit)
                    releaseDisconnect.await()
                }

                val disconnect = async(Dispatchers.Default) { repository.disconnect() }
                disconnectEntered.await()
                val update = async(Dispatchers.Default) { runCatching { repository.updateAutomaticSyncsPerDay(8) } }
                releaseDisconnect.complete(Unit)
                disconnect.await()

                assertTrue(update.await().isFailure)
                assertNull(db.simpleFinDao().getProfile())
                assertTrue(fake.scheduledCounts.isEmpty())
            }
        }

    @Test
    fun ordinarySyncPathsShareSuccessCadenceEligibility() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 1_000_000_000L
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(
                        connectionId = "current",
                        lastSyncAttemptAtEpochMillis = fake.currentTime,
                        lastSuccessfulSyncAtEpochMillis = fake.currentTime,
                    ),
                )
                fake.credential = "current" to OLD_URL
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    SimpleFinAccountsResult(emptyList())
                }

                assertEquals(SimpleFinSyncResult.Throttled, repository.syncNow())
                assertEquals(SimpleFinSyncResult.Throttled, repository.syncIfStale())
                assertEquals(
                    androidx.work.ListenableWorker.Result
                        .success()
                        .javaClass,
                    SimpleFinSyncWorker.runSync(repository).javaClass,
                )
                assertEquals(0, requests)
            }
        }

    @Test
    fun syncNowRequestsRecommendedWindow() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 1_700_000_000_000L
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current-window"))
                fake.credential = "current-window" to OLD_URL
                var requestedWindow: Pair<Long, Long>? = null
                fake.accounts = { _, start, end ->
                    requestedWindow = start to end
                    SimpleFinAccountsResult(emptyList())
                }

                assertTrue(repository.syncNow() is SimpleFinSyncResult.Success)

                val (start, end) = checkNotNull(requestedWindow)
                assertEquals(TimeUnit.MILLISECONDS.toSeconds(fake.currentTime), end)
                assertEquals(TimeUnit.DAYS.toSeconds(45), end - start)
            }
        }

    @Test
    fun successfulSyncBecomesEligibleAtConfiguredCadenceBoundary() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 2_000_000_000L
                val interval = automaticSyncIntervalMillis(12)
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(
                        connectionId = "current",
                        automaticSyncsPerDay = 12,
                        lastSyncAttemptAtEpochMillis = fake.currentTime - interval + 1,
                        lastSuccessfulSyncAtEpochMillis = fake.currentTime - interval + 1,
                    ),
                )
                fake.credential = "current" to OLD_URL
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    SimpleFinAccountsResult(emptyList())
                }

                assertEquals(SimpleFinSyncResult.Throttled, repository.syncNow())
                fake.currentTime++
                assertTrue(repository.syncNow() is SimpleFinSyncResult.Success)
                assertEquals(1, requests)
            }
        }

    @Test
    fun failedAttemptMayRetryAtTwoHourBoundary() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 3_000_000_000L
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(
                        connectionId = "current",
                        lastSyncAttemptAtEpochMillis = fake.currentTime - SIMPLEFIN_RETRY_INTERVAL_MILLIS + 1,
                        lastSuccessfulSyncAtEpochMillis = fake.currentTime - SIMPLEFIN_RETRY_INTERVAL_MILLIS - 1,
                    ),
                )
                fake.credential = "current" to OLD_URL
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    SimpleFinAccountsResult(emptyList())
                }

                assertEquals(SimpleFinSyncResult.Throttled, repository.syncIfStale())
                assertEquals(0, requests)
                fake.currentTime++
                assertTrue(repository.syncIfStale() is SimpleFinSyncResult.Success)
                assertEquals(1, requests)
            }
        }

    @Test
    fun futureSyncTimestampRemainsIneligible() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 4_000_000_000L
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(
                        connectionId = "current",
                        lastSyncAttemptAtEpochMillis = fake.currentTime + 1,
                    ),
                )

                assertEquals(SimpleFinSyncResult.Throttled, repository.syncNow())
            }
        }

    @Test
    fun workerRetriesOnlyNetworkTimeoutAndProvider5xxFailures() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 5_000_000_000L
                val cases =
                    listOf(
                        SocketTimeoutException("synthetic timeout") to true,
                        IOException("synthetic network failure") to true,
                        SimpleFinTransientException("synthetic provider failure") to true,
                        SimpleFinMalformedTokenException() to false,
                        SimpleFinReconnectException() to false,
                        SSLHandshakeException("synthetic TLS failure") to false,
                        SimpleFinRateLimitException() to false,
                        SimpleFinException("synthetic protocol failure") to false,
                        IllegalStateException("synthetic unknown failure") to false,
                    )

                cases.forEachIndexed { index, (failure, shouldRetry) ->
                    val connectionId = "worker-$index"
                    fake.currentTime += SIMPLEFIN_RETRY_INTERVAL_MILLIS
                    db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = connectionId))
                    fake.credential = connectionId to OLD_URL
                    fake.accounts = { _, _, _ -> throw failure }

                    val actual = SimpleFinSyncWorker.runSync(repository)
                    val expected =
                        if (shouldRetry) {
                            androidx.work.ListenableWorker.Result
                                .retry()
                        } else {
                            androidx.work.ListenableWorker.Result
                                .success()
                        }
                    assertEquals(expected.javaClass, actual.javaClass)
                }
            }
        }

    @Test
    fun syncFailuresHaveStableKindsRetryabilityAndRedactedMessages() =
        runBlocking {
            withRepository { db, fake, repository ->
                val cases =
                    listOf(
                        Triple(SimpleFinMalformedTokenException(), SimpleFinFailureKind.MALFORMED_TOKEN, false),
                        Triple(SimpleFinReconnectException(), SimpleFinFailureKind.AUTHENTICATION, false),
                        Triple(SocketTimeoutException("secret timeout $OLD_URL"), SimpleFinFailureKind.TIMEOUT, true),
                        Triple(IOException("secret network $OLD_URL"), SimpleFinFailureKind.NETWORK, true),
                        Triple(SSLHandshakeException("secret TLS $OLD_URL"), SimpleFinFailureKind.TLS, false),
                        Triple(SimpleFinRateLimitException(), SimpleFinFailureKind.RATE_LIMIT, false),
                        Triple(SimpleFinTransientException("secret 5xx body"), SimpleFinFailureKind.PROVIDER_5XX, true),
                        Triple(SimpleFinException("secret protocol body"), SimpleFinFailureKind.PROTOCOL, false),
                        Triple(IllegalStateException("secret unknown body"), SimpleFinFailureKind.UNKNOWN, false),
                    )

                cases.forEachIndexed { index, (failure, kind, retryable) ->
                    fake.currentTime += SIMPLEFIN_RETRY_INTERVAL_MILLIS
                    db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current-$index"))
                    fake.credential = "current-$index" to OLD_URL
                    fake.accounts = { _, _, _ -> throw failure }

                    val result = repository.syncNow() as SimpleFinSyncResult.Failure

                    assertEquals(kind, result.kind)
                    assertEquals(retryable, result.retryable)
                    assertFalse(result.message.contains("secret"))
                    assertFalse(result.message.contains(OLD_URL))
                    assertFalse(result.message.contains("body"))
                }
            }
        }

    @Test
    fun successfulWorkerSyncAwaitsWidgetRefreshOnlyWhenRowsWereWritten() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 5_500_000_000L
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                fake.credential = "current" to OLD_URL
                var refreshes = 0
                val refreshWidget: suspend () -> Unit = {
                    kotlinx.coroutines.yield()
                    refreshes++
                }

                fake.accounts = { _, _, _ -> SimpleFinAccountsResult(emptyList()) }
                SimpleFinSyncWorker.runSync(repository, refreshWidget)
                assertEquals(0, refreshes)

                fake.currentTime += automaticSyncIntervalMillis(1)
                fake.accounts = { _, _, _ ->
                    SimpleFinAccountsResult(listOf(account("checking", transactionId = "new-row")))
                }
                SimpleFinSyncWorker.runSync(repository, refreshWidget)
                assertEquals(1, refreshes)

                fake.currentTime += automaticSyncIntervalMillis(1)
                SimpleFinSyncWorker.runSync(repository, refreshWidget)
                assertEquals(2, refreshes)

                fake.currentTime += automaticSyncIntervalMillis(1)
                fake.accounts = { _, _, _ -> SimpleFinAccountsResult(emptyList()) }
                SimpleFinSyncWorker.runSync(repository, refreshWidget)
                assertEquals(2, refreshes)

                fake.currentTime += automaticSyncIntervalMillis(1)
                fake.accounts = { _, _, _ -> throw SimpleFinException("permanent") }
                SimpleFinSyncWorker.runSync(repository, refreshWidget)
                assertEquals(2, refreshes)
            }
        }

    @Test
    fun committedWorkerSyncSucceedsWhenWidgetRefreshThrows() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 5_600_000_000L
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                fake.credential = "current" to OLD_URL
                fake.accounts = { _, _, _ ->
                    SimpleFinAccountsResult(listOf(account("checking", transactionId = "committed-row")))
                }

                val result =
                    SimpleFinSyncWorker.runSync(repository) {
                        throw IllegalStateException("widget refresh failed")
                    }

                assertEquals(
                    androidx.work.ListenableWorker.Result
                        .success()
                        .javaClass,
                    result.javaClass,
                )
                assertEquals(1, db.transactionDao().getAll().size)
                assertEquals(fake.currentTime, db.simpleFinDao().getProfile()!!.lastSuccessfulSyncAtEpochMillis)
            }
        }

    @Test
    fun committedWorkerSyncRethrowsWidgetRefreshCancellation() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.currentTime = 5_700_000_000L
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                fake.credential = "current" to OLD_URL
                fake.accounts = { _, _, _ ->
                    SimpleFinAccountsResult(listOf(account("checking", transactionId = "cancelled-refresh-row")))
                }
                val cancellation = CancellationException("widget refresh cancelled")

                val thrown =
                    runCatching {
                        SimpleFinSyncWorker.runSync(repository) { throw cancellation }
                    }.exceptionOrNull()

                assertTrue(thrown === cancellation)
                assertEquals(1, db.transactionDao().getAll().size)
                assertEquals(fake.currentTime, db.simpleFinDao().getProfile()!!.lastSuccessfulSyncAtEpochMillis)
            }
        }

    @Test
    fun periodicSchedulingUpdatesSingleUniqueWork() =
        runBlocking {
            SimpleFinSyncWorker.cancel(context)
            try {
                SimpleFinSyncWorker.schedule(context, 1)
                SimpleFinSyncWorker.schedule(context, 12)

                val active =
                    WorkManager
                        .getInstance(context)
                        .getWorkInfosForUniqueWork(SimpleFinSyncWorker.PERIODIC_WORK_NAME)
                        .get()
                        .filter { !it.state.isFinished }
                assertEquals(1, active.size)
            } finally {
                SimpleFinSyncWorker.cancel(context)
            }
        }

    @Test
    fun partialErrorsRecordGuardedFailureWithoutWritingDataOrAdvancingSuccess() =
        runBlocking {
            withRepository { db, fake, repository ->
                val successfulAt = 1234L
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(connectionId = "current", lastSuccessfulSyncAtEpochMillis = successfulAt),
                )
                fake.credential = "current" to OLD_URL
                fake.accounts = { _, _, _ ->
                    SimpleFinAccountsResult(
                        accounts = listOf(account("partial", transactionId = "must-not-write")),
                        errors = listOf("one account failed"),
                    )
                }

                val result = repository.syncNow()

                assertTrue(result is SimpleFinSyncResult.Failure && !result.retryable)
                val profile = db.simpleFinDao().getProfile()!!
                assertEquals(successfulAt, profile.lastSuccessfulSyncAtEpochMillis)
                assertTrue(profile.lastSyncAttemptAtEpochMillis != null)
                assertEquals("one account failed", profile.lastError)
                assertTrue(
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )
                assertTrue(db.transactionDao().getAll().isEmpty())
            }
        }

    @Test
    fun staleSyncAfterDisconnectAndReconnectRollsBackAllWrites() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "old"))
                fake.credential = "old" to OLD_URL
                val oldRequestStarted = CompletableDeferred<Unit>()
                val oldResponse = CompletableDeferred<SimpleFinAccountsResult>()
                fake.accounts = { url, _, _ ->
                    if (url == OLD_URL) {
                        oldRequestStarted.complete(Unit)
                        oldResponse.await()
                    } else {
                        SimpleFinAccountsResult(emptyList())
                    }
                }

                val staleSync = async(Dispatchers.Default) { repository.syncNow() }
                oldRequestStarted.await()
                repository.disconnect()
                val reconnect = async(Dispatchers.Default) { repository.connect("new-token") }
                db.simpleFinDao().observeProfile().first { it != null && it.connectionId != "old" }
                val newConnectionId = db.simpleFinDao().getProfile()!!.connectionId

                oldResponse.complete(SimpleFinAccountsResult(listOf(account("old-account", "old-transaction"))))
                assertTrue(staleSync.await() is SimpleFinSyncResult.Failure)
                assertTrue(reconnect.await() is SimpleFinSyncResult.Success)

                assertEquals(newConnectionId, db.simpleFinDao().getProfile()!!.connectionId)
                assertTrue(
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )
                assertTrue(db.transactionDao().getAll().isEmpty())
            }
        }

    @Test
    fun stale403DoesNotPauseReconnectOrDeleteNewCredential() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "old"))
                fake.credential = "old" to OLD_URL
                val oldRequestStarted = CompletableDeferred<Unit>()
                val oldResponse = CompletableDeferred<SimpleFinAccountsResult>()
                fake.accounts = { url, _, _ ->
                    if (url == OLD_URL) {
                        oldRequestStarted.complete(Unit)
                        oldResponse.await()
                    } else {
                        SimpleFinAccountsResult(emptyList())
                    }
                }

                val staleSync = async(Dispatchers.Default) { repository.syncNow() }
                oldRequestStarted.await()
                repository.disconnect()
                val reconnect = async(Dispatchers.Default) { repository.connect("new-token") }
                db.simpleFinDao().observeProfile().first { it != null && it.connectionId != "old" }
                val newProfile = db.simpleFinDao().getProfile()!!
                val deletesBefore403 = fake.deleteCount

                oldResponse.completeExceptionally(SimpleFinReconnectException())
                assertTrue(staleSync.await() is SimpleFinSyncResult.Failure)
                assertTrue(reconnect.await() is SimpleFinSyncResult.Success)

                assertEquals(deletesBefore403, fake.deleteCount)
                assertEquals(newProfile.connectionId to NEW_URL, fake.credential)
                assertFalse(db.simpleFinDao().getProfile()!!.isPaused)
            }
        }

    @Test
    fun disconnectInvalidatesPendingConnectBeforeCredentialsArePublished() =
        runBlocking {
            withRepository { db, fake, repository ->
                val claimStarted = CompletableDeferred<Unit>()
                val claimResponse = CompletableDeferred<String>()
                fake.claim = {
                    claimStarted.complete(Unit)
                    claimResponse.await()
                }

                val connect = async(Dispatchers.Default) { repository.connect("pending-token") }
                claimStarted.await()
                repository.disconnect()
                claimResponse.complete(NEW_URL)

                assertTrue(connect.await() is SimpleFinSyncResult.Failure)
                assertNull(db.simpleFinDao().getProfile())
                assertNull(fake.credential)
                assertEquals(0, fake.scheduleCount)
            }
        }

    @Test
    fun disconnectWhileReplacementFetchFailsDoesNotRestoreOldConnection() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "old"))
                fake.credential = "old" to OLD_URL
                val fetchStarted = CompletableDeferred<Unit>()
                val fetchResponse = CompletableDeferred<SimpleFinAccountsResult>()
                fake.accounts = { _, _, _ ->
                    fetchStarted.complete(Unit)
                    fetchResponse.await()
                }

                val replacement = async(Dispatchers.Default) { repository.connect("replacement-token") }
                fetchStarted.await()
                repository.disconnect()
                fetchResponse.completeExceptionally(SimpleFinException("fetch failed"))

                assertTrue(replacement.await() is SimpleFinSyncResult.Failure)
                assertNull(db.simpleFinDao().getProfile())
                assertTrue(
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )
                assertNull(fake.credential)
                assertEquals(1, fake.cancelCount)
            }
        }

    @Test
    fun cancellationAfterDisconnectEntersStillCompletesCleanup() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "connected"))
                db.simpleFinDao().upsertAccounts(
                    listOf(SimpleFinAccountEntity("account", "Checking", "USD", null, null, null, null, 1L)),
                )
                fake.credential = "connected" to OLD_URL
                val cleanupStarted = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                fake.beforeDeleteCredential = {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }

                val disconnect = async(Dispatchers.Default) { repository.disconnect() }
                cleanupStarted.await()
                assertNull(db.simpleFinDao().getProfile())
                assertTrue(
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )

                disconnect.cancel()
                releaseCleanup.complete(Unit)
                disconnect.join()

                assertTrue(disconnect.isCancelled)
                assertNull(db.simpleFinDao().getProfile())
                assertTrue(
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )
                assertNull(fake.credential)
                assertEquals(1, fake.deleteCount)
                assertEquals(1, fake.cancelCount)
            }
        }

    @Test
    fun replacementCancellationRestoresExistingConnectionAndRethrows() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "old"))
                fake.credential = "old" to OLD_URL
                val saveStarted = CompletableDeferred<Unit>()
                val neverFinishSave = CompletableDeferred<Unit>()
                fake.save = { connectionId, accessUrl ->
                    fake.credential = connectionId to accessUrl
                    if (connectionId != "old") {
                        saveStarted.complete(Unit)
                        neverFinishSave.await()
                    }
                }

                val connect = async(Dispatchers.Default) { repository.connect("cancel-token") }
                saveStarted.await()
                connect.cancelAndJoin()

                assertTrue(connect.isCancelled)
                assertEquals("old", db.simpleFinDao().getProfile()!!.connectionId)
                assertEquals("old" to OLD_URL, fake.credential)
                assertEquals(listOf(1), fake.scheduledCounts)
            }
        }

    @Test
    fun saveFailurePreservesExistingConnection() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "old"))
                db.simpleFinDao().upsertAccounts(
                    listOf(SimpleFinAccountEntity("old-account", "Old", "USD", null, null, null, null, 1L)),
                )
                fake.credential = "old" to OLD_URL
                fake.save = { connectionId, accessUrl ->
                    if (connectionId != "old") throw SimpleFinException("save failed before write")
                    fake.credential = connectionId to accessUrl
                }

                val result = repository.connect("replacement-token")

                assertTrue(result is SimpleFinSyncResult.Failure)
                assertEquals("old", db.simpleFinDao().getProfile()!!.connectionId)
                assertEquals("old" to OLD_URL, fake.credential)
                assertEquals(
                    listOf("old-account"),
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .map { it.accountId },
                )
                assertEquals(listOf(1), fake.scheduledCounts)
            }
        }

    @Test
    fun replacementSchedulingFailurePublishesNothingAndRestoresExistingConnection() =
        runBlocking {
            withRepository { db, fake, repository ->
                var fetches = 0
                fake.accounts = { _, _, _ ->
                    fetches++
                    SimpleFinAccountsResult(
                        listOf(
                            account(
                                if (fetches ==
                                    1
                                ) {
                                    "old-account"
                                } else {
                                    "new-account"
                                },
                                if (fetches == 1) "old-transaction" else "new-transaction",
                            ),
                        ),
                    )
                }
                assertTrue(repository.connect("old-token") is SimpleFinSyncResult.Success)
                val oldProfile = db.simpleFinDao().getProfile()!!
                val oldAccounts = db.simpleFinDao().observeAccounts().firstValue()
                val oldTransactions = db.transactionDao().getAll()
                val oldCredential = fake.credential
                var schedules = 0
                fake.schedule = { if (++schedules == 1) throw IllegalStateException("schedule failed") }

                assertTrue(repository.connect("replacement-token") is SimpleFinSyncResult.Failure)

                assertEquals(oldProfile, db.simpleFinDao().getProfile())
                assertEquals(oldAccounts, db.simpleFinDao().observeAccounts().firstValue())
                assertEquals(oldTransactions, db.transactionDao().getAll())
                assertEquals(oldCredential, fake.credential)
            }
        }

    @Test
    fun replacementInitialFetchFailurePreservesExistingConnection() =
        runBlocking {
            withRepository { db, fake, repository ->
                val old = SimpleFinProfileEntity(connectionId = "old", automaticSyncsPerDay = 4)
                db.simpleFinDao().upsertProfile(old)
                db.simpleFinDao().upsertAccounts(listOf(SimpleFinAccountEntity("old-account", "Old", "USD", null, null, null, null, 1L)))
                fake.credential = "old" to OLD_URL
                fake.accounts = { _, _, _ -> throw SimpleFinException("fetch failed") }

                assertTrue(repository.connect("replacement-token") is SimpleFinSyncResult.Failure)

                assertEquals(old, db.simpleFinDao().getProfile())
                assertEquals("old" to OLD_URL, fake.credential)
                assertEquals(
                    listOf("old-account"),
                    db
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .map { it.accountId },
                )
                assertEquals(listOf(4), fake.scheduledCounts)
            }
        }

    @Test
    fun stagedConnectionPreventsASecondSetupTokenFromBeingClaimed() =
        runBlocking {
            withRepository { _, fake, repository ->
                val accountRequestStarted = CompletableDeferred<Unit>()
                val accountResponse = CompletableDeferred<SimpleFinAccountsResult>()
                var claims = 0
                fake.claim = {
                    claims++
                    OLD_URL
                }
                fake.accounts = { _, _, _ ->
                    accountRequestStarted.complete(Unit)
                    accountResponse.await()
                }

                val firstConnect = async(Dispatchers.Default) { repository.connect("first-token") }
                accountRequestStarted.await()
                val secondConnect = repository.connect("must-not-be-claimed") as SimpleFinSyncResult.Failure

                assertEquals(1, claims)
                assertEquals(SimpleFinFailureKind.PROTOCOL, secondConnect.kind)
                assertTrue(secondConnect.message.contains("already in progress"))

                firstConnect.cancelAndJoin()
                assertTrue(firstConnect.isCancelled)
                assertTrue(fake.pendingCredential != null)
                repository.cancelPendingConnection()
                assertNull(fake.pendingCredential)
            }
        }

    @Test
    fun twoRepositoriesSerializeConcurrentThrottledSyncs() =
        runBlocking {
            withRepository { db, fake, firstRepository ->
                val secondRepository = SimpleFinSyncRepository(db, fake.bundle())
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "shared"))
                fake.credential = "shared" to OLD_URL
                val requestStarted = CompletableDeferred<Unit>()
                val releaseRequest = CompletableDeferred<Unit>()
                val requests = AtomicInteger()
                val active = AtomicInteger()
                val maxActive = AtomicInteger()
                fake.accounts = { _, _, _ ->
                    requests.incrementAndGet()
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, current) }
                    requestStarted.complete(Unit)
                    try {
                        releaseRequest.await()
                        SimpleFinAccountsResult(emptyList())
                    } finally {
                        active.decrementAndGet()
                    }
                }

                val first = async(Dispatchers.Default) { firstRepository.syncNow() }
                requestStarted.await()
                val second =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        secondRepository.syncNow()
                    }
                assertEquals(1, requests.get())

                releaseRequest.complete(Unit)
                assertTrue(first.await() is SimpleFinSyncResult.Success)
                assertEquals(SimpleFinSyncResult.Throttled, second.await())
                assertEquals(1, requests.get())
                assertEquals(1, maxActive.get())
            }
        }

    @Test
    fun queuedStaleSyncRereadsPausedProfileInsideLock() =
        runBlocking {
            withRepository { db, fake, firstRepository ->
                val secondRepository = SimpleFinSyncRepository(db, fake.bundle())
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "shared"))
                fake.credential = "shared" to OLD_URL
                val requestStarted = CompletableDeferred<Unit>()
                val releaseRequest = CompletableDeferred<Unit>()
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    SimpleFinAccountsResult(emptyList())
                }

                val first = async(Dispatchers.Default) { firstRepository.syncNow() }
                requestStarted.await()
                val queued =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        secondRepository.syncIfStale()
                    }
                assertEquals(1, db.simpleFinDao().pauseForReconnect("shared"))
                releaseRequest.complete(Unit)

                assertTrue(first.await() is SimpleFinSyncResult.Success)
                assertTrue(queued.await() is SimpleFinSyncResult.Failure)
                assertEquals(1, requests)
            }
        }

    @Test
    fun cancelledSyncReleasesProcessLockForAnotherRepository() =
        runBlocking {
            withRepository { db, fake, firstRepository ->
                val secondRepository = SimpleFinSyncRepository(db, fake.bundle())
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "shared"))
                fake.credential = "shared" to OLD_URL
                val requestStarted = CompletableDeferred<Unit>()
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    if (requests == 1) {
                        requestStarted.complete(Unit)
                        awaitCancellation()
                    }
                    SimpleFinAccountsResult(emptyList())
                }

                val cancelled = async(Dispatchers.Default) { firstRepository.syncNow() }
                requestStarted.await()
                val next =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        secondRepository.syncNow()
                    }
                fake.currentTime += SIMPLEFIN_RETRY_INTERVAL_MILLIS
                cancelled.cancelAndJoin()

                assertTrue(cancelled.isCancelled)
                assertTrue(next.await() is SimpleFinSyncResult.Success)
                assertEquals(2, requests)
            }
        }

    @Test
    fun workerUsesStaleThrottledPath() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(
                    SimpleFinProfileEntity(
                        connectionId = "fresh",
                        lastSuccessfulSyncAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                var requests = 0
                fake.accounts = { _, _, _ ->
                    requests++
                    SimpleFinAccountsResult(emptyList())
                }

                SimpleFinSyncWorker.runSync(repository)

                assertEquals(0, requests)
                assertNull(db.simpleFinDao().getProfile()!!.lastSyncAttemptAtEpochMillis)
            }
        }

    @Test
    fun rawVersion4DatabaseMigratesCompletelyToVersion6() =
        runBlocking {
            val name = "simplefin-v4-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            val preferences = context.getSharedPreferences(SimpleFinMigrationCleanup.PREFERENCES, Context.MODE_PRIVATE)
            val credentialStore = SimpleFinCredentialStore(context)
            val workManager = WorkManager.getInstance(context)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                raw.execSQL(
                    "CREATE TABLE transactions (id TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, cents INTEGER NOT NULL, recurringInterval TEXT, source TEXT NOT NULL DEFAULT 'local', accountKey TEXT, accountName TEXT, PRIMARY KEY(id))",
                )
                raw.execSQL("CREATE INDEX index_transactions_occurredAtEpochMillis ON transactions(occurredAtEpochMillis)")
                raw.execSQL("CREATE INDEX index_transactions_source ON transactions(source)")
                raw.execSQL("CREATE INDEX index_transactions_accountKey ON transactions(accountKey)")
                raw.execSQL(
                    "CREATE TABLE simplefin_profile (id TEXT NOT NULL PRIMARY KEY DEFAULT 'default', connectedAtEpochMillis INTEGER, lastSyncAttemptAtEpochMillis INTEGER, lastSuccessfulSyncAtEpochMillis INTEGER, lastError TEXT, isPaused INTEGER NOT NULL DEFAULT 0)",
                )
                raw.execSQL(
                    "CREATE TABLE simplefin_accounts (accountId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT, institutionName TEXT, balanceAmount TEXT, availableBalanceAmount TEXT, balanceDateEpochSeconds INTEGER, lastSeenAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL(
                    "CREATE TABLE simplefin_ignored_transactions (transactionId TEXT NOT NULL PRIMARY KEY, ignoredAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL("INSERT INTO simplefin_profile (id, connectedAtEpochMillis, isPaused) VALUES ('default', 99, 0)")
                raw.execSQL("INSERT INTO simplefin_accounts (accountId, name, lastSeenAtEpochMillis) VALUES ('old-account', 'Old', 99)")
                raw.execSQL(
                    "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, source, accountKey) VALUES ('simplefin:old-account:kept', 99, 'Old synced', 'Other', '', -100, 'simplefin', 'old-account')",
                )
                raw.execSQL(
                    "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, source) VALUES ('local', 99, 'Local', 'Other', '', -100, 'local')",
                )
                raw.execSQL(
                    "INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis) VALUES ('simplefin:old-account:deleted', 99)",
                )
                raw.version = 4
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_4_5, FlowMoneyDatabase.MIGRATION_5_6)
                    .build()
            try {
                assertNull(migrated.simpleFinDao().getProfile())
                assertTrue(
                    migrated
                        .simpleFinDao()
                        .observeAccounts()
                        .firstValue()
                        .isEmpty(),
                )
                assertTrue(migrated.simpleFinDao().ignoredTransactionIds().isEmpty())
                assertEquals(listOf("local"), migrated.transactionDao().getAll().map { it.id })

                credentialStore.save("legacy", OLD_URL)
                val periodic = PeriodicWorkRequestBuilder<SimpleFinSyncWorker>(1, TimeUnit.DAYS).build()
                val oneShot =
                    OneTimeWorkRequestBuilder<SimpleFinSyncWorker>()
                        .setInitialDelay(1, TimeUnit.DAYS)
                        .build()
                workManager
                    .enqueueUniquePeriodicWork(
                        SimpleFinSyncWorker.PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.REPLACE,
                        periodic,
                    ).result
                    .get()
                workManager
                    .enqueueUniqueWork(
                        SimpleFinSyncWorker.ONE_SHOT_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        oneShot,
                    ).result
                    .get()
                preferences.edit().remove(SimpleFinMigrationCleanup.COMPLETE).commit()

                SimpleFinMigrationCleanup.run(context, migrated)

                assertNull(credentialStore.read("legacy"))
                assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(periodic.id).get()!!.state)
                assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(oneShot.id).get()!!.state)
                assertEquals(listOf("local"), migrated.transactionDao().getAll().map { it.id })
                assertTrue(preferences.getBoolean(SimpleFinMigrationCleanup.COMPLETE, false))
            } finally {
                credentialStore.delete()
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun rawVersion3DatabaseMigratesThroughVersion4ToVersion6() =
        runBlocking {
            val name = "simplefin-v3-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                raw.execSQL(
                    "CREATE TABLE transactions (id TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, cents INTEGER NOT NULL, recurringInterval TEXT, PRIMARY KEY(id))",
                )
                raw.execSQL("CREATE INDEX index_transactions_occurredAtEpochMillis ON transactions(occurredAtEpochMillis)")
                raw.execSQL(
                    "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents) VALUES ('old', 99, 'Old', 'Other', '', -100)",
                )
                raw.version = 3
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_3_4, FlowMoneyDatabase.MIGRATION_4_5, FlowMoneyDatabase.MIGRATION_5_6)
                    .build()
            try {
                val transaction = migrated.transactionDao().getAll().single()
                assertEquals("local", transaction.source)
                assertNull(transaction.accountKey)
                migrated.simpleFinDao().upsertProfile(SimpleFinProfileEntity())
                assertEquals("legacy", migrated.simpleFinDao().getProfile()!!.connectionId)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun rawVersion5ProfileMigratesToVersion6WithoutDataLoss() =
        runBlocking {
            val name = "simplefin-v5-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                raw.execSQL(
                    "CREATE TABLE transactions (id TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, cents INTEGER NOT NULL, recurringInterval TEXT, source TEXT NOT NULL DEFAULT 'local', accountKey TEXT, accountName TEXT, PRIMARY KEY(id))",
                )
                raw.execSQL("CREATE INDEX index_transactions_occurredAtEpochMillis ON transactions(occurredAtEpochMillis)")
                raw.execSQL("CREATE INDEX index_transactions_source ON transactions(source)")
                raw.execSQL("CREATE INDEX index_transactions_accountKey ON transactions(accountKey)")
                raw.execSQL(
                    "CREATE TABLE simplefin_profile (id TEXT NOT NULL PRIMARY KEY DEFAULT 'default', connectionId TEXT NOT NULL DEFAULT 'legacy', connectedAtEpochMillis INTEGER, lastSyncAttemptAtEpochMillis INTEGER, lastSuccessfulSyncAtEpochMillis INTEGER, lastError TEXT, isPaused INTEGER NOT NULL DEFAULT 0)",
                )
                raw.execSQL(
                    "CREATE TABLE simplefin_accounts (accountId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT, institutionName TEXT, balanceAmount TEXT, availableBalanceAmount TEXT, balanceDateEpochSeconds INTEGER, lastSeenAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL(
                    "CREATE TABLE simplefin_ignored_transactions (transactionId TEXT NOT NULL PRIMARY KEY, ignoredAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL(
                    "INSERT INTO simplefin_profile (id, connectionId, connectedAtEpochMillis, lastSyncAttemptAtEpochMillis, lastSuccessfulSyncAtEpochMillis, lastError, isPaused) VALUES ('default', 'connection-5', 11, 22, 33, 'saved error', 1)",
                )
                raw.version = 5
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_5_6)
                    .build()
            try {
                val profile = migrated.simpleFinDao().getProfile()!!
                assertEquals("default", profile.id)
                assertEquals("connection-5", profile.connectionId)
                assertEquals(11L, profile.connectedAtEpochMillis)
                assertEquals(22L, profile.lastSyncAttemptAtEpochMillis)
                assertEquals(33L, profile.lastSuccessfulSyncAtEpochMillis)
                assertEquals("saved error", profile.lastError)
                assertTrue(profile.isPaused)
                assertEquals(1, profile.automaticSyncsPerDay)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    private suspend fun withRepository(block: suspend (FlowMoneyDatabase, FakeFunctions, SimpleFinSyncRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        val fake = FakeFunctions()
        try {
            block(db, fake, SimpleFinSyncRepository(db, fake.bundle()))
        } finally {
            db.close()
        }
    }

    private class FakeFunctions {
        var credential: Pair<String, String>? = null
        var pendingCredential: SimpleFinPendingCredential? = null
        var rollbackCredential: Pair<String, String>? = null
        var deleteCount = 0
        var pendingDeleteCount = 0
        var rollbackDeleteCount = 0
        var promotionCount = 0
        var claimCount = 0
        var cancelCount = 0
        var currentTime = System.currentTimeMillis()
        val scheduledCounts = mutableListOf<Int>()
        var beforeDeleteCredential: suspend () -> Unit = {}
        var claim: suspend (String) -> String = { NEW_URL }
        var accounts: suspend (String, Long, Long) -> SimpleFinAccountsResult = { _, _, _ ->
            SimpleFinAccountsResult(emptyList())
        }
        var save: suspend (String, String) -> Unit = { connectionId, accessUrl ->
            credential = connectionId to accessUrl
        }
        var schedule: suspend (Int) -> Unit = { scheduledCounts += it }

        val scheduleCount get() = scheduledCounts.size

        fun bundle() =
            SimpleFinSyncFunctions(
                claim = {
                    claimCount++
                    claim(it)
                },
                accounts = { url, start, end -> accounts(url, start, end) },
                saveCredential = { connectionId, accessUrl -> save(connectionId, accessUrl) },
                readCredential = { expected -> credential?.takeIf { it.first == expected }?.second },
                deleteCredential = {
                    beforeDeleteCredential()
                    deleteCount++
                    credential = null
                },
                stagePendingCredential = { connectionId, accessUrl ->
                    pendingCredential = SimpleFinPendingCredential(connectionId, accessUrl)
                },
                readPendingCredential = { pendingCredential },
                promotePendingCredential = { expectedConnectionId, previousConnectionId, previousAccessUrl ->
                    val staged = checkNotNull(pendingCredential)
                    check(staged.connectionId == expectedConnectionId)
                    if (rollbackCredential == null && previousConnectionId != null && previousAccessUrl != null) {
                        rollbackCredential = previousConnectionId to previousAccessUrl
                    }
                    promotionCount++
                    save(staged.connectionId, staged.accessUrl)
                },
                restoreRollbackCredential = { expectedConnectionId ->
                    rollbackCredential
                        ?.takeIf { it.first == expectedConnectionId }
                        ?.let {
                            save(it.first, it.second)
                            true
                        } ?: false
                },
                deletePendingCredential = {
                    pendingDeleteCount++
                    pendingCredential = null
                },
                deleteRollbackCredential = {
                    rollbackDeleteCount++
                    rollbackCredential = null
                },
                scheduleWork = { schedule(it) },
                cancelWork = { cancelCount++ },
                now = { currentTime },
            )
    }

    private class EmptyGateway : TransactionGateway {
        override val transactions: Flow<List<Transaction>> = MutableStateFlow(emptyList())

        override suspend fun load() = emptyList<Transaction>()

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    private fun account(
        id: String,
        transactionId: String,
    ) = SimpleFinAccount(
        providerConnectionId = "provider",
        id = id,
        name = id,
        orgName = "Bank",
        currency = "USD",
        balance = "1.00",
        availableBalance = "1.00",
        transactions =
            listOf(
                SimpleFinTransaction(transactionId, 1_700_000_000L, "-1.00", "Merchant", pending = false),
            ),
    )

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

    private companion object {
        const val OLD_URL = "https://old-user:old-password@bridge.simplefin.org/simplefin"
        const val NEW_URL = "https://new-user:new-password@bridge.simplefin.org/simplefin"
    }
}
