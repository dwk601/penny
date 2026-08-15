package com.dwk.flowmoney

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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
    fun pickerCallbackUsesKnownOperationWhenRecreatedStateWasLost() {
        val uri = Uri.parse("content://test/recreated.csv")

        assertEquals(DataOperation.Import, pickerResultOperation(uri, DataOperation.Import))
        assertEquals(DataOperation.Export, pickerResultOperation(uri, DataOperation.Export))
        assertNull(pickerResultOperation(null, DataOperation.Import))
    }
}
