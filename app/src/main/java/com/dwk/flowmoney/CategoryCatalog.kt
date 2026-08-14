package com.dwk.flowmoney

import java.util.Locale

data class CategoryOption(
    val label: String,
    val usageCount: Int,
    val lastUsedEpochMillis: Long?,
) {
    val isUsed: Boolean = usageCount > 0
}

data class CategoryPickerOptions(
    val visible: List<CategoryOption>,
    val hiddenCount: Int,
)

object CategoryCatalog {
    val expenseCategories = listOf(
        "Food",
        "Groceries",
        "Restaurants",
        "Coffee",
        "Transit",
        "Gas",
        "Ride share",
        "Car",
        "Parking",
        "Rent",
        "Mortgage",
        "Utilities",
        "Phone",
        "Internet",
        "Insurance",
        "Healthcare",
        "Pharmacy",
        "Fitness",
        "Shopping",
        "Clothing",
        "Home",
        "Pets",
        "Kids",
        "Entertainment",
        "Travel",
        "Subscriptions",
        "Education",
        "Gifts",
        "Charity",
        "Taxes",
        "Fees",
        "Debt",
        "Other",
    )

    val incomeCategories = listOf(
        "Salary",
        "Bonus",
        "Freelance",
        "Business",
        "Interest",
        "Dividends",
        "Investments",
        "Rental",
        "Refund",
        "Reimbursement",
        "Transfer",
        "Gift",
        "Sale",
        "Other",
    )

    fun categoriesFor(isExpense: Boolean): List<String> {
        return if (isExpense) expenseCategories else incomeCategories
    }

    fun rankedCategories(
        transactions: List<Transaction>,
        isExpense: Boolean,
    ): List<CategoryOption> {
        val baseCategories = categoriesFor(isExpense)
        val baseOrder = baseCategories
            .mapIndexed { index, category -> category.normalizedCategoryKey() to index }
            .toMap()
        val labelsByKey = linkedMapOf<String, String>()
        baseCategories.forEach { category -> labelsByKey[category.normalizedCategoryKey()] = category }

        val usageByKey = mutableMapOf<String, CategoryUsage>()
        transactions
            .asSequence()
            .filter { transaction -> if (isExpense) transaction.cents < 0 else transaction.cents > 0 }
            .forEach { transaction ->
                val label = transaction.category.trim().ifBlank { "Other" }
                val key = label.normalizedCategoryKey()
                labelsByKey.putIfAbsent(key, label)
                usageByKey.getOrPut(key) { CategoryUsage() }.record(transaction.occurredAtEpochMillis)
            }

        return labelsByKey.map { (key, label) ->
            val usage = usageByKey[key]
            CategoryOption(
                label = label,
                usageCount = usage?.count ?: 0,
                lastUsedEpochMillis = usage?.lastUsedEpochMillis,
            )
        }.sortedWith(
            compareByDescending<CategoryOption> { it.isUsed }
                .thenByDescending { it.usageCount }
                .thenByDescending { it.lastUsedEpochMillis ?: Long.MIN_VALUE }
                .thenBy { baseOrder[it.label.normalizedCategoryKey()] ?: Int.MAX_VALUE }
                .thenBy { it.label },
        )
    }

    fun pickerOptions(
        transactions: List<Transaction>,
        isExpense: Boolean,
        selectedCategory: String,
        expanded: Boolean,
        collapsedLimit: Int = 8,
    ): CategoryPickerOptions {
        return pickerOptions(
            ranked = rankedCategories(transactions = transactions, isExpense = isExpense),
            selectedCategory = selectedCategory,
            expanded = expanded,
            collapsedLimit = collapsedLimit,
        )
    }

    fun pickerOptions(
        ranked: List<CategoryOption>,
        selectedCategory: String,
        expanded: Boolean,
        collapsedLimit: Int = 8,
    ): CategoryPickerOptions {
        if (expanded || ranked.size <= collapsedLimit) {
            return CategoryPickerOptions(visible = ranked, hiddenCount = 0)
        }

        val visible = ranked.take(collapsedLimit).toMutableList()
        val selected = ranked.firstOrNull {
            it.label.normalizedCategoryKey() == selectedCategory.normalizedCategoryKey()
        }
        if (selected != null && visible.none { it.label.normalizedCategoryKey() == selected.label.normalizedCategoryKey() }) {
            visible[visible.lastIndex] = selected
        }

        return CategoryPickerOptions(
            visible = visible,
            hiddenCount = ranked.size - visible.size,
        )
    }

    private data class CategoryUsage(
        var count: Int = 0,
        var lastUsedEpochMillis: Long = Long.MIN_VALUE,
    ) {
        fun record(occurredAtEpochMillis: Long) {
            count += 1
            if (occurredAtEpochMillis > lastUsedEpochMillis) {
                lastUsedEpochMillis = occurredAtEpochMillis
            }
        }
    }
}

private fun String.normalizedCategoryKey(): String {
    return trim().lowercase(Locale.US)
}
