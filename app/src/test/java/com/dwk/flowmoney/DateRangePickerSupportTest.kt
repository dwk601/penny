package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DateRangePickerSupportTest {
    @Test
    fun simpleFinPickerAllowsExactlyTodayAndPreceding44CalendarDays() {
        val today = LocalDate.of(2026, 3, 15)
        val allowedRange = simpleFinResyncPickerRange(today)

        assertThat(allowedRange.startInclusive).isEqualTo(LocalDate.of(2026, 1, 30))
        assertThat(allowedRange.lastInclusive).isEqualTo(today)
        assertThat(allowedRange.endExclusive).isEqualTo(LocalDate.of(2026, 3, 16))
        assertThat(allowedRange.startInclusive.datesUntil(allowedRange.endExclusive).count()).isEqualTo(45L)
        assertThat(isPickerDateSelectable(localDateToPickerMillis(allowedRange.startInclusive.minusDays(1)), allowedRange)).isFalse()
        assertThat(isPickerDateSelectable(localDateToPickerMillis(allowedRange.startInclusive), allowedRange)).isTrue()
        assertThat(isPickerDateSelectable(localDateToPickerMillis(today), allowedRange)).isTrue()
        assertThat(isPickerDateSelectable(localDateToPickerMillis(today.plusDays(1)), allowedRange)).isFalse()
    }

    @Test
    fun pickerSelectionProducesOnlyValidBoundedHalfOpenRanges() {
        val today = LocalDate.of(2026, 3, 15)
        val allowedRange = simpleFinResyncPickerRange(today)

        assertThat(
            pickerSelectionToLocalDateRange(
                localDateToPickerMillis(LocalDate.of(2026, 3, 10)),
                localDateToPickerMillis(today),
                allowedRange,
            ),
        ).isEqualTo(PennyLocalDateRange(LocalDate.of(2026, 3, 10), today.plusDays(1)))
        assertThat(pickerSelectionToLocalDateRange(null, localDateToPickerMillis(today), allowedRange)).isNull()
        assertThat(
            pickerSelectionToLocalDateRange(
                localDateToPickerMillis(today),
                localDateToPickerMillis(today.minusDays(1)),
                allowedRange,
            ),
        ).isNull()
        assertThat(
            pickerSelectionToLocalDateRange(
                localDateToPickerMillis(allowedRange.startInclusive.minusDays(1)),
                localDateToPickerMillis(today),
                allowedRange,
            ),
        ).isNull()
        assertThat(
            pickerSelectionToLocalDateRange(
                localDateToPickerMillis(today),
                localDateToPickerMillis(today.plusDays(1)),
                allowedRange,
            ),
        ).isNull()
    }

    @Test
    fun pickerWindowUsesClockZoneAcrossADstDateBoundary() {
        val instant = Instant.parse("2024-03-10T07:30:00Z")
        val newYorkRange = simpleFinResyncPickerRange(Clock.fixed(instant, ZoneId.of("America/New_York")))
        val losAngelesRange = simpleFinResyncPickerRange(Clock.fixed(instant, ZoneId.of("America/Los_Angeles")))

        assertThat(newYorkRange.lastInclusive).isEqualTo(LocalDate.of(2024, 3, 10))
        assertThat(losAngelesRange.lastInclusive).isEqualTo(LocalDate.of(2024, 3, 9))
        assertThat(pickerMillisToLocalDate(localDateToPickerMillis(newYorkRange.startInclusive)))
            .isEqualTo(newYorkRange.startInclusive)
        assertThat(pickerMillisToLocalDate(localDateToPickerMillis(newYorkRange.lastInclusive)))
            .isEqualTo(newYorkRange.lastInclusive)
    }

    @Test
    fun localDateInstantBoundsUseDstSafeCalendarMidnights() {
        val newYork = ZoneId.of("America/New_York")
        val springForward = PennyLocalDateRange(LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 11)).toInstantRange(newYork)
        val fallBack = PennyLocalDateRange(LocalDate.of(2024, 11, 3), LocalDate.of(2024, 11, 4)).toInstantRange(newYork)

        assertThat(springForward.startInclusive).isEqualTo(Instant.parse("2024-03-10T05:00:00Z"))
        assertThat(springForward.endExclusive).isEqualTo(Instant.parse("2024-03-11T04:00:00Z"))
        assertThat(Duration.between(springForward.startInclusive, springForward.endExclusive)).isEqualTo(Duration.ofHours(23))
        assertThat(fallBack.startInclusive).isEqualTo(Instant.parse("2024-11-03T04:00:00Z"))
        assertThat(fallBack.endExclusive).isEqualTo(Instant.parse("2024-11-04T05:00:00Z"))
        assertThat(Duration.between(fallBack.startInclusive, fallBack.endExclusive)).isEqualTo(Duration.ofHours(25))
    }
}
