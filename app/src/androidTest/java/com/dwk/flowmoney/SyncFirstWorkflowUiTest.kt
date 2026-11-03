package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SyncFirstWorkflowUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test
    fun reviewQueueGroupsNewestFirstAndCategoryTargetsAreBoundedAccessible() {
        val newer = syncedTransaction("new", LocalDate.of(2026, 7, 10), merchant = "New Cafe")
        val older = syncedTransaction("old", LocalDate.of(2026, 7, 9), merchant = "Old Cafe")
        var categorized: Triple<String, String, Boolean>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(older, newer),
                    onEdit = {},
                    onCategorize = { transaction, category, future ->
                        categorized = Triple(transaction.id, category, future)
                    },
                    onBulkCategorize = { _, _ -> },
                    onBulkAcceptOther = {},
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val newerTop =
            composeRule
                .onNodeWithTag("review_row_new")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val olderTop =
            composeRule
                .onNodeWithTag("review_row_old")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("newer=$newerTop older=$olderTop", newerTop < olderTop)
        composeRule.onNodeWithText("Friday, Jul 10").assertIsDisplayed()
        composeRule.onNodeWithText("Thursday, Jul 9").assertIsDisplayed()

        val food = composeRule.onNodeWithTag("review_category_new_food").assertIsDisplayed()
        val foodBounds = food.fetchSemanticsNode().boundsInRoot
        assertTrue(foodBounds.height / composeRule.density.density >= 48f)
        food.performClick()
        composeRule.runOnIdle { assertEquals(Triple("new", "Food", false), categorized) }

        composeRule
            .onNodeWithTag("review_category_new_more")
            .assertContentDescriptionEquals("More categories for New Cafe")
        assertEquals(
            5,
            composeRule
                .onNodeWithTag("review_category_new_choices", useUnmergedTree = true)
                .fetchSemanticsNode()
                .children.size,
        )
    }

    @Test
    fun reviewSelectionKeepsPresentationGeometryAt300Dp() {
        val transaction = syncedTransaction("stable", LocalDate.of(2026, 7, 10), "A long merchant name for the review queue").copy(accountName = "Checking")
        var edits = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(300.dp, 800.dp))) {
                FlowMoneyTheme(dynamicColor = false) {
                    ReviewPage(listOf(transaction), onEdit = { edits++ }, onCategorize = { _, _, _ -> }, onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false)
                }
            }
        }
        fun geometry(): List<Float> {
            composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_stable"))
            val row = composeRule.onNodeWithTag("review_row_stable").fetchSemanticsNode().boundsInRoot
            val merchant = composeRule.onNodeWithText(transaction.merchant, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val amount = composeRule.onNodeWithText("-\$5.00", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val metadata = composeRule.onNodeWithText("Other · Checking", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return listOf(merchant.left - row.left, merchant.top - row.top, merchant.bottom - row.top, amount.left - row.left, amount.top - row.top, metadata.left - row.left, metadata.width)
        }
        val before = geometry()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_action"))
        composeRule.onNodeWithTag("review_select_action").performClick()
        val after = geometry()
        val density = composeRule.onNodeWithTag("review_row_stable").fetchSemanticsNode().layoutInfo.density.density
        assertTrue("normal mode must not have checkbox gutter", kotlin.math.abs(after[0] - before[0] - 56f * density) < 2f)
        assertEquals("metadata gutter", 56f * density, after[5] - before[5], 2f)
        assertEquals("merchant top", before[1], after[1], 1f)
        assertEquals("amount top", before[4], after[4], 1f)
        assertEquals(before[4] >= before[2], after[4] >= after[2])
        assertEquals(before[3], after[3], 1f)
        assertTrue(after[6] < before[6])
        composeRule.onNodeWithTag("review_row_stable").performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertEquals(0, edits)
    }

    @Test fun reviewBulkControlsRemainStickyAndDisabledUntilSelected() {
        val rows = (0..12).map { syncedTransaction("sticky-$it", LocalDate.of(2026, 7, 10).minusDays(it.toLong()), "Merchant $it") }
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(rows, onEdit = {}, onCategorize = { _, _, _ -> }, onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false, modifier = Modifier.fillMaxSize())
            }
        }
        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_select_action").assertDoesNotExist()
        composeRule.onNodeWithTag("review_done_selecting").assertContentDescriptionEquals("Done selecting")
        composeRule.onNodeWithTag("review_bulk_actions").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_accept_other_action").assertDoesNotExist()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_sticky-12"))
        composeRule.onNodeWithTag("review_bulk_controls").assertIsDisplayed()
        composeRule.onNodeWithTag("review_row_sticky-12").performClick()
        composeRule.onNodeWithTag("review_bulk_actions").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("review_bulk_category_food").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun stickyBulkControlsLeaveReviewRowsSelectableAt300DpTwoX() {
        val rows = (0..5).map { syncedTransaction("compact-$it", LocalDate.of(2026, 7, 10).minusDays(it.toLong()), "Merchant $it") }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(300.dp, 533.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(2f)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        AdaptiveFlowMoneyShell(selectedTab = DashboardTab.Review, snackbarHostState = remember { SnackbarHostState() }, onTabSelected = {}, onAddTransaction = {}, onData = {}, modifier = Modifier.fillMaxSize()) { padding ->
                            ReviewPage(rows, onEdit = {}, onCategorize = { _, _, _ -> }, onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false, modifier = Modifier.fillMaxSize().padding(padding))
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("review_select_action").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("review_selected_count", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        composeRule.onNodeWithTag("review_bulk_actions").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_compact-5"))
        val bar = composeRule.onNodeWithTag("review_bulk_controls").assertIsDisplayed().fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        assertTrue("bar height ${bar.boundsInRoot.height / density}", bar.boundsInRoot.height / density in 55.5f..64f)
        val card = composeRule.onNodeWithTag("review_row_compact-5").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("review_list").fetchSemanticsNode().boundsInRoot
        assertTrue("card=$card bar=${bar.boundsInRoot} list=$list", card.top >= bar.boundsInRoot.bottom - 1 && card.bottom <= list.bottom + 1)
        listOf("review_done_selecting", "review_bulk_actions").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $bounds", bounds.width / density >= 47.5f && bounds.height / density >= 47.5f)
        }
        composeRule.onNodeWithTag("review_quick_category_choices").assertDoesNotExist()
        composeRule.onNodeWithTag("review_row_compact-5").performClick()
        composeRule.onNodeWithTag("review_selected_count").assertTextEquals("1 selected")
        for (tag in listOf("food", "groceries", "restaurants", "coffee", "more")) {
            composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_quick_category_$tag"))
            val target = composeRule.onNodeWithTag("review_quick_category_$tag").fetchSemanticsNode().boundsInRoot
            assertTrue("quick target $tag $target", target.width / density >= 47.5f && target.height / density >= 47.5f)
        }
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_compact-5"))
        composeRule.onNodeWithTag("review_bulk_actions").performClick()
        composeRule.onNodeWithTag("review_accept_other_action").assertIsDisplayed()
        composeRule.onNodeWithTag("review_bulk_category_food").assertIsDisplayed()
    }

    @Test fun reviewQuickActionsAndCloseClearSelectionAndDismissUnavailableMenu() {
        val row = syncedTransaction("quick", LocalDate.of(2026, 7, 10))
        var busy by mutableStateOf(false)
        var result: Pair<List<String>, String>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(listOf(row), onEdit = {}, onCategorize = { _, _, _ -> }, onBulkCategorize = { ids, category -> result = ids to category }, onBulkAcceptOther = {}, operationBusy = busy)
            }
        }
        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_row_quick").performClick()
        composeRule.onNodeWithTag("review_bulk_actions").performClick()
        composeRule.onNodeWithTag("review_accept_other_action").assertIsDisplayed()
        composeRule.runOnIdle { busy = true }
        composeRule.onNodeWithTag("review_accept_other_action").assertDoesNotExist()
        composeRule.onNodeWithTag("review_bulk_actions").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_done_selecting").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("review_bulk_controls").assertDoesNotExist()
        composeRule.runOnIdle { busy = false }
        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_selected_count").assertTextEquals("0 selected")
        composeRule.onNodeWithTag("review_row_quick").performClick()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_quick_category_food"))
        composeRule.onNodeWithTag("review_quick_category_food").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("quick") to "Food", result) }
    }

    @Test fun reviewActionChipsKeepShapeAndDisabledColorsInBothThemes() {
        val row = syncedTransaction("chip", LocalDate.of(2026, 7, 10))
        var dark by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var onSurface = Color.Unspecified
        composeRule.setContent {
            FlowMoneyTheme(darkTheme = dark, dynamicColor = false) {
                val current = MaterialTheme.colorScheme.onSurface
                SideEffect { onSurface = current }
                Surface { ReviewPage(listOf(row), onEdit = {}, onCategorize = { _, _, _ -> }, onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = busy) }
            }
        }
        fun pixels(tag: String): Pair<Color, Color> {
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val image = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val inset = (4 * node.layoutInfo.density.density).toInt().coerceAtMost(image.height - 1)
            return image[0, 0] to image[image.width / 2, inset]
        }
        fun check(tag: String) {
            val enabled = pixels(tag)
            assertTrue("$tag enabled must contrast with background", enabled.first != enabled.second)
            composeRule.runOnIdle { busy = true }
            val disabled = pixels(tag)
            val expected = onSurface.copy(alpha = 0.12f).compositeOver(disabled.first)
            val actual = disabled.second
            assertTrue("$tag disabled $actual expected $expected", kotlin.math.abs(actual.red - expected.red) < .05f && kotlin.math.abs(actual.green - expected.green) < .05f && kotlin.math.abs(actual.blue - expected.blue) < .05f)
            assertTrue("$tag enabled and disabled differ", enabled.second != disabled.second)
            assertEquals("$tag corner shape", enabled.first, disabled.first)
            composeRule.runOnIdle { busy = false }
        }
        for (isDark in listOf(false, true)) {
            composeRule.runOnIdle { dark = isDark }
            check("review_category_chip_food")
            composeRule.onNodeWithTag("review_select_action").performClick()
            composeRule.onNodeWithTag("review_row_chip").performClick()
            check("review_quick_category_food")
            composeRule.onNodeWithTag("review_done_selecting").performClick()
        }
    }

    @Test
    fun reviewIdentityAnnouncesPostedAndPurchaseDatesAndRecurrence() {
        val posted = syncedTransaction("posted", LocalDate.of(2026, 7, 10), "Posted cafe")
        val purchased = syncedTransaction("purchase", LocalDate.of(2026, 7, 10), "Purchase cafe").copy(
            transactedAtEpochMillis = posted.occurredAtEpochMillis,
            recurringInterval = RecurrenceInterval.Monthly,
        )
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(posted, purchased), onEdit = {}, onCategorize = { _, _, _ -> },
                    onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Posted cafe, -\$5.00, Other, Posted Jul 10, 2026", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Purchase cafe, -\$5.00, Other, Purchase Jul 10, 2026, 12:00 PM, Recurring Monthly", useUnmergedTree = true).assertExists()
    }

    @Test
    fun selectionExposesSelectedStateDisablesEditAndUsesOneBulkCallback() {
        val first = syncedTransaction("first", LocalDate.of(2026, 7, 10))
        val second = syncedTransaction("second", LocalDate.of(2026, 7, 10))
        var edits = 0
        var bulk: Pair<List<String>, String>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(first, second),
                    onEdit = { edits++ },
                    onCategorize = { _, _, _ -> },
                    onBulkCategorize = { ids, category -> bulk = ids to category },
                    onBulkAcceptOther = {},
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule
            .onNodeWithTag("review_row_first")
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        val actions =
            composeRule
                .onNodeWithTag("review_row_first")
                .fetchSemanticsNode()
                .config
                .getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertFalse(actions.any { it.label == "Edit" || it.label == "Delete" })
        assertEquals(0, edits)

        composeRule.onNodeWithTag("review_row_second").performClick()
        composeRule.onNodeWithTag("review_bulk_actions").performClick()
        composeRule.onNodeWithTag("review_bulk_category_food").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("first", "second").toSet(), bulk?.first?.toSet())
            assertEquals("Food", bulk?.second)
            assertEquals(0, edits)
        }
    }

    @Test
    fun acceptAsOtherRequiresExplicitConfirmationBeforeOneBulkCallback() {
        val transaction = syncedTransaction("accept", LocalDate.of(2026, 7, 10))
        var acceptedIds: List<String>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(transaction),
                    onEdit = {},
                    onCategorize = { _, _, _ -> },
                    onBulkCategorize = { _, _ -> },
                    onBulkAcceptOther = { acceptedIds = it },
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_row_accept").performClick()
        composeRule.onNodeWithTag("review_bulk_actions").performClick()
        composeRule.onNodeWithTag("review_accept_other_action").performClick()
        composeRule.onNodeWithText("Accept as Other?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(null, acceptedIds) }
        composeRule.onNodeWithTag("review_bulk_actions").performClick()
        composeRule.onNodeWithTag("review_accept_other_action").performClick()
        composeRule.onNodeWithTag("review_confirm_accept_other").performClick()
        composeRule.runOnIdle { assertEquals(listOf("accept"), acceptedIds) }
    }

    @Test
    fun expandedFiltersExposeSourceStableAccountReviewAndEveryPresentCategory() {
        val synced =
            syncedTransaction("synced", LocalDate.of(2026, 7, 10)).copy(
                accountKey = "account-a",
                accountName = "Checking",
            )
        val categories = (1..15).map { "Category $it" }
        val manual =
            categories.mapIndexed { index, category ->
                Transaction(
                    id = "manual-$index",
                    occurredAtEpochMillis = synced.occurredAtEpochMillis - index - 1,
                    merchant = "Manual $index",
                    category = category,
                    note = "",
                    cents = -100,
                )
            }
        val accounts =
            listOf(
                account("account-a", "Checking", "Bank A"),
                account("account-b", "Checking", "Bank B"),
            )
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyScreen(
                    uiState =
                        MainUiState(
                            isLoading = false,
                            sortedTransactions = listOf(synced) + manual,
                            simpleFin = SimpleFinUiState(accounts = accounts),
                        ),
                    selectedTab = DashboardTab.Transactions,
                    onChartRangeModeSelected = {},
                    onSelectedMonthChange = {},
                    onEdit = {},
                    onViewAllTransactions = {},
                    onDelete = {},
                    onAddTransaction = {},
                    onData = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        composeRule
            .onNodeWithTag("transaction_source_filter_manual")
            .performClick()
            .assertIsSelected()
        composeRule
            .onNodeWithTag("transactions_list")
            .performScrollToNode(hasTestTag("transaction_account_filter_action"))
        composeRule.onNodeWithTag("transaction_account_filter_action").performClick()
        composeRule.onNodeWithText("Checking (Bank A)").assertIsDisplayed().performClick()
        composeRule
            .onNodeWithTag("transaction_account_filter_action")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Checking (Bank A)",
                ),
            )
        composeRule.onNodeWithTag("transaction_unreviewed_filter").performClick().assertIsSelected()
        composeRule.onNodeWithTag("transaction_category_filter_category15").assertExists()
    }

    @Test fun filterSummaryWrapsAndClearsCorrectlyAtOneX() = assertFilterSummary(1f)
    @Test fun filterSummaryWrapsAndClearsCorrectlyAtTwoX() = assertFilterSummary(2f)

    private fun assertFilterSummary(fontScale: Float) {
        val transaction = syncedTransaction("brooklyn", LocalDate.of(2026, 7, 10), "Brooklyn Cafe").copy(accountKey = "account-a", accountName = "Checking", locationCity = "Brooklyn", locationState = "NY", locationCountry = "US")
        val accounts = listOf(account("account-a", "Checking", "Bank A"), account("account-b", "Checking", "Bank B"))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(300.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(fontScale)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        FlowMoneyScreen(uiState = MainUiState(isLoading = false, sortedTransactions = listOf(transaction), simpleFin = SimpleFinUiState(accounts = accounts)),
                            selectedTab = DashboardTab.Transactions, onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
                    }
                }
            }
        }
        fun scroll(tag: String) = composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        scroll("transaction_time_filter_group")
        composeRule.onNodeWithText("Range").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_filter_week").performClick()
        scroll("transaction_source_filter_synced")
        composeRule.onNodeWithTag("transaction_source_filter_synced").performClick()
        scroll("transaction_account_filter_action")
        composeRule.onNodeWithTag("transaction_account_filter_action").performClick()
        composeRule.onNodeWithText("Checking (Bank A)").performClick()
        scroll("transaction_unreviewed_filter")
        composeRule.onNodeWithTag("transaction_unreviewed_filter").performClick()
        scroll("transaction_category_filter_other")
        composeRule.onNodeWithTag("transaction_category_filter_other").performClick()
        scroll("transaction_city_filter_brooklyn")
        composeRule.onNodeWithTag("transaction_city_filter_brooklyn").performClick()
        scroll("transaction_country_filter_us")
        composeRule.onNodeWithTag("transaction_country_filter_us").performClick()
        scroll("transaction_where_filter")
        composeRule.onNodeWithTag("transaction_where_filter").performTextInput("Brooklyn")
        val expected = "Last 7 days · Other · Synced · Checking (Bank A) · Needs review · Brooklyn · US · “Brooklyn”"
        scroll("transaction_filter_toggle")
        val searchBounds = composeRule.onNodeWithTag("transaction_where_filter").fetchSemanticsNode().boundsInRoot
        val toggleBounds = composeRule.onNodeWithTag("transaction_filter_toggle").fetchSemanticsNode().boundsInRoot
        val viewBounds = composeRule.onNodeWithTag("transaction_view_mode_group").fetchSemanticsNode().boundsInRoot
        assertTrue(searchBounds.bottom <= toggleBounds.top && viewBounds.top == toggleBounds.top)
        composeRule.onNodeWithText("Filters · 6", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("transaction_filter_toggle").assertContentDescriptionEquals("Filters, 6 active")
        composeRule.onAllNodesWithTag("transaction_filter_clear_compact").assertCountEquals(1)
        val summary = composeRule.onNodeWithTag("transaction_filter_summary", useUnmergedTree = true).assertTextEquals(expected)
        val layouts = mutableListOf<TextLayoutResult>()
        summary.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().lineCount > 1)
        repeat(layouts.single().lineCount) { assertFalse(layouts.single().isLineEllipsized(it)) }
        scroll("transaction_search_clear")
        composeRule.onNodeWithTag("transaction_search_clear").assertContentDescriptionEquals("Clear search").performClick()
        scroll("transaction_filter_toggle")
        composeRule.onNodeWithText("Filters · 6", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("transaction_filter_summary", useUnmergedTree = true).assertTextEquals(expected.substringBeforeLast(" · “Brooklyn”"))
        scroll("transaction_where_filter")
        composeRule.onNodeWithTag("transaction_where_filter").performTextInput("Brooklyn")
        scroll("transaction_filter_clear_compact")
        val summaryRow = composeRule.onNodeWithTag("transaction_filter_summary_row").fetchSemanticsNode().boundsInRoot
        val clearBounds = composeRule.onNodeWithTag("transaction_filter_clear_compact").fetchSemanticsNode().boundsInRoot
        assertTrue("clear=$clearBounds summary row=$summaryRow", kotlin.math.abs(clearBounds.right - summaryRow.right) <= 2f)
        composeRule.onNodeWithTag("transaction_filter_clear_compact").assertContentDescriptionEquals("Clear all filters").performClick()
        scroll("transaction_filter_toggle")
        composeRule.onNodeWithTag("transaction_filter_summary", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_filter_clear_compact").assertDoesNotExist()
        assertEquals("", composeRule.onNodeWithTag("transaction_where_filter").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        composeRule.onNodeWithTag("transaction_filter_toggle").assertContentDescriptionEquals("Filters").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.onNodeWithText("Filters", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("transaction_time_filter_group").assertDoesNotExist()
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_content_brooklyn"))
        composeRule.onNodeWithTag("transaction_content_brooklyn").assertIsDisplayed()
    }

    @Test fun filterControlsFitAtBothWidthsAndEveryFontScale() {
        var width by mutableStateOf(300)
        var scale by mutableStateOf(1f)
        val row = syncedTransaction("filter-size", LocalDate.of(2026, 7, 10))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width.dp, 800.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(scale)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        FlowMoneyScreen(uiState = MainUiState(isLoading = false, sortedTransactions = listOf(row)), selectedTab = DashboardTab.Transactions,
                            onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
                    }
                }
            }
        }
        fun verify(label: String, description: String) {
            for (screenWidth in listOf(300, 360)) for (fontScale in listOf(1f, 1.5f, 2f)) {
                composeRule.runOnIdle { width = screenWidth; scale = fontScale }
                composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_filter_controls"))
                val toggle = composeRule.onNodeWithTag("transaction_filter_toggle").assertContentDescriptionEquals(description).fetchSemanticsNode()
                val bounds = toggle.boundsInRoot
                val density = toggle.layoutInfo.density.density
                listOf("transaction_view_list", "transaction_view_map", "transaction_filter_toggle").forEach { tag ->
                    val target = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue("$screenWidth/$fontScale $tag $target", target.width / density >= 47.5f && target.height / density >= 47.5f)
                }
                val labelNode = composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                val labelBounds = labelNode.fetchSemanticsNode().boundsInRoot
                val layouts = mutableListOf<TextLayoutResult>()
                labelNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("$screenWidth/$fontScale $label", layouts.single().hasVisualOverflow)
                assertFalse(layouts.single().isLineEllipsized(0))
                assertTrue(layouts.single().layoutInput.style.fontSize.value >= 10f)
                val caret = composeRule.onNodeWithTag("transaction_filter_caret", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("toggle=$bounds text=$labelBounds caret=$caret", labelBounds.left >= bounds.left - 1 && labelBounds.right <= caret.left + 1 && caret.right <= bounds.right + 1 && caret.top >= bounds.top && caret.bottom <= bounds.bottom)
            }
        }
        verify("Filters", "Filters")
        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        composeRule.onNodeWithTag("transactions_list").performScrollToNode(hasTestTag("transaction_category_filter_other"))
        composeRule.onNodeWithTag("transaction_category_filter_other").performClick()
        verify("Filters · 1", "Filters, 1 active")
    }

    @Test fun searchOnlySummarySharesLineAndClearAlignsAtEnd() {
        val row = syncedTransaction("summary-alignment", LocalDate.of(2026, 7, 10), "A")
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(360.dp, 800.dp))) {
                FlowMoneyTheme(dynamicColor = false) {
                    FlowMoneyScreen(uiState = MainUiState(isLoading = false, sortedTransactions = listOf(row)), selectedTab = DashboardTab.Transactions,
                        onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
                }
            }
        }
        composeRule.onNodeWithTag("transaction_where_filter").performTextInput("A")
        composeRule.onNodeWithTag("transaction_filter_toggle").assertContentDescriptionEquals("Filters")
        composeRule.onNodeWithTag("transaction_filter_summary").assertTextEquals("All time · “A”")
        val summary = composeRule.onNodeWithTag("transaction_filter_summary").fetchSemanticsNode().boundsInRoot
        val rowBounds = composeRule.onNodeWithTag("transaction_filter_summary_row").fetchSemanticsNode().boundsInRoot
        val clear = composeRule.onNodeWithTag("transaction_filter_clear_compact").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("summary=$summary clear=$clear", kotlin.math.abs(summary.center.y - clear.center.y) <= 2f)
        assertTrue("row=$rowBounds clear=$clear", kotlin.math.abs(rowBounds.right - clear.right) <= 2f)
        composeRule.onNodeWithTag("transaction_filter_clear_compact").performClick()
        composeRule.onNodeWithTag("transaction_filter_clear_compact").assertDoesNotExist()
        assertEquals("", composeRule.onNodeWithTag("transaction_where_filter").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun disconnectedAndPendingLabelsAndRoutesRemainDistinctAtAllScales() {
        var scale by mutableStateOf(1f)
        var pending by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var dataClicks = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(scale)) {
                FlowMoneyTheme(dynamicColor = false) {
                    FlowMoneyTopAppBar(onData = { dataClicks++ }, onAddTransaction = {}, simpleFin = SimpleFinUiState(isConnectionPending = pending), operation = if (busy) DataOperation.Import else null)
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f, 2f)) for (isPending in listOf(false, true)) {
            composeRule.runOnIdle { scale = fontScale; pending = isPending; busy = false }
            val label = if (isPending) "Pending" else if (fontScale >= 1.5f) "No bank" else "Disconnected"
            composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val description = if (isPending) "Connection pending; open Data to retry" else "Bank sync disconnected; open Data to connect"
            composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)).performClick()
        }
        composeRule.runOnIdle { assertEquals(6, dataClicks); busy = true }
        composeRule.onNodeWithTag("sync_health_action").assertIsNotEnabled()
    }

    @Test
    fun syncHealthRoutesDisconnectedAndDailyStatesToDataAndEligibleStateToManualSync() {
        val now = 1_800_000_000_000L
        var simpleFin by mutableStateOf(SimpleFinUiState())
        var dataClicks = 0
        var syncClicks = 0
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyTopAppBar(
                    onData = { dataClicks++ },
                    onAddTransaction = {},
                    simpleFin = simpleFin,
                    syncHealthNowEpochMillis = now,
                    onManualSync = { syncClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, dataClicks)
            simpleFin =
                SimpleFinUiState(
                    profile =
                        SimpleFinProfileEntity(
                            connectionId = "eligible",
                            lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(4),
                            lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(4),
                            automaticSyncsPerDay = 4,
                        ),
                )
        }
        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, syncClicks)
            simpleFin =
                SimpleFinUiState(
                    profile =
                        SimpleFinProfileEntity(
                            connectionId = "daily",
                            lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(1),
                            lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(1),
                            automaticSyncsPerDay = 1,
                        ),
                )
        }
        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(2, dataClicks)
            assertEquals(1, syncClicks)
            simpleFin = SimpleFinUiState(profile = SimpleFinProfileEntity(automaticSyncsPerDay = 4, lastSyncAttemptAtEpochMillis = now))
        }
        composeRule.onNodeWithText("Sync now", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle { assertEquals(3, dataClicks); assertEquals(1, syncClicks) }
    }

    @Test
    fun syncHealthReportsCompletionInterruptionAndFailuresTruthfully() {
        val now = 1_800_000_000_000L
        val base = SimpleFinProfileEntity(automaticSyncsPerDay = 4)
        fun state(profile: SimpleFinProfileEntity) = syncHealthUiState(SimpleFinUiState(profile = profile), null, now)
        val never = state(base)
        assertEquals("Sync now", never.label)
        assertEquals("Not synced yet; sync now", never.stateDescription)
        assertTrue(never.manualSync)
        val completed = base.copy(lastSyncAttemptAtEpochMillis = now - 1_000, lastSuccessfulSyncAtEpochMillis = now - 1_000)
        assertEquals("Synced", state(completed).label)
        assertTrue(state(completed).stateDescription.startsWith("Sync completed; cooling down"))
        assertEquals("Synced", state(completed.copy(lastSuccessfulSyncAtEpochMillis = now)).label)
        assertEquals("Synced", state(completed.copy(lastSyncAttemptAtEpochMillis = null)).label)
        val due = completed.copy(lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(4), lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(4))
        assertEquals("Sync now", state(due).label)
        assertTrue(state(due).manualSync)
        val interrupted = completed.copy(lastSyncAttemptAtEpochMillis = now, lastSuccessfulSyncAtEpochMillis = now - 1_000)
        assertEquals("Sync now", state(interrupted).label)
        assertTrue(state(interrupted).stateDescription.startsWith("Last sync didn’t finish; retry after"))
        assertFalse(state(interrupted).manualSync)
        val retry = interrupted.copy(lastSyncAttemptAtEpochMillis = now - SIMPLEFIN_RETRY_INTERVAL_MILLIS, lastSuccessfulSyncAtEpochMillis = now - SIMPLEFIN_RETRY_INTERVAL_MILLIS - 1)
        assertEquals("Last sync didn’t finish; sync now", state(retry).stateDescription)
        assertTrue(state(retry).manualSync)
        assertEquals("Error", state(interrupted.copy(lastError = "failed")).label)
        assertFalse(state(interrupted.copy(lastError = "failed")).manualSync)
        assertTrue(state(retry.copy(lastError = "failed")).manualSync)
        assertEquals("Synced", state(completed.copy(lastError = "stale")).label)
        assertEquals("Reconnect", state(base.copy(isPaused = true)).label)
        assertEquals("Pending", syncHealthUiState(SimpleFinUiState(isConnectionPending = true), null, now).label)
        assertEquals("Disconnected", syncHealthUiState(SimpleFinUiState(), null, now).label)
        assertEquals("Syncing", syncHealthUiState(SimpleFinUiState(), DataOperation.Sync, now).label)
    }

    @Test
    fun syncHealthRefreshesAtBoundaryAndOnResumeWithoutPolling() {
        class MutableClock(var time: Long) : Clock() {
            var reads = 0
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long { reads++; return time }
        }
        val clock = MutableClock(1_800_000_000_000L)
        val interval = automaticSyncIntervalMillis(4)
        var profile by mutableStateOf(SimpleFinProfileEntity(automaticSyncsPerDay = 4, lastSuccessfulSyncAtEpochMillis = clock.time - interval + 10_000))
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val now = rememberSyncHealthNow(clock, profile)
                FlowMoneyTopAppBar(onData = {}, onAddTransaction = {}, simpleFin = SimpleFinUiState(profile = profile), syncHealthNowEpochMillis = now)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Synced", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { clock.time += 5_000 }
        composeRule.mainClock.advanceTimeBy(10_000)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Synced", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { clock.time += 5_000 }
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Sync now", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { profile = profile.copy(lastSuccessfulSyncAtEpochMillis = clock.time - interval + 20_000) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Synced", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { clock.time += 20_000 }
        composeRule.mainClock.advanceTimeBy(20_000)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Sync now", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { profile = profile.copy(lastSuccessfulSyncAtEpochMillis = clock.time - interval + 20_000) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Synced", useUnmergedTree = true).assertExists()
        composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED; clock.time += 20_000; owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Sync now", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sync due; sync now"))
        for (p in listOf(profile.copy(lastSuccessfulSyncAtEpochMillis = Long.MAX_VALUE - 1), profile.copy(lastSuccessfulSyncAtEpochMillis = clock.time - interval), profile.copy(lastSuccessfulSyncAtEpochMillis = null))) {
            composeRule.runOnIdle { profile = p }
            composeRule.mainClock.advanceTimeByFrame()
            val reads = clock.reads
            composeRule.mainClock.advanceTimeBy(30_000)
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(reads, clock.reads)
        }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun syncedEditorLeadsWithRawBankDetailsAndGatesProviderOwnedFieldsBehindAdvanced() {
        val transaction = syncedTransaction("editor", LocalDate.of(2026, 7, 10), merchant = "Effective Cafe")
        var draft by
            mutableStateOf(
                EditorDraft(
                    id = transaction.id,
                    occurredAtEpochMillis = transaction.occurredAtEpochMillis,
                    merchant = transaction.merchant,
                    amount = "5.00",
                    isExpense = true,
                    category = "Other",
                    note = "",
                    recurringIntervalName = "",
                    source = "simplefin",
                    accountKey = "account-a",
                    accountName = "Checking",
                    reviewedAtEpochMillis = null,
                    providerDescription = "RAW PROVIDER DESCRIPTION 123",
                    merchantOverride = null,
                    providerMerchant = "Provider Cafe",
                    flowKind = FlowKind.NORMAL,
                    flowKindOverride = null,
                ),
            )
        var futureSave: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                    onSaveWithFutureRule = { futureSave = it },
                )
            }
        }

        composeRule.onNodeWithTag("synced_provider_summary").assertIsDisplayed()
        composeRule.onNodeWithText("Checking").assertIsDisplayed()
        composeRule.onNodeWithTag("synced_provider_description").assertIsDisplayed()
        composeRule.onNodeWithText("RAW PROVIDER DESCRIPTION 123").assertIsDisplayed()
        composeRule.onNodeWithText("Expense").assertDoesNotExist()

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_use_future"))
        composeRule.onNodeWithTag("synced_use_future").performClick()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_advanced_toggle"))
        composeRule.onNodeWithTag("synced_advanced_toggle").performClick()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_advanced_warning"))
        composeRule.onNodeWithTag("synced_advanced_warning").assertIsDisplayed()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasText("Expense"))
        composeRule.onNodeWithText("Expense").assertIsDisplayed()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals("RAW PROVIDER DESCRIPTION 123", futureSave?.providerDescription)
            assertEquals("Provider Cafe", futureSave?.providerMerchant)
            assertEquals("Checking", futureSave?.accountName)
            assertEquals(FlowKind.TRANSFER, futureSave?.flowKindOverride)
        }
    }

    @Test
    fun providerTransferCanBeExplicitlyCorrectedToNormalWithoutChangingClassification() {
        val transaction =
            syncedTransaction("provider-transfer", LocalDate.of(2026, 7, 10))
                .copy(flowKind = FlowKind.TRANSFER)
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule
            .onNodeWithTag("transfer_status_control")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Treat as transfer",
                ),
            )
        composeRule.onNodeWithTag("transfer_status_transfer").assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_not_transfer").performClick().assertIsSelected()
        composeRule
            .onNodeWithTag("transfer_status_control")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Not a transfer",
                ),
            )
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals(FlowKind.TRANSFER, saved?.flowKind)
            assertEquals(FlowKind.NORMAL, saved?.flowKindOverride)
        }
    }

    @Test
    fun returningToProviderKindClearsOverrideAndAllowsProviderReclassificationAfterRoundTrip() {
        val transaction = syncedTransaction("round-trip-flow", LocalDate.of(2026, 7, 10))
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_not_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            val savedTransaction = requireNotNull(saved)
            assertEquals(FlowKind.NORMAL, savedTransaction.flowKind)
            assertEquals(null, savedTransaction.flowKindOverride)

            val reclassifiedDraft =
                savedTransaction
                    .copy(flowKind = FlowKind.TRANSFER)
                    .toEntity()
                    .toTransaction()
                    .toEditorDraft()
            assertEquals(null, reclassifiedDraft.flowKindOverride)
            assertEquals(FlowKind.TRANSFER, reclassifiedDraft.effectiveFlowKind)
        }
    }

    @Test
    fun normalTransactionCanBeExplicitlyTreatedAsTransfer() {
        val transaction = syncedTransaction("normal-flow", LocalDate.of(2026, 7, 10))
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_not_transfer").assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals(FlowKind.NORMAL, saved?.flowKind)
            assertEquals(FlowKind.TRANSFER, saved?.flowKindOverride)
        }
    }

    @Test
    fun connectedAccountsCardRendersBalanceAvailableBalanceAndAsOfTimestamp() {
        val balanceDateSeconds = 1_766_145_600L
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                DataSheet(
                    simpleFin =
                        SimpleFinUiState(
                            profile = SimpleFinProfileEntity(connectionId = "connected"),
                            accounts =
                                listOf(
                                    account("account-a", "Checking", "Bank A").copy(
                                        balanceAmount = "1234.56",
                                        availableBalanceAmount = "1000.00",
                                        balanceDateEpochSeconds = balanceDateSeconds,
                                    ),
                                ),
                        ),
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("connected_accounts_card"))
        composeRule.onNodeWithTag("connected_accounts_card").assertIsDisplayed()
        composeRule.onNodeWithText("Checking").assertIsDisplayed()
        composeRule.onNodeWithText("$1,234.56").assertIsDisplayed()
        composeRule.onNodeWithText("Available $1,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("as of ${listDateText(balanceDateSeconds * 1000L)}").assertIsDisplayed()
    }

    @Test
    fun overviewReviewStaysSeparateFromTopBarConnectionStatus() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        val dao = FlowMoneyDatabase.get(context).simpleFinDao()
        val healthy =
            SimpleFinProfileEntity(
                connectionId = "healthy",
                connectedAtEpochMillis = 1_800_000_000_000L,
                lastSyncAttemptAtEpochMillis = 1_800_000_000_000L,
                lastSuccessfulSyncAtEpochMillis = 1_800_000_000_000L,
                automaticSyncsPerDay = 4,
            )
        runBlocking { dao.upsertProfile(healthy) }
        val pending =
            listOf(
                syncedTransaction("pending-a", LocalDate.of(2026, 7, 10), merchant = "Pending Cafe"),
                syncedTransaction("pending-b", LocalDate.of(2026, 7, 9), merchant = "Pending Diner"),
            )
        val gateway = BannerGateway(pending)
        val viewModel =
            MainViewModel(
                repository = gateway,
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
            runCatching { composeRule.onNodeWithTag("overview_pending_review_count", useUnmergedTree = true).assertTextEquals("Review 2") }.isSuccess
        }
        composeRule.onNodeWithTag("overview_review_action").assertIsDisplayed().assertContentDescriptionEquals("Review 2 synced transactions")
        composeRule.onNodeWithTag("sync_health_action").assertIsDisplayed()
        composeRule.onNodeWithText("Last successful sync", substring = true).assertDoesNotExist()

        fun assertNoOverviewSyncActions() {
            composeRule.onAllNodesWithContentDescription("Bank sync status").assertCountEquals(1)
            composeRule.onNodeWithText("Connect bank").assertDoesNotExist()
            composeRule.onNodeWithText("Open Data").assertDoesNotExist()
            composeRule.onNodeWithText("Adjust sync cadence", substring = true).assertDoesNotExist()
        }
        runBlocking { dao.upsertProfile(healthy.copy(isPaused = true, lastError = "SimpleFIN reconnect required")) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bank reconnect required; open Data")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        runBlocking { dao.clearProfile() }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bank sync disconnected; open Data to connect")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        runBlocking { dao.upsertProfile(healthy.copy(automaticSyncsPerDay = 1, lastSuccessfulSyncAtEpochMillis = 1, lastSyncAttemptAtEpochMillis = 1)) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sync due; automatic sync is once daily; open Data to change cadence")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        composeRule.onNodeWithTag("overview_pending_review_count", useUnmergedTree = true).assertTextEquals("Review 2")
        composeRule.runOnIdle { gateway.rows.value = listOf(pending.first()); gateway.pending.value = listOf(pending.first()) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("overview_review_action").assertContentDescriptionEquals("Review 1 synced transaction") }.isSuccess
        }
        composeRule.runOnIdle {
            gateway.rows.value = gateway.rows.value.map { it.copy(reviewedAtEpochMillis = 1L) }
            gateway.pending.value = emptyList()
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("overview_review_action").assertDoesNotExist() }.isSuccess
        }
    }

    private class BannerGateway(
        initial: List<Transaction>,
    ) : TransactionGateway {
        val rows = MutableStateFlow(initial)
        val pending = MutableStateFlow(initial)
        override val transactions: Flow<List<Transaction>> = rows
        override val unreviewedTransactions: Flow<List<Transaction>> = pending

        override suspend fun load(): List<Transaction> = rows.value

        override suspend fun getUnreviewedTransactions(): List<Transaction> = pending.value

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    @Test
    fun syncedEditorShowsTransactedTimeOnlyWhenItDiffersFromThePostedTime() {
        val transaction = syncedTransaction("transacted", LocalDate.of(2026, 7, 10))
        val transactedAt = transaction.occurredAtEpochMillis - 86_400_000L
        val sameDayDifferentTime = transaction.occurredAtEpochMillis - 3_600_000L
        var draft by mutableStateOf(transaction.copy(transactedAtEpochMillis = transactedAt).toEditorDraft())
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_provider_summary"))
        composeRule.onNodeWithTag("synced_transacted_at").assertIsDisplayed()
        assertTransactedAtShows(transactedAt)
        composeRule.onNodeWithText("Posted").assertIsDisplayed()

        // Same calendar day, different clock time still deserves the disambiguating line.
        composeRule.runOnIdle { draft = draft.copy(transactedAtEpochMillis = sameDayDifferentTime) }
        composeRule.onNodeWithTag("synced_transacted_at").assertIsDisplayed()
        assertTransactedAtShows(sameDayDifferentTime)

        // Exactly equal timestamps carry no information, so the line disappears entirely.
        composeRule.runOnIdle {
            draft = draft.copy(transactedAtEpochMillis = transaction.occurredAtEpochMillis)
        }
        composeRule.onNodeWithTag("synced_transacted_at").assertDoesNotExist()

        composeRule.runOnIdle { draft = draft.copy(transactedAtEpochMillis = null) }
        composeRule.onNodeWithTag("synced_transacted_at").assertDoesNotExist()
    }

    /** `StatusLine` does not merge its label and value, so match the tagged row's descendants. */
    private fun assertTransactedAtShows(epochMillis: Long) {
        composeRule
            .onNode(
                hasTestTag("synced_transacted_at") and
                    hasAnyDescendant(hasText(listDateText(epochMillis))) and
                    hasAnyDescendant(hasText("Transacted")),
            ).assertIsDisplayed()
    }

    /** Mirrors MainActivity's private `ListDateFormatter` so the assertion reads the same text. */
    private fun listDateText(epochMillis: Long): String =
        Instant
            .ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))

    private fun syncedTransaction(
        id: String,
        date: LocalDate,
        merchant: String = id,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis =
            LocalDateTime
                .of(date, LocalTime.NOON)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        merchant = merchant,
        category = "Other",
        note = "",
        cents = -500,
        source = "simplefin",
        reviewedAtEpochMillis = null,
        providerDescription = "RAW $merchant",
        providerMerchant = merchant,
    )

    private fun account(
        id: String,
        name: String,
        institution: String,
    ) = SimpleFinAccountEntity(
        accountId = id,
        name = name,
        currency = "USD",
        institutionName = institution,
        balanceAmount = null,
        availableBalanceAmount = null,
        balanceDateEpochSeconds = null,
        lastSeenAtEpochMillis = 1L,
    )
}
