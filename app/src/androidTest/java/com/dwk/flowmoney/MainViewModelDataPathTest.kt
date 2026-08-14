package com.dwk.flowmoney

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

    @Test fun newestFirstGatewayFeedsRecentSuggestionsAndAnalyticsWithoutResorting() = runBlocking {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val newestFirst = listOf(
            transaction("newest", start + 3, -300),
            transaction("middle", start + 2, 200),
            transaction("oldest", start + 1, -100),
        )

        withContext(Dispatchers.Main) {
            val store = ViewModelStore()
            val viewModel = ViewModelProvider(
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
        override suspend fun load() = emptyList<Transaction>()
        override suspend fun upsert(transaction: Transaction) = Unit
        override suspend fun importTransactions(transactions: List<Transaction>) = transactions.size
        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = transactions.size
        override suspend fun delete(id: String) = Unit
    }

    private fun transaction(id: String, occurredAtEpochMillis: Long, cents: Int) = Transaction(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = id,
        category = "Food",
        note = "",
        cents = cents,
    )
}
