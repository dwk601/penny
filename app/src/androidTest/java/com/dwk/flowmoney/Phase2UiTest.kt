package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase2UiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rangeExposesSelectedSemanticsWith48dpTarget() {
        setScreen(DashboardTab.Overview)
        val range = composeRule.onNodeWithTag("range_week").fetchSemanticsNode()
        assertTrue(range.boundsInRoot.height / composeRule.density.density >= 48f)
        composeRule.onNodeWithTag("range_week").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        assertTrue(
            composeRule.onNodeWithTag("range_mode_group")
                .fetchSemanticsNode().config.contains(SemanticsProperties.SelectableGroup),
        )

    }

    @Test
    fun filtersExposeExpandedSemantics() {
        setScreen(DashboardTab.Transactions)
        composeRule.onNodeWithTag("transaction_where_filter").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_filter_toggle").performClick().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"),
        )
        assertTrue(
            composeRule.onNodeWithTag("transaction_time_filter_group")
                .fetchSemanticsNode().config.contains(SemanticsProperties.SelectableGroup),
        )
        assertTrue(
            composeRule.onNodeWithTag("transaction_category_filter_group")
                .fetchSemanticsNode().config.contains(SemanticsProperties.SelectableGroup),
        )
    }

    @Test
    fun transactionRowsExposeNamedEditAndDeleteActions() {
        setScreen(DashboardTab.Overview)

        composeRule.onNodeWithTag("transaction_content_tx-phase2").assert(
            SemanticsMatcher("has named transaction actions") { node ->
                node.config[SemanticsActions.CustomActions]
                    .map { it.label }
                    .containsAll(listOf("Edit", "Delete"))
            },
        )
    }

    @Test
    fun yearControlExposes48dpLabelledTarget() {
        setScreen(DashboardTab.Overview, ChartRangeMode.Month)
        composeRule.onNodeWithTag("range_month").performClick()
        composeRule.onNodeWithTag("overview_list")
            .performScrollToNode(hasTestTag("month_year_previous"))
        val year = composeRule.onNodeWithTag("month_year_previous")
            .assertContentDescriptionEquals("Previous year")
            .fetchSemanticsNode()
        assertTrue(year.boundsInRoot.height / composeRule.density.density >= 48f)
    }

    @Test
    fun insightSelectorsExpose48dpSelectableTargets() {
        setScreen(DashboardTab.Insights)
        // Week mode buckets one day per cell, so the bucket key is the day itself.
        listOf("insight_bucket_2026-07-10", "insight_category_food").forEach { tag ->
            composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag(tag))
            val selector = composeRule.onNodeWithTag(tag).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, false),
            ).fetchSemanticsNode()
            assertTrue(selector.boundsInRoot.height / composeRule.density.density >= 48f)
        }
    }

    private fun setScreen(tab: DashboardTab, mode: ChartRangeMode = ChartRangeMode.Week) {
        val transaction = Transaction(
            id = "tx-phase2",
            occurredAtEpochMillis = LocalDate.of(2026, 7, 10).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            merchant = "Cafe",
            category = "Food",
            note = "",
            cents = -500,
        )
        val state = MainUiState(
            isLoading = false,
            sortedTransactions = listOf(transaction),
            recentTransactions = listOf(transaction),
            rangeTransactions = listOf(transaction),
            metrics = DashboardMetrics(500, 0, 1),
            dateRange = DashboardDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), "July 2026"),
            chartRangeMode = mode,
            selectedMonth = YearMonth.of(2026, 7),
            availableMonths = listOf(YearMonth.of(2026, 7), YearMonth.of(2025, 7)),
            dailySpending = listOf(DailySpend(LocalDate.of(2026, 7, 10), 500)),
            categoryTotals = listOf(CategoryTotal("Food", 500)),
        )
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyScreen(
                    uiState = state,
                    selectedTab = tab,
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
    }
}
