package com.dwk.flowmoney

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
                            onRetryConnection = {},
                            onCancelPendingConnection = {},
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
                        onRetryConnection = {},
                        onCancelPendingConnection = {},
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

        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("more_details_toggle"))
        composeRule.onNodeWithTag("more_details_toggle").performClick()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("date_picker_button"))
        val date = composeRule.onNodeWithTag("date_picker_button").fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithTag("time_picker_button").fetchSemanticsNode().boundsInRoot
        assertTrue(date.bottom <= time.top || date.right <= time.left)
        composeRule.onNodeWithTag("date_picker_button").assertIsDisplayed()
        composeRule.onNodeWithTag("time_picker_button").assertIsDisplayed()
    }

    @Test
    fun amountSuggestionsAreTaggedBelowThePadAndApplyImmediately() {
        val history =
            TransactionSuggestions.history(
                listOf(
                    Transaction(
                        id = "suggested",
                        occurredAtEpochMillis = 1_765_000_000_000,
                        merchant = "Cafe",
                        category = "Food",
                        note = "",
                        cents = -1_234,
                    ),
                ),
            )
        composeRule.setContent {
            var draft by remember { mutableStateOf(newEditorDraft().copy(category = "Food")) }
            MaterialTheme {
                TransactionEditor(
                    transaction = null,
                    draft = draft,
                    suggestionHistory = history,
                    onDraftChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                )
            }
        }

        composeRule
            .onNodeWithTag("amount_suggestion_0")
            .performScrollTo()
            .assertTextContains("\$12.34", substring = true)
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("amount_display"))
        composeRule.onNodeWithTag("amount_display").assertTextContains("-\$12.34")
    }

    @Test
    fun nearbySuggestionAddressExpandsMoreDetailsAndStaysExpanded() {
        val draftState =
            mutableStateOf(
                newEditorDraft().copy(recurringIntervalName = RecurrenceInterval.Monthly.name),
            )
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                TransactionEditor(
                    transaction = null,
                    draft = draftState.value,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draftState.value = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                )
            }
        }

        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("more_details_toggle"))
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithTag("more_details_toggle").performClick()
        composeRule.onNodeWithText("More details").assertIsDisplayed()

        composeRule.runOnIdle {
            val currentDraft = draftState.value
            draftState.value =
                currentDraft.copy(
                    merchant = currentDraft.merchant.ifBlank { "Corner Cafe" },
                    note = currentDraft.note.ifBlank { "123 Main Street" },
                )
        }
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("note_field"))
        composeRule.onNodeWithTag("note_field").assertIsDisplayed().assertTextContains("123 Main Street")
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("note_field"))
        composeRule.onNodeWithTag("note_field").assertIsDisplayed().assertTextContains("123 Main Street")
        composeRule.runOnIdle {
            draftState.value = draftState.value.copy(merchant = "Corner Cafe Updated")
        }
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
    }

    @Test
    fun moreDetailsRestoresAndAdvancedRecordsAutoExpand() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
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

        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("more_details_toggle"))
        composeRule.onNodeWithText("Does not repeat", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("more_details_toggle").performClick()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("date_picker_button"))
        composeRule.onNodeWithTag("date_picker_button").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("date_picker_button"))
        composeRule.onNodeWithTag("date_picker_button").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("amount_key_back"))
        composeRule.onNodeWithTag("amount_key_back").assertContentDescriptionEquals("Delete last digit")
    }

    @Test
    fun advancedRecordsAutoExpandMoreDetails() {
        val advancedDraft =
            newEditorDraft().copy(
                id = "advanced",
                note = "Remember this",
                recurringIntervalName = RecurrenceInterval.Monthly.name,
            )
        composeRule.setContent {
            MaterialTheme {
                TransactionEditor(
                    transaction =
                        Transaction(
                            id = "advanced",
                            occurredAtEpochMillis = advancedDraft.occurredAtEpochMillis,
                            merchant = "Rent",
                            category = "Rent",
                            note = advancedDraft.note,
                            cents = -100_000,
                            recurringInterval = RecurrenceInterval.Monthly,
                        ),
                    draft = advancedDraft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = {},
                    onSave = {},
                    onDelete = {},
                    onCancel = {},
                    persistenceBusy = false,
                )
            }
        }
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("note_field"))
        composeRule.onNodeWithTag("note_field").assertIsDisplayed()
        composeRule.onNodeWithTag("recurring_chip_monthly").assertIsSelected()
    }

    @Test
    fun transactionDaysShowSeparateTotalsAndSupportSwipeDelete() {
        val date = java.time.LocalDate.of(2026, 7, 10)
        val occurredAt = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val expense = Transaction("expense", occurredAt, "Cafe", "Food", "", -1_250)
        val income = Transaction("income", occurredAt + 1, "Client", "Salary", "", 5_000)
        var edits = 0
        var deletes = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(360.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.5f)) {
                    MaterialTheme {
                        FlowMoneyScreen(
                            uiState = MainUiState(isLoading = false, sortedTransactions = listOf(income, expense)),
                            selectedTab = DashboardTab.Transactions,
                            onChartRangeModeSelected = {},
                            onSelectedMonthChange = {},
                            onEdit = { edits++ },
                            onViewAllTransactions = {},
                            onDelete = { deletes++ },
                            onAddTransaction = {},
                            onData = {},
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag("transactions_list")
            .performScrollToNode(hasTestTag("transaction_day_header_$date"))
        composeRule.onNodeWithTag("transaction_day_spent_$date").assertTextContains("Spent \$12.50")
        composeRule.onNodeWithTag("transaction_day_received_$date").assertTextContains("Received \$50.00")
        composeRule
            .onNodeWithTag("transactions_list")
            .performScrollToNode(hasTestTag("transaction_content_expense"))
        val expenseRow =
            composeRule
                .onNodeWithTag("transaction_content_expense")
                .assertIsDisplayed()
                .fetchSemanticsNode()
        assertTrue(expenseRow.boundsInRoot.height / composeRule.density.density >= 48f)
        composeRule.onNodeWithTag("transaction_content_expense").performTouchInput { swipeLeft() }
        assertEquals(0, edits)
        assertEquals(1, deletes)

        // A failed delete leaves the row in place; resetting the dismiss state must allow a retry.
        composeRule.onNodeWithTag("transaction_content_expense").performTouchInput { swipeLeft() }
        assertEquals(0, edits)
        assertEquals(2, deletes)
    }

    private fun setEmptyScreen(
        tab: DashboardTab,
        onAdd: () -> Unit,
        onData: () -> Unit,
    ) {
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
        val transaction =
            Transaction(
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
