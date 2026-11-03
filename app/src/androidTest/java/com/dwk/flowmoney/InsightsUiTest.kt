package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class InsightsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingDoesNotShowEmptyOrInsightContent() {
        setScreen(uiState = MainUiState(isLoading = true))

        composeRule.onNodeWithTag("app_loading_state").assertIsDisplayed()
        composeRule.onNodeWithText("Loading your spending").assertIsDisplayed()
        composeRule.onNodeWithText("No spending in this range").assertDoesNotExist()
        composeRule.onNodeWithTag("spending_timeline_chart").assertDoesNotExist()
    }

    @Test
    fun historicalRangeIsShownOnceWithoutClaimingThisMonth() {
        setScreen(uiState = reportingState())

        composeRule.onNodeWithText("Spent").assertIsDisplayed()
        composeRule.onNodeWithText("Spent this month").assertDoesNotExist()
        composeRule.onNodeWithText("Spent this week").assertDoesNotExist()
        composeRule.onAllNodesWithText("July 2026", substring = true).assertCountEquals(1)
        composeRule.onNodeWithTag("insights_total_caption").assertIsDisplayed()
    }

    @Test
    fun zeroInsightsShowAddActionInsteadOfCharts() {
        var addCount = 0
        setScreen(
            uiState =
                MainUiState(
                    isLoading = false,
                    dailySpending = listOf(DailySpend(DAY, 0)),
                    dateRange = RANGE,
                ),
            onAddTransaction = { addCount++ },
        )

        composeRule.onNodeWithText("No spending in this range").assertIsDisplayed()
        composeRule.onNodeWithTag("insights_empty_add").performClick()
        composeRule.onNodeWithTag("spending_timeline_chart").assertDoesNotExist()
        composeRule.onNodeWithTag("category_breakdown_chart").assertDoesNotExist()
        composeRule.onNodeWithText("Peak \$0.01").assertDoesNotExist()
        assertEquals(1, addCount)
    }

    @Test
    fun optionalCorrectionsShowIncludedSpendAndLinkToReview() {
        var reviewClicks = 0
        setScreen(
            uiState =
                MainUiState(
                    isLoading = false,
                    pendingReviewSummary = UnreviewedSpendingSummary(spentCents = 1_250, transactionCount = 2),
                    dateRange = RANGE,
                ),
            onReview = { reviewClicks++ },
        )

        composeRule.onAllNodesWithText("2 optional corrections · \$12.50 · included under Other").assertCountEquals(1)
        composeRule.onNodeWithTag("insights_pending_review_note").assertIsDisplayed()
        composeRule.onNodeWithTag("insights_pending_review_action").performClick()
        composeRule.runOnIdle { assertEquals(1, reviewClicks) }
    }

    @Test fun undoSnackbarDoesNotCoverLastInsightsReviewAction() {
        var show: () -> Unit = {}
        val state = populatedState().copy(pendingReviewSummary = UnreviewedSpendingSummary(1250, 1))
        composeRule.setContent {
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            show = { scope.launch { host.showSnackbar("Deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) } }
            FlowMoneyTheme(dynamicColor = false) {
                AdaptiveFlowMoneyShell(selectedTab = DashboardTab.Insights, snackbarHostState = host, onTabSelected = {}, onAddTransaction = {}, onData = {}, modifier = Modifier.fillMaxSize()) { padding ->
                    FlowMoneyScreen(uiState = state, selectedTab = DashboardTab.Insights, onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {}, modifier = Modifier.fillMaxSize().padding(padding))
                }
            }
        }
        composeRule.runOnIdle { show() }
        composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag("insights_pending_review_action"))
        val action = composeRule.onNodeWithTag("insights_pending_review_action").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val snack = composeRule.onNodeWithTag("app_snackbar_host").fetchSemanticsNode().boundsInRoot
        assertTrue("last action $action covered by snackbar $snack", action.bottom <= snack.top + 1f)
    }

    @Test fun singularOptionalCorrectionIsOneCompleteNote() {
        setScreen(uiState = MainUiState(isLoading = false, pendingReviewSummary = UnreviewedSpendingSummary(1_250, 1), dateRange = RANGE))
        composeRule.onAllNodesWithText("1 optional correction · \$12.50 · included under Other").assertCountEquals(1)
    }

    @Test fun categoryAndBucketOnlyHeadingsAreExact() {
        setScreen(uiState = populatedState())
        composeRule.onNodeWithTag("insight_category_food").performClick()
        composeRule.onNodeWithText("Food spending").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_category_food").performClick()
        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        composeRule.onNodeWithText("Jul 8–14").assertIsDisplayed()
    }

    @Test fun allCategoriesReconcileExpandDrillDownAndReset() {
        val time = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val categories = listOf("Food", "Housing", "Travel", "Other", "Bills", "Coffee")
        val rows = categories.mapIndexed { index, category ->
            Transaction("cat-$index", time, "Merchant $index", category, "", -(600 - index * 100), source = if (index == 3 || index == 5) "simplefin" else "manual",
                transactedAtEpochMillis = if (index == 5) time - 2 * 86_400_000L else null,
                reviewedAtEpochMillis = if (index == 5) 1L else null,
                recurringInterval = if (index == 5) RecurrenceInterval.Monthly else null)
        } +
            Transaction("transfer", time, "Transfer", "Food", "", -9000, flowKind = FlowKind.TRANSFER)
        val totals = DashboardAnalytics.categoryTotals(rows, limit = Int.MAX_VALUE)
        assertEquals(2100L, totals.sumOf { it.cents })
        var state by mutableStateOf(populatedState().copy(sortedTransactions = rows, rangeTransactions = rows, categoryTotals = totals, dailySpending = monthDailySpending(DAY to 2100L)))
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FlowMoneyScreen(uiState = state, selectedTab = DashboardTab.Insights, onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
                }
            }
        }
        val list = composeRule.onNodeWithTag("insights_list")
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("2 more categories · \$3.00").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_category_bills").assertDoesNotExist()
        composeRule.onNodeWithTag("insight_categories_expand").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        composeRule.onNodeWithTag("insight_categories_expand").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeRule.onNodeWithTag("insight_category_food").performClick()
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("Show less").performClick()
        list.performScrollToNode(hasTestTag("insight_category_food"))
        composeRule.onNodeWithTag("insight_category_food").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithTag("insight_categories_expand").performClick()
        list.performScrollToNode(hasTestTag("insight_category_coffee"))
        composeRule.onNodeWithTag("insight_category_coffee").performClick()
        list.performScrollToNode(hasTestTag("insight_transaction_cat-5"))
        composeRule.onNodeWithText("Coffee spending").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_transaction_cat-5").assertIsDisplayed()
        composeRule.onNode(hasText("Jul 8 · posted Jul 10") and hasAnyAncestor(hasTestTag("insight_transaction_cat-5")), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("transaction_repeat_cat-5", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("insight_transaction_transfer").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onNodeWithTag("insight_transaction_cat-5").assertDoesNotExist()
        composeRule.onNodeWithTag("insight_categories_expand").performClick()
        list.performScrollToNode(hasTestTag(SELECTED_BUCKET))
        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("2 more categories · \$3.00").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_categories_expand").performClick()
        composeRule.runOnIdle { state = state.copy(dateRange = RANGE.copy(label = "Changed range")) }
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("2 more categories · \$3.00").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(categoryTotals = totals.take(5), rangeTransactions = rows.filterNot { it.id == "cat-5" }, dailySpending = monthDailySpending(DAY to 2000L)) }
        list.performScrollToNode(hasTestTag(SELECTED_BUCKET))
        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        list.performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithText("1 more category · \$2.00").assertIsDisplayed()
    }

    @Test
    fun insightTextOptionsExposeSelectionAndChartSummary() {
        setScreen(uiState = populatedState())

        composeRule
            .onNodeWithContentDescription(
                "Spending timeline for July 2026. Total \$12.50. Peak \$12.50.",
            ).assertIsDisplayed()
        composeRule
            .onNodeWithTag(SELECTED_BUCKET)
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule
            .onNodeWithTag(OTHER_BUCKET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
        composeRule
            .onNodeWithTag("insight_category_food")
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule.onNodeWithText("Food spending · Jul 8–14").assertIsDisplayed()
    }

    @Test fun collapsedCategoriesKeepOtherAndAlignAmountsWithSubOnePercent() {
        val totals = listOf(CategoryTotal("Food", 50000), CategoryTotal("Housing", 30000), CategoryTotal("Travel", 15000), CategoryTotal("Bills", 4000), CategoryTotal("Other", 600), CategoryTotal("Coffee", 500))
        setScreen(populatedState().copy(categoryTotals = totals, dailySpending = monthDailySpending(DAY to 100100L), pendingReviewSummary = UnreviewedSpendingSummary(600, 1)))
        composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag("insight_category_other"))
        composeRule.onNodeWithTag("insight_category_other").assertExists()
        composeRule.onNodeWithTag("insight_category_bills").assertDoesNotExist()
        composeRule.onNodeWithText("<1%", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("2 more categories · \$45.00").assertExists()
        val summary = composeRule.onNodeWithTag("category_breakdown_chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(summary.contains("Other \$6.00") && summary.contains("2 more") && !summary.contains("Bills"))
        val food = composeRule.onNode(hasText("\$500.00") and hasAnyAncestor(hasTestTag("insight_category_food")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val other = composeRule.onNode(hasText("\$6.00") and hasAnyAncestor(hasTestTag("insight_category_other")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("amount columns must align", kotlin.math.abs(food.right - other.right) <= 1f)
    }

    @Test fun chartBarContrastAgainstLightBackground() = assertChartContrast(false)
    @Test fun chartBarContrastAgainstDarkBackground() = assertChartContrast(true)

    private fun assertChartContrast(dark: Boolean) {
        var background = Color.Unspecified
        var outline = Color.Unspecified
        var expense = Color.Unspecified
        val state = populatedState().copy(dailySpending = monthDailySpending(DAY to 1250L, LocalDate.of(2026, 7, 20) to 700L))
        composeRule.setContent {
            FlowMoneyTheme(darkTheme = dark, dynamicColor = false) {
                background = MaterialTheme.colorScheme.background
                outline = MaterialTheme.colorScheme.outline
                expense = LocalFinanceColors.current.expense
                Surface(color = background) {
                    FlowMoneyScreen(uiState = state, selectedTab = DashboardTab.Insights, onChartRangeModeSelected = {}, onSelectedMonthChange = {}, onEdit = {}, onViewAllTransactions = {}, onDelete = {}, onAddTransaction = {}, onData = {})
                }
            }
        }
        assertTrue("outline token contrast", financeContrastRatio(outline, background) >= 3f)
        assertTrue("expense token contrast", financeContrastRatio(expense, background) >= 3f)
        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        val chart = composeRule.onNodeWithTag("spending_timeline_chart")
        val chartBounds = chart.fetchSemanticsNode().boundsInRoot
        val selected = composeRule.onNodeWithTag(SELECTED_BUCKET).fetchSemanticsNode().boundsInRoot
        val unselected = composeRule.onNodeWithTag(OTHER_BUCKET).fetchSemanticsNode().boundsInRoot
        val pixels = chart.captureToImage().toPixelMap()
        val selectedX = ((selected.left + selected.right) / 2 - chartBounds.left).toInt()
        val unselectedX = ((unselected.left + unselected.right) / 2 - chartBounds.left).toInt()
        val bottomY = (pixels.height * 0.85f).toInt()
        val actualBackground = pixels[unselectedX, (pixels.height * 0.1f).toInt()]
        val selectedPixel = pixels[selectedX, bottomY]
        val unselectedPixel = pixels[unselectedX, bottomY]
        assertTrue("selected pixels dark=$dark $selectedPixel / $actualBackground", financeContrastRatio(selectedPixel, actualBackground) >= 3f)
        assertTrue("unselected pixels dark=$dark $unselectedPixel / $actualBackground", financeContrastRatio(unselectedPixel, actualBackground) >= 3f)
    }

    @Test
    fun tappingABarSelectsThatBucket() {
        setScreen(uiState = populatedState())

        composeRule.onNodeWithTag(OTHER_BUCKET).performClick()

        composeRule
            .onNodeWithTag(OTHER_BUCKET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule
            .onNodeWithTag(SELECTED_BUCKET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }

    @Test
    fun verticalSwipeOverTimelineScrollsInsightsWithoutScrubbingSelection() {
        setScreen(uiState = scrollingState())
        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        val categoryTopBeforeSwipe =
            composeRule
                .onNodeWithTag("category_breakdown_chart")
                .fetchSemanticsNode()
                .boundsInRoot.top

        composeRule.onNodeWithTag("spending_timeline_chart").performTouchInput { swipeUp() }

        val categoryTopAfterSwipe =
            composeRule
                .onNodeWithTag("category_breakdown_chart")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Insights list should scroll from a swipe over the chart", categoryTopAfterSwipe < categoryTopBeforeSwipe)
        composeRule
            .onNodeWithTag(SELECTED_BUCKET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule
            .onNodeWithTag(OTHER_BUCKET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }

    @Test
    fun selectedInsightTransactionOpensEditor() {
        var edited: Transaction? = null
        setScreen(
            uiState = populatedState(),
            onEdit = { edited = it },
        )

        composeRule.onNodeWithTag("insight_category_food").performClick()
        composeRule
            .onNodeWithTag("insight_transaction_tx-food")
            .performScrollTo()
            .performClick()

        assertEquals("tx-food", edited?.id)
    }

    @Test
    fun drillDownExcludesSameDayTransfersAndGroupsUnreviewedNormalSpendingUnderOther() {
        setScreen(uiState = reportingState())

        composeRule.onNodeWithTag(SELECTED_BUCKET).performClick()
        composeRule
            .onNodeWithTag("insights_list")
            .performScrollToNode(hasTestTag("insight_transaction_tx-food"))
        composeRule.onNodeWithTag("insight_transaction_tx-food").assertIsDisplayed()
        composeRule
            .onNodeWithTag("insights_list")
            .performScrollToNode(hasTestTag("insight_transaction_tx-unreviewed"))
        composeRule.onNodeWithTag("insight_transaction_tx-unreviewed").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_transaction_tx-transfer").assertDoesNotExist()

        composeRule
            .onNodeWithTag("insights_list")
            .performScrollToNode(hasTestTag("insight_category_other"))
        composeRule.onNodeWithTag("insight_category_other").performClick()
        composeRule.onNodeWithText("Other spending · Jul 8–14").assertIsDisplayed()
        composeRule
            .onNodeWithTag("insights_list")
            .performScrollToNode(hasTestTag("insight_transaction_tx-unreviewed"))
        composeRule.onNodeWithTag("insight_transaction_tx-unreviewed").assertIsDisplayed()
        composeRule.onNodeWithTag("insight_transaction_tx-food").assertDoesNotExist()
        composeRule.onNodeWithTag("insight_transaction_tx-transfer").assertDoesNotExist()
    }

    @Test
    fun overviewRangeCountExcludesTransfers() {
        setScreen(
            uiState = reportingState(),
            selectedTab = DashboardTab.Overview,
        )

        composeRule.onNodeWithText("2 transactions · Transfers excluded").performScrollTo().assertIsDisplayed()
    }

    @Test fun emptyRangeExplainsMissingLatestSpending() {
        setScreen(uiState = reportingState().copy(latestSpendingTransactions = emptyList()), selectedTab = DashboardTab.Overview)
        composeRule.onNodeWithText("No spending in this period").assertIsDisplayed()
        composeRule.onNodeWithText("Latest spending").assertDoesNotExist()
    }

    @Test fun insightsDisclosesExcludedTransfers() {
        setScreen(uiState = reportingState())
        composeRule.onNodeWithText("Transfers excluded", substring = true).assertIsDisplayed()
    }

    @Test fun transactionsDisclosesExcludedTransfers() {
        setScreen(uiState = reportingState(), selectedTab = DashboardTab.Transactions)
        composeRule.onNodeWithText("Transfers excluded", substring = true).assertIsDisplayed()
    }

    private fun setScreen(
        uiState: MainUiState,
        selectedTab: DashboardTab = DashboardTab.Insights,
        onEdit: (Transaction) -> Unit = {},
        onAddTransaction: () -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
              Surface(color = MaterialTheme.colorScheme.background) {
                FlowMoneyScreen(
                    uiState = uiState,
                    selectedTab = selectedTab,
                    onChartRangeModeSelected = {},
                    onSelectedMonthChange = {},
                    onEdit = onEdit,
                    onViewAllTransactions = {},
                    onDelete = {},
                    onAddTransaction = onAddTransaction,
                    onData = {},
                    modifier = Modifier.fillMaxSize(),
                    onReview = onReview,
                )
              }
            }
        }
    }

    private fun populatedState(): MainUiState {
        val transaction =
            Transaction(
                id = "tx-food",
                occurredAtEpochMillis = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                merchant = "Lunch",
                category = "Food",
                note = "",
                cents = -1_250,
            )
        return MainUiState(
            isLoading = false,
            sortedTransactions = listOf(transaction),
            latestSpendingTransactions = listOf(transaction),
            rangeTransactions = listOf(transaction),
            metrics = DashboardMetrics(spentCents = 1_250, incomeCents = 0, transactionCount = 1),
            dateRange = RANGE,
            chartRangeMode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2026, 7),
            availableMonths = listOf(YearMonth.of(2026, 7)),
            dailySpending = monthDailySpending(DAY to 1_250L),
            categoryTotals = listOf(CategoryTotal("Food", 1_250)),
        )
    }

    private fun reportingState(): MainUiState {
        val occurredAt = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val transactions =
            listOf(
                Transaction(
                    id = "tx-food",
                    occurredAtEpochMillis = occurredAt,
                    merchant = "Lunch",
                    category = "Food",
                    note = "",
                    cents = -1_250,
                    source = "simplefin",
                    reviewedAtEpochMillis = 1,
                ),
                Transaction(
                    id = "tx-unreviewed",
                    occurredAtEpochMillis = occurredAt,
                    merchant = "Pending trip",
                    category = "Travel",
                    note = "",
                    cents = -500,
                    source = "simplefin",
                ),
                Transaction(
                    id = "tx-transfer",
                    occurredAtEpochMillis = occurredAt,
                    merchant = "Account transfer",
                    category = "Food",
                    note = "",
                    cents = -2_000,
                    source = "simplefin",
                    flowKind = FlowKind.TRANSFER,
                ),
            )
        return MainUiState(
            isLoading = false,
            sortedTransactions = transactions,
            latestSpendingTransactions = transactions.filter { it.effectiveFlowKind == FlowKind.NORMAL && it.cents < 0 }.take(3),
            rangeTransactions = transactions,
            metrics = DashboardAnalytics.metrics(transactions),
            dateRange = RANGE,
            chartRangeMode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2026, 7),
            availableMonths = listOf(YearMonth.of(2026, 7)),
            dailySpending = monthDailySpending(DAY to 1_750L),
            categoryTotals = DashboardAnalytics.categoryTotals(transactions),
            pendingReviewSummary = DashboardAnalytics.unreviewedSpendingSummary(transactions),
        )
    }

    private fun scrollingState(): MainUiState {
        val transactions =
            List(30) { index ->
                Transaction(
                    id = "tx-$index",
                    occurredAtEpochMillis = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    merchant = "Lunch $index",
                    category = "Food",
                    note = "",
                    cents = -100,
                )
            }
        return MainUiState(
            isLoading = false,
            sortedTransactions = transactions,
            latestSpendingTransactions = transactions.take(3),
            rangeTransactions = transactions,
            metrics = DashboardMetrics(spentCents = 3_000, incomeCents = 0, transactionCount = transactions.size),
            dateRange = RANGE,
            chartRangeMode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2026, 7),
            availableMonths = listOf(YearMonth.of(2026, 7)),
            dailySpending = monthDailySpending(DAY to 3_000L),
            categoryTotals = listOf(CategoryTotal("Food", 3_000)),
        )
    }

    /**
     * Month mode charts a full day-per-day list for the range, exactly like
     * [DashboardAnalytics.dailySpending] builds it, so [DashboardAnalytics.spendBuckets] chunks it
     * into the same weeks production shows.
     */
    private fun monthDailySpending(vararg spend: Pair<LocalDate, Long>): List<DailySpend> {
        val byDate = spend.toMap()
        return generateSequence(RANGE.startInclusive) { date ->
            date.plusDays(1).takeIf { it.isBefore(RANGE.endExclusive) }
        }.map { date -> DailySpend(date, byDate[date] ?: 0L) }.toList()
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 7, 10)
        val RANGE =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 1),
                endExclusive = LocalDate.of(2026, 8, 1),
                label = "July 2026",
            )

        /** July 10 falls in the Jul 8-14 chunk of a month-mode timeline. */
        const val SELECTED_BUCKET = "insight_bucket_2026-07-08"
        const val OTHER_BUCKET = "insight_bucket_2026-07-15"
    }
}
