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
}
