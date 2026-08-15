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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

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
                operation.value = DataOperation.Import
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
