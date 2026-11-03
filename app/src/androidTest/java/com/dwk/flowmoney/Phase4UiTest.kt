package com.dwk.flowmoney

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
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
import org.junit.Assert.assertFalse
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
    fun editorSaveLabelsStayContainedForExpenseIncomeAndEffectiveTransfers() {
        var width by mutableStateOf(360)
        var scale by mutableStateOf(1f)
        var draft by mutableStateOf(newEditorDraft().copy(amount = "12.50", isExpense = true))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(scale)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        TransactionEditor(transaction = null, draft = draft, suggestionHistory = TransactionSuggestionHistory.Empty,
                            onDraftChange = { draft = it }, onSave = {}, onDelete = {}, onCancel = {}, persistenceBusy = false)
                    }
                }
            }
        }
        val examples = listOf(
            newEditorDraft().copy(amount = "12.50", isExpense = true) to "Save expense",
            newEditorDraft().copy(amount = "12.50", isExpense = false) to "Save income",
            newEditorDraft().copy(amount = "12.50", isExpense = true, source = "simplefin", flowKind = FlowKind.TRANSFER) to "Save transfer",
            newEditorDraft().copy(amount = "12.50", isExpense = false, flowKind = FlowKind.NORMAL, flowKindOverride = FlowKind.TRANSFER) to "Save transfer",
            newEditorDraft().copy(amount = "12.50", isExpense = true, flowKind = FlowKind.TRANSFER, flowKindOverride = FlowKind.NORMAL) to "Save expense",
        )
        for (screenWidth in listOf(300, 360)) for (fontScale in listOf(1f, 2f)) for ((example, label) in examples) {
            composeRule.runOnIdle { width = screenWidth; scale = fontScale; draft = example }
            val save = composeRule.onNodeWithTag("save_transaction_button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            composeRule.onNodeWithTag("delete_transaction_button").assertIsDisplayed().assertContentDescriptionEquals("Delete transaction")
            val text = composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val bounds = text.fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().isLineEllipsized(0))
            assertFalse("$screenWidth/$fontScale $label overflow", layouts.single().hasVisualOverflow)
            assertTrue("$screenWidth/$fontScale $label $bounds not within $save", bounds.left >= save.left && bounds.right <= save.right && bounds.top >= save.top && bounds.bottom <= save.bottom)
        }
    }

    @Test
    fun editorDeleteBusyUsesDisabledTintAndIgnoresTouchesInBothThemes() {
        var busy by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var deletes = 0
        composeRule.setContent {
            FlowMoneyTheme(darkTheme = dark, dynamicColor = false) {
                val expenseColor = LocalFinanceColors.current.expense
                val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                Surface {
                    Box {
                        TransactionEditor(transaction = null, draft = newEditorDraft().copy(amount = "12.50"), suggestionHistory = TransactionSuggestionHistory.Empty,
                            onDraftChange = {}, onSave = {}, onDelete = { deletes++ }, onCancel = {}, persistenceBusy = busy)
                        Box(Modifier.size(12.dp).background(expenseColor).testTag("expense_reference"))
                        Box(Modifier.offset(x = 16.dp).size(12.dp).background(MaterialTheme.colorScheme.surface).background(disabledColor).testTag("disabled_reference"))
                    }
                }
            }
        }
        fun checkTint(referenceTag: String): Color {
            val pixels = composeRule.onNodeWithTag("delete_transaction_button").captureToImage().toPixelMap()
            val background = pixels[0, 0]
            val reference = composeRule.onNodeWithTag(referenceTag).captureToImage().toPixelMap()
            val target = reference[reference.width / 2, reference.height / 2]
            var matches = 0
            val colors = mutableMapOf<Color, Int>()
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                colors[pixel] = (colors[pixel] ?: 0) + 1
                if (kotlin.math.abs(pixel.red - target.red) < 0.04f && kotlin.math.abs(pixel.green - target.green) < 0.04f && kotlin.math.abs(pixel.blue - target.blue) < 0.04f) matches++
            }
            assertTrue("expected tint $target on $background; $matches matching pixels; colors ${colors.entries.sortedByDescending { it.value }.take(12)}", matches >= 5)
            return target
        }
        for (isDark in listOf(false, true)) {
            composeRule.runOnIdle { dark = isDark; busy = false }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled()
            val enabledTint = checkTint("expense_reference")
            composeRule.runOnIdle { busy = true }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithTag("delete_transaction_button").assertIsNotEnabled().performTouchInput { click() }
            val disabledTint = checkTint("disabled_reference")
            assertTrue("enabled and disabled colors must differ", kotlin.math.abs(enabledTint.red - disabledTint.red) + kotlin.math.abs(enabledTint.green - disabledTint.green) + kotlin.math.abs(enabledTint.blue - disabledTint.blue) > 0.12f)
            composeRule.runOnIdle { assertEquals(0, deletes) }
        }
        composeRule.runOnIdle { busy = false }
        composeRule.onNodeWithTag("delete_transaction_button").assertIsEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun selectedActionChipUsesPrimaryColorsAndMoreLessKeepsItsShape() {
        var selected by mutableStateOf(false)
        var expanded by mutableStateOf(false)
        var primary = Color.Unspecified
        var secondary = Color.Unspecified
        composeRule.setContent {
            FlowMoneyTheme(darkTheme = false, dynamicColor = false) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val secondaryColor = MaterialTheme.colorScheme.secondaryContainer
                SideEffect { primary = primaryColor; secondary = secondaryColor }
                androidx.compose.foundation.layout.Column {
                    SuggestionChip("Near me", selected = selected, isSelection = false, testTag = "near_me_fixture", onClick = { selected = !selected })
                    SuggestionChip(if (expanded) "Less" else "More", isSelection = false, testTag = "more_less_fixture", onClick = { expanded = !expanded })
                }
            }
        }
        fun centerTop(tag: String): Color {
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, (4 * node.layoutInfo.density.density).toInt()]
        }
        assertEquals(secondary.toArgb(), centerTop("near_me_fixture").toArgb())
        composeRule.onNodeWithTag("near_me_fixture").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(primary.toArgb(), centerTop("near_me_fixture").toArgb())
        assertEquals(secondary.toArgb(), centerTop("more_less_fixture").toArgb())
        composeRule.onNodeWithTag("more_less_fixture").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Less").assertIsDisplayed()
        assertEquals(secondary.toArgb(), centerTop("more_less_fixture").toArgb())
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
    fun transactionsHeadingStaysSingleLineAtTwoXFont() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(360.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(2f)) {
                    MaterialTheme {
                        FlowMoneyScreen(
                            uiState = MainUiState(isLoading = false), selectedTab = DashboardTab.Transactions,
                            onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {},
                            onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {},
                        )
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("Transactions").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(!layouts.single().isLineEllipsized(0))
        composeRule.onNodeWithText("Spent \$0.00 · 0 transactions").assertIsDisplayed()
    }

    @Test
    fun dayTotalsExcludeTransfersAndReactToOverride() {
        val date = java.time.LocalDate.of(2026, 7, 10)
        val timestamp = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val expense = Transaction("normal", timestamp, "Cafe", "Food", "", -1_250)
        val income = Transaction("income", timestamp + 1, "Client", "Salary", "", 5_000)
        val transfer = Transaction("transfer", timestamp + 2, "Payment", "Food", "", -2_000, flowKind = FlowKind.TRANSFER)
        var overridden by mutableStateOf(false)
        var neutral = Color.Unspecified
        var expenseColor = Color.Unspecified
        composeRule.setContent {
            MaterialTheme {
                neutral = MaterialTheme.colorScheme.onSurfaceVariant
                expenseColor = LocalFinanceColors.current.expense
                FlowMoneyScreen(
                    uiState = MainUiState(isLoading = false, sortedTransactions = listOf(transfer, income, expense.copy(flowKindOverride = if (overridden) FlowKind.TRANSFER else null))),
                    selectedTab = DashboardTab.Transactions,
                    onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {},
                    onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {},
                )
            }
        }
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_day_header_$date"))
        composeRule.onNodeWithTag("transaction_day_spent_$date").assertTextContains("Spent \$12.50")
        composeRule.onNodeWithTag("transaction_day_received_$date").assertTextContains("Received \$50.00")
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_content_transfer"))
        composeRule.onNodeWithTag("transaction_content_transfer").assertContentDescriptionEquals("Payment, -\$20.00, Transfer, Occurred Jul 10, 2026, 12:00 AM")
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("-\$20.00", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(neutral, layouts.single().layoutInput.style.color)
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_content_normal"))
        val expenseLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("-\$12.50", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(expenseLayouts) }
        assertEquals(expenseColor, expenseLayouts.single().layoutInput.style.color)
        assertTrue(expenseColor != neutral)
        composeRule.runOnIdle { overridden = true }
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_day_header_$date"))
        composeRule.onNodeWithTag("transaction_day_spent_$date").assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_day_received_$date").assertTextContains("Received \$50.00")
    }

    @Test
    fun dayHeadersOmitZeroSidesForExpenseIncomeTransferAndMixed() {
        val day = java.time.LocalDate.of(2026, 7, 10)
        fun at(offset: Long) = day.minusDays(offset).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = listOf(
            Transaction("expense-only", at(0), "Expense", "Food", "", -100),
            Transaction("income-only", at(1), "Income", "Salary", "", 200),
            Transaction("transfer-only", at(2), "Transfer", "Other", "", -300, flowKind = FlowKind.TRANSFER),
            Transaction("mixed-expense", at(3), "Mixed expense", "Food", "", -400),
            Transaction("mixed-income", at(3), "Mixed income", "Salary", "", 500),
        )
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyScreen(uiState = MainUiState(isLoading = false, sortedTransactions = rows), selectedTab = DashboardTab.Transactions,
                    onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
            }
        }
        listOf(0L, 1L, 2L, 3L).forEach { offset ->
            val date = day.minusDays(offset)
            composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_day_header_$date"))
            val spent = composeRule.onNodeWithTag("transaction_day_spent_$date")
            val received = composeRule.onNodeWithTag("transaction_day_received_$date")
            if (offset == 0L || offset == 3L) spent.assertExists() else spent.assertDoesNotExist()
            if (offset == 1L || offset == 3L) received.assertExists() else received.assertDoesNotExist()
            if (offset == 2L) composeRule.onNodeWithTag("transaction_day_totals_$date").assertDoesNotExist()
            else composeRule.onNodeWithTag("transaction_day_totals_$date").assertExists()
        }
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
            latestSpendingTransactions = listOf(transaction),
            rangeTransactions = listOf(transaction),
            metrics = DashboardMetrics(spentCents = 450, incomeCents = 0, transactionCount = 1),
        )
    }
}
