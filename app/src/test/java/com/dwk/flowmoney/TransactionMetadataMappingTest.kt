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
                flowKind = FlowKind.TRANSFER,
                flowKindOverride = FlowKind.NORMAL,
            )

        val domain = entity.toTransaction()

        assertThat(domain.merchant).isEqualTo("Corner Store")
        assertThat(domain.providerMerchant).isEqualTo("Provider Merchant 88")
        assertThat(domain.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(domain.effectiveFlowKind).isEqualTo(FlowKind.NORMAL)
        assertThat(domain.toEntity()).isEqualTo(entity)
    }

    @Test
    fun providerTransactedAtSurvivesEntityToDomainAndBack() {
        val entity =
            TransactionEntity(
                id = "simplefin:transacted",
                occurredAtEpochMillis = 1_766_145_600_000L,
                merchant = "Provider Merchant 88",
                category = "Food",
                note = "",
                cents = -100,
                source = "simplefin",
                transactedAtEpochMillis = 1_766_059_200_000L,
            )

        val domain = entity.toTransaction()

        assertThat(domain.transactedAtEpochMillis).isEqualTo(1_766_059_200_000L)
        assertThat(domain.occurredAtEpochMillis).isEqualTo(1_766_145_600_000L)
        assertThat(domain.toEntity()).isEqualTo(entity)

        val absent = entity.copy(transactedAtEpochMillis = null)
        assertThat(absent.toTransaction().transactedAtEpochMillis).isNull()
        assertThat(absent.toTransaction().toEntity()).isEqualTo(absent)
    }
}
