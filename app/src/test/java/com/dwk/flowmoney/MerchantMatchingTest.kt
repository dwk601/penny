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
    fun cosmeticDisplayIsSeparateAndRetainsDigitsLocationsAndCase() {
        assertThat(cosmeticMerchantDisplay("  ACME 123   North Location  "))
            .isEqualTo("ACME 123 North Location")
        assertThat(normalizedProviderMerchantKey("ACME 123 North Location"))
            .isEqualTo("acme 123 north location")
    }
}
