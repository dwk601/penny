package com.dwk.flowmoney

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainViewModelDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @After fun closeDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun rangeCountResetAndRestoreDelegateExactInputsToGateway() =
        runBlocking {
            val gateway = FakeGateway(MutableStateFlow(emptyList()))
            val range = PennyLocalDateRange(LocalDate.of(2024, 11, 3), LocalDate.of(2024, 11, 4))
            val zoneId = ZoneId.of("America/New_York")
            val count = TransactionRangeCount(transactionCount = 1, tombstoneCount = 1)
            val snapshot =
                TransactionRangeResetSnapshot(
                    transactionRows = listOf(transaction("restorable", 1, -100).toEntity()),
                    tombstoneRows =
                        listOf(
                            SimpleFinIgnoredTransactionEntity(
                                transactionId = "restorable-tombstone",
                                ignoredAtEpochMillis = 2,
                                occurredAtEpochMillis = 1,
                            ),
                        ),
                )
            gateway.countResult = count
            gateway.resetResult = snapshot

            withContext(Dispatchers.Main) {
                val store = ViewModelStore()
                val viewModel =
                    ViewModelProvider(
                        store,
                        MainViewModel.Factory(
                            repository = gateway,
                            simpleFinRepository = SimpleFinSyncRepository(context),
                            simpleFinAccounts = MutableStateFlow(emptyList()),
                        ),
                    )[MainViewModel::class.java]
                try {
                    assertEquals(count, viewModel.countRange(range, zoneId))
                    val clock = Clock.fixed(Instant.parse("2024-11-03T12:00:00Z"), ZoneOffset.UTC)
                    val pending = viewModel.resetRange(range, zoneId, count, clock)
                    assertEquals(snapshot.count, pending.count)
                    assertEquals(count, gateway.resetExpectedCount)
                    val resetRefreshes = AtomicInteger()
                    assertTrue(viewModel.refreshWidgetsAfterRangeReset(pending.id) { resetRefreshes.incrementAndGet() })
                    assertFalse(viewModel.refreshWidgetsAfterRangeReset(pending.id) { resetRefreshes.incrementAndGet() })
                    assertEquals(PendingRangeResetRestoreResult.Restored, viewModel.restorePendingRangeReset(pending.id))
                    assertEquals(PendingRangeResetRestoreResult.Restored, viewModel.restorePendingRangeReset(pending.id))
                    val restoreRefreshes = AtomicInteger()
                    assertTrue(viewModel.completePendingRangeResetRestore(pending.id) { restoreRefreshes.incrementAndGet() })
                    assertFalse(viewModel.completePendingRangeResetRestore(pending.id) { restoreRefreshes.incrementAndGet() })
                    assertEquals(1, resetRefreshes.get())
                    assertEquals(1, restoreRefreshes.get())
                    assertEquals(listOf("count", "reset", "restore"), gateway.rangeCalls)
                    assertEquals(listOf(range to zoneId, range to zoneId), gateway.rangeRequests)
                    assertSame(snapshot, gateway.restoredSnapshot)
                    assertEquals(null, viewModel.pendingRangeReset.value)
                } finally {
                    store.clear()
                }
            }
        }

    @Test fun resetRangeDefensivelyRejectsSelectionThatAgedOutAcrossMidnight() =
        runBlocking {
            val gateway = FakeGateway(MutableStateFlow(emptyList()))
            val zoneId = ZoneOffset.UTC
            val range = PennyLocalDateRange(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 1, 31))
            val expected = TransactionRangeCount(transactionCount = 0, tombstoneCount = 0)
            val afterMidnight = Clock.fixed(Instant.parse("2026-03-16T00:00:00Z"), zoneId)

            withContext(Dispatchers.Main) {
                val store = ViewModelStore()
                val viewModel =
                    ViewModelProvider(
                        store,
                        MainViewModel.Factory(
                            repository = gateway,
                            simpleFinRepository = SimpleFinSyncRepository(context),
                            simpleFinAccounts = MutableStateFlow(emptyList()),
                        ),
                    )[MainViewModel::class.java]
                try {
                    val failure =
                        runCatching {
                            viewModel.resetRange(range, zoneId, expected, afterMidnight)
                        }.exceptionOrNull()

                    assertTrue(failure is SimpleFinResetRangeOutOfWindowException)
                    assertEquals(emptyList<String>(), gateway.rangeCalls)
                    assertEquals(null, viewModel.pendingRangeReset.value)
                } finally {
                    store.clear()
                }
            }
        }

    @Test fun newestFirstGatewayFeedsRecentSuggestionsAndAnalyticsWithoutResorting() =
        runBlocking {
            val start =
                LocalDate
                    .now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val newestFirst =
                listOf(
                    transaction("newest", start + 3, -300),
                    transaction("middle", start + 2, 200),
                    transaction("oldest", start + 1, -100),
                )

            withContext(Dispatchers.Main) {
                val store = ViewModelStore()
                val viewModel =
                    ViewModelProvider(
                        store,
                        MainViewModel.Factory(
                            repository = FakeGateway(MutableStateFlow(newestFirst)),
                            simpleFinRepository = SimpleFinSyncRepository(context),
                            simpleFinAccounts = MutableStateFlow(emptyList()),
                        ),
                    )[MainViewModel::class.java]
                try {
                    viewModel.reportInitializationComplete()
                    val state = viewModel.uiState.first { !it.isLoading }

                    assertEquals(listOf("newest", "middle", "oldest"), state.sortedTransactions.map { it.id })
                    assertEquals(newestFirst, state.recentTransactions)
                    assertEquals(newestFirst, state.suggestionHistory.transactions)
                    assertEquals(
                        DashboardMetrics(spentCents = 400, incomeCents = 200, transactionCount = 3),
                        state.metrics,
                    )
                } finally {
                    store.clear()
                }
            }
        }

    private class FakeGateway(
        override val transactions: Flow<List<Transaction>>,
    ) : TransactionGateway {
        var countResult = TransactionRangeCount(transactionCount = 0, tombstoneCount = 0)
        var resetResult = TransactionRangeResetSnapshot(emptyList(), emptyList())
        val rangeCalls = mutableListOf<String>()
        val rangeRequests = mutableListOf<Pair<PennyLocalDateRange, ZoneId>>()
        var restoredSnapshot: TransactionRangeResetSnapshot? = null
        var resetExpectedCount: TransactionRangeCount? = null

        override suspend fun load() = emptyList<Transaction>()

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun delete(id: String) = Unit

        override suspend fun countRange(
            range: PennyLocalDateRange,
            zoneId: ZoneId,
        ): TransactionRangeCount {
            rangeCalls += "count"
            rangeRequests += range to zoneId
            return countResult
        }

        override suspend fun resetRange(
            range: PennyLocalDateRange,
            zoneId: ZoneId,
            expectedCount: TransactionRangeCount,
        ): TransactionRangeResetSnapshot {
            rangeCalls += "reset"
            rangeRequests += range to zoneId
            resetExpectedCount = expectedCount
            return resetResult
        }

        override suspend fun restoreRange(snapshot: TransactionRangeResetSnapshot): TransactionRangeCount {
            rangeCalls += "restore"
            restoredSnapshot = snapshot
            return snapshot.count
        }
    }

    private fun transaction(
        id: String,
        occurredAtEpochMillis: Long,
        cents: Int,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = id,
        category = "Food",
        note = "",
        cents = cents,
    )
}
