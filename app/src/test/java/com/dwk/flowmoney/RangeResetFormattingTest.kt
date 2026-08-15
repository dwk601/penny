package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class RangeResetFormattingTest {
    @Test
    fun humanReadableResetRangeHandlesSingleAndCrossMonthSelections() {
        assertThat(
            resetDaysRangeLabel(
                PennyLocalDateRange(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 16)),
            ),
        ).isEqualTo("March 15, 2026")
        assertThat(
            resetDaysRangeLabel(
                PennyLocalDateRange(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 3)),
            ),
        ).isEqualTo("March 30–April 2, 2026")
    }

    @Test
    fun affectedCountIncludesTransactionsAndDatedSimpleFinRecordsIncludingZero() {
        assertThat(resetDaysCountLabel(TransactionRangeCount(transactionCount = 2, tombstoneCount = 1)))
            .isEqualTo("3 items affected: 2 transactions + 1 deleted SimpleFIN record")
        assertThat(resetDaysCountLabel(TransactionRangeCount(transactionCount = 0, tombstoneCount = 0)))
            .isEqualTo("0 items affected: 0 transactions + 0 deleted SimpleFIN records")
    }
}
