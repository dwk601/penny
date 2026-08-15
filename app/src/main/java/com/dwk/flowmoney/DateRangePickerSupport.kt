package com.dwk.flowmoney

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal const val SIMPLEFIN_RESYNC_PICKER_DAYS = 45

/** A local-calendar range whose start is included and whose end is excluded. */
data class PennyLocalDateRange(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
) {
    init {
        require(startInclusive < endExclusive) { "A date range must contain at least one local date" }
    }

    val lastInclusive: LocalDate
        get() = endExclusive.minusDays(1)

    operator fun contains(date: LocalDate): Boolean = date >= startInclusive && date < endExclusive

    fun contains(range: PennyLocalDateRange): Boolean = range.startInclusive >= startInclusive && range.endExclusive <= endExclusive
}

/** Instant bounds produced from the same local time zone at both calendar boundaries. */
internal data class PennyInstantRange(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(endExclusive >= startInclusive) { "Instant range end must not precede its start" }
    }
}

internal fun simpleFinResyncPickerRange(today: LocalDate): PennyLocalDateRange =
    PennyLocalDateRange(
        startInclusive = today.minusDays((SIMPLEFIN_RESYNC_PICKER_DAYS - 1).toLong()),
        endExclusive = today.plusDays(1),
    )

internal fun simpleFinResyncPickerRange(clock: Clock): PennyLocalDateRange = simpleFinResyncPickerRange(LocalDate.now(clock))

internal fun simpleFinResyncPickerRange(
    clock: Clock,
    zoneId: ZoneId,
): PennyLocalDateRange = simpleFinResyncPickerRange(LocalDate.now(clock.withZone(zoneId)))

internal fun isSimpleFinResetRangeCurrent(
    range: PennyLocalDateRange,
    clock: Clock,
    zoneId: ZoneId,
): Boolean = simpleFinResyncPickerRange(clock, zoneId).contains(range)

/** Material date pickers encode calendar dates as UTC-midnight millis, not local instants. */
internal fun localDateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickerMillisToLocalDate(utcTimeMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun isPickerDateSelectable(
    utcTimeMillis: Long,
    allowedRange: PennyLocalDateRange,
): Boolean = pickerMillisToLocalDate(utcTimeMillis) in allowedRange

/** Converts Material's inclusive end date to Penny's half-open local-date contract. */
internal fun pickerSelectionToLocalDateRange(
    selectedStartDateMillis: Long?,
    selectedEndDateMillis: Long?,
    allowedRange: PennyLocalDateRange,
): PennyLocalDateRange? {
    if (selectedStartDateMillis == null || selectedEndDateMillis == null) return null
    val startInclusive = pickerMillisToLocalDate(selectedStartDateMillis)
    val selectedEndInclusive = pickerMillisToLocalDate(selectedEndDateMillis)
    if (selectedEndInclusive < startInclusive) return null
    val endExclusive =
        try {
            selectedEndInclusive.plusDays(1)
        } catch (_: DateTimeException) {
            return null
        }
    return PennyLocalDateRange(startInclusive, endExclusive).takeIf(allowedRange::contains)
}

/** Resolves both half-open date bounds with calendar start-of-day rules, including DST gaps/overlaps. */
internal fun PennyLocalDateRange.toInstantRange(zoneId: ZoneId): PennyInstantRange =
    PennyInstantRange(
        startInclusive = startInclusive.atStartOfDay(zoneId).toInstant(),
        endExclusive = endExclusive.atStartOfDay(zoneId).toInstant(),
    )
