package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class DashboardAnalyticsTest {
    private val utc = ZoneId.of("UTC")

    @Test fun monthRangeSupportsSpecificMonthYearSelection() {
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.of(2025, 3),
                today = LocalDate.of(2026, 6, 19),
            )

        assertThat(range.startInclusive).isEqualTo(LocalDate.of(2025, 3, 1))
        assertThat(range.endExclusive).isEqualTo(LocalDate.of(2025, 4, 1))
        assertThat(range.label).isEqualTo("March 2025")
    }

    @Test fun weekRangeUsesLastSevenDaysIncludingToday() {
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Week,
                selectedMonth = YearMonth.of(2026, 6),
                today = LocalDate.of(2026, 6, 19),
            )

        assertThat(range.startInclusive).isEqualTo(LocalDate.of(2026, 6, 13))
        assertThat(range.endExclusive).isEqualTo(LocalDate.of(2026, 6, 20))
    }

    @Test fun filtersMetricsAndDailyChartBySelectedRange() {
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.of(2026, 6),
                today = LocalDate.of(2026, 6, 19),
            )
        val transactions =
            listOf(
                transaction(id = "may", date = LocalDate.of(2026, 5, 31), cents = -999),
                transaction(id = "coffee", date = LocalDate.of(2026, 6, 3), cents = -500),
                transaction(id = "pay", date = LocalDate.of(2026, 6, 15), cents = 200_000),
                transaction(id = "july", date = LocalDate.of(2026, 7, 1), cents = -1200),
            )

        val filtered = DashboardAnalytics.filterTransactions(transactions, range, utc)
        val metrics = DashboardAnalytics.metrics(filtered)
        val daily = DashboardAnalytics.dailySpending(filtered, range, utc)

        assertThat(filtered.map { it.id }).containsExactly("coffee", "pay").inOrder()
        assertThat(metrics.spentCents).isEqualTo(500L)
        assertThat(metrics.incomeCents).isEqualTo(200_000L)
        assertThat(metrics.transactionCount).isEqualTo(2)
        assertThat(daily.first().date).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(daily.last().date).isEqualTo(LocalDate.of(2026, 6, 30))
        assertThat(daily.first { it.date == LocalDate.of(2026, 6, 3) }.cents).isEqualTo(500L)
    }

    @Test fun categoryTotalsExcludesUnreviewedOtherWithoutChangingOverallSpending() {
        val date = LocalDate.of(2026, 6, 3)
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.of(2026, 6),
                today = date,
            )
        val transactions =
            listOf(
                transaction(id = "local-other", date = date, cents = -300, category = "Other"),
                transaction(
                    id = "reviewed-other",
                    date = date,
                    cents = -400,
                    category = "Other",
                    source = "simplefin",
                    reviewedAtEpochMillis = 123,
                ),
                transaction(
                    id = "unreviewed-other",
                    date = date,
                    cents = -1_000,
                    category = "Other",
                    source = "simplefin",
                ),
            )

        assertThat(DashboardAnalytics.categoryTotals(transactions))
            .containsExactly(CategoryTotal(category = "Other", cents = 700))
        assertThat(DashboardAnalytics.metrics(transactions).spentCents).isEqualTo(1_700L)
        assertThat(DashboardAnalytics.dailySpending(transactions, range, utc).single { it.date == date }.cents)
            .isEqualTo(1_700L)
    }

    @Test fun categoryTotalsRanksReviewedMixedCategoriesAndIgnoresIncome() {
        val date = LocalDate.of(2026, 6, 3)
        val transactions =
            listOf(
                transaction(id = "housing", date = date, cents = -900, category = "Housing"),
                transaction(id = "food", date = date, cents = -500, category = "Food"),
                transaction(id = "transport", date = date, cents = -100, category = "Transport"),
                transaction(
                    id = "unreviewed-food",
                    date = date,
                    cents = -3_000,
                    category = "Food",
                    source = "simplefin",
                ),
                transaction(
                    id = "unreviewed-income",
                    date = date,
                    cents = 5_000,
                    category = "Housing",
                    source = "simplefin",
                ),
            )

        assertThat(DashboardAnalytics.categoryTotals(transactions))
            .containsExactly(
                CategoryTotal(category = "Housing", cents = 900),
                CategoryTotal(category = "Food", cents = 500),
                CategoryTotal(category = "Transport", cents = 100),
            ).inOrder()
    }

    @Test fun unreviewedSpendingSummaryCountsOnlyExpenseTransactions() {
        val date = LocalDate.of(2026, 6, 3)
        val transactions =
            listOf(
                transaction(id = "expense-1", date = date, cents = -250, source = "simplefin"),
                transaction(id = "expense-2", date = date, cents = -75, source = "simplefin"),
                transaction(id = "income", date = date, cents = 500, source = "simplefin"),
                transaction(id = "zero", date = date, cents = 0, source = "simplefin"),
                transaction(
                    id = "reviewed-expense",
                    date = date,
                    cents = -600,
                    source = "simplefin",
                    reviewedAtEpochMillis = 123,
                ),
                transaction(id = "local-expense", date = date, cents = -700),
            )

        assertThat(DashboardAnalytics.unreviewedSpendingSummary(transactions)).isEqualTo(
            UnreviewedSpendingSummary(spentCents = 325, transactionCount = 2),
        )
    }

    @Test fun emptyDataAndEmptySelectedRangeHaveNoRankingsOrPendingSpending() {
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.of(2026, 6),
                today = LocalDate.of(2026, 6, 19),
            )
        val outsideRange =
            transaction(
                id = "outside",
                date = LocalDate.of(2026, 5, 31),
                cents = -500,
                source = "simplefin",
            )
        val ranged = DashboardAnalytics.filterTransactions(listOf(outsideRange), range, utc)

        assertThat(ranged).isEmpty()
        assertThat(DashboardAnalytics.categoryTotals(ranged)).isEmpty()
        assertThat(DashboardAnalytics.categoryTotals(emptyList())).isEmpty()
        assertThat(DashboardAnalytics.unreviewedSpendingSummary(ranged)).isEqualTo(
            UnreviewedSpendingSummary(spentCents = 0, transactionCount = 0),
        )
        assertThat(DashboardAnalytics.unreviewedSpendingSummary(emptyList())).isEqualTo(
            UnreviewedSpendingSummary(spentCents = 0, transactionCount = 0),
        )
    }

    @Test fun categoryRankingLimitIsAppliedAfterUnreviewedSpendingIsExcluded() {
        val date = LocalDate.of(2026, 6, 3)
        val transactions =
            listOf(
                transaction(id = "one", date = date, cents = -500, category = "One"),
                transaction(id = "two", date = date, cents = -400, category = "Two"),
                transaction(id = "three", date = date, cents = -300, category = "Three"),
                transaction(id = "four", date = date, cents = -200, category = "Four"),
                transaction(
                    id = "pending",
                    date = date,
                    cents = -10_000,
                    category = "Pending",
                    source = "simplefin",
                ),
            )

        assertThat(DashboardAnalytics.categoryTotals(transactions, limit = 2))
            .containsExactly(
                CategoryTotal(category = "One", cents = 500),
                CategoryTotal(category = "Two", cents = 400),
            ).inOrder()
        assertThat(DashboardAnalytics.categoryTotals(transactions, limit = 0)).isEmpty()
    }

    @Test fun aggregatesIntMinimumAndTotalsBeyondIntMaximum() {
        val date = LocalDate.of(2026, 6, 3)
        val range =
            DashboardAnalytics.rangeFor(
                mode = ChartRangeMode.Month,
                selectedMonth = YearMonth.of(2026, 6),
                today = date,
            )
        val transactions =
            listOf(
                transaction(id = "minimum", date = date, cents = Int.MIN_VALUE),
                transaction(id = "expense", date = date, cents = -1_000_000_000),
                transaction(id = "income-1", date = date, cents = Int.MAX_VALUE),
                transaction(id = "income-2", date = date, cents = Int.MAX_VALUE),
            )

        val metrics = DashboardAnalytics.metrics(transactions)
        val daily = DashboardAnalytics.dailySpending(transactions, range, utc)
        val categories = DashboardAnalytics.categoryTotals(transactions)

        assertThat(metrics.spentCents).isEqualTo(3_147_483_648L)
        assertThat(metrics.incomeCents).isEqualTo(4_294_967_294L)
        assertThat(metrics.netCents).isEqualTo(1_147_483_646L)
        assertThat(daily.first { it.date == date }.cents).isEqualTo(3_147_483_648L)
        assertThat(categories.single().cents).isEqualTo(3_147_483_648L)
    }

    @Test fun availableMonthsIncludesOnlyTransactionMonthsAndCurrentMonth() {
        val months =
            DashboardAnalytics.availableMonths(
                transactions =
                    listOf(
                        transaction(id = "old", date = LocalDate.of(2025, 1, 12), cents = -1200),
                        transaction(id = "same-month", date = LocalDate.of(2025, 1, 20), cents = -3400),
                        transaction(id = "recent", date = LocalDate.of(2026, 4, 2), cents = 5000),
                    ),
                today = LocalDate.of(2026, 6, 19),
                zoneId = utc,
            )

        assertThat(months)
            .containsExactly(
                YearMonth.of(2026, 6),
                YearMonth.of(2026, 4),
                YearMonth.of(2025, 1),
            ).inOrder()
    }

    private fun transaction(
        id: String,
        date: LocalDate,
        cents: Int,
        category: String = if (cents < 0) "Food" else "Salary",
        source: String = "local",
        reviewedAtEpochMillis: Long? = null,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis = date.atStartOfDay(utc).toInstant().toEpochMilli(),
            merchant = id,
            category = category,
            note = "",
            cents = cents,
            source = source,
            reviewedAtEpochMillis = reviewedAtEpochMillis,
        )
}
