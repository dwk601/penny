package com.dwk.flowmoney

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val DIACRITIC_MARKS = Regex("\\p{Mn}+")
private val WHITESPACE = Regex("\\s+")

enum class TransactionTimeFilter(
    val label: String,
) {
    All("All"),
    Week("Week"),
    Month("Month"),
}

enum class TransactionSourceFilter(
    val label: String,
) {
    All("All"),
    Synced("Synced"),
    Manual("Manual"),
}

data class TransactionFilter(
    val time: TransactionTimeFilter = TransactionTimeFilter.All,
    val category: String? = null,
    val where: String = "",
    val source: TransactionSourceFilter = TransactionSourceFilter.All,
    val accountKey: String? = null,
    val unreviewedOnly: Boolean = false,
)

data class TransactionAccountOption(
    val accountKey: String,
    val accountName: String,
    val institutionName: String?,
    val label: String,
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
                transaction.matchesSource(filter.source) &&
                (category == null || transaction.category.searchKey() == category) &&
                (filter.accountKey == null || transaction.accountKey == filter.accountKey) &&
                (!filter.unreviewedOnly || transaction.isUnreviewed) &&
                (query.isBlank() || transaction.matchesWhere(query))
        }
    }

    fun categories(transactions: List<Transaction>): List<String> =
        transactions
            .map { it.category.trim().ifBlank { "Other" } }
            .distinctBy { it.searchKey() }

    fun accountOptions(simpleFinAccounts: List<SimpleFinAccountEntity>): List<TransactionAccountOption> =
        accountOptionsFrom(
            simpleFinAccounts.distinctBy { it.accountId }.map { account ->
                AccountOptionData(
                    accountKey = account.accountId,
                    accountName = account.name.accountNameOr(account.accountId),
                    institutionName = account.institutionName.cleanedOrNull(),
                )
            },
        )
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

private fun Transaction.matchesSource(filter: TransactionSourceFilter): Boolean =
    when (filter) {
        TransactionSourceFilter.All -> true
        TransactionSourceFilter.Synced -> source == SIMPLEFIN_SOURCE
        TransactionSourceFilter.Manual -> source != SIMPLEFIN_SOURCE
    }

private fun Transaction.matchesWhere(query: String): Boolean =
    merchant.searchKey().contains(query) ||
        note.searchKey().contains(query) ||
        accountName.orEmpty().searchKey().contains(query)

private data class AccountOptionData(
    val accountKey: String,
    val accountName: String,
    val institutionName: String?,
)

private fun accountOptionsFrom(options: List<AccountOptionData>): List<TransactionAccountOption> {
    val accountNameCounts = options.groupingBy { it.accountName.searchKey() }.eachCount()
    return options.map { option ->
        val hasDuplicateName = accountNameCounts.getValue(option.accountName.searchKey()) > 1
        TransactionAccountOption(
            accountKey = option.accountKey,
            accountName = option.accountName,
            institutionName = option.institutionName,
            label =
                if (hasDuplicateName && option.institutionName != null) {
                    "${option.accountName} (${option.institutionName})"
                } else {
                    option.accountName
                },
        )
    }
}

private fun String?.accountNameOr(fallback: String): String = cleanedOrNull() ?: fallback

private fun String?.cleanedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun String.searchKey(): String {
    val normalized =
        Normalizer
            .normalize(this, Normalizer.Form.NFD)
            .replace(DIACRITIC_MARKS, "")
    return normalized.trim().lowercase(Locale.US).replace(WHITESPACE, " ")
}

private const val SIMPLEFIN_SOURCE = "simplefin"
