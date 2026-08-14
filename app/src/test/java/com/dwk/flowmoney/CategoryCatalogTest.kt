package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryCatalogTest {
    @Test fun rankedCategoriesPutFrequentlyUsedRecentCategoriesFirst() {
        val transactions = listOf(
            transaction(id = "1", category = "Travel", occurredAtEpochMillis = 1_000),
            transaction(id = "2", category = "Travel", occurredAtEpochMillis = 2_000),
            transaction(id = "3", category = "Coffee", occurredAtEpochMillis = 5_000),
            transaction(id = "4", category = "Groceries", occurredAtEpochMillis = 4_000),
            transaction(id = "5", category = "Groceries", occurredAtEpochMillis = 6_000),
        )

        val ranked = CategoryCatalog.rankedCategories(transactions, isExpense = true)

        assertThat(ranked.take(3).map { it.label }).containsExactly("Groceries", "Travel", "Coffee").inOrder()
        assertThat(ranked.first().usageCount).isEqualTo(2)
    }

    @Test fun pickerOptionsHideLessUsedCategoriesUntilExpanded() {
        val transactions = listOf(
            transaction(id = "1", category = "Coffee", occurredAtEpochMillis = 5_000),
            transaction(id = "2", category = "Coffee", occurredAtEpochMillis = 6_000),
            transaction(id = "3", category = "Gas", occurredAtEpochMillis = 4_000),
        )

        val collapsed = CategoryCatalog.pickerOptions(
            transactions = transactions,
            isExpense = true,
            selectedCategory = "Food",
            expanded = false,
            collapsedLimit = 4,
        )
        val expanded = CategoryCatalog.pickerOptions(
            transactions = transactions,
            isExpense = true,
            selectedCategory = "Food",
            expanded = true,
            collapsedLimit = 4,
        )

        assertThat(collapsed.visible.map { it.label }).containsExactly("Coffee", "Gas", "Food", "Groceries").inOrder()
        assertThat(collapsed.hiddenCount).isGreaterThan(0)
        assertThat(expanded.visible.size).isGreaterThan(collapsed.visible.size)
        assertThat(expanded.hiddenCount).isEqualTo(0)
    }

    @Test fun pickerOptionsKeepSelectedHiddenCategoryVisible() {
        val collapsed = CategoryCatalog.pickerOptions(
            transactions = emptyList(),
            isExpense = true,
            selectedCategory = "Taxes",
            expanded = false,
            collapsedLimit = 4,
        )

        assertThat(collapsed.visible.map { it.label }).contains("Taxes")
        assertThat(collapsed.hiddenCount).isGreaterThan(0)
    }

    private fun transaction(
        id: String,
        category: String,
        occurredAtEpochMillis: Long,
        cents: Int = -100,
    ): Transaction {
        return Transaction(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            merchant = category,
            category = category,
            note = "",
            cents = cents,
        )
    }
}
