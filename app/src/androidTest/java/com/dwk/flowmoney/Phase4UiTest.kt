package com.dwk.flowmoney

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase4UiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyOverviewActionsCallAddAndDataCallbacks() {
        var adds = 0
        var data = 0
        setEmptyScreen(DashboardTab.Overview, onAdd = { adds++ }, onData = { data++ })

        composeRule.onNodeWithTag("empty_add_transaction").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("empty_data_action").assertIsDisplayed().performClick()

        assertEquals(1, adds)
        assertEquals(1, data)
    }

    @Test
    fun emptyTransactionsStateRemainsActionable() {
        var adds = 0
        var data = 0
        setEmptyScreen(DashboardTab.Transactions, onAdd = { adds++ }, onData = { data++ })

        composeRule.onNodeWithTag("empty_add_transaction").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("empty_data_action").assertIsDisplayed().performClick()

        assertEquals(1, adds)
        assertEquals(1, data)
    }

    @Test
    fun wideWindowCentersAndConstrainsOverviewContent() {
        var forcedDensity = 0f
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(1_000.dp, 600.dp))) {
                val density = LocalDensity.current
                SideEffect { forcedDensity = density.density }
                MaterialTheme {
                    FlowMoneyScreen(
                        uiState = MainUiState(isLoading = false),
                        selectedTab = DashboardTab.Overview,
                        onChartRangeModeSelected = {},
                        onSelectedMonthChange = {},
                        onEdit = {},
                        onViewAllTransactions = {},
                        onDelete = {},
                        onAddTransaction = {},
                        onData = {},
                        modifier = Modifier,
                    )
                }
            }
        }

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val page = composeRule.onNodeWithTag("overview_list").fetchSemanticsNode().boundsInRoot
        val maxPageWidth = 720.dp.value * forcedDensity
        assertTrue("page width=${page.width}, expected=$maxPageWidth", kotlin.math.abs(page.width - maxPageWidth) <= 2f)
        assertTrue("page left=${page.left}, root=$root, page=$page", kotlin.math.abs(page.left - (root.width - page.width) / 2f) <= 2f)
        composeRule.onNodeWithTag("empty_add_transaction").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("empty_data_action").assertIsDisplayed()
    }

    @Test
    fun phoneLandscapeKeepsOverviewAndDataActionsReachable() {
        composeRule.setContent {
            var showData by remember { mutableStateOf(false) }
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(700.dp, 360.dp))) {
                MaterialTheme {
                    if (showData) {
                        DataSheet(
                            simpleFin = SimpleFinUiState(),
                            operation = null,
                            onOpenSetup = {},
                            onConnect = {},
                            onSync = {},
                            onImport = {},
                            onExport = {},
                            onDisconnect = {},
                            onClose = { showData = false },
                        )
                    } else {
                        FlowMoneyScreen(
                            uiState = MainUiState(isLoading = false),
                            selectedTab = DashboardTab.Overview,
                            onChartRangeModeSelected = {},
                            onSelectedMonthChange = {},
                            onEdit = {},
                            onViewAllTransactions = {},
                            onDelete = {},
                            onAddTransaction = {},
                            onData = { showData = true },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("date_range_controls").assertIsDisplayed()
        composeRule.onNodeWithTag("empty_add_transaction").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("empty_data_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("csv_import_button").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_export_button").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_token").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fontScaleKeepsSummaryReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.3f)) {
                MaterialTheme {
                    FlowMoneyScreen(
                        uiState = nonEmptyState(),
                        selectedTab = DashboardTab.Overview,
                        onChartRangeModeSelected = {},
                        onSelectedMonthChange = {},
                        onEdit = {},
                        onViewAllTransactions = {},
                        onDelete = {},
                        onAddTransaction = {},
                        onData = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Income").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Net").assertIsDisplayed()
    }

    @Test
    fun fontScaleKeepsDataActionsReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.3f)) {
                MaterialTheme {
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
                    )
                }
            }
        }
        composeRule.onNodeWithTag("csv_import_button").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_export_button").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_token").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fontScaleKeepsEditorDateAndTimeControlsSeparateAndReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.3f)) {
                MaterialTheme {
                    TransactionEditor(
                        transaction = null,
                        draft = newEditorDraft(),
                        suggestionHistory = TransactionSuggestionHistory.Empty,
                        onDraftChange = {},
                        onSave = {},
                        onDelete = null,
                        onCancel = {},
                        persistenceBusy = false,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("date_picker_button"))
        val date = composeRule.onNodeWithTag("date_picker_button").fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithTag("time_picker_button").fetchSemanticsNode().boundsInRoot
        assertTrue(date.bottom <= time.top || date.right <= time.left)
        composeRule.onNodeWithTag("date_picker_button").assertIsDisplayed()
        composeRule.onNodeWithTag("time_picker_button").assertIsDisplayed()
    }

    private fun setEmptyScreen(tab: DashboardTab, onAdd: () -> Unit, onData: () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyScreen(
                    uiState = MainUiState(isLoading = false),
                    selectedTab = tab,
                    onChartRangeModeSelected = {},
                    onSelectedMonthChange = {},
                    onEdit = {},
                    onViewAllTransactions = {},
                    onDelete = {},
                    onAddTransaction = onAdd,
                    onData = onData,
                )
            }
        }
    }

    private fun nonEmptyState(): MainUiState {
        val transaction = Transaction(
            id = "phase4",
            occurredAtEpochMillis = 1L,
            merchant = "Cafe",
            category = "Food",
            note = "",
            cents = -450,
        )
        return MainUiState(
            isLoading = false,
            sortedTransactions = listOf(transaction),
            recentTransactions = listOf(transaction),
            rangeTransactions = listOf(transaction),
            metrics = DashboardMetrics(spentCents = 450, incomeCents = 0, transactionCount = 1),
        )
    }
}
