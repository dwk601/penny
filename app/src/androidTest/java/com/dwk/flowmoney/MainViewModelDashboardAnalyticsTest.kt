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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MainViewModelDashboardAnalyticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @After fun closeDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun rangeAnalyticsIncludePendingSpendUnderOtherAndReactToCorrections() =
        runBlocking {
            val start =
                LocalDate
                    .now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val transactions =
                MutableStateFlow(
                    listOf(
                        transaction(
                            id = "pending-expense",
                            occurredAtEpochMillis = start + 3,
                            cents = -900,
                            category = "Travel",
                        ),
                        transaction(
                            id = "reviewed-expense",
                            occurredAtEpochMillis = start + 2,
                            cents = -100,
                            category = "Food",
                            reviewedAtEpochMillis = 1,
                        ),
                        transaction(
                            id = "pending-income",
                            occurredAtEpochMillis = start + 1,
                            cents = 500,
                            category = "Salary",
                        ),
                    ),
                )

            withContext(Dispatchers.Main) {
                val store = ViewModelStore()
                val viewModel =
                    ViewModelProvider(
                        store,
                        MainViewModel.Factory(
                            repository = FakeGateway(transactions),
                            simpleFinRepository = SimpleFinSyncRepository(context),
                            simpleFinAccounts = MutableStateFlow(emptyList()),
                        ),
                    )[MainViewModel::class.java]
                try {
                    assertTrue(viewModel.uiState.value.isLoading)
                    viewModel.reportInitializationComplete()
                    val initial =
                        viewModel.uiState.first {
                            !it.isLoading && it.pendingReviewSummary.transactionCount == 1
                        }

                    assertEquals(
                        DashboardMetrics(spentCents = 1_000, incomeCents = 500, transactionCount = 3),
                        initial.metrics,
                    )
                    assertEquals(listOf("pending-expense", "reviewed-expense"), initial.latestSpendingTransactions.map { it.id })
                    assertEquals(
                        listOf(
                            CategoryTotal(category = "Other", cents = 900),
                            CategoryTotal(category = "Food", cents = 100),
                        ),
                        initial.categoryTotals,
                    )
                    assertEquals(
                        UnreviewedSpendingSummary(spentCents = 900, transactionCount = 1),
                        initial.pendingReviewSummary,
                    )

                    transactions.value =
                        transactions.value.map { transaction ->
                            if (transaction.id == "pending-expense") {
                                transaction.copy(reviewedAtEpochMillis = 2)
                            } else {
                                transaction
                            }
                        }
                    val updated =
                        viewModel.uiState.first {
                            !it.isLoading &&
                                it.pendingReviewSummary.transactionCount == 0 &&
                                it.categoryTotals.firstOrNull()?.category == "Travel"
                        }

                    assertEquals(initial.metrics, updated.metrics)
                    assertEquals(initial.latestSpendingTransactions.map { it.id }, updated.latestSpendingTransactions.map { it.id })
                    assertEquals(
                        listOf(
                            CategoryTotal(category = "Travel", cents = 900),
                            CategoryTotal(category = "Food", cents = 100),
                        ),
                        updated.categoryTotals,
                    )
                    assertEquals(
                        UnreviewedSpendingSummary(spentCents = 0, transactionCount = 0),
                        updated.pendingReviewSummary,
                    )
                } finally {
                    store.clear()
                }
            }
        }

    @Test fun latestSpendingTracksRangeAndEffectiveTransferOverrides() = runBlocking {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val older = LocalDate.now().minusDays(9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = MutableStateFlow(listOf(
            transaction("expense-a", start + 8, -100, "Food"),
            transaction("income", start + 7, 500, "Salary"),
            transaction("transfer", start + 6, -200, "Food").copy(flowKind = FlowKind.TRANSFER),
            transaction("expense-b", start + 5, -300, "Food"),
            transaction("expense-c", start + 4, -400, "Food"),
            transaction("expense-d", start + 3, -500, "Food"),
            transaction("out-of-range", older, -600, "Food"),
        ))
        withContext(Dispatchers.Main) {
            val store = ViewModelStore()
            val viewModel = ViewModelProvider(
                store,
                MainViewModel.Factory(FakeGateway(rows), SimpleFinSyncRepository(context), MutableStateFlow(emptyList())),
            )[MainViewModel::class.java]
            try {
                viewModel.reportInitializationComplete()
                val initial = viewModel.uiState.first { !it.isLoading }
                assertEquals(listOf("expense-a", "expense-b", "expense-c"), initial.latestSpendingTransactions.map { it.id })
                rows.value = rows.value.map {
                    when (it.id) {
                        "expense-a" -> it.copy(flowKindOverride = FlowKind.TRANSFER)
                        "transfer" -> it.copy(flowKindOverride = FlowKind.NORMAL)
                        else -> it
                    }
                }
                val updated = viewModel.uiState.first { state ->
                    state.latestSpendingTransactions.firstOrNull()?.id == "transfer"
                }
                assertEquals(listOf("transfer", "expense-b", "expense-c"), updated.latestSpendingTransactions.map { it.id })
                assertEquals(FlowKind.TRANSFER, updated.sortedTransactions.first { it.id == "transfer" }.flowKind)
                assertEquals(FlowKind.NORMAL, updated.sortedTransactions.first { it.id == "expense-a" }.flowKind)
            } finally {
                store.clear()
            }
        }
    }

    @Test fun allSpendingCategoriesReachStateAndReconcileAfterReview() = runBlocking {
        val time = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = MutableStateFlow(
            listOf("Food", "Housing", "Travel", "Bills", "Coffee").mapIndexed { index, category ->
                transaction("reviewed-$index", time + index, -(index + 1) * 100, category, reviewedAtEpochMillis = 1)
            } + transaction("pending", time + 10, -600, "Groceries") +
                transaction("transfer", time + 11, -9000, "Food", reviewedAtEpochMillis = 1).copy(flowKind = FlowKind.TRANSFER),
        )
        withContext(Dispatchers.Main) {
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, MainViewModel.Factory(FakeGateway(rows), SimpleFinSyncRepository(context), MutableStateFlow(emptyList())))[MainViewModel::class.java]
            try {
                vm.reportInitializationComplete()
                val initial = vm.uiState.first { !it.isLoading && it.categoryTotals.size == 6 }
                assertEquals(2100L, initial.metrics.spentCents)
                assertEquals(initial.metrics.spentCents, initial.categoryTotals.sumOf { it.cents })
                assertTrue(initial.categoryTotals.any { it.category == "Other" && it.cents == 600L })
                rows.value = rows.value.map { if (it.id == "pending") it.copy(reviewedAtEpochMillis = 2) else it }
                val corrected = vm.uiState.first { it.categoryTotals.any { total -> total.category == "Groceries" } }
                assertEquals(6, corrected.categoryTotals.size)
                assertEquals(corrected.metrics.spentCents, corrected.categoryTotals.sumOf { it.cents })
                assertEquals(2100L, corrected.metrics.spentCents)
                assertEquals(0, corrected.pendingReviewSummary.transactionCount)
            } finally {
                store.clear()
            }
        }
    }

    private class FakeGateway(
        override val transactions: Flow<List<Transaction>>,
    ) : TransactionGateway {
        override suspend fun load() = emptyList<Transaction>()

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    private fun transaction(
        id: String,
        occurredAtEpochMillis: Long,
        cents: Int,
        category: String,
        reviewedAtEpochMillis: Long? = null,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            merchant = id,
            category = category,
            note = "",
            cents = cents,
            source = "simplefin",
            reviewedAtEpochMillis = reviewedAtEpochMillis,
        )
}
