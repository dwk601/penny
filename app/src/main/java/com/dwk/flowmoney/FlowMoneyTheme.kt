package com.dwk.flowmoney

import android.os.Build
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

internal val LocalFinanceColors = staticCompositionLocalOf { LightFinanceColors }

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
            darkTheme -> FlowMoneyDarkColorScheme
            else -> FlowMoneyLightColorScheme
        }
    // Use the resolved scheme so wallpaper-derived surfaces get the contrast-safe variant.
    val financeColors = if (colors.surface.luminance() < 0.5f) DarkFinanceColors else LightFinanceColors

    CompositionLocalProvider(LocalFinanceColors provides financeColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = FlowMoneyTypography,
            shapes = FlowMoneyShapes,
            content = content,
        )
    }
}
