package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionSuggestionsTest {
    @Test fun signedCentsStoresExpenseNegativeAndIncomePositive() {
        assertThat(TransactionSuggestions.signedCents(1299, isExpense = true)).isEqualTo(-1299)
        assertThat(TransactionSuggestions.signedCents(-1299, isExpense = true)).isEqualTo(-1299)
        assertThat(TransactionSuggestions.signedCents(1299, isExpense = false)).isEqualTo(1299)
        assertThat(TransactionSuggestions.signedCents(-1299, isExpense = false)).isEqualTo(1299)
    }

    @Test fun merchantSuggestionsPreferCurrentCategoryAndRecentRows() {
        val transactions = listOf(
            transaction("A", "Market", "Food", -2500, 1000),
            transaction("B", "Train Pass", "Transit", -5000, 4000),
            transaction("C", "Corner Market", "Food", -1300, 3000),
            transaction("D", "Main Market", "Food", -4000, 2000),
        )

        val suggestions = TransactionSuggestions.merchantSuggestions(
            transactions = transactions,
            query = "market",
            category = "Food",
            isExpense = true,
        )
        val historySuggestions = TransactionSuggestions.merchantSuggestions(
            history = TransactionSuggestions.history(transactions),
            query = "market",
            category = "Food",
            isExpense = true,
        )

        assertThat(suggestions).containsExactly("Corner Market", "Main Market", "Market").inOrder()
        assertThat(historySuggestions).isEqualTo(suggestions)
    }

    private fun transaction(
        id: String = "id-${System.nanoTime()}",
        merchant: String,
        category: String,
        cents: Int,
        occurredAtEpochMillis: Long,
    ): Transaction {
        return Transaction(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            merchant = merchant,
            category = category,
            note = "",
            cents = cents,
        )
    }
}
