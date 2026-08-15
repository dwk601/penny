package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelReadinessTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context.getSharedPreferences("flow_money", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun resetState() {
        FlowMoneyDatabase.resetForTest()
        context.getSharedPreferences("flow_money", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun legacyImportIsObservedBeforeReadyEmptyState() = runBlocking {
        val legacy = transaction("legacy")
        val transactions = MutableStateFlow<List<Transaction>>(emptyList())

        withViewModel(transactions) { viewModel ->
            val observed = mutableListOf<MainUiState>()
            val collection = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.uiState.take(2).toList(observed)
            }
            yield()

            assertTrue(viewModel.uiState.value.isLoading)
            transactions.value = listOf(legacy)
            viewModel.reportInitializationComplete()

            withTimeout(5_000) { collection.await() }
            assertTrue(observed.first().isLoading)
            assertEquals(listOf(legacy), observed.last().sortedTransactions)
            assertTrue(!observed.last().isLoading)
        }
    }

    @Test
    fun initializationReadyStillWaitsForFirstRoomBackedEmission() = runBlocking {
        val transactions = MutableSharedFlow<List<Transaction>>(replay = 1)

        withViewModel(transactions) { viewModel ->
            val observed = mutableListOf<MainUiState>()
            val collection = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.uiState.take(2).toList(observed)
            }
            yield()

            viewModel.reportInitializationComplete()
            yield()
            assertEquals(1, observed.size)
            assertTrue(viewModel.uiState.value.isLoading)

            transactions.emit(emptyList())
            withTimeout(5_000) { collection.await() }
            assertTrue(!observed.last().isLoading)
            assertTrue(observed.last().sortedTransactions.isEmpty())
        }
    }

    @Test
    fun failedMigrationPreservesLegacyPreferencesAndShowsOneError() {
        val preferences = context.getSharedPreferences("flow_money", Context.MODE_PRIVATE)
        preferences.edit().putInt("transactions_csv", 7).commit()
        val message = "Saved transaction migration could not be completed; it will retry next launch."

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(message).fetchSemanticsNodes().size == 1
            }
            composeRule.onAllNodesWithText(message).assertCountEquals(1)
            assertEquals(7, preferences.getInt("transactions_csv", 0))
            assertFalse(preferences.getBoolean("room_migrated", false))

            scenario.recreate()
            composeRule.waitForIdle()
            val duplicateErrorStayedAbsent = try {
                composeRule.waitUntil(1_000) {
                    composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
                }
                false
            } catch (_: ComposeTimeoutException) {
                true
            }
            assertTrue("Migration error reappeared after recreation", duplicateErrorStayedAbsent)
            composeRule.onAllNodesWithText(message).assertCountEquals(0)
        }
    }

    @Test
    fun successfulActivityMigrationKeepsLoadingUntilImportedTransactionIsReady() {
        val legacy = transaction("activity-legacy")
        val preferences = context.getSharedPreferences("flow_money", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("transactions_csv", CsvCodec.encode(listOf(legacy))).commit())
        val database = FlowMoneyDatabase.get(context)
        val sqlDatabase = database.openHelper.writableDatabase
        sqlDatabase.beginTransaction()
        var transactionOpen = true
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("app_loading_state").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("app_loading_state").assertIsDisplayed()
            composeRule.onNodeWithTag("empty_add_transaction").assertDoesNotExist()

            sqlDatabase.endTransaction()
            transactionOpen = false

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(legacy.merchant).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText(legacy.merchant).onFirst().assertIsDisplayed()
            composeRule.onNodeWithTag("app_loading_state").assertDoesNotExist()
            composeRule.onNodeWithTag("empty_add_transaction").assertDoesNotExist()
            assertEquals(
                listOf(legacy.id),
                runBlocking { database.transactionDao().getAll() }.map { it.id },
            )
            assertTrue(preferences.getBoolean("room_migrated", false))
        } finally {
            if (transactionOpen) sqlDatabase.endTransaction()
            scenario.close()
        }
    }

    private suspend fun withViewModel(
        transactions: Flow<List<Transaction>>,
        test: suspend (MainViewModel) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val store = ViewModelStore()
        val repository = SimpleFinSyncRepository(context)
        val viewModel = ViewModelProvider(
            store,
            MainViewModel.Factory(FakeTransactionGateway(transactions), repository, MutableStateFlow(emptyList())),
        )[MainViewModel::class.java]
        try {
            test(viewModel)
        } finally {
            store.clear()
        }
    }

    private class FakeTransactionGateway(
        override val transactions: Flow<List<Transaction>>,
    ) : TransactionGateway {
        override suspend fun load() = emptyList<Transaction>()
        override suspend fun upsert(transaction: Transaction) = Unit
        override suspend fun importTransactions(transactions: List<Transaction>) = transactions.size
        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = transactions.size
        override suspend fun delete(id: String) = Unit
    }

    private fun transaction(id: String) = Transaction(
        id = id,
        occurredAtEpochMillis = 1_765_000_000_000,
        merchant = "Legacy merchant",
        category = "Other",
        note = "",
        cents = -100,
    )
}
