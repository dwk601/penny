package com.dwk.flowmoney

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTimePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PennyOverviewTopAppBar(
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    onData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MediumFlexibleTopAppBar(
        title = title,
        subtitle = subtitle,
        actions = {
            TextButton(
                onClick = onData,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text("Data") }
        },
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scrollBehavior = null,
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
        modifier = modifier
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
