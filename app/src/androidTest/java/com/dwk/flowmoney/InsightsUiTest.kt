package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

        composeRule.onNodeWithText("Optional corrections").assertIsDisplayed()
        composeRule.onNodeWithTag("insights_pending_review_count").assertIsDisplayed()
        composeRule.onNodeWithText("2 transactions").assertIsDisplayed()
        composeRule.onNodeWithTag("insights_pending_review_amount").assertIsDisplayed()
        composeRule.onNodeWithText("$12.50").assertIsDisplayed()
        composeRule
            .onNodeWithText("Already included in spending and grouped under Other; review only to correct details.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("insights_pending_review_action").performClick()
        composeRule.runOnIdle { assertEquals(1, reviewClicks) }
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
        composeRule.onNodeWithText("Food spending").assertIsDisplayed()
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
        composeRule.onNodeWithText("Other spending").assertIsDisplayed()
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

        composeRule.onNodeWithText("3 total · 2 in range").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(
        uiState: MainUiState,
        selectedTab: DashboardTab = DashboardTab.Insights,
        onEdit: (Transaction) -> Unit = {},
        onAddTransaction: () -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
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
            recentTransactions = listOf(transaction),
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
            recentTransactions = transactions,
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
            recentTransactions = transactions.take(5),
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
