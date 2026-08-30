package com.dwk.flowmoney

import android.content.Context
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

internal const val SIMPLEFIN_SCHEDULE_PREFERENCES = "simplefin_schedule"

private const val PREFERRED_SYNC_HOUR = "preferred_sync_hour"
private const val PREFERRED_SYNC_MINUTE = "preferred_sync_minute"

internal fun readPreferredSyncTime(context: Context): LocalTime? {
    val prefs =
        context.applicationContext.getSharedPreferences(SIMPLEFIN_SCHEDULE_PREFERENCES, Context.MODE_PRIVATE)
    if (!prefs.contains(PREFERRED_SYNC_HOUR) || !prefs.contains(PREFERRED_SYNC_MINUTE)) return null
    return LocalTime.of(
        prefs.getInt(PREFERRED_SYNC_HOUR, 0),
        prefs.getInt(PREFERRED_SYNC_MINUTE, 0),
    )
}

internal fun writePreferredSyncTime(
    context: Context,
    time: LocalTime?,
) {
    val prefs =
        context.applicationContext.getSharedPreferences(SIMPLEFIN_SCHEDULE_PREFERENCES, Context.MODE_PRIVATE)
    if (time == null) {
        prefs
            .edit()
            .remove(PREFERRED_SYNC_HOUR)
            .remove(PREFERRED_SYNC_MINUTE)
            .apply()
    } else {
        prefs
            .edit()
            .putInt(PREFERRED_SYNC_HOUR, time.hour)
            .putInt(PREFERRED_SYNC_MINUTE, time.minute)
            .apply()
    }
}

internal fun initialSyncDelayMillis(
    preferred: LocalTime?,
    now: ZonedDateTime,
): Long {
    if (preferred == null) return 0L
    val today = now.toLocalDate().atTime(preferred).atZone(now.zone)
    val next = if (today.isBefore(now)) today.plusDays(1) else today
    return Duration.between(now, next).toMillis()
}
