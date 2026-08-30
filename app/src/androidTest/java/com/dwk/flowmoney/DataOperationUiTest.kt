package com.dwk.flowmoney

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DataOperationUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun activeOperationAnnouncesProgressAndDisablesConflictingControls() {
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(),
                    operation = DataOperation.Import,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("data_operation_status")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Importing CSV"))
        composeRule.onNodeWithTag("csv_import_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("csv_export_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("simplefin_setup_token").assertIsNotEnabled()
        composeRule.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test
    fun activeBankOperationDisablesSyncDisconnectAndCsvControls() {
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(profile = SimpleFinProfileEntity(connectionId = "test")),
                    operation = DataOperation.Sync,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("data_operation_status")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Syncing bank"))
        composeRule.onNodeWithTag("simplefin_sync_button").assertIsNotEnabled()
        composeRule.onNodeWithText("Disconnect").assertIsNotEnabled()
        composeRule.onNodeWithTag("csv_import_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("csv_export_button").assertIsNotEnabled()
    }

    @Test
    fun simpleFinConnectionStatusCoversEveryStateWithAccessibilitySemantics() {
        val simpleFin = mutableStateOf(SimpleFinUiState())
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = simpleFin.value,
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        fun assertStatus(expected: String) {
            composeRule
                .onNodeWithTag("simplefin_connection_status")
                .assertIsDisplayed()
                .assertContentDescriptionEquals("SimpleFIN connection status")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
            composeRule.onNodeWithText(expected).assertIsDisplayed()
        }

        assertStatus("Not connected")
        composeRule.runOnIdle {
            simpleFin.value = SimpleFinUiState(profile = SimpleFinProfileEntity(connectionId = "connected"))
        }
        assertStatus("Connected")
        composeRule.runOnIdle {
            simpleFin.value =
                SimpleFinUiState(
                    profile = SimpleFinProfileEntity(connectionId = "paused", isPaused = true),
                )
        }
        assertStatus("Reconnect required")
        composeRule.runOnIdle {
            simpleFin.value = SimpleFinUiState(isConnectionPending = true)
        }
        assertStatus("Not connected")
        composeRule.runOnIdle {
            simpleFin.value =
                SimpleFinUiState(
                    profile = SimpleFinProfileEntity(connectionId = "connected-pending"),
                    isConnectionPending = true,
                )
        }
        assertStatus("Connected")
    }

    @Test
    fun resetDaysCardAppearsForEverySimpleFinConnectionState() {
        val simpleFin =
            mutableStateOf(
                SimpleFinUiState(profile = SimpleFinProfileEntity(connectionId = "connected")),
            )
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = simpleFin.value,
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        fun assertResetCardVisible() {
            composeRule
                .onNodeWithTag("data_sheet_list")
                .performScrollToNode(hasTestTag("simplefin_reset_days_card"))
            composeRule.onNodeWithTag("simplefin_reset_days_card").assertIsDisplayed()
        }

        assertResetCardVisible()
        composeRule.runOnIdle {
            simpleFin.value =
                SimpleFinUiState(
                    profile = SimpleFinProfileEntity(connectionId = "paused", isPaused = true),
                )
        }
        assertResetCardVisible()
        composeRule.runOnIdle {
            simpleFin.value =
                SimpleFinUiState(
                    profile = SimpleFinProfileEntity(connectionId = "pending-profile"),
                    isConnectionPending = true,
                )
        }
        assertResetCardVisible()

        composeRule.runOnIdle { simpleFin.value = SimpleFinUiState(isConnectionPending = true) }
        assertResetCardVisible()
        composeRule.runOnIdle { simpleFin.value = SimpleFinUiState() }
        assertResetCardVisible()
    }

    @Test
    fun resetDaysPickerLoadsSelectedRangeAndAffectedCountAsynchronously() {
        val today = LocalDate.of(2026, 3, 15)
        val countStarted = CompletableDeferred<Unit>()
        val releaseCount = CompletableDeferred<TransactionRangeCount>()
        var resetRequest: Pair<PennyLocalDateRange, TransactionRangeCount>? = null
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(profile = SimpleFinProfileEntity(connectionId = "connected")),
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onCountResetDays = { _, _ ->
                        countStarted.complete(Unit)
                        releaseCount.await()
                    },
                    onResetDays = { range, _, count -> resetRequest = range to count },
                    resetDaysClock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC),
                    resetDaysZoneId = ZoneOffset.UTC,
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_picker_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_card").assertIsDisplayed()
        composeRule
            .onNodeWithText("Manually added and CSV-imported rows cannot be downloaded.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_picker_button").performClick()
        composeRule.onNodeWithTag("simplefin_reset_days_picker_dialog", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Select dates to resync").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitUntil(5_000) { countStarted.isCompleted }

        composeRule.onNodeWithTag("simplefin_reset_days_count_busy").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_picker_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("simplefin_reset_days_reset_button").assertIsNotEnabled()
        releaseCount.complete(TransactionRangeCount(transactionCount = 2, tombstoneCount = 1))
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule
                    .onNodeWithTag("simplefin_reset_days_count")
                    .assertTextContains("3 items affected: 2 transactions + 1 deleted SimpleFIN record")
            }.isSuccess
        }

        composeRule.onNodeWithTag("simplefin_reset_days_range").assertTextContains("March 15, 2026")
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_reset_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_reset_button").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(
                PennyLocalDateRange(today, today.plusDays(1)) to
                    TransactionRangeCount(transactionCount = 2, tombstoneCount = 1),
                resetRequest,
            )
        }
    }

    @Test
    fun resetDaysCountFailureCanRetryToZeroWithoutLosingSelection() {
        var attempts = 0
        var resetCount: TransactionRangeCount? = null
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(profile = SimpleFinProfileEntity(connectionId = "connected")),
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onCountResetDays = { _, _ ->
                        attempts += 1
                        if (attempts == 1) error("synthetic count failure")
                        TransactionRangeCount(transactionCount = 0, tombstoneCount = 0)
                    },
                    onResetDays = { _, _, count -> resetCount = count },
                    resetDaysClock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC),
                    resetDaysZoneId = ZoneOffset.UTC,
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_picker_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_picker_button").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("Could not count affected data. Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_count_retry_button").performClick()
        composeRule
            .onNodeWithTag("simplefin_reset_days_count")
            .assertTextContains("0 items affected: 0 transactions + 0 deleted SimpleFIN records")
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_reset_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_reset_button").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(TransactionRangeCount(transactionCount = 0, tombstoneCount = 0), resetCount)
        }
    }

    @Test
    fun pendingConnectionRetriesWithoutTokenAndCanStartOver() {
        var retries = 0
        var cancellations = 0
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(isConnectionPending = true),
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = { retries++ },
                    onCancelPendingConnection = { cancellations++ },
                )
            }
        }

        composeRule.onNodeWithTag("simplefin_pending_card").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_token").assertDoesNotExist()
        composeRule
            .onNodeWithTag("simplefin_retry_connection_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag("simplefin_cancel_pending_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, retries)
        assertEquals(1, cancellations)
    }

    @Test
    fun merchantRulesListExactValuesAndRequireIndividualDeleteConfirmation() {
        var deletedKey: String? = null
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(),
                    operation = null,
                    merchantRules =
                        listOf(
                            MerchantRuleEntity(
                                normalizedProviderMerchant = "coffee shop 42",
                                category = "Coffee",
                                merchantOverride = "Morning coffee",
                            ),
                        ),
                    onDeleteMerchantRule = { deletedKey = it },
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("merchant_rules_card"))
        composeRule.onNodeWithText("coffee shop 42").assertIsDisplayed()
        composeRule.onNodeWithText("Coffee · Display: Morning coffee").assertIsDisplayed()
        composeRule.onNodeWithTag("delete_merchant_rule_0").performClick()
        composeRule.onNodeWithText("Existing transactions will not be rewritten.", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertNull(deletedKey) }
        composeRule.onNodeWithTag("confirm_delete_merchant_rule").performClick()
        composeRule.runOnIdle { assertEquals("coffee shop 42", deletedKey) }
    }

    @Test
    fun successfulConnectionUsesDedicatedSnackbarMessage() {
        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                val result = SimpleFinSyncResult.Success(inserted = 2, updated = 1, skipped = 0)
                snackbarHostState.showSnackbar(result.connectionSnackbarMessage())
            }
            MaterialTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    DataSheet(
                        simpleFin = SimpleFinUiState(),
                        operation = null,
                        onOpenSetup = {},
                        onConnect = {},
                        onSync = {},
                        onImport = {},
                        onExport = {},
                        onDisconnect = {},
                        onClose = {},
                        onRetryConnection = {},
                        onCancelPendingConnection = {},
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }

        composeRule.onNodeWithText("SimpleFIN connected").assertIsDisplayed()
        composeRule.onNodeWithText("Synced 2 new transactions, 1 updated transaction").assertDoesNotExist()
    }

    @Test
    fun resultSnackbarDoesNotKeepDataControlsDisabled() {
        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { snackbarHostState.showSnackbar("CSV exported") }
            MaterialTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    DataSheet(
                        simpleFin = SimpleFinUiState(),
                        operation = null,
                        onOpenSetup = {},
                        onConnect = {},
                        onSync = {},
                        onImport = {},
                        onExport = {},
                        onDisconnect = {},
                        onClose = {},
                        onRetryConnection = {},
                        onCancelPendingConnection = {},
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }

        composeRule.onNodeWithText("CSV exported").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_import_button").assertIsEnabled()
        composeRule.onNodeWithTag("csv_export_button").assertIsEnabled()
    }

    @Test
    fun activeSuspendedOperationKeepsMaterialSheetVisibleAfterSwipeTowardHidden() {
        var dismissed = false
        composeRule.setContent {
            val operation = remember { mutableStateOf<DataOperation?>(null) }
            LaunchedEffect(Unit) {
                operation.value = DataOperation.ResetDays
                awaitCancellation()
            }
            MaterialTheme {
                DataSheetModal(operation = operation.value, onDismissRequest = { dismissed = true }) {
                    DataSheet(
                        simpleFin = SimpleFinUiState(),
                        operation = operation.value,
                        onOpenSetup = {},
                        onConnect = {},
                        onSync = {},
                        onImport = {},
                        onExport = {},
                        onDisconnect = {},
                        onClose = {},
                        onRetryConnection = {},
                        onCancelPendingConnection = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("data_sheet_modal").performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertFalse(dismissed)
        composeRule.onNodeWithTag("data_sheet_modal").assertIsDisplayed()
        composeRule.onNodeWithTag("data_operation_status").assertIsDisplayed()
    }

    @Test
    fun geminiCardShowsSavedAndAutoCategorizeOn() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        val keyStore = GeminiApiKeyStore(context)
        val statusStore = GeminiRunStatusStore(context)
        try {
            keyStore.save(EXISTING_GEMINI_KEY)
            statusStore.recordSuccess(
                atEpochMillis = System.currentTimeMillis() - TWELVE_MINUTES_AGO,
                labeled = 7,
                queueEmpty = false,
            )
            val viewModel =
                MainViewModel(
                    repository = GeminiCardGateway(),
                    simpleFinRepository = SimpleFinSyncRepository(context),
                    simpleFinAccounts = MutableStateFlow(emptyList()),
                )
            viewModel.reportInitializationComplete()
            composeRule.setContent {
                FlowMoneyTheme(dynamicColor = false) {
                    FlowMoneyApp(
                        viewModel = viewModel,
                        coldStartSimpleFinSync = { null },
                        transactionWidgetRefresh = {},
                    )
                }
            }
            composeRule.waitUntil(5_000) {
                runCatching { composeRule.onNodeWithText("Penny").assertIsDisplayed() }.isSuccess
            }

            composeRule.onNodeWithText("Data").performClick()
            scrollToGeminiCard()
            composeRule.onNodeWithTag("gemini_key_status").assertTextContains("Auto-categorize on · key saved")
            composeRule
                .onNodeWithText("marked reviewed automatically", substring = true)
                .assertIsDisplayed()
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule
                        .onNodeWithTag("gemini_key_run_status")
                        .assertTextContains("Last run 12 min ago · 7 categorized and confirmed")
                }.isSuccess
            }

            // Saving a replacement key resets the recorded run and confirms the state change.
            composeRule.onNodeWithTag("gemini_key_field").performTextInput(REPLACEMENT_GEMINI_KEY)
            composeRule.onNodeWithTag("gemini_key_save_button").performClick()
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule
                        .onNodeWithText("Gemini key saved. Auto-categorize runs after each sync.")
                        .assertIsDisplayed()
                }.isSuccess
            }
            scrollToGeminiCard()
            composeRule.onNodeWithTag("gemini_key_status").assertTextContains("Auto-categorize on · key saved")
            composeRule.onNodeWithTag("gemini_key_run_status").assertTextContains("Runs after the next sync")
            assertEquals(REPLACEMENT_GEMINI_KEY, keyStore.read())
            assertEquals(GeminiRunStatus(), statusStore.load())

            // Clearing the key turns auto-categorize off and drops the run line entirely.
            composeRule.onNodeWithTag("gemini_key_clear_button").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule
                        .onNodeWithTag("gemini_key_status")
                        .assertTextContains("Auto-categorize off · no key saved")
                }.isSuccess
            }
            composeRule.onNodeWithTag("gemini_key_run_status").assertDoesNotExist()
            composeRule.onNodeWithTag("gemini_key_clear_button").assertDoesNotExist()
            composeRule.waitUntil(10_000) {
                runCatching {
                    composeRule
                        .onNodeWithText("Gemini key removed. Auto-categorize off.")
                        .assertIsDisplayed()
                }.isSuccess
            }
            assertNull(keyStore.read())
            assertEquals(GeminiRunStatus(), statusStore.load())
        } finally {
            keyStore.delete()
            statusStore.clear()
            FlowMoneyDatabase.resetForTest()
            context.deleteDatabase("flow_money.db")
        }
    }

    @Test
    fun geminiRunStatusLineCoversEveryRecordedRunState() {
        val keySaved = mutableStateOf(true)
        val status = mutableStateOf(GeminiRunStatus())
        composeRule.setContent {
            MaterialTheme {
                DataSheet(
                    simpleFin = SimpleFinUiState(),
                    operation = null,
                    geminiKeySaved = keySaved.value,
                    geminiStatus = status.value,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                )
            }
        }

        fun assertRunStatus(expected: String) {
            scrollToGeminiCard()
            composeRule.onNodeWithTag("gemini_key_run_status").assertTextContains(expected)
        }

        scrollToGeminiCard()
        composeRule.onNodeWithTag("gemini_key_status").assertTextContains("Auto-categorize on · key saved")
        assertRunStatus("Runs after the next sync")

        val twelveMinutesAgo = System.currentTimeMillis() - TWELVE_MINUTES_AGO
        composeRule.runOnIdle {
            status.value = GeminiRunStatus(lastRunAtEpochMillis = twelveMinutesAgo, lastLabeled = 7)
        }
        assertRunStatus("Last run 12 min ago · 7 categorized and confirmed")
        composeRule.runOnIdle {
            status.value = GeminiRunStatus(lastRunAtEpochMillis = twelveMinutesAgo, lastLabeled = 0)
        }
        assertRunStatus("Last run 12 min ago · 0 categorized and confirmed")
        composeRule.runOnIdle {
            status.value =
                GeminiRunStatus(lastRunAtEpochMillis = twelveMinutesAgo, lastQueueEmpty = true)
        }
        assertRunStatus("Last run 12 min ago · nothing to categorize")
        composeRule.runOnIdle {
            status.value = GeminiRunStatus(lastRunAtEpochMillis = twelveMinutesAgo, lastFailed = true)
        }
        assertRunStatus("Last run failed · retries after the next sync")

        composeRule.runOnIdle { keySaved.value = false }
        scrollToGeminiCard()
        composeRule.onNodeWithTag("gemini_key_status").assertTextContains("Auto-categorize off · no key saved")
        composeRule.onNodeWithTag("gemini_key_run_status").assertDoesNotExist()
        composeRule.onNodeWithTag("gemini_key_clear_button").assertDoesNotExist()
    }

    private fun scrollToGeminiCard() {
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("gemini_key_card"))
    }

    private class GeminiCardGateway : TransactionGateway {
        private val rows = MutableStateFlow<List<Transaction>>(emptyList())
        override val transactions: Flow<List<Transaction>> = rows

        override suspend fun load(): List<Transaction> = rows.value

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    @Test
    fun pickerCallbackUsesKnownOperationWhenRecreatedStateWasLost() {
        val uri = Uri.parse("content://test/recreated.csv")

        assertEquals(DataOperation.Import, pickerResultOperation(uri, DataOperation.Import))
        assertEquals(DataOperation.Export, pickerResultOperation(uri, DataOperation.Export))
        assertNull(pickerResultOperation(null, DataOperation.Import))
    }

    private companion object {
        const val EXISTING_GEMINI_KEY = "existing-gemini-key-fixture"
        const val REPLACEMENT_GEMINI_KEY = "replacement-gemini-key-fixture"

        /** Twelve minutes plus a little slack so the rendered relative time stays "12 min ago". */
        const val TWELVE_MINUTES_AGO = 12 * 60_000L + 5_000L
    }
}
