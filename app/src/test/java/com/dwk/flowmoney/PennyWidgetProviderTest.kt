package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test

class PennyWidgetProviderTest {
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
}
