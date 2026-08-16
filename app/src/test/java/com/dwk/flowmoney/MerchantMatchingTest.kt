package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantMatchingTest {
    @Test
    fun ruleKeyChangesOnlyCaseAndWhitespace() {
        assertThat(normalizedProviderMerchantKey("  Store\t#1042   Downtown  5th Ave "))
            .isEqualTo("store #1042 downtown 5th ave")
        assertThat(normalizedProviderMerchantKey("Store #1043 Downtown 5th Ave"))
            .isNotEqualTo(normalizedProviderMerchantKey("Store #1042 Downtown 5th Ave"))
    }

    @Test
    fun canonicalRuleKeyRemovesOnlyExplicitProviderReferences() {
        assertThat(
            canonicalProviderMerchantKey(
                "Store #1042 Downtown [Provider Ref: AB12CD34] ORDER ID: ZX98YU76",
            ),
        ).isEqualTo("store #1042 downtown")
        assertThat(canonicalProviderMerchantKey("Store #1042 Downtown*AB12CD34"))
            .isEqualTo("store #1042 downtown")
        assertThat(canonicalProviderMerchantKey("Store #1043 Downtown*ZX98YU76"))
            .isEqualTo("store #1043 downtown")

        assertThat(canonicalProviderMerchantKey("SQ *Coffee Bar 123 North"))
            .isEqualTo(normalizedProviderMerchantKey("SQ *Coffee Bar 123 North"))
        assertThat(canonicalProviderMerchantKey("Store *Downtown42 Location 7"))
            .isEqualTo(normalizedProviderMerchantKey("Store *Downtown42 Location 7"))
        assertThat(canonicalProviderMerchantKey("Order 66 Store #12"))
            .isEqualTo("order 66 store #12")
    }

    @Test
    fun canonicalRuleKeyRejectsBlankAndTooShortFallbacks() {
        assertThat(canonicalProviderMerchantKey("*AB12CD34")).isNull()
        assertThat(canonicalProviderMerchantKey("Go *AB12CD34")).isNull()
        assertThat(canonicalProviderMerchantKey("A [REF: ZX98YU76]")).isNull()
    }

    @Test
    fun cosmeticDisplayIsSeparateAndRetainsDigitsLocationsAndCase() {
        assertThat(cosmeticMerchantDisplay("  ACME 123   North Location  "))
            .isEqualTo("ACME 123 North Location")
        assertThat(normalizedProviderMerchantKey("ACME 123 North Location"))
            .isEqualTo("acme 123 north location")
    }
}
