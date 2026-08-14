package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Test

class DashboardAnalyticsTest {
    private val utc = ZoneId.of("UTC")

    @Test fun monthRangeSupportsSpecificMonthYearSelection() {
        val range = DashboardAnalytics.rangeFor(
            mode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2025, 3),
            today = LocalDate.of(2026, 6, 19),
        )

        assertThat(range.startInclusive).isEqualTo(LocalDate.of(2025, 3, 1))
        assertThat(range.endExclusive).isEqualTo(LocalDate.of(2025, 4, 1))
        assertThat(range.label).isEqualTo("March 2025")
    }

    @Test fun weekRangeUsesLastSevenDaysIncludingToday() {
        val range = DashboardAnalytics.rangeFor(
            mode = ChartRangeMode.Week,
            selectedMonth = YearMonth.of(2026, 6),
            today = LocalDate.of(2026, 6, 19),
        )

        assertThat(range.startInclusive).isEqualTo(LocalDate.of(2026, 6, 13))
        assertThat(range.endExclusive).isEqualTo(LocalDate.of(2026, 6, 20))
    }

    @Test fun filtersMetricsAndDailyChartBySelectedRange() {
        val range = DashboardAnalytics.rangeFor(
            mode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2026, 6),
            today = LocalDate.of(2026, 6, 19),
        )
        val transactions = listOf(
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

    @Test fun aggregatesIntMinimumAndTotalsBeyondIntMaximum() {
        val date = LocalDate.of(2026, 6, 3)
        val range = DashboardAnalytics.rangeFor(
            mode = ChartRangeMode.Month,
            selectedMonth = YearMonth.of(2026, 6),
            today = date,
        )
        val transactions = listOf(
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
        val months = DashboardAnalytics.availableMonths(
            transactions = listOf(
                transaction(id = "old", date = LocalDate.of(2025, 1, 12), cents = -1200),
                transaction(id = "same-month", date = LocalDate.of(2025, 1, 20), cents = -3400),
                transaction(id = "recent", date = LocalDate.of(2026, 4, 2), cents = 5000),
            ),
            today = LocalDate.of(2026, 6, 19),
            zoneId = utc,
        )

        assertThat(months).containsExactly(
            YearMonth.of(2026, 6),
            YearMonth.of(2026, 4),
            YearMonth.of(2025, 1),
        ).inOrder()
    }

    private fun transaction(
        id: String,
        date: LocalDate,
        cents: Int,
    ): Transaction {
        return Transaction(
            id = id,
            occurredAtEpochMillis = date.atStartOfDay(utc).toInstant().toEpochMilli(),
            merchant = id,
            category = if (cents < 0) "Food" else "Salary",
            note = "",
            cents = cents,
        )
    }
}
