package com.dwk.flowmoney

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class ChartRangeMode(
    val label: String,
) {
    Week("Week"),
    Month("Month"),
}

data class DashboardDateRange(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
    val label: String,
)

/**
 * Selected-range reporting totals.
 *
 * Spending, income, and [transactionCount] include only transactions whose
 * [Transaction.effectiveFlowKind] is [FlowKind.NORMAL]. [transferCents] is the sum of the
 * absolute value of every effective transfer in the range, so two matched transfer legs each
 * contribute their own magnitude. It is disclosure-only and defaults to zero for existing callers.
 */
data class DashboardMetrics(
    val spentCents: Long,
    val incomeCents: Long,
    val transactionCount: Int,
    val transferCents: Long = 0,
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

/**
 * Optional correction workload for selected-range, unreviewed normal expenses.
 *
 * These transactions are already included in dashboard spending and grouped under Other in
 * category totals; review is not required for reporting. [transactionCount] counts negative
 * transactions where [Transaction.isUnreviewed] is true and [Transaction.effectiveFlowKind] is
 * [FlowKind.NORMAL]. [spentCents] is their positive spending total. Income, zero-value
 * transactions, and effective transfers do not contribute to either value.
 */
data class UnreviewedSpendingSummary(
    val spentCents: Long,
    val transactionCount: Int,
)

object DashboardAnalytics {
    fun rangeFor(
        mode: ChartRangeMode,
        selectedMonth: YearMonth,
        today: LocalDate = LocalDate.now(),
    ): DashboardDateRange =
        when (mode) {
            ChartRangeMode.Week -> {
                DashboardDateRange(
                    startInclusive = today.minusDays(6),
                    endExclusive = today.plusDays(1),
                    label = "Last 7 days",
                )
            }

            ChartRangeMode.Month -> {
                DashboardDateRange(
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
    ): List<Transaction> =
        transactions.filter { transaction ->
            val date = transaction.localDate(zoneId)
            !date.isBefore(range.startInclusive) && date.isBefore(range.endExclusive)
        }

    fun metrics(transactions: List<Transaction>): DashboardMetrics {
        var spent = 0L
        var income = 0L
        var transfers = 0L
        var transactionCount = 0
        transactions.forEach { transaction ->
            if (transaction.effectiveFlowKind == FlowKind.TRANSFER) {
                transfers += kotlin.math.abs(transaction.cents.toLong())
            } else {
                transactionCount += 1
                when {
                    transaction.cents < 0 -> spent -= transaction.cents.toLong()
                    transaction.cents > 0 -> income += transaction.cents.toLong()
                }
            }
        }
        return DashboardMetrics(
            spentCents = spent,
            incomeCents = income,
            transactionCount = transactionCount,
            transferCents = transfers,
        )
    }

    fun dailySpending(
        transactions: List<Transaction>,
        range: DashboardDateRange,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DailySpend> {
        val spendingByDay = mutableMapOf<LocalDate, Long>()
        transactions.forEach { transaction ->
            if (transaction.effectiveFlowKind == FlowKind.NORMAL && transaction.cents < 0) {
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
            if (transaction.effectiveFlowKind == FlowKind.NORMAL && transaction.cents < 0) {
                val category =
                    if (transaction.isUnreviewed) {
                        "Other"
                    } else {
                        transaction.category.trim().ifBlank { "Other" }
                    }
                totals[category] = (totals[category] ?: 0L) - transaction.cents.toLong()
            }
        }
        return totals
            .map { (category, cents) -> CategoryTotal(category = category, cents = cents) }
            .sortedByDescending { it.cents }
            .take(limit)
    }

    fun unreviewedSpendingSummary(transactions: List<Transaction>): UnreviewedSpendingSummary {
        var spent = 0L
        var count = 0
        transactions.forEach { transaction ->
            if (
                transaction.effectiveFlowKind == FlowKind.NORMAL &&
                transaction.isUnreviewed &&
                transaction.cents < 0
            ) {
                spent -= transaction.cents.toLong()
                count += 1
            }
        }
        return UnreviewedSpendingSummary(
            spentCents = spent,
            transactionCount = count,
        )
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

internal fun Transaction.localDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant
        .ofEpochMilli(occurredAtEpochMillis)
        .atZone(zoneId)
        .toLocalDate()

private fun java.time.Month.displayName(): String = getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
