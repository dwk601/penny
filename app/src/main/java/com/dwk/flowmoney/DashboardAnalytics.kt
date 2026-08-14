package com.dwk.flowmoney

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class ChartRangeMode(val label: String) {
    Week("Week"),
    Month("Month"),
}

data class DashboardDateRange(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
    val label: String,
)

data class DashboardMetrics(
    val spentCents: Long,
    val incomeCents: Long,
    val transactionCount: Int,
) {
    val netCents: Long = incomeCents - spentCents
}

data class DailySpend(
    val date: LocalDate,
    val cents: Long,
)

data class CategoryTotal(
    val category: String,
    val cents: Long,
)

object DashboardAnalytics {
    fun rangeFor(
        mode: ChartRangeMode,
        selectedMonth: YearMonth,
        today: LocalDate = LocalDate.now(),
    ): DashboardDateRange {
        return when (mode) {
            ChartRangeMode.Week -> DashboardDateRange(
                startInclusive = today.minusDays(6),
                endExclusive = today.plusDays(1),
                label = "Last 7 days",
            )
            ChartRangeMode.Month -> DashboardDateRange(
                startInclusive = selectedMonth.atDay(1),
                endExclusive = selectedMonth.plusMonths(1).atDay(1),
                label = "${selectedMonth.month.displayName()} ${selectedMonth.year}",
            )
        }
    }

    fun filterTransactions(
        transactions: List<Transaction>,
        range: DashboardDateRange,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Transaction> {
        return transactions.filter { transaction ->
            val date = transaction.localDate(zoneId)
            !date.isBefore(range.startInclusive) && date.isBefore(range.endExclusive)
        }
    }

    fun metrics(transactions: List<Transaction>): DashboardMetrics {
        var spent = 0L
        var income = 0L
        transactions.forEach { transaction ->
            when {
                transaction.cents < 0 -> spent -= transaction.cents.toLong()
                transaction.cents > 0 -> income += transaction.cents.toLong()
            }
        }
        return DashboardMetrics(
            spentCents = spent,
            incomeCents = income,
            transactionCount = transactions.size,
        )
    }

    fun dailySpending(
        transactions: List<Transaction>,
        range: DashboardDateRange,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DailySpend> {
        val spendingByDay = mutableMapOf<LocalDate, Long>()
        transactions.forEach { transaction ->
            if (transaction.cents < 0) {
                val date = transaction.localDate(zoneId)
                spendingByDay[date] = (spendingByDay[date] ?: 0L) - transaction.cents.toLong()
            }
        }

        return generateSequence(range.startInclusive) { date ->
            date.plusDays(1).takeIf { it.isBefore(range.endExclusive) }
        }.map { date ->
            DailySpend(date = date, cents = spendingByDay[date] ?: 0L)
        }.toList()
    }

    fun categoryTotals(
        transactions: List<Transaction>,
        limit: Int = 4,
    ): List<CategoryTotal> {
        val totals = mutableMapOf<String, Long>()
        transactions.forEach { transaction ->
            if (transaction.cents < 0) {
                val category = transaction.category.trim().ifBlank { "Other" }
                totals[category] = (totals[category] ?: 0L) - transaction.cents.toLong()
            }
        }
        return totals.map { (category, cents) -> CategoryTotal(category = category, cents = cents) }
            .sortedByDescending { it.cents }
            .take(limit)
    }

    fun availableMonths(
        transactions: List<Transaction>,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<YearMonth> {
        val months = mutableSetOf(YearMonth.from(today))
        transactions.forEach { months += YearMonth.from(it.localDate(zoneId)) }
        return months.sortedDescending()
    }
}

internal fun Transaction.localDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
    return Instant.ofEpochMilli(occurredAtEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
}

private fun java.time.Month.displayName(): String {
    return getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
}
