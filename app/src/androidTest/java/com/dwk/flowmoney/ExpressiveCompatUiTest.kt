package com.dwk.flowmoney

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpressiveCompatUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun overviewTopAppBarKeepsCopyAndDataActionAtCompactLargeFont() {
        var dataClicks = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(320.dp, 360.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.5f)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        PennyOverviewTopAppBar(
                            title = { Text("Penny") },
                            subtitle = { Text("Overview") },
                            onData = { dataClicks++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Penny").assertIsDisplayed()
        composeRule.onNodeWithText("Overview").assertIsDisplayed()
        composeRule.onNodeWithText("Data").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, dataClicks) }
    }

    @Test
    fun appLabelAndDataShareTheSameCompactTopBarRow() {
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyTopAppBar(
                    selectedTab = DashboardTab.Overview,
                    onData = {},
                )
            }
        }

        val pennyBounds = composeRule.onNodeWithText("Penny").fetchSemanticsNode().boundsInRoot
        val dataBounds = composeRule.onNodeWithText("Data").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Penny and Data should share one row: ${pennyBounds.center.y} vs ${dataBounds.center.y}",
            kotlin.math.abs(pennyBounds.center.y - dataBounds.center.y) <= 8f,
        )
        composeRule.onNodeWithText("Overview").assertIsDisplayed()
    }

    @Test
    fun binaryChoicePreservesRadioSemanticsTargetsAndControlledCallbacks() {
        var firstClicks = 0
        var secondClicks = 0
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                PennyBinaryChoice(
                    firstLabel = "Week",
                    firstSelected = false,
                    onFirstClick = { firstClicks++ },
                    firstModifier = Modifier.testTag("first_choice"),
                    secondLabel = "Month",
                    secondSelected = true,
                    onSecondClick = { secondClicks++ },
                    secondModifier = Modifier.testTag("second_choice"),
                    modifier = Modifier.fillMaxWidth().testTag("choice_group"),
                )
            }
        }

        assertTrue(
            composeRule
                .onNodeWithTag("choice_group")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.SelectableGroup),
        )
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val unselected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        composeRule.onNodeWithTag("first_choice").assert(radioRole).assert(unselected)
        composeRule.onNodeWithTag("second_choice").assert(radioRole).assert(selected)
        listOf("first_choice", "second_choice").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag was ${bounds.width}x${bounds.height}", bounds.width / composeRule.density.density >= 48f)
            assertTrue("$tag was ${bounds.width}x${bounds.height}", bounds.height / composeRule.density.density >= 48f)
        }

        composeRule.onNodeWithTag("second_choice").performClick().assert(selected)
        composeRule.onNodeWithTag("first_choice").performClick().assert(unselected)
        composeRule.runOnIdle {
            assertEquals(1, secondClicks)
            assertEquals(1, firstClicks)
        }
    }

    @Test
    fun richTimePickerOkConfirmsExactInitial24HourTime() {
        var confirmed: Pair<Int, Int>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                PennyRichTimePickerDialog(
                    initialHour = 23,
                    initialMinute = 47,
                    is24Hour = true,
                    onDismiss = {},
                    onConfirm = { hour, minute -> confirmed = hour to minute },
                )
            }
        }

        composeRule.onNodeWithText("Select time").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals(23 to 47, confirmed) }
    }

    @Test
    fun richTimePickerCancelDismissesWithoutConfirming() {
        var dismisses = 0
        var confirms = 0
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                PennyRichTimePickerDialog(
                    initialHour = 8,
                    initialMinute = 5,
                    is24Hour = true,
                    onDismiss = { dismisses++ },
                    onConfirm = { _, _ -> confirms++ },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(1, dismisses)
            assertEquals(0, confirms)
        }
    }

    @Test
    fun richTimePickerDismissRequestRemovesParentHostedDialogWithoutConfirming() {
        var dismisses = 0
        var confirms = 0
        composeRule.setContent {
            var show by remember { mutableStateOf(true) }
            FlowMoneyTheme(dynamicColor = false) {
                if (show) {
                    PennyRichTimePickerDialog(
                        initialHour = 8,
                        initialMinute = 5,
                        is24Hour = true,
                        onDismiss = {
                            dismisses++
                            show = false
                        },
                        onConfirm = { _, _ -> confirms++ },
                        modifier = Modifier.testTag("time_picker_dialog"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Select time").assertIsDisplayed()
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("time_picker_dialog", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Select time").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, dismisses)
            assertEquals(0, confirms)
        }
    }

    @Test
    fun simpleFinDateRangePickerConfirmsHalfOpenInitialRange() {
        val today = LocalDate.of(2026, 3, 15)
        val initialRange = PennyLocalDateRange(LocalDate.of(2026, 3, 10), today.plusDays(1))
        var confirmed: PennyLocalDateRange? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                PennySimpleFinDateRangePickerDialog(
                    initialRange = initialRange,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                    clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC),
                )
            }
        }

        val title = composeRule.onNodeWithText("Select dates to resync").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(title.config.contains(SemanticsProperties.Heading))
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals(initialRange, confirmed) }
    }

    @Test
    fun simpleFinDateRangePickerRejectsUnboundedInitialRangeAndCancelsCleanly() {
        val today = LocalDate.of(2026, 3, 15)
        var dismisses = 0
        var confirms = 0
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                PennySimpleFinDateRangePickerDialog(
                    initialRange = PennyLocalDateRange(today.minusDays(45), today.plusDays(1)),
                    onDismiss = { dismisses++ },
                    onConfirm = { confirms++ },
                    clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC),
                )
            }
        }

        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(1, dismisses)
            assertEquals(0, confirms)
        }
    }

    @Test
    fun binaryChoiceFitsCompactLargeFontAndUpdatesInternalSelection() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(320.dp, 360.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(1.5f)) {
                    var expenseSelected by remember { mutableStateOf(true) }
                    FlowMoneyTheme(dynamicColor = false) {
                        PennyBinaryChoice(
                            firstLabel = "Expense",
                            firstSelected = expenseSelected,
                            onFirstClick = { expenseSelected = true },
                            firstModifier = Modifier.testTag("expense_choice"),
                            secondLabel = "Income",
                            secondSelected = !expenseSelected,
                            onSecondClick = { expenseSelected = false },
                            secondModifier = Modifier.testTag("income_choice"),
                            modifier = Modifier.fillMaxWidth().testTag("compact_choice_group"),
                        )
                    }
                }
            }
        }

        val group =
            composeRule
                .onNodeWithTag("compact_choice_group")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val expense =
            composeRule
                .onNodeWithTag("expense_choice")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val income =
            composeRule
                .onNodeWithTag("income_choice")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val boundsMessage = "group=$group expense=$expense income=$income"
        listOf(expense, income).forEach { bounds ->
            assertTrue(boundsMessage, bounds.width / composeRule.density.density >= 48f)
            assertTrue(boundsMessage, bounds.height / composeRule.density.density >= 48f)
            assertTrue(boundsMessage, bounds.left >= group.left && bounds.top >= group.top)
            assertTrue(boundsMessage, bounds.right <= group.right && bounds.bottom <= group.bottom)
        }
        assertTrue(boundsMessage, expense.right <= income.left || income.right <= expense.left)

        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val unselected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        composeRule.onNodeWithTag("income_choice").performClick().assert(selected)
        composeRule
            .onNodeWithTag("expense_choice")
            .assert(unselected)
            .performClick()
            .assert(selected)
        composeRule.onNodeWithTag("income_choice").assert(unselected)
    }
}
