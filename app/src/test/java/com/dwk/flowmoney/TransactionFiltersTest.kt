package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class TransactionFiltersTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 20)

    @Test fun filtersByWeekCategoryAndWhereText() {
        val transactions = listOf(
            transaction(id = "match", date = today, merchant = "Blue Bottle", category = "Coffee"),
            transaction(id = "old", date = today.minusDays(8), merchant = "Blue Bottle", category = "Coffee"),
            transaction(id = "category", date = today, merchant = "Blue Bottle", category = "Food"),
            transaction(id = "where", date = today, merchant = "Corner Shop", category = "Coffee"),
        )

        val filtered = TransactionFilters.apply(
            transactions = transactions,
            filter = TransactionFilter(
                time = TransactionTimeFilter.Week,
                category = "Coffee",
                where = "blue",
            ),
            today = today,
            zoneId = utc,
        )

        assertThat(filtered.map { it.id }).containsExactly("match")
    }

    @Test fun whereTextMatchesCaseAndAccentsInMerchantOrNote() {
        val transactions = listOf(
            transaction(id = "merchant", date = today, merchant = "Café   Nero", category = "Coffee"),
            transaction(id = "note", date = today, merchant = "Shop", category = "Food", note = "Work lunch"),
        )

        assertThat(TransactionFilters.apply(transactions, TransactionFilter(where = "  CAFE NERO "), today, utc).map { it.id })
            .containsExactly("merchant")
        assertThat(TransactionFilters.apply(transactions, TransactionFilter(where = "work"), today, utc).map { it.id })
            .containsExactly("note")
    }

    private fun transaction(
        id: String,
        date: LocalDate,
        merchant: String,
        category: String,
        note: String = "",
    ): Transaction {
        return Transaction(
            id = id,
            occurredAtEpochMillis = date.atStartOfDay(utc).toInstant().toEpochMilli(),
            merchant = merchant,
            category = category,
            note = note,
            cents = -100,
        )
    }
}
