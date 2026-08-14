package com.dwk.flowmoney

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase5FinancialLegibilityUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun compactLargeTextKeepsOverviewAmountsExactAndContained() {
        val transaction = transaction(id = "overview", cents = -100)
        setScreen(
            tab = DashboardTab.Overview,
            state = MainUiState(
                isLoading = false,
                sortedTransactions = listOf(transaction),
                recentTransactions = listOf(transaction),
                rangeTransactions = listOf(transaction),
                metrics = DashboardMetrics(
                    spentCents = 123_456_789,
                    incomeCents = 987_654_321,
                    transactionCount = 1,
                ),
            ),
        )

        listOf("\$1,234,567.89", "\$9,876,543.21", "\$8,641,975.32").forEach { amount ->
            assertTextContained(amount, "overview_list")
        }
    }

    @Test
    fun compactLargeTextKeepsSignedTransactionAmountExactAndContained() {
        val transaction = transaction(
            id = "long-amount",
            cents = -1_234_567_890,
            merchant = "A merchant name that is allowed to yield before the amount",
        )
        setScreen(
            tab = DashboardTab.Transactions,
            state = MainUiState(isLoading = false, sortedTransactions = listOf(transaction)),
        )

        assertTextContained("-\$12,345,678.90", "transaction_content_long-amount")
    }

    @Test
    fun compactLargeTextKeepsFourInsightLegendAmountsExactAndContained() {
        val totals = listOf(
            CategoryTotal("Automotive Maintenance and Emergency Repairs", 1_234_567_890),
            CategoryTotal("Housing", 987_654_321),
            CategoryTotal("Travel", 765_432_109),
            CategoryTotal("Food", 543_210_987),
        )
        setScreen(
            tab = DashboardTab.Insights,
            state = MainUiState(
                isLoading = false,
                dailySpending = listOf(DailySpend(LocalDate.of(2026, 7, 10), 1_234_567_890)),
                categoryTotals = totals,
            ),
        )

        listOf(
            "\$12,345,678.90" to "insight_category_automotivemaintenanceandemergencyrepairs",
            "\$9,876,543.21" to "insight_category_housing",
            "\$7,654,321.09" to "insight_category_travel",
            "\$5,432,109.87" to "insight_category_food",
        ).forEach { (amount, itemTag) -> assertTextContained(amount, itemTag) }
    }

    private fun setScreen(tab: DashboardTab, state: MainUiState) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.Companion.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.5f)) {
                    FlowMoneyTheme(dynamicColor = false) {
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
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }

    private fun assertTextContained(text: String, parentTag: String) {
        val textNode = composeRule.onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(parentTag)),
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
        val parent = composeRule.onNodeWithTag(parentTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val child = textNode.fetchSemanticsNode().boundsInRoot
        assertContained(text, child, parentTag, parent)
    }

    private fun assertContained(text: String, child: Rect, parentTag: String, parent: Rect) {
        assertTrue(
            "$text bounds=$child escaped $parentTag bounds=$parent",
            child.left >= parent.left && child.top >= parent.top &&
                child.right <= parent.right && child.bottom <= parent.bottom,
        )
    }

    private fun transaction(
        id: String,
        cents: Int,
        merchant: String = "Merchant",
    ) = Transaction(
        id = id,
        occurredAtEpochMillis = 1L,
        merchant = merchant,
        category = "Other",
        note = "Secondary text that may yield",
        cents = cents,
    )
}
