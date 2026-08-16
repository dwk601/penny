package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test

class PennyWidgetProviderTest {
    @Test
    fun compactWidgetAmountKeepsSubThousandValuesExact() {
        assertThat(formatCompactWidgetUsd(0)).isEqualTo("\$0.00")
        assertThat(formatCompactWidgetUsd(12_345)).isEqualTo("\$123.45")
        assertThat(formatCompactWidgetUsd(99_999)).isEqualTo("\$999.99")
        assertThat(formatCompactWidgetUsd(-99_999)).isEqualTo("-\$999.99")
    }

    @Test
    fun compactWidgetAmountAbbreviatesMagnitudeWithDeterministicRoundingAndSign() {
        assertThat(formatCompactWidgetUsd(100_000)).isEqualTo("\$1K")
        assertThat(formatCompactWidgetUsd(123_456)).isEqualTo("\$1.2K")
        assertThat(formatCompactWidgetUsd(1_234_567)).isEqualTo("\$12K")
        assertThat(formatCompactWidgetUsd(124_999)).isEqualTo("\$1.2K")
        assertThat(formatCompactWidgetUsd(125_000)).isEqualTo("\$1.3K")
        assertThat(formatCompactWidgetUsd(123_456_789)).isEqualTo("\$1.2M")
        assertThat(formatCompactWidgetUsd(99_999_999)).isEqualTo("\$1M")
        assertThat(formatCompactWidgetUsd(-123_456)).isEqualTo("-\$1.2K")
        assertThat(formatCompactWidgetUsd(Long.MIN_VALUE)).isEqualTo("-\$92Q")
    }

    @Test
    fun widgetStatsIncludeUnreviewedNormalSpendingUnderOther() {
        val stats =
            calculateWidgetTransactionStats(
                listOf(
                    transaction(id = "local-food", category = "Food", cents = -4_000),
                    transaction(
                        id = "reviewed-food",
                        category = "Food",
                        cents = -2_000,
                        source = "simplefin",
                        reviewedAtEpochMillis = 2L,
                    ),
                    transaction(
                        id = "reviewed-travel",
                        category = "Travel",
                        cents = -1_000,
                        source = "simplefin",
                        reviewedAtEpochMillis = 3L,
                    ),
                    transaction(
                        id = "unreviewed-travel",
                        category = "Travel",
                        cents = -12_000,
                        source = "simplefin",
                    ),
                ),
            )

        assertThat(stats.metrics.spentCents).isEqualTo(19_000)
        assertThat(stats.metrics.transactionCount).isEqualTo(4)
        assertThat(stats.categoryTotals)
            .containsExactly(
                CategoryTotal(category = "Other", cents = 12_000),
                CategoryTotal(category = "Food", cents = 6_000),
                CategoryTotal(category = "Travel", cents = 1_000),
            ).inOrder()
        assertThat(stats.pendingReviewCount).isEqualTo(1)
    }

    @Test
    fun widgetStatsHaveNoPendingReviewForLocalAndReviewedSimpleFinTransactions() {
        val stats =
            calculateWidgetTransactionStats(
                listOf(
                    transaction(id = "local", category = "Food", cents = -1_000),
                    transaction(
                        id = "reviewed",
                        category = "Travel",
                        cents = -2_000,
                        source = "simplefin",
                        reviewedAtEpochMillis = 2L,
                    ),
                ),
            )

        assertThat(stats.pendingReviewCount).isEqualTo(0)
        assertThat(stats.categoryTotals.map { it.category }).containsExactly("Travel", "Food").inOrder()
    }

    @Test
    fun widgetStatsGroupEveryUnreviewedNormalExpenseUnderOther() {
        val stats =
            calculateWidgetTransactionStats(
                listOf(
                    transaction(
                        id = "unreviewed-food",
                        category = "Food",
                        cents = -5_000,
                        source = "simplefin",
                    ),
                    transaction(
                        id = "unreviewed-travel",
                        category = "Travel",
                        cents = -4_000,
                        source = "simplefin",
                    ),
                ),
            )

        assertThat(stats.metrics.spentCents).isEqualTo(9_000)
        assertThat(stats.categoryTotals)
            .containsExactly(CategoryTotal(category = "Other", cents = 9_000))
        assertThat(stats.pendingReviewCount).isEqualTo(2)
    }

    @Test
    fun widgetStatsExcludeEffectiveTransfersFromEveryTotalAndPendingCount() {
        val stats =
            calculateWidgetTransactionStats(
                listOf(
                    transaction(
                        id = "unreviewed-normal",
                        category = "Suggested",
                        cents = -500,
                        source = "simplefin",
                    ),
                    transaction(
                        id = "outgoing-transfer",
                        category = "Transfer",
                        cents = -900,
                        source = "simplefin",
                        flowKind = FlowKind.TRANSFER,
                    ),
                    transaction(
                        id = "incoming-transfer",
                        category = "Transfer",
                        cents = 1_000,
                        flowKind = FlowKind.TRANSFER,
                    ),
                    transaction(
                        id = "normal-overridden-transfer",
                        category = "Hidden",
                        cents = -800,
                        source = "simplefin",
                        flowKindOverride = FlowKind.TRANSFER,
                    ),
                    transaction(
                        id = "transfer-overridden-normal",
                        category = "Refund",
                        cents = 400,
                        flowKind = FlowKind.TRANSFER,
                        flowKindOverride = FlowKind.NORMAL,
                    ),
                ),
            )

        assertThat(stats.metrics).isEqualTo(
            DashboardMetrics(
                spentCents = 500,
                incomeCents = 400,
                transactionCount = 2,
                transferCents = 2_700,
            ),
        )
        assertThat(stats.categoryTotals)
            .containsExactly(CategoryTotal(category = "Other", cents = 500))
        assertThat(stats.pendingReviewCount).isEqualTo(1)
    }

    @Test
    fun broadcastUpdateAwaitsWorkBeforeFinishing() {
        val events = mutableListOf<String>()

        runWidgetBroadcastUpdate(
            update = {
                yield()
                events += "updated"
            },
            finish = { events += "finished" },
        )

        assertThat(events).containsExactly("updated", "finished").inOrder()
    }

    @Test
    fun broadcastUpdateFinishesExactlyOnceWhenUpdateThrowsException() {
        var finishes = 0

        runWidgetBroadcastUpdate(
            update = { throw IllegalStateException("RemoteViews failed") },
            finish = { finishes++ },
        )

        assertThat(finishes).isEqualTo(1)
    }

    @Test
    fun broadcastUpdateFinishesExactlyOnceWhenUpdateThrowsError() {
        var finishes = 0

        runWidgetBroadcastUpdate(
            update = { throw AssertionError("Binder failed") },
            finish = { finishes++ },
        )

        assertThat(finishes).isEqualTo(1)
    }

    @Test
    fun broadcastUpdateRethrowsCancellationAfterFinishingExactlyOnce() {
        val cancellation = CancellationException("broadcast cancelled")
        var finishes = 0

        val thrown =
            assertThrows(CancellationException::class.java) {
                runWidgetBroadcastUpdate(
                    update = { throw cancellation },
                    finish = { finishes++ },
                )
            }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(finishes).isEqualTo(1)
    }

    @Test
    fun runnableBoundarySwallowsCancellationAfterFinishingExactlyOnce() {
        var finishes = 0

        runWidgetBroadcastUpdateAtRunnableBoundary(
            update = { throw CancellationException("broadcast cancelled") },
            finish = { finishes++ },
        )

        assertThat(finishes).isEqualTo(1)
    }

    @Test
    fun runnableBoundaryContainsErrorAfterFinishingExactlyOnce() {
        var finishes = 0

        runWidgetBroadcastUpdateAtRunnableBoundary(
            update = { throw AssertionError("Binder failed") },
            finish = { finishes++ },
        )

        assertThat(finishes).isEqualTo(1)
    }

    @Test
    fun runnableBoundaryContainsFinishThrowableAfterExactlyOneAttempt() {
        val events = mutableListOf<String>()

        runWidgetBroadcastUpdateAtRunnableBoundary(
            update = { events += "updated" },
            finish = {
                events += "finish attempted"
                throw AssertionError("PendingResult.finish failed")
            },
        )

        assertThat(events).containsExactly("updated", "finish attempted").inOrder()
    }

    private fun transaction(
        id: String,
        category: String,
        cents: Int,
        source: String = "local",
        reviewedAtEpochMillis: Long? = null,
        flowKind: FlowKind = FlowKind.NORMAL,
        flowKindOverride: FlowKind? = null,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis = 1L,
            merchant = id,
            category = category,
            note = "",
            cents = cents,
            source = source,
            reviewedAtEpochMillis = reviewedAtEpochMillis,
            flowKind = flowKind,
            flowKindOverride = flowKindOverride,
        )
}
