package com.dwk.flowmoney

import java.util.Locale
import kotlin.math.absoluteValue

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
