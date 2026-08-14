package com.dwk.flowmoney

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val DIACRITIC_MARKS = Regex("\\p{Mn}+")
private val WHITESPACE = Regex("\\s+")

enum class TransactionTimeFilter(val label: String) {
    All("All"),
    Week("Week"),
    Month("Month"),
}

data class TransactionFilter(
    val time: TransactionTimeFilter = TransactionTimeFilter.All,
    val category: String? = null,
    val where: String = "",
)

object TransactionFilters {
    fun apply(
        transactions: List<Transaction>,
        filter: TransactionFilter,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Transaction> {
        val query = filter.where.searchKey()
        val category = filter.category?.searchKey()
        return transactions.filter { transaction ->
            transaction.matchesTime(filter.time, today, zoneId) &&
                (category == null || transaction.category.searchKey() == category) &&
                (query.isBlank() || transaction.matchesWhere(query))
        }
    }

    fun categories(transactions: List<Transaction>): List<String> {
        return transactions
            .map { it.category.trim().ifBlank { "Other" } }
            .distinctBy { it.searchKey() }
            .take(12)
    }
}

private fun Transaction.matchesTime(
    filter: TransactionTimeFilter,
    today: LocalDate,
    zoneId: ZoneId,
): Boolean {
    val date = localDate(zoneId)
    return when (filter) {
        TransactionTimeFilter.All -> true
        TransactionTimeFilter.Week -> !date.isBefore(today.minusDays(6)) && !date.isAfter(today)
        TransactionTimeFilter.Month -> date.year == today.year && date.month == today.month
    }
}

private fun Transaction.matchesWhere(query: String): Boolean {
    return merchant.searchKey().contains(query) || note.searchKey().contains(query)
}

private fun String.searchKey(): String {
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(DIACRITIC_MARKS, "")
    return normalized.trim().lowercase(Locale.US).replace(WHITESPACE, " ")
}
