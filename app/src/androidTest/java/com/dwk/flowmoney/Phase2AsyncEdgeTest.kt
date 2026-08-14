package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class Phase2AsyncEdgeTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: ViewModelStore

    @Before
    fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        store = ViewModelStore()
    }

    @After
    fun clearViewModel() {
        store.clear()
        FlowMoneyDatabase.resetForTest()
    }

    @Test
    fun startupSyncFailureDoesNotCrashComposition() {
        val syncCalls = AtomicInteger()
        setApp(
            gateway = FakeGateway(),
            coldStartSimpleFinSync = {
                syncCalls.incrementAndGet()
                throw IllegalStateException("profile query unavailable")
            },
        )

        composeRule.waitUntil(5_000) { syncCalls.get() == 1 }
        composeRule.onNodeWithText("Penny").assertIsDisplayed()
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
    }

    @Test
    fun startupWidgetRefreshFailureDoesNotCrashComposition() {
        val widgetRefreshCalls = AtomicInteger()
        setApp(
            gateway = FakeGateway(),
            coldStartSimpleFinSync = { SimpleFinSyncResult.Success(inserted = 1, updated = 0, skipped = 0) },
            transactionWidgetRefresh = {
                widgetRefreshCalls.incrementAndGet()
                throw IllegalStateException("widget service unavailable")
            },
        )

        composeRule.waitUntil(5_000) { widgetRefreshCalls.get() == 1 }
        composeRule.onNodeWithText("Penny").assertIsDisplayed()
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
    }

    @Test
    fun connectSuccessMessageSurvivesWidgetRefreshFailure() {
        val widgetRefreshCalls = AtomicInteger()
        var receivedToken: String? = null
        setApp(
            gateway = FakeGateway(),
            connectSimpleFin = { token ->
                receivedToken = token
                SimpleFinSyncResult.Success(inserted = 1, updated = 2, skipped = 0)
            },
            transactionWidgetRefresh = {
                widgetRefreshCalls.incrementAndGet()
                throw IllegalStateException("widget service unavailable")
            },
        )

        composeRule.onNodeWithText("Data").performClick()
        composeRule.onNodeWithTag("simplefin_setup_token").performScrollTo().performTextInput("setup-token")
        composeRule.onNodeWithText("Connect").performClick()

        composeRule.onNodeWithText("Synced 1 new transaction, 2 updated transactions").assertIsDisplayed()
        assertEquals("setup-token", receivedToken)
        assertEquals(1, widgetRefreshCalls.get())
        assertTrue(composeRule.onAllNodesWithText("Bank connection failed").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun manualSyncSuccessMessageSurvivesWidgetRefreshFailure() {
        val widgetRefreshCalls = AtomicInteger()
        setApp(
            gateway = FakeGateway(),
            simpleFinProfile = SimpleFinProfileEntity(connectionId = "manual-sync"),
            coldStartSimpleFinSync = { null },
            manualSimpleFinSync = {
                SimpleFinSyncResult.Success(inserted = 2, updated = 1, skipped = 0)
            },
            transactionWidgetRefresh = {
                widgetRefreshCalls.incrementAndGet()
                throw IllegalStateException("widget service unavailable")
            },
        )

        composeRule.onNodeWithText("Data").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("simplefin_sync_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("simplefin_sync_button").performScrollTo().performClick()

        composeRule.onNodeWithText("Synced 2 new transactions, 1 updated transaction").assertIsDisplayed()
        assertEquals(1, widgetRefreshCalls.get())
        assertTrue(composeRule.onAllNodesWithText("Bank sync failed").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun recreationDuringSaveRecoversControlsAndRetriesWithoutPersistingInterruptedAttempt() {
        val firstStarted = CompletableDeferred<Unit>()
        val gateway = FakeGateway()
        gateway.onUpsert = { transaction ->
            gateway.upserts += transaction
            if (gateway.upserts.size == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                gateway.rows.value = listOf(transaction)
            }
        }
        val restoration = StateRestorationTester(composeRule)
        setApp(gateway, restoration)

        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_5").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Interrupted save")
        composeRule.onNodeWithTag("save_transaction_button").performClick()
        composeRule.waitUntil(5_000) { firstStarted.isCompleted }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("save_transaction_button").assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.size == 1 }
        assertEquals(2, gateway.upserts.size)
        assertEquals(
            2,
            gateway.upserts
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(gateway.upserts.last(), gateway.rows.value.single())
    }

    @Test
    fun failedNewSaveKeepsEditorStateAndRetryPersistsOneTransaction() {
        val gateway = FakeGateway()
        gateway.onUpsert = { transaction ->
            gateway.upserts += transaction
            if (gateway.upserts.size == 1) {
                throw IllegalStateException("database unavailable")
            }
            gateway.rows.value = listOf(transaction)
        }
        setApp(gateway)

        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_5").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Retry Cafe")
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.onNodeWithText("Could not save transaction.").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("merchant_field").assertTextContains("Retry Cafe")
        composeRule.onNodeWithTag("save_transaction_button").assertIsEnabled()
        assertTrue(composeRule.onAllNodesWithTag("delete_transaction_button").fetchSemanticsNodes().isEmpty())
        assertTrue(gateway.rows.value.isEmpty())
        assertEquals(1, gateway.upserts.size)

        composeRule.onNodeWithTag("save_transaction_button").performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.size == 1 }

        assertEquals(2, gateway.upserts.size)
        assertEquals(
            2,
            gateway.upserts
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(gateway.upserts.last(), gateway.rows.value.single())
    }

    @Test
    fun successfulSaveClosesAndPersistsWhenWidgetRefreshFails() {
        val gateway = FakeGateway()
        val widgetRefreshCalls = AtomicInteger()
        setApp(
            gateway = gateway,
            transactionWidgetRefresh = {
                widgetRefreshCalls.incrementAndGet()
                throw IllegalStateException("widget service unavailable")
            },
        )

        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_5").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Saved Cafe")
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.waitUntil(5_000) {
            gateway.rows.value
                .singleOrNull()
                ?.merchant == "Saved Cafe" &&
                widgetRefreshCalls.get() == 1 &&
                composeRule.onAllNodesWithTag("transaction_editor").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, gateway.upserts.size)
        assertTrue(composeRule.onAllNodesWithText("Could not save transaction.").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun failedEditPreservesOriginalIdAndDraft() {
        val original = transaction("edit-failure", "Original Cafe")
        val gateway =
            FakeGateway(listOf(original)).apply {
                onUpsert = { transaction ->
                    upserts += transaction
                    throw IllegalStateException("database unavailable")
                }
            }
        setApp(gateway)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        val merchantField = composeRule.onNodeWithTag("merchant_field")
        merchantField.performScrollTo()
        merchantField.performTextClearance()
        merchantField.performTextInput("Original Cafe Updated")
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.onNodeWithText("Could not save transaction.").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("merchant_field").assertTextContains("Original Cafe Updated")
        composeRule.onNodeWithTag("save_transaction_button").assertIsEnabled()
        composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled()
        assertEquals(listOf(original), gateway.rows.value)
        assertEquals(original.id, gateway.upserts.single().id)
    }

    @Test
    fun deleteFailureClearsBusyAndKeepsEditorDraftOpen() {
        val original = transaction("delete-failure", "Failure Cafe")
        val gateway =
            FakeGateway(listOf(original)).apply {
                onDelete = { throw IllegalStateException("database unavailable") }
            }
        setApp(gateway)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithTag("confirm_delete_button").performClick()

        composeRule.onNodeWithText("Could not delete", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("merchant_field").assertTextContains(original.merchant)
        composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled()
        assertEquals(listOf(original), gateway.rows.value)
    }

    @Test
    fun undoFailureReportsRestoreFailureAndLeavesRowDeleted() {
        val original = transaction("undo-failure", "Restore Cafe")
        val gateway =
            FakeGateway(listOf(original)).apply {
                onUpsert = { transaction ->
                    upserts += transaction
                    throw IllegalStateException("database unavailable")
                }
            }
        setApp(gateway)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithTag("confirm_delete_button").performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.isEmpty() }
        composeRule.onNodeWithText("Undo").performClick()

        composeRule.onNodeWithText("Could not restore", substring = true).assertIsDisplayed()
        assertTrue(gateway.rows.value.isEmpty())
        assertEquals(1, gateway.deleteCalls)
        assertEquals(listOf(original), gateway.upserts)
        assertTrue(composeRule.onAllNodesWithText("Could not delete", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deleteAndUndoPersistWhenWidgetRefreshFails() {
        val original = transaction("widget-refresh-failure", "Widget Cafe")
        val gateway = FakeGateway(listOf(original))
        val widgetRefreshCalls = AtomicInteger()
        setApp(
            gateway = gateway,
            transactionWidgetRefresh = {
                widgetRefreshCalls.incrementAndGet()
                throw IllegalStateException("widget service unavailable")
            },
        )

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithTag("confirm_delete_button").performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.isEmpty() }
        composeRule.onNodeWithText("Undo").performClick()

        composeRule.waitUntil(5_000) {
            gateway.rows.value.singleOrNull() == original && widgetRefreshCalls.get() == 2
        }
        assertEquals(1, gateway.deleteCalls)
        assertEquals(listOf(original), gateway.upserts)
        assertTrue(composeRule.onAllNodesWithText("Could not delete", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Could not restore", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun inFlightUndoDisablesEditorPersistenceAndRunsOneRestore() {
        val original = transaction("undo-interleaving", "Restore First Cafe")
        val editorTarget = transaction("undo-editor", "Keep Editing Cafe")
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val gateway =
            FakeGateway(listOf(original, editorTarget)).apply {
                onUpsert = { transaction ->
                    upserts += transaction
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                    rows.value = rows.value.filterNot { it.id == transaction.id } + transaction
                }
            }
        setApp(gateway)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithTag("confirm_delete_button").performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.none { it.id == original.id } }
        val undoAction =
            composeRule
                .onNodeWithText("Undo")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!

        composeRule.onNodeWithTag("transaction_content_${editorTarget.id}").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        val saveAction =
            composeRule
                .onNodeWithTag("save_transaction_button")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val deleteAction =
            composeRule
                .onNodeWithTag("delete_transaction_button")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            undoAction()
            undoAction()
        }
        composeRule.waitUntil(5_000) { restoreStarted.isCompleted }

        composeRule.onNodeWithTag("save_transaction_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("delete_transaction_button").assertIsNotEnabled()
        composeRule.runOnIdle {
            saveAction()
            deleteAction()
        }
        assertEquals(1, gateway.upserts.size)
        assertTrue(composeRule.onAllNodesWithTag("confirm_delete_button").fetchSemanticsNodes().isEmpty())

        releaseRestore.complete(Unit)
        composeRule.waitUntil(5_000) { gateway.rows.value.any { it.id == original.id } }
        composeRule.onNodeWithTag("save_transaction_button").assertIsEnabled()
        composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled()
        assertEquals(listOf(original), gateway.upserts)
    }

    @Test
    fun deleteCancellationIsNotReportedAsFailure() {
        val original = transaction("delete-cancelled", "Cancelled Cafe")
        val deleteStarted = CompletableDeferred<Unit>()
        val gateway =
            FakeGateway(listOf(original)).apply {
                onDelete = {
                    deleteStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        val restoration = StateRestorationTester(composeRule)
        setApp(gateway, restoration)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithTag("confirm_delete_button").performClick()
        composeRule.waitUntil(5_000) { deleteStarted.isCompleted }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled()
        assertTrue(composeRule.onAllNodesWithText("Could not delete", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(listOf(original), gateway.rows.value)
    }

    @Test
    fun rapidDuplicateDeleteRunsOneDeleteAndOneUndoPath() {
        val original = transaction("duplicate-delete", "One Delete Cafe")
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val gateway =
            FakeGateway(listOf(original)).apply {
                onDelete = { id ->
                    deleteCalls++
                    deleteStarted.complete(Unit)
                    releaseDelete.await()
                    rows.value = rows.value.filterNot { it.id == id }
                }
            }
        setApp(gateway)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        val click =
            composeRule
                .onNodeWithTag("confirm_delete_button")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            click()
            click()
        }
        composeRule.waitUntil(5_000) { deleteStarted.isCompleted }
        assertEquals(1, gateway.deleteCalls)

        releaseDelete.complete(Unit)
        composeRule.waitUntil(5_000) { gateway.rows.value.isEmpty() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitUntil(5_000) { gateway.rows.value.singleOrNull() == original }
        assertEquals(1, gateway.deleteCalls)
        assertEquals(1, gateway.upserts.size)
        assertEquals(original, gateway.rows.value.single())
    }

    private fun setApp(
        gateway: FakeGateway,
        restoration: StateRestorationTester? = null,
        transactionWidgetRefresh: suspend (Context) -> Unit = {},
        coldStartSimpleFinSync: (suspend () -> SimpleFinSyncResult?)? = null,
        connectSimpleFin: (suspend (String) -> SimpleFinSyncResult)? = null,
        manualSimpleFinSync: (suspend () -> SimpleFinSyncResult)? = null,
        simpleFinProfile: SimpleFinProfileEntity? = null,
    ) {
        val expectedTransactionIds = gateway.rows.value.map { it.id }
        val db = FlowMoneyDatabase.get(context)
        simpleFinProfile?.let { profile ->
            runBlocking { db.simpleFinDao().upsertProfile(profile) }
        }
        val simpleFinRepository = SimpleFinSyncRepository(context)
        val viewModel =
            ViewModelProvider(
                store,
                MainViewModel.Factory(gateway, simpleFinRepository, MutableStateFlow(emptyList())),
            )[MainViewModel::class.java]
        viewModel.reportInitializationComplete()
        val syncOnColdStart = coldStartSimpleFinSync ?: viewModel::syncSimpleFinIfStale
        val connect = connectSimpleFin ?: viewModel::connectSimpleFin
        val syncManually = manualSimpleFinSync ?: viewModel::syncSimpleFinNow
        if (restoration == null) {
            composeRule.setContent {
                FlowMoneyApp(
                    viewModel = viewModel,
                    transactionWidgetRefresh = transactionWidgetRefresh,
                    coldStartSimpleFinSync = syncOnColdStart,
                    connectSimpleFin = connect,
                    manualSimpleFinSync = syncManually,
                )
            }
        } else {
            restoration.setContent {
                FlowMoneyApp(
                    viewModel = viewModel,
                    transactionWidgetRefresh = transactionWidgetRefresh,
                    coldStartSimpleFinSync = syncOnColdStart,
                    connectSimpleFin = connect,
                    manualSimpleFinSync = syncManually,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Penny").fetchSemanticsNodes().isNotEmpty() &&
                expectedTransactionIds.all { id ->
                    composeRule
                        .onAllNodesWithTag("transaction_content_$id")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
        }
    }

    private class FakeGateway(
        initial: List<Transaction> = emptyList(),
    ) : TransactionGateway {
        val rows = MutableStateFlow(initial)
        override val transactions: Flow<List<Transaction>> = rows
        val upserts = mutableListOf<Transaction>()
        var deleteCalls = 0
        var onUpsert: suspend (Transaction) -> Unit = { transaction ->
            upserts += transaction
            rows.value = rows.value.filterNot { it.id == transaction.id } + transaction
        }
        var onDelete: suspend (String) -> Unit = { id ->
            deleteCalls++
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun load() = rows.value

        override suspend fun upsert(transaction: Transaction) = onUpsert(transaction)

        override suspend fun importTransactions(transactions: List<Transaction>): Int {
            rows.value = transactions
            return transactions.size
        }

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>) = importTransactions(transactions)

        override suspend fun delete(id: String) = onDelete(id)
    }

    private fun transaction(
        id: String,
        merchant: String,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis = Instant.now().toEpochMilli(),
        merchant = merchant,
        category = "Food",
        note = "Edge test",
        cents = -500,
    )
}
