package com.dwk.flowmoney

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sqrt

internal object PennyMotion {
    const val DurationShort = 150
    const val DurationMedium = 250
    const val DurationLong = 300

    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardAccelerateEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val StandardDecelerateEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
}

@Immutable
internal data class FinanceColors(
    val income: Color,
    val expense: Color,
)

private val LightFinanceColors =
    FinanceColors(
        income = Color(0xFF006C54),
        expense = Color(0xFFB3261E),
    )

private val DarkFinanceColors =
    FinanceColors(
        income = Color(0xFF73DBB2),
        expense = Color(0xFFFFB4AB),
    )

private const val MinimumFinanceContrast = 4.5f
private const val WcagLuminanceOffset = 0.05f
private const val FinanceAdjustmentSearchSteps = 64
private const val FinanceAdjustmentRefinementSteps = 12

internal val LocalFinanceColors = staticCompositionLocalOf { LightFinanceColors }

internal fun financeContrastRatio(
    foreground: Color,
    background: Color,
): Float {
    val foregroundLuminance = foreground.luminance()
    val backgroundLuminance = background.luminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + WcagLuminanceOffset) / (darker + WcagLuminanceOffset)
}

private fun Color.minimumContrast(backgrounds: List<Color>): Float =
    backgrounds.minOf { background -> financeContrastRatio(this, background) }

private fun Color.hasFinanceTextContrast(backgrounds: List<Color>): Boolean = minimumContrast(backgrounds) >= MinimumFinanceContrast

private fun FinanceColors.minimumContrast(backgrounds: List<Color>): Float =
    minOf(income.minimumContrast(backgrounds), expense.minimumContrast(backgrounds))

private fun FinanceColors.hasFinanceTextContrast(backgrounds: List<Color>): Boolean =
    income.hasFinanceTextContrast(backgrounds) && expense.hasFinanceTextContrast(backgrounds)

internal fun ColorScheme.financeTextBackgrounds(): List<Color> =
    listOf(
        background,
        surface,
        surfaceVariant,
        surfaceBright,
        surfaceDim,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
        secondaryContainer,
        errorContainer,
    )

private fun neutralColorAtLuminance(luminance: Float): Color =
    Color(
        red = luminance,
        green = luminance,
        blue = luminance,
        colorSpace = ColorSpaces.LinearSrgb,
    )

private fun ColorScheme.bestSharedFinanceContentColor(backgrounds: List<Color>): Color {
    if (onSurface.hasFinanceTextContrast(backgrounds)) return onSurface

    val backgroundLuminances = backgrounds.map(Color::luminance).distinct().sorted()
    val candidateLuminances =
        buildList {
            add(0f)
            backgroundLuminances.zipWithNext { lower, upper ->
                // Within a gap, contrast to the nearest background on each side is
                // balanced at sqrt((lower + .05) * (upper + .05)) - .05. Along with
                // black and white, these are all continuous maximin candidates.
                add(
                    sqrt(
                        (lower + WcagLuminanceOffset) *
                            (upper + WcagLuminanceOffset),
                    ) - WcagLuminanceOffset,
                )
            }
            add(1f)
        }
    val preferredLuminance = onSurface.luminance()

    // Evaluate the represented colors, then break exact score ties by proximity to
    // onSurface and finally by the darker tone. At most 13 tones are considered for
    // the 12 finance backgrounds, so this stays deterministic and cheap in composition.
    return candidateLuminances
        .map(::neutralColorAtLuminance)
        .maxWithOrNull(
            compareBy<Color> { color -> color.minimumContrast(backgrounds) }
                .thenBy { color -> -abs(color.luminance() - preferredLuminance) }
                .thenBy { color -> -color.luminance() },
        ) ?: neutralColorAtLuminance(0f)
}

private fun Color.adjustedToward(
    safeContentColor: Color,
    backgrounds: List<Color>,
): Color {
    if (hasFinanceTextContrast(backgrounds)) return this
    if (!safeContentColor.hasFinanceTextContrast(backgrounds)) return safeContentColor

    fun adjusted(fraction: Float): Color = lerp(this, safeContentColor, fraction)

    var failingFraction = 0f
    var passingFraction = 1f
    for (step in 1..FinanceAdjustmentSearchSteps) {
        val fraction = step.toFloat() / FinanceAdjustmentSearchSteps
        if (adjusted(fraction).hasFinanceTextContrast(backgrounds)) {
            passingFraction = fraction
            break
        }
        failingFraction = fraction
    }
    repeat(FinanceAdjustmentRefinementSteps) {
        val fraction = (failingFraction + passingFraction) / 2f
        if (adjusted(fraction).hasFinanceTextContrast(backgrounds)) {
            passingFraction = fraction
        } else {
            failingFraction = fraction
        }
    }
    return adjusted(passingFraction)
}

private fun FinanceColors.adjustedToward(
    safeContentColor: Color,
    backgrounds: List<Color>,
): FinanceColors =
    FinanceColors(
        income = income.adjustedToward(safeContentColor, backgrounds),
        expense = expense.adjustedToward(safeContentColor, backgrounds),
    )

internal fun ColorScheme.resolveFinanceColors(): FinanceColors {
    val backgrounds = financeTextBackgrounds()
    val candidates = listOf(LightFinanceColors, DarkFinanceColors)
    candidates
        .filter { candidate -> candidate.hasFinanceTextContrast(backgrounds) }
        .maxByOrNull { candidate -> candidate.minimumContrast(backgrounds) }
        ?.let { candidate -> return candidate }

    val bestCandidate = candidates.maxBy { candidate -> candidate.minimumContrast(backgrounds) }
    return bestCandidate.adjustedToward(
        safeContentColor = bestSharedFinanceContentColor(backgrounds),
        backgrounds = backgrounds,
    )
}

private val FlowMoneyLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF101613),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E8DC),
        onPrimaryContainer = Color(0xFF18211B),
        inversePrimary = Color(0xFF9CD8BC),
        secondary = LightFinanceColors.income,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFC8F2E3),
        onSecondaryContainer = Color(0xFF00382A),
        tertiary = Color(0xFF815F00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE08A),
        onTertiaryContainer = Color(0xFF281900),
        background = Color(0xFFF6F7F4),
        onBackground = Color(0xFF101613),
        surface = Color.White,
        onSurface = Color(0xFF101613),
        surfaceVariant = Color(0xFFE1E6E0),
        onSurfaceVariant = Color(0xFF414942),
        surfaceTint = Color(0xFF101613),
        inverseSurface = Color(0xFF2C332E),
        inverseOnSurface = Color(0xFFEEF3ED),
        error = LightFinanceColors.expense,
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF727B73),
        outlineVariant = Color(0xFFC2CBC3),
        scrim = Color.Black,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDDE3DD),
        surfaceContainer = Color(0xFFF0F4EE),
        surfaceContainerHigh = Color(0xFFEBEFE9),
        surfaceContainerHighest = Color(0xFFE5EAE4),
        surfaceContainerLow = Color(0xFFF6F9F4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )

private val FlowMoneyDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF9CD8BC),
        onPrimary = Color(0xFF003827),
        primaryContainer = Color(0xFF1E5140),
        onPrimaryContainer = Color(0xFFB8F5D4),
        inversePrimary = Color(0xFF101613),
        secondary = Color(0xFF75DDB9),
        onSecondary = Color(0xFF003828),
        secondaryContainer = Color(0xFF00513C),
        onSecondaryContainer = Color(0xFF92F8D2),
        tertiary = Color(0xFFFFD568),
        onTertiary = Color(0xFF423000),
        tertiaryContainer = Color(0xFF5F4600),
        onTertiaryContainer = Color(0xFFFFE08A),
        background = Color(0xFF101512),
        onBackground = Color(0xFFE0E5DE),
        surface = Color(0xFF101512),
        onSurface = Color(0xFFE0E5DE),
        surfaceVariant = Color(0xFF414942),
        onSurfaceVariant = Color(0xFFC2CBC3),
        surfaceTint = Color(0xFF9CD8BC),
        inverseSurface = Color(0xFFE0E5DE),
        inverseOnSurface = Color(0xFF2C332E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8C958D),
        outlineVariant = Color(0xFF414942),
        scrim = Color.Black,
        surfaceBright = Color(0xFF363B36),
        surfaceDim = Color(0xFF101512),
        surfaceContainer = Color(0xFF1C211D),
        surfaceContainerHigh = Color(0xFF262B27),
        surfaceContainerHighest = Color(0xFF303631),
        surfaceContainerLow = Color(0xFF171C18),
        surfaceContainerLowest = Color(0xFF0B0F0C),
    )

internal fun flowMoneyStaticColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) FlowMoneyDarkColorScheme else FlowMoneyLightColorScheme

private val FlowMoneyTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
    )

private val FlowMoneyShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

@Composable
internal fun FlowMoneyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            else -> flowMoneyStaticColorScheme(darkTheme)
        }
    val financeColors = remember(colors) { colors.resolveFinanceColors() }

    CompositionLocalProvider(LocalFinanceColors provides financeColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = FlowMoneyTypography,
            shapes = FlowMoneyShapes,
            content = content,
        )
    }
}
