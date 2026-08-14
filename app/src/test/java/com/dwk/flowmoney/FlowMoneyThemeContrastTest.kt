package com.dwk.flowmoney

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class FlowMoneyThemeContrastTest {
    @Test fun staticLightSchemeMeetsFinanceContrast() {
        assertFeasibleAndDistinct(flowMoneyStaticColorScheme(darkTheme = false))
    }

    @Test fun staticDarkSchemeMeetsFinanceContrast() {
        assertFeasibleAndDistinct(flowMoneyStaticColorScheme(darkTheme = true))
    }

    @Test fun midLuminanceBelowHalfMeetsFinanceContrast() {
        val scheme = schemeWithFinanceBackgrounds(listOf(neutralAtLuminance(0.49f)))

        assertFeasibleAndDistinct(scheme)
    }

    @Test fun midLuminanceAboveHalfMeetsFinanceContrast() {
        val scheme = schemeWithFinanceBackgrounds(listOf(neutralAtLuminance(0.51f)))

        assertFeasibleAndDistinct(scheme)
    }

    @Test fun incompatibleExtremeSpanningSchemeReturnsDeterministicBestEffort() {
        val middle = neutralAtLuminance(0.18f)
        val scheme =
            schemeWithFinanceBackgrounds(
                backgrounds = listOf(Color.Black, middle, Color.White),
                onSurface = middle,
            )

        val first = scheme.resolveFinanceColors()
        repeat(5) {
            assertThat(scheme.resolveFinanceColors()).isEqualTo(first)
        }

        val backgrounds = scheme.financeTextBackgrounds()
        val incomeMinimum = backgrounds.minOf { financeContrastRatio(first.income, it) }
        val expenseMinimum = backgrounds.minOf { financeContrastRatio(first.expense, it) }
        val middleLuminance = middle.luminance()
        val continuousBest =
            max(
                sqrt((middleLuminance + 0.05f) / 0.05f),
                sqrt(1.05f / (middleLuminance + 0.05f)),
            )

        assertThat(incomeMinimum.isFinite()).isTrue()
        assertThat(expenseMinimum.isFinite()).isTrue()
        assertThat(incomeMinimum).isLessThan(4.5f)
        assertThat(expenseMinimum).isLessThan(4.5f)
        assertThat(incomeMinimum).isWithin(0.01f).of(continuousBest)
        assertThat(expenseMinimum).isWithin(0.01f).of(continuousBest)
        assertThat(first.income).isEqualTo(first.expense)
    }

    private fun assertFeasibleAndDistinct(scheme: ColorScheme) {
        val financeColors = scheme.resolveFinanceColors()
        val backgrounds = scheme.financeTextBackgrounds()

        listOf(financeColors.income, financeColors.expense).forEachIndexed { colorIndex, color ->
            backgrounds.forEachIndexed { backgroundIndex, background ->
                assertWithMessage("finance color $colorIndex against background $backgroundIndex")
                    .that(financeContrastRatio(color, background))
                    .isAtLeast(4.5f)
            }
        }

        val income = financeColors.income.convert(ColorSpaces.Srgb)
        val expense = financeColors.expense.convert(ColorSpaces.Srgb)
        val channelSeparation =
            abs(income.red - expense.red) +
                abs(income.green - expense.green) +
                abs(income.blue - expense.blue)

        assertThat(financeColors.income).isNotEqualTo(financeColors.expense)
        assertThat(channelSeparation).isGreaterThan(0.05f)
        assertThat(income.green).isGreaterThan(income.red)
        assertThat(expense.red).isGreaterThan(expense.green)
    }

    private fun neutralAtLuminance(luminance: Float): Color =
        Color(
            red = luminance,
            green = luminance,
            blue = luminance,
            colorSpace = ColorSpaces.LinearSrgb,
        )

    private fun schemeWithFinanceBackgrounds(
        backgrounds: List<Color>,
        onSurface: Color = Color.White,
    ): ColorScheme {
        fun background(index: Int): Color = backgrounds[index % backgrounds.size]

        return lightColorScheme(
            background = background(0),
            surface = background(1),
            surfaceVariant = background(2),
            surfaceBright = background(3),
            surfaceDim = background(4),
            surfaceContainerLowest = background(5),
            surfaceContainerLow = background(6),
            surfaceContainer = background(7),
            surfaceContainerHigh = background(8),
            surfaceContainerHighest = background(9),
            secondaryContainer = background(10),
            errorContainer = background(11),
            onSurface = onSurface,
        )
    }
}
