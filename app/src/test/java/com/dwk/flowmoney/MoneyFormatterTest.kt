package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyFormatterTest {
    @Test fun parsesDollarTextToCents() {
        assertThat(MoneyFormatter.parseAmountToCents("12.34")).isEqualTo(1234)
        assertThat(MoneyFormatter.parseAmountToCents("$1,234.50")).isEqualTo(123450)
        assertThat(MoneyFormatter.parseAmountToCents("0.9")).isEqualTo(90)
    }

    @Test fun formatsCentsAsUsd() {
        assertThat(MoneyFormatter.formatUsd(123450)).isEqualTo("$1,234.50")
        assertThat(MoneyFormatter.formatUsd(-500)).isEqualTo("-$5.00")
    }

    @Test fun formatsLongMinimumWithoutOverflow() {
        assertThat(MoneyFormatter.formatUsd(Long.MIN_VALUE)).isEqualTo("-$92,233,720,368,547,758.08")
    }
}
