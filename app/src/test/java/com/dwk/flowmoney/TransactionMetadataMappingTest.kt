package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionMetadataMappingTest {
    @Test
    fun entityMappingDisplaysOverrideAndRoundTripsProviderOwnership() {
        val entity =
            TransactionEntity(
                id = "simplefin:id",
                occurredAtEpochMillis = 1,
                merchant = "Provider Merchant 88",
                category = "Food",
                note = "user note",
                cents = -100,
                recurringInterval = RecurrenceInterval.Monthly.name,
                source = "simplefin",
                accountKey = "account",
                accountName = "Checking",
                reviewedAtEpochMillis = 2,
                providerDescription = "RAW DESCRIPTION 88",
                merchantOverride = "Corner Store",
            )

        val domain = entity.toTransaction()

        assertThat(domain.merchant).isEqualTo("Corner Store")
        assertThat(domain.providerMerchant).isEqualTo("Provider Merchant 88")
        assertThat(domain.toEntity()).isEqualTo(entity)
    }
}
