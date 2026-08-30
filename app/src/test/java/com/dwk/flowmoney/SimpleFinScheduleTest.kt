package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The initial delay decides when the first automatic SimpleFIN sync fires after a re-anchor.
 * Getting it wrong either skips a whole day of syncing or fires immediately and burns one of
 * SimpleFIN's 24 daily requests, so every branch is pinned here against a fixed clock.
 */
class SimpleFinScheduleTest {
    @Test fun noPreferredTimeKeepsTheWorkerUnanchoredAndStartsImmediately() {
        assertThat(initialSyncDelayMillis(preferred = null, now = at(2026, 7, 10, 13, 45))).isEqualTo(0L)
    }

    @Test fun aPreferredTimeStillAheadTodayDelaysUntilLaterTheSameDay() {
        val delay = initialSyncDelayMillis(preferred = LocalTime.of(21, 30), now = at(2026, 7, 10, 13, 45))

        assertThat(delay).isEqualTo(Duration.ofHours(7).plusMinutes(45).toMillis())
    }

    @Test fun aPreferredTimeThatAlreadyPassedRollsOverToTomorrow() {
        val delay = initialSyncDelayMillis(preferred = LocalTime.of(6, 15), now = at(2026, 7, 10, 13, 45))

        assertThat(delay).isEqualTo(Duration.ofHours(16).plusMinutes(30).toMillis())
    }

    @Test fun aPreferredTimeExactlyNowFiresNowInsteadOfWaitingAFullDay() {
        val delay = initialSyncDelayMillis(preferred = LocalTime.of(13, 45), now = at(2026, 7, 10, 13, 45))

        assertThat(delay).isEqualTo(0L)
    }

    @Test fun oneSecondPastThePreferredTimeWaitsAlmostAFullDay() {
        val delay = initialSyncDelayMillis(preferred = LocalTime.of(13, 45), now = at(2026, 7, 10, 13, 45, 1))

        assertThat(delay).isEqualTo(Duration.ofDays(1).minusSeconds(1).toMillis())
    }

    @Test fun midnightIsAPreferenceLikeAnyOtherAndIsNeverTreatedAsUnset() {
        val delay = initialSyncDelayMillis(preferred = LocalTime.MIDNIGHT, now = at(2026, 7, 10, 23, 59))

        assertThat(delay).isEqualTo(Duration.ofMinutes(1).toMillis())
    }

    @Test fun delaysAreNeverNegativeAcrossAFullDayOfNowValues() {
        val preferred = LocalTime.of(9, 5)
        for (minute in 0 until 24 * 60) {
            val now = at(2026, 7, 10, minute / 60, minute % 60)

            val delay = initialSyncDelayMillis(preferred, now)

            assertThat(delay).isAtLeast(0L)
            assertThat(delay).isAtMost(Duration.ofDays(1).toMillis())
            assertThat(now.plusNanos(delay * 1_000_000).toLocalTime()).isEqualTo(preferred)
        }
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): ZonedDateTime =
        ZonedDateTime.of(
            LocalDateTime.of(year, month, day, hour, minute, second),
            ZoneId.of("America/New_York"),
        )
}
