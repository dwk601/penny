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
        assertThat(restored.flowKind).isEqualTo(FlowKind.NORMAL)
        assertThat(restored.flowKindOverride).isNull()
    }

    @Test fun truncatedEditorDraftShapeUsesSizeCheckedDefaults() {
        val restored = restoreEditorDraft(listOf("saved-id", 42L, "Merchant"))

        assertThat(restored.id).isEqualTo("saved-id")
        assertThat(restored.occurredAtEpochMillis).isEqualTo(42L)
        assertThat(restored.merchant).isEqualTo("Merchant")
        assertThat(restored.source).isEqualTo("local")
        assertThat(restored.accountKey).isNull()
    }

    @Test fun editorDraftRoundTripPreservesProviderTransactedAt() {
        val original =
            transaction("synced-transacted", LocalDate.of(2026, 7, 10), -500)
                .copy(
                    source = "simplefin",
                    providerMerchant = "Provider Cafe",
                    transactedAtEpochMillis = 1_766_059_200_000L,
                ).toEditorDraft()

        val saved = saveEditorDraft(original)
        val restored = restoreEditorDraft(saved)

        assertThat(saved).hasSize(21)
        assertThat(restored.transactedAtEpochMillis).isEqualTo(1_766_059_200_000L)
        assertThat(restored).isEqualTo(original)
        assertThat(restored.toTransaction().transactedAtEpochMillis).isEqualTo(1_766_059_200_000L)

        val absent = original.copy(transactedAtEpochMillis = null)
        val restoredAbsent = restoreEditorDraft(saveEditorDraft(absent))
        assertThat(restoredAbsent.transactedAtEpochMillis).isNull()
        assertThat(restoredAbsent).isEqualTo(absent)
    }

    @Test fun legacyTwentyElementEditorDraftRestoresWithNullTransactedAt() {
        val legacy =
            listOf<Any>(
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
                5678L,
                true,
                "RAW DESCRIPTION",
                true,
                "Override display",
                true,
                "Provider Merchant 17",
                FlowKind.TRANSFER.name,
                FlowKind.NORMAL.name,
            )

        val restored = restoreEditorDraft(legacy)

        assertThat(legacy).hasSize(20)
        assertThat(restored.transactedAtEpochMillis).isNull()
        assertThat(restored.id).isEqualTo("simplefin:id")
        assertThat(restored.reviewedAtEpochMillis).isEqualTo(5678L)
        assertThat(restored.providerDescription).isEqualTo("RAW DESCRIPTION")
        assertThat(restored.merchantOverride).isEqualTo("Override display")
        assertThat(restored.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(restored.flowKindOverride).isEqualTo(FlowKind.NORMAL)
        assertThat(restored.toTransaction().transactedAtEpochMillis).isNull()
    }

    @Test fun trueColdStartCompletesOnOverview() {
        val routed =
            completeColdStartNavigation(
                state = DashboardNavigationState(selectedTab = DashboardTab.Review),
                isLoading = false,
            )

        assertThat(routed.selectedTab).isEqualTo(DashboardTab.Overview)
        assertThat(routed.coldStartRoutingHandled).isTrue()
        assertThat(completeColdStartNavigation(routed, isLoading = false)).isEqualTo(routed)
    }

    @Test fun coldStartNeverOverridesRestoredUserOrExternalNavigation() {
        val protectedStates =
            listOf(
                DashboardNavigationState(selectedTab = DashboardTab.Insights, restoredNavigation = true),
                DashboardNavigationState(selectedTab = DashboardTab.Transactions, userSelectedBeforeLoading = true),
                DashboardNavigationState(selectedTab = DashboardTab.Review, externalRouteHandled = true),
            )

        protectedStates.forEach { state ->
            val routed = completeColdStartNavigation(state, isLoading = false)
            assertThat(routed.selectedTab).isEqualTo(state.selectedTab)
            assertThat(routed.coldStartRoutingHandled).isTrue()
        }
        assertThat(
            completeColdStartNavigation(DashboardNavigationState(), isLoading = true),
        ).isEqualTo(DashboardNavigationState())
    }

    @Test fun editorDraftRoundTripPreservesTransferClassificationAndExplicitOverride() {
        val original =
            transaction("transfer", LocalDate.of(2026, 7, 10), -500)
                .copy(
                    source = "simplefin",
                    providerMerchant = "Provider transfer",
                    flowKind = FlowKind.TRANSFER,
                    flowKindOverride = FlowKind.NORMAL,
                ).toEditorDraft()

        val restored = restoreEditorDraft(saveEditorDraft(original))
        val converted = restored.toTransaction()

        assertThat(restored.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(restored.flowKindOverride).isEqualTo(FlowKind.NORMAL)
        assertThat(restored.effectiveFlowKind).isEqualTo(FlowKind.NORMAL)
        assertThat(converted.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(converted.flowKindOverride).isEqualTo(FlowKind.NORMAL)
    }

    @Test fun explicitTransferCorrectionsEncodeTheSelectedState() {
        val providerTransfer =
            transaction("provider-transfer", LocalDate.of(2026, 7, 10), -500)
                .copy(
                    source = "simplefin",
                    providerMerchant = "Provider transfer",
                    flowKind = FlowKind.TRANSFER,
                ).toEditorDraft()
        val normal =
            transaction("normal", LocalDate.of(2026, 7, 10), -500)
                .toEditorDraft()

        val correctedNormal = providerTransfer.copy(flowKindOverride = FlowKind.NORMAL).toTransaction()
        val correctedTransfer = normal.copy(flowKindOverride = FlowKind.TRANSFER).toTransaction()

        assertThat(correctedNormal.flowKind).isEqualTo(FlowKind.TRANSFER)
        assertThat(correctedNormal.flowKindOverride).isEqualTo(FlowKind.NORMAL)
        assertThat(correctedTransfer.flowKind).isEqualTo(FlowKind.NORMAL)
        assertThat(correctedTransfer.flowKindOverride).isEqualTo(FlowKind.TRANSFER)
    }

    @Test fun editorSaveMarksOnlyExistingSimpleFinDraftReviewed() {
        val existingSimpleFin =
            transaction("synced", LocalDate.of(2026, 7, 10), -500)
                .copy(source = "simplefin", providerMerchant = "Provider")
                .toEditorDraft()
        val existingLocal = transaction("local", LocalDate.of(2026, 7, 10), -500).toEditorDraft()

        val reviewed = existingSimpleFin.prepareForEditorSave("synced", nowEpochMillis = 1234L)
        val local = existingLocal.prepareForEditorSave("local", nowEpochMillis = 1234L)
        val newLocal = newEditorDraft().prepareForEditorSave("new-local", nowEpochMillis = 1234L)

        assertThat(reviewed.reviewedAtEpochMillis).isEqualTo(1234L)
        assertThat(local.reviewedAtEpochMillis).isNull()
        assertThat(newLocal.reviewedAtEpochMillis).isNull()
    }

    @Test fun syncHealthUsesRepositoryEligibilityAndNeverOffersCooldownBypass() {
        val now = 1_800_000_000_000L
        val coolingProfile =
            SimpleFinProfileEntity(
                connectionId = "connected",
                lastSuccessfulSyncAtEpochMillis = now,
                lastSyncAttemptAtEpochMillis = now,
                automaticSyncsPerDay = 4,
            )
        val cooling = syncHealthUiState(SimpleFinUiState(profile = coolingProfile), operation = null, nowEpochMillis = now)

        assertThat(cooling.kind).isEqualTo(SyncHealthKind.CoolingDown)
        assertThat(cooling.manualSync).isFalse()
        assertThat(cooling.stateDescription).contains("cooling down until")
        assertThat(simpleFinNextEligibleSyncAt(coolingProfile))
            .isEqualTo(now + automaticSyncIntervalMillis(4))

        val eligible =
            syncHealthUiState(
                SimpleFinUiState(
                    profile =
                        coolingProfile.copy(
                            lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(4),
                            lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(4),
                        ),
                ),
                operation = null,
                nowEpochMillis = now,
            )
        assertThat(eligible.manualSync).isTrue()
        assertThat(eligible.kind).isEqualTo(SyncHealthKind.Connected)
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
