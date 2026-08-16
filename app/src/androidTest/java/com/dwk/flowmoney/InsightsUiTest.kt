package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun pendingReviewShowsSeparateCountAmountAndLinksToReview() {
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

        composeRule.onNodeWithTag("insights_pending_review_count").assertIsDisplayed()
        composeRule.onNodeWithText("2 transactions").assertIsDisplayed()
        composeRule.onNodeWithTag("insights_pending_review_amount").assertIsDisplayed()
        composeRule.onNodeWithText("$12.50").assertIsDisplayed()
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
            .onNodeWithTag("insight_day_2026-07-10")
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule
            .onNodeWithTag("insight_day_2026-07-11")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
        composeRule
            .onNodeWithTag("insight_category_food")
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule.onNodeWithText("Food spending").assertIsDisplayed()
    }

    @Test
    fun canvasTapSelectsTheDayAtItsTapCoordinate() {
        setScreen(uiState = populatedState())

        composeRule.onNodeWithTag("spending_timeline_chart").performTouchInput {
            click(Offset(right - 1f, center.y))
        }

        composeRule
            .onNodeWithTag("insight_day_2026-07-11")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
    }

    @Test
    fun verticalSwipeOverTimelineScrollsInsightsWithoutScrubbingSelection() {
        setScreen(uiState = scrollingState())
        composeRule.onNodeWithTag("spending_timeline_chart").performTouchInput {
            click(Offset(1f, center.y))
        }
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
            .onNodeWithTag("insight_day_2026-07-10")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule
            .onNodeWithTag("insight_day_2026-07-11")
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

    private fun setScreen(
        uiState: MainUiState,
        onEdit: (Transaction) -> Unit = {},
        onAddTransaction: () -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyScreen(
                    uiState = uiState,
                    selectedTab = DashboardTab.Insights,
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
            dailySpending = listOf(DailySpend(DAY, 1_250), DailySpend(ZERO_DAY, 0)),
            categoryTotals = listOf(CategoryTotal("Food", 1_250)),
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
            dailySpending = listOf(DailySpend(DAY, 3_000), DailySpend(ZERO_DAY, 0)),
            categoryTotals = listOf(CategoryTotal("Food", 3_000)),
        )
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 7, 10)
        val ZERO_DAY: LocalDate = LocalDate.of(2026, 7, 11)
        val RANGE =
            DashboardDateRange(
                startInclusive = LocalDate.of(2026, 7, 1),
                endExclusive = LocalDate.of(2026, 8, 1),
                label = "July 2026",
            )
    }
}
