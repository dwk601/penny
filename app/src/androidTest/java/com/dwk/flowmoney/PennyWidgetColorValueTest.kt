package com.dwk.flowmoney

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the quick-add chip against the inverse band it sits on.
 *
 * [PennyWidgetIntentTest.widgetDayNightColorsMeetWcagTextContrast] only checks *text* legibility
 * (on-colors against their own container), so a quick-add fill that melts into the surrounding
 * band still passes it. That regression shipped once: night `widget_quick_add` was a pale mint on
 * a pale band at roughly 1.05:1, leaving the tap target invisible while every text pair stayed
 * comfortably above 4.5:1. These assertions cover the adjacent-surface axis instead.
 *
 * The threshold is WCAG 2.1 SC 1.4.11 (non-text contrast, 3:1) because the chip is a UI component
 * boundary rather than text.
 */
@RunWith(AndroidJUnit4::class)
class PennyWidgetColorValueTest {
    @Test
    fun quickAddChipStaysVisibleAgainstInverseBandInBothNightModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val quickAddFills = mutableListOf<Int>()

        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
            val themed = configuredFor(context, nightMode)
            val quickAdd = themed.getColor(R.color.widget_quick_add)
            val surface = themed.getColor(R.color.widget_surface_inverse)
            quickAddFills += quickAdd

            assertContrastAtLeast(
                foreground = quickAdd,
                background = surface,
                minimum = 3.0,
                label = "widget_quick_add vs widget_surface_inverse (nightMode=$nightMode)",
            )
        }

        assertFalse(
            "quick-add fill must differ between day and night",
            quickAddFills[0] == quickAddFills[1],
        )
    }

    /**
     * The chip only reads as a distinct affordance if it also separates from the band's own text,
     * which sits directly beside it inside the same container.
     */
    @Test
    fun quickAddChipSeparatesFromInverseBandTextInBothNightModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
            val themed = configuredFor(context, nightMode)
            val quickAdd = themed.getColor(R.color.widget_quick_add)

            assertContrastAtLeast(
                foreground = themed.getColor(R.color.widget_on_quick_add),
                background = quickAdd,
                minimum = 4.5,
                label = "widget_on_quick_add vs widget_quick_add (nightMode=$nightMode)",
            )
        }
    }

    private fun configuredFor(
        context: Context,
        nightMode: Int,
    ): Context {
        val configuration =
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
        return context.createConfigurationContext(configuration)
    }

    private fun assertContrastAtLeast(
        foreground: Int,
        background: Int,
        minimum: Double,
        label: String,
    ) {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        val contrast = (lighter + 0.05) / (darker + 0.05)
        assertTrue(
            "Expected $label contrast >= $minimum:1, was $contrast:1",
            contrast >= minimum,
        )
    }

    private fun relativeLuminance(color: Int): Double =
        0.2126 * linearized(Color.red(color)) +
            0.7152 * linearized(Color.green(color)) +
            0.0722 * linearized(Color.blue(color))

    private fun linearized(component: Int): Double {
        val value = component / 255.0
        return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
}
