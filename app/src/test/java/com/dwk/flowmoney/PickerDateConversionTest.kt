package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

class PickerDateConversionTest {
    @Test
    fun utcPickerDatePreservesCalendarDateFromNonUtcBoundary() {
        val zone = ZoneId.of("Pacific/Kiritimati")
        val localDateTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(0, 15))
        val sourceInstant = localDateTime.atZone(zone).toInstant()
        val intendedDate = sourceInstant.atZone(zone).toLocalDate()

        val pickerDate = Instant.ofEpochMilli(localDateAtUtcStartOfDay(intendedDate))
            .atZone(ZoneId.of("UTC"))
            .toLocalDate()

        assertThat(sourceInstant.atZone(ZoneId.of("UTC")).toLocalDate()).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(pickerDate).isEqualTo(intendedDate)
    }
}
