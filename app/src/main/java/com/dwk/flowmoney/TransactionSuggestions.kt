package com.dwk.flowmoney

import java.util.Locale
import kotlin.math.absoluteValue

data class AmountSuggestion(
    val cents: Int,
    val source: AmountSuggestionSource,
)

enum class AmountSuggestionSource {
    LastMatch,
    FrequentMatch,
    Category,
    Recent,
}

data class TransactionSuggestionHistory(
    val transactions: List<Transaction>,
    val expensesByRecency: List<Transaction>,
    val incomeByRecency: List<Transaction>,
    val expenseCategories: List<CategoryOption> = emptyList(),
    val incomeCategories: List<CategoryOption> = emptyList(),
) {
    fun bySign(isExpense: Boolean): List<Transaction> {
        return if (isExpense) expensesByRecency else incomeByRecency
    }

    fun categories(isExpense: Boolean): List<CategoryOption> {
        return if (isExpense) expenseCategories else incomeCategories
    }

    companion object {
        val Empty = TransactionSuggestionHistory(
            transactions = emptyList(),
            expensesByRecency = emptyList(),
            incomeByRecency = emptyList(),
            expenseCategories = CategoryCatalog.rankedCategories(emptyList(), isExpense = true),
            incomeCategories = CategoryCatalog.rankedCategories(emptyList(), isExpense = false),
        )
    }
}

object TransactionSuggestions {
    fun history(transactions: List<Transaction>): TransactionSuggestionHistory {
        return historyFromNewestFirst(transactions.sortedByDescending { it.occurredAtEpochMillis })
    }

    fun historyFromNewestFirst(transactions: List<Transaction>): TransactionSuggestionHistory {
        val sorted = transactions
        return TransactionSuggestionHistory(
            transactions = sorted,
            expensesByRecency = sorted.filter { it.cents < 0 },
            incomeByRecency = sorted.filter { it.cents > 0 },
            expenseCategories = CategoryCatalog.rankedCategories(sorted, isExpense = true),
            incomeCategories = CategoryCatalog.rankedCategories(sorted, isExpense = false),
        )
    }

    fun signedCents(amountCents: Int, isExpense: Boolean): Int {
        val absolute = amountCents.absoluteValue
        return if (isExpense) -absolute else absolute
    }

    fun amountSuggestions(
        transactions: List<Transaction>,
        merchant: String,
        category: String,
        isExpense: Boolean,
        limit: Int = 6,
    ): List<AmountSuggestion> {
        return amountSuggestions(
            history = history(transactions),
            merchant = merchant,
            category = category,
            isExpense = isExpense,
            limit = limit,
        )
    }

    fun amountSuggestions(
        history: TransactionSuggestionHistory,
        merchant: String,
        category: String,
        isExpense: Boolean,
        limit: Int = 6,
    ): List<AmountSuggestion> {
        val normalizedMerchant = merchant.normalizedKey()
        val normalizedCategory = category.normalizedKey()
        val sorted = history.bySign(isExpense)

        val suggestions = mutableListOf<AmountSuggestion>()
        val seenAmounts = linkedSetOf<Int>()

        fun add(cents: Int, source: AmountSuggestionSource) {
            val amount = cents.absoluteValue
            if (amount > 0 && seenAmounts.add(amount) && suggestions.size < limit) {
                suggestions += AmountSuggestion(amount, source)
            }
        }

        val exactMatches = if (normalizedMerchant.isNotBlank()) {
            sorted.filter { transaction ->
                transaction.merchant.normalizedKey() == normalizedMerchant &&
                    transaction.category.normalizedKey() == normalizedCategory
            }
        } else {
            emptyList()
        }
        exactMatches.firstOrNull()?.let { add(it.cents, AmountSuggestionSource.LastMatch) }

        exactMatches
            .groupBy { it.cents.absoluteValue }
            .entries
            .filter { it.value.size > 1 }
            .sortedWith(
                compareByDescending<Map.Entry<Int, List<Transaction>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.maxOf { it.occurredAtEpochMillis } },
            )
            .forEach { entry -> add(entry.key, AmountSuggestionSource.FrequentMatch) }

        sorted
            .filter { transaction ->
                normalizedCategory.isNotBlank() &&
                    transaction.category.normalizedKey() == normalizedCategory
            }
            .forEach { transaction -> add(transaction.cents, AmountSuggestionSource.Category) }

        sorted.forEach { transaction -> add(transaction.cents, AmountSuggestionSource.Recent) }

        return suggestions
    }

    fun merchantSuggestions(
        transactions: List<Transaction>,
        query: String,
        category: String,
        isExpense: Boolean,
        limit: Int = 5,
    ): List<String> {
        return merchantSuggestions(
            history = history(transactions),
            query = query,
            category = category,
            isExpense = isExpense,
            limit = limit,
        )
    }

    fun merchantSuggestions(
        history: TransactionSuggestionHistory,
        query: String,
        category: String,
        isExpense: Boolean,
        limit: Int = 5,
    ): List<String> {
        val normalizedQuery = query.normalizedKey()
        val normalizedCategory = category.normalizedKey()
        val filtered = history.bySign(isExpense)
            .filter { it.merchant.isNotBlank() }
            .filter { transaction ->
                normalizedQuery.isBlank() ||
                    transaction.merchant.normalizedKey().contains(normalizedQuery)
            }
        val categoryMatches = if (normalizedCategory.isBlank()) {
            filtered
        } else {
            filtered.filter { it.category.normalizedKey() == normalizedCategory }
        }
        val otherMatches = if (normalizedCategory.isBlank()) {
            emptyList()
        } else {
            filtered.filterNot { it.category.normalizedKey() == normalizedCategory }
        }

        return (categoryMatches + otherMatches)
            .distinctBy { it.merchant.normalizedKey() }
            .take(limit)
            .map { it.merchant }
    }
}

private fun String.normalizedKey(): String {
    return trim().lowercase(Locale.US)
}
