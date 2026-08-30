package com.dwk.flowmoney

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Typography contract for the Penny widget layouts: Inter typeface assignment, tabular-figure
 * opt-in, and the app type scale maxima.
 *
 * Deliberately holds no text-fitting assertions -- PennyWidgetResourceTest and
 * PennyWidgetIntentTest own font-scale fitting. This test only pins which face, feature, and
 * ceiling each widget TextView declares.
 */
@RunWith(AndroidJUnit4::class)
class PennyWidgetTypeResourceTest {
    private val semibold = R.font.inter_semibold
    private val regular = R.font.inter_regular

    @Test
    fun fontFingerprintDistinguishesTheInterFaces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val semiboldPrint = fontFingerprint(ResourcesCompat.getFont(context, semibold))
        val regularPrint = fontFingerprint(ResourcesCompat.getFont(context, regular))
        val defaultPrint = fontFingerprint(Typeface.DEFAULT)

        // Guards the typeface assertions below from silently passing on identical metrics.
        assertTrue("inter_semibold and inter_regular must differ", semiboldPrint != regularPrint)
        assertTrue("inter_semibold must differ from the platform default", semiboldPrint != defaultPrint)
        assertTrue("inter_regular must differ from the platform default", regularPrint != defaultPrint)
    }

    @Test
    fun labelsUseInterSemibold() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTypeface(context, R.layout.widget_penny_summary, R.id.widget_spent_label, semibold)
        assertTypeface(context, R.layout.widget_penny_compact, R.id.widget_spent_label, semibold)
        assertTypeface(context, R.layout.widget_penny_wide, R.id.widget_spent_label, semibold)
        assertTypeface(context, R.layout.widget_penny_preview, R.id.widget_preview_label, semibold)
        assertTypeface(context, R.layout.widget_penny_initial, R.id.widget_initial_label, semibold)
    }

    @Test
    fun supportingTextUsesInterRegular() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTypeface(context, R.layout.widget_penny_summary, R.id.widget_count, regular)
        assertTypeface(context, R.layout.widget_penny_wide, R.id.widget_count, regular)
        assertTypeface(context, R.layout.widget_penny_wide, R.id.widget_categories, regular)
        assertTypeface(context, R.layout.widget_penny_preview, R.id.widget_preview_detail, regular)
    }

    @Test
    fun amountsUseInterSemibold() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTypeface(context, R.layout.widget_penny_summary, R.id.widget_amount, semibold)
        assertTypeface(context, R.layout.widget_penny_compact, R.id.widget_amount, semibold)
        assertTypeface(context, R.layout.widget_penny_wide, R.id.widget_amount, semibold)
        assertTypeface(context, R.layout.widget_penny_preview, R.id.widget_preview_amount, semibold)
    }

    @Test
    fun addButtonsAndGuidanceUseInterSemiboldWithoutSyntheticBold() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val buttons =
            listOf(
                Triple(R.layout.widget_penny_summary, R.id.widget_add_button, "summary"),
                Triple(R.layout.widget_penny_compact, R.id.widget_add_button, "compact"),
                Triple(R.layout.widget_penny_wide, R.id.widget_add_button, "wide"),
                Triple(R.layout.widget_penny_preview, R.id.widget_preview_add_button, "preview"),
                Triple(R.layout.widget_penny_initial, R.id.widget_initial_guidance, "initial guidance"),
            )

        buttons.forEach { (layoutId, viewId, name) ->
            val textView = findTextView(context, layoutId, viewId)
            assertTypefaceMatches(name, context, textView, semibold)
            // Inter SemiBold reports Typeface.BOLD because it declares weight 600, so the real
            // synthetic-bold signal is the paint's fake-bold flag, not the typeface style.
            assertFalse(
                "$name must not smear Inter SemiBold with synthetic bold",
                textView.paint.isFakeBoldText,
            )
        }
    }

    @Test
    fun onlyFullWidthAmountsOptIntoTabularFigures() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertHasTnum(context, R.layout.widget_penny_summary, R.id.widget_amount, "summary amount")
        assertHasTnum(context, R.layout.widget_penny_wide, R.id.widget_amount, "wide amount")
        assertHasTnum(
            context,
            R.layout.widget_penny_preview,
            R.id.widget_preview_amount,
            "preview amount",
        )

        // Compact squeezes the amount with textScaleX, which fights fixed-advance figures.
        assertLacksTnum(context, R.layout.widget_penny_compact, R.id.widget_amount, "compact amount")
        assertLacksTnum(context, R.layout.widget_penny_summary, R.id.widget_count, "summary count")
        assertLacksTnum(
            context,
            R.layout.widget_penny_summary,
            R.id.widget_spent_label,
            "summary label",
        )
    }

    @Test
    fun typeScaleMaximaMatchTheAppScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertAutoSizeMax(context, R.layout.widget_penny_summary, R.id.widget_amount, 30f)
        assertAutoSizeMax(context, R.layout.widget_penny_preview, R.id.widget_preview_amount, 30f)
        assertAutoSizeMax(context, R.layout.widget_penny_wide, R.id.widget_amount, 28f)
        assertAutoSizeMax(context, R.layout.widget_penny_compact, R.id.widget_amount, 20f)

        listOf(
            Triple(R.layout.widget_penny_summary, R.id.widget_spent_label, "summary label"),
            Triple(R.layout.widget_penny_compact, R.id.widget_spent_label, "compact label"),
            Triple(R.layout.widget_penny_wide, R.id.widget_spent_label, "wide label"),
            Triple(R.layout.widget_penny_preview, R.id.widget_preview_label, "preview label"),
            Triple(R.layout.widget_penny_initial, R.id.widget_initial_label, "initial label"),
            Triple(R.layout.widget_penny_summary, R.id.widget_count, "summary count"),
            Triple(R.layout.widget_penny_wide, R.id.widget_count, "wide count"),
            Triple(R.layout.widget_penny_wide, R.id.widget_categories, "wide categories"),
            Triple(R.layout.widget_penny_preview, R.id.widget_preview_detail, "preview detail"),
        ).forEach { (layoutId, viewId, name) ->
            assertAutoSizeMax(context, layoutId, viewId, 12f, name)
        }
    }

    @Test
    fun addButtonAndGuidanceCeilingsAreUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        listOf(
            Triple(R.layout.widget_penny_summary, R.id.widget_add_button, "summary add"),
            Triple(R.layout.widget_penny_compact, R.id.widget_add_button, "compact add"),
            Triple(R.layout.widget_penny_wide, R.id.widget_add_button, "wide add"),
            Triple(R.layout.widget_penny_preview, R.id.widget_preview_add_button, "preview add"),
        ).forEach { (layoutId, viewId, name) ->
            val textView = findTextView(context, layoutId, viewId)
            assertSpEquals(context, "$name max", 23f, textView.autoSizeMaxTextSize)
            assertSpEquals(context, "$name min", 14f, textView.autoSizeMinTextSize)
            assertDpEquals(context, "$name width", 48f, textView.layoutParams.width)
            assertDpEquals(context, "$name height", 48f, textView.layoutParams.height)
        }

        assertAutoSizeMax(
            context,
            R.layout.widget_penny_initial,
            R.id.widget_initial_guidance,
            14f,
            "initial guidance",
        )
    }

    @Test
    fun compactKeepsItsHorizontalSqueezeAndPadding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = inflate(context, R.layout.widget_penny_compact)
        val amount = root.findViewById<TextView>(R.id.widget_amount)

        assertEquals("compact amount textScaleX", 0.75f, amount.textScaleX, 0.0001f)
        assertDpEquals(context, "compact left padding", 8f, root.paddingLeft)
        assertDpEquals(context, "compact right padding", 8f, root.paddingRight)
    }

    private fun inflate(
        context: Context,
        layoutId: Int,
    ): ViewGroup = RemoteViews(context.packageName, layoutId).apply(context, null) as ViewGroup

    private fun findTextView(
        context: Context,
        layoutId: Int,
        viewId: Int,
    ): TextView {
        val view = inflate(context, layoutId).findViewById<View>(viewId)
        assertNotNull("missing view ${context.resources.getResourceEntryName(viewId)}", view)
        return view as TextView
    }

    private fun assertTypeface(
        context: Context,
        layoutId: Int,
        viewId: Int,
        fontId: Int,
    ) {
        val name =
            "${context.resources.getResourceEntryName(layoutId)}." +
                context.resources.getResourceEntryName(viewId)
        assertTypefaceMatches(name, context, findTextView(context, layoutId, viewId), fontId)
    }

    private fun assertTypefaceMatches(
        name: String,
        context: Context,
        textView: TextView,
        fontId: Int,
    ) {
        val expected = ResourcesCompat.getFont(context, fontId)
        assertNotNull("could not load ${context.resources.getResourceEntryName(fontId)}", expected)
        // Typeface has no equals(), and resource inflation and ResourcesCompat hand back distinct
        // instances, so compare the glyph metrics the face actually produces.
        assertEquals(
            "$name typeface must be ${context.resources.getResourceEntryName(fontId)}",
            fontFingerprint(expected),
            fontFingerprint(textView.typeface),
        )
    }

    /** Advance widths plus vertical metrics for a probe string -- identifies the loaded face. */
    private fun fontFingerprint(typeface: Typeface?): String {
        val paint =
            TextPaint().apply {
                this.typeface = typeface
                textSize = 64f
            }
        val probe = "ABCabcgy0123456789$.,"
        val widths = FloatArray(probe.length)
        paint.getTextWidths(probe, widths)
        val metrics = paint.fontMetrics
        return widths.joinToString(",") + "|${metrics.ascent}|${metrics.descent}"
    }

    private fun assertHasTnum(
        context: Context,
        layoutId: Int,
        viewId: Int,
        name: String,
    ) {
        val features = findTextView(context, layoutId, viewId).fontFeatureSettings
        assertTrue(
            "$name must request tabular figures, was $features",
            features?.contains("tnum") == true,
        )
    }

    private fun assertLacksTnum(
        context: Context,
        layoutId: Int,
        viewId: Int,
        name: String,
    ) {
        val features = findTextView(context, layoutId, viewId).fontFeatureSettings
        assertTrue(
            "$name must not request tabular figures, was $features",
            features?.contains("tnum") != true,
        )
    }

    private fun assertAutoSizeMax(
        context: Context,
        layoutId: Int,
        viewId: Int,
        expectedSp: Float,
        name: String? = null,
    ) {
        val label =
            name ?: "${context.resources.getResourceEntryName(layoutId)}." +
                context.resources.getResourceEntryName(viewId)
        val textView = findTextView(context, layoutId, viewId)
        assertEquals(
            "$label must use uniform auto-size",
            TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
            textView.autoSizeTextType,
        )
        assertSpEquals(context, "$label max", expectedSp, textView.autoSizeMaxTextSize)
    }

    private fun assertSpEquals(
        context: Context,
        label: String,
        expectedSp: Float,
        actualPx: Int,
    ) {
        val expectedPx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                expectedSp,
                context.resources.displayMetrics,
            )
        assertTrue(
            "$label expected ${expectedSp}sp (~${expectedPx}px) but was ${actualPx}px",
            abs(expectedPx - actualPx) <= 1f,
        )
    }

    private fun assertDpEquals(
        context: Context,
        label: String,
        expectedDp: Float,
        actualPx: Int,
    ) {
        val expectedPx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                expectedDp,
                context.resources.displayMetrics,
            )
        assertTrue(
            "$label expected ${expectedDp}dp (~${expectedPx}px) but was ${actualPx}px",
            abs(expectedPx - actualPx) <= 1f,
        )
    }
}
