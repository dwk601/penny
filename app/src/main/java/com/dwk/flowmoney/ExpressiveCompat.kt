package com.dwk.flowmoney

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RichTimePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PennyOverviewTopAppBar(
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    MediumFlexibleTopAppBar(
        title = subtitle,
        navigationIcon = {
            Box(
                modifier = Modifier.padding(start = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                title()
            }
        },
        actions = {
            TextButton(
                onClick = onData,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text("Data") }
        },
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
internal fun PennyBinaryChoice(
    firstLabel: String,
    firstSelected: Boolean,
    onFirstClick: () -> Unit,
    firstModifier: Modifier,
    secondLabel: String,
    secondSelected: Boolean,
    onSecondClick: () -> Unit,
    secondModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier.selectableGroup(),
        expandedRatio = 0f,
    ) {
        val firstItemModifier = firstModifier.weight(1f)
        val secondItemModifier = secondModifier.weight(1f)
        customItem(
            buttonGroupContent = {
                PennyBinaryChoiceItem(
                    label = firstLabel,
                    selected = firstSelected,
                    onClick = onFirstClick,
                    modifier = firstItemModifier,
                )
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                PennyBinaryChoiceItem(
                    label = secondLabel,
                    selected = secondSelected,
                    onClick = onSecondClick,
                    modifier = secondItemModifier,
                )
            },
            menuContent = {},
        )
    }
}

@Composable
private fun PennyBinaryChoiceItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PennyRichTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickerState = rememberTimePickerState(initialHour, initialMinute, is24Hour)
    RichTimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = modifier,
    ) {
        Text("Select time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        TimePicker(state = pickerState, shapes = TimePickerDefaults.shapes())
    }
}

/**
 * Selects a bounded SimpleFIN resync range using Penny's half-open local-date contract.
 * Cancel, back, and outside dismissal call [onDismiss]; confirmation calls only [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PennySimpleFinDateRangePickerDialog(
    initialRange: PennyLocalDateRange? = null,
    onDismiss: () -> Unit,
    onConfirm: (PennyLocalDateRange) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val selectableRange = simpleFinResyncPickerRange(clock)
    key(selectableRange, initialRange) {
        val boundedInitialRange = initialRange?.takeIf(selectableRange::contains)
        val selectableDates = remember(selectableRange) { SimpleFinResyncSelectableDates(selectableRange) }
        val pickerState =
            rememberDateRangePickerState(
                initialSelectedStartDateMillis = boundedInitialRange?.startInclusive?.let(::localDateToPickerMillis),
                initialSelectedEndDateMillis = boundedInitialRange?.lastInclusive?.let(::localDateToPickerMillis),
                initialDisplayedMonthMillis =
                    localDateToPickerMillis(boundedInitialRange?.startInclusive ?: selectableRange.lastInclusive),
                yearRange = selectableRange.startInclusive.year..selectableRange.lastInclusive.year,
                selectableDates = selectableDates,
            )
        val selectedRange =
            pickerSelectionToLocalDateRange(
                pickerState.selectedStartDateMillis,
                pickerState.selectedEndDateMillis,
                selectableRange,
            )

        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerSelectionToLocalDateRange(
                            pickerState.selectedStartDateMillis,
                            pickerState.selectedEndDateMillis,
                            selectableRange,
                        )?.let(onConfirm)
                    },
                    enabled = selectedRange != null,
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            modifier = modifier,
        ) {
            DateRangePicker(
                state = pickerState,
                modifier = Modifier.weight(1f),
                title = {
                    Text(
                        text = "Select dates to resync",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                showModeToggle = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class SimpleFinResyncSelectableDates(
    private val selectableRange: PennyLocalDateRange,
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = isPickerDateSelectable(utcTimeMillis, selectableRange)

    override fun isSelectableYear(year: Int): Boolean = year in selectableRange.startInclusive.year..selectableRange.lastInclusive.year
}
