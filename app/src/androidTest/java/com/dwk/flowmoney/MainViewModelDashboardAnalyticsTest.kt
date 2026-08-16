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

    @Test fun rangeAnalyticsReactToReviewStateWithoutChangingOverallMetrics() =
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
                            category = "Other",
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
                    assertEquals(
                        listOf(CategoryTotal(category = "Food", cents = 100)),
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
                                it.categoryTotals.firstOrNull()?.category == "Other"
                        }

                    assertEquals(initial.metrics, updated.metrics)
                    assertEquals(
                        listOf(
                            CategoryTotal(category = "Other", cents = 900),
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
