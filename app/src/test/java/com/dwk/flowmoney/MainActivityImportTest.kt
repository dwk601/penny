package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class MainActivityImportTest {
    @Test fun advertisedLengthProbeFailureFallsBackToUnknownLength() {
        assertThat(csvAdvertisedLength { error("provider probe failed") }).isEqualTo(-1L)
    }

    @Test fun widgetRefreshFailureIsBestEffort() =
        runTest {
            bestEffortWidgetRefresh { throw IllegalStateException("widget service unavailable") }
        }

    @Test fun widgetRefreshCancellationIsRethrown() =
        runTest {
            val expected = CancellationException("cancel refresh")
            val actual =
                try {
                    bestEffortWidgetRefresh { throw expected }
                    null
                } catch (failure: CancellationException) {
                    failure
                }

            assertThat(actual).isSameInstanceAs(expected)
        }

    @Test fun widgetQuickAddSeedsOnlyTheLearnedExpenseCategory() {
        val history =
            TransactionSuggestions.history(
                listOf(
                    transaction("travel-new", LocalDate.of(2026, 7, 10), -2_500, "Travel"),
                    transaction("travel-old", LocalDate.of(2026, 7, 9), -1_500, "Travel"),
                    transaction("food", LocalDate.of(2026, 7, 8), -900, "Food"),
                    transaction("salary", LocalDate.of(2026, 7, 7), 500_000, "Salary"),
                ),
            )

        val draft = widgetQuickAddDraft(history)

        assertThat(draft.category).isEqualTo("Travel")
        assertThat(draft.isExpense).isTrue()
        assertThat(draft.merchant).isEmpty()
        assertThat(draft.amount).isEmpty()
        assertThat(draft.note).isEmpty()
        assertThat(draft.recurringIntervalName).isEmpty()
        assertThat(draft.id).isNull()
    }

    @Test fun olderEditorDraftShapeRestoresWithSafeSyncDefaults() {
        val restored =
            restoreEditorDraft(
                listOf(
                    "simplefin:id",
                    1234L,
                    "Provider Merchant 17",
                    "12.34",
                    true,
                    "Other",
                    "note",
                    "Monthly",
                    "simplefin",
                    "account-key",
                    "Checking",
                ),
            )

        assertThat(restored.id).isEqualTo("simplefin:id")
        assertThat(restored.providerMerchant).isEqualTo("Provider Merchant 17")
        assertThat(restored.reviewedAtEpochMillis).isNull()
        assertThat(restored.providerDescription).isNull()
        assertThat(restored.merchantOverride).isNull()
    }

    @Test fun truncatedEditorDraftShapeUsesSizeCheckedDefaults() {
        val restored = restoreEditorDraft(listOf("saved-id", 42L, "Merchant"))

        assertThat(restored.id).isEqualTo("saved-id")
        assertThat(restored.occurredAtEpochMillis).isEqualTo(42L)
        assertThat(restored.merchant).isEqualTo("Merchant")
        assertThat(restored.source).isEqualTo("local")
        assertThat(restored.accountKey).isNull()
    }

    @Test fun transactionDayGroupsKeepSeparateSpentAndReceivedTotals() {
        val recentDate = LocalDate.of(2026, 7, 10)
        val earlierDate = LocalDate.of(2026, 7, 9)
        val groups =
            transactionDayGroups(
                listOf(
                    transaction("expense", recentDate, -1_250),
                    transaction("income", recentDate, 5_000),
                    transaction("earlier", earlierDate, -300),
                ),
            )

        assertThat(groups.map { it.date }).containsExactly(recentDate, earlierDate).inOrder()
        assertThat(groups.first().transactions.map { it.id }).containsExactly("expense", "income").inOrder()
        assertThat(groups.first().spentCents).isEqualTo(1_250L)
        assertThat(groups.first().receivedCents).isEqualTo(5_000L)
        assertThat(groups.last().spentCents).isEqualTo(300L)
        assertThat(groups.last().receivedCents).isEqualTo(0L)
    }

    private fun transaction(
        id: String,
        date: LocalDate,
        cents: Int,
        category: String = "Other",
    ) = Transaction(
        id = id,
        occurredAtEpochMillis =
            LocalDateTime
                .of(date, LocalTime.NOON)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        merchant = "Merchant",
        category = category,
        note = "",
        cents = cents,
    )
}
