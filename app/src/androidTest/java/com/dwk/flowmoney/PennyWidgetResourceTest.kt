package com.dwk.flowmoney

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class PennyWidgetResourceTest {
    @Test
    fun widgetInfoUsesDedicatedPreviewAndNeutralInitialLayouts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewLayout = widgetInfoLayout(context, "previewLayout")
        val initialLayout = widgetInfoLayout(context, "initialLayout")
        val runtimeLayouts =
            setOf(
                R.layout.widget_penny_compact,
                R.layout.widget_penny_summary,
                R.layout.widget_penny_wide,
            )

        assertEquals(R.layout.widget_penny_preview, previewLayout)
        assertEquals(R.layout.widget_penny_initial, initialLayout)
        assertFalse("preview and initial layouts must be distinct", previewLayout == initialLayout)
        assertFalse("picker preview must not be a runtime layout", previewLayout in runtimeLayouts)
        assertFalse("initial frame must not be a runtime layout", initialLayout in runtimeLayouts)
    }

    @Test
    fun initialFrameUsesCompactRuntimeBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val compactBackground = layoutRootResource(context, R.layout.widget_penny_compact, "background")
        val initialBackground = layoutRootResource(context, R.layout.widget_penny_initial, "background")

        assertEquals(R.drawable.widget_penny_compact_background, compactBackground)
        assertEquals("initial and runtime compact backgrounds", compactBackground, initialBackground)
    }

    @Test
    fun pickerPreviewAndInitialFrameStayBoundedAndPrivacySafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases =
            listOf(
                StaticWidgetLayoutCase(
                    name = "picker preview 110x110",
                    layoutId = R.layout.widget_penny_preview,
                    widthDp = 110,
                    heightDp = 110,
                    expectedText =
                        mapOf(
                            R.id.widget_preview_label to R.string.widget_preview_label,
                            R.id.widget_preview_add_button to R.string.widget_quick_add,
                            R.id.widget_preview_amount to R.string.widget_preview_amount,
                            R.id.widget_preview_detail to R.string.widget_preview_detail,
                        ),
                    expectedTextColors =
                        mapOf(
                            R.id.widget_preview_label to R.color.widget_on_surface_inverse_muted,
                            R.id.widget_preview_add_button to R.color.widget_on_quick_add,
                            R.id.widget_preview_amount to R.color.widget_on_surface_inverse,
                            R.id.widget_preview_detail to R.color.widget_on_surface_inverse_muted,
                        ),
                    expectedContentDescriptions =
                        mapOf(
                            R.id.widget_preview_add_button to R.string.widget_quick_add_description,
                            R.id.widget_preview_amount to R.string.widget_preview_amount_description,
                        ),
                    addButtonId = R.id.widget_preview_add_button,
                ),
                StaticWidgetLayoutCase(
                    name = "initial frame compact 110x48",
                    layoutId = R.layout.widget_penny_initial,
                    widthDp = 110,
                    heightDp = 48,
                    expectedText =
                        mapOf(
                            R.id.widget_initial_label to R.string.widget_initial_label,
                            R.id.widget_initial_guidance to R.string.widget_initial_guidance,
                        ),
                    expectedTextColors =
                        mapOf(
                            R.id.widget_initial_label to R.color.widget_on_surface_inverse_muted,
                            R.id.widget_initial_guidance to R.color.widget_on_surface_inverse,
                        ),
                ),
                StaticWidgetLayoutCase(
                    name = "initial frame standard 110x110",
                    layoutId = R.layout.widget_penny_initial,
                    widthDp = 110,
                    heightDp = 110,
                    expectedText =
                        mapOf(
                            R.id.widget_initial_label to R.string.widget_initial_label,
                            R.id.widget_initial_guidance to R.string.widget_initial_guidance,
                        ),
                    expectedTextColors =
                        mapOf(
                            R.id.widget_initial_label to R.color.widget_on_surface_inverse_muted,
                            R.id.widget_initial_guidance to R.color.widget_on_surface_inverse,
                        ),
                ),
            )

        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
            listOf(1f, 1.5f, 2f).forEach { fontScale ->
                val configuration =
                    Configuration(context.resources.configuration).apply {
                        this.fontScale = fontScale
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                    }
                val configuredContext = context.createConfigurationContext(configuration)
                cases.forEach { case ->
                    assertStaticLayout(configuredContext, case, fontScale, nightMode)
                }
            }
        }
    }

    private fun assertStaticLayout(
        context: Context,
        case: StaticWidgetLayoutCase,
        fontScale: Float,
        nightMode: Int,
    ) {
        val density = context.resources.displayMetrics.density
        val width = (case.widthDp * density + 0.5f).toInt()
        val height = (case.heightDp * density + 0.5f).toInt()
        val description = "${case.name}, fontScale $fontScale, nightMode $nightMode"
        val root = RemoteViews(context.packageName, case.layoutId).apply(context, null) as ViewGroup

        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals("$description width", width, root.width)
        assertEquals("$description height", height, root.height)
        assertNotNull("$description background", root.background)

        val visibleViews = visibleDescendants(root)
        visibleViews.forEach { view ->
            val bounds = descendantBounds(root, view)
            val viewDescription = "$description ${viewDescription(context, view)}"
            assertTrue("$viewDescription has empty bounds $bounds", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("$viewDescription starts outside root $bounds", bounds.left >= 0 && bounds.top >= 0)
            assertTrue("$viewDescription exceeds right edge $bounds", bounds.right <= root.width)
            assertTrue("$viewDescription exceeds bottom edge $bounds", bounds.bottom <= root.height)
        }

        val textViews = visibleViews.filterIsInstance<TextView>()
        assertEquals("$description text IDs", case.expectedText.keys, textViews.map { it.id }.toSet())
        textViews.forEach { textView ->
            val expectedText = context.getString(case.expectedText.getValue(textView.id))
            val textDescription = "$description ${viewDescription(context, textView)}"
            assertEquals("$textDescription text", expectedText, textView.text.toString())
            assertTextFits(textDescription, textView)
            assertEquals(
                "$textDescription day/night color",
                context.getColor(case.expectedTextColors.getValue(textView.id)),
                textView.currentTextColor,
            )
        }
        assertTextDoesNotOverlap(description, root, textViews)
        if (case.layoutId == R.layout.widget_penny_initial) {
            assertInitialTextGeometry(description, root, textViews, density)
        }
        val expectedContentDescriptions =
            case.expectedContentDescriptions.mapValues { (_, stringId) -> context.getString(stringId) }
        val actualContentDescriptions =
            textViews
                .mapNotNull { textView ->
                    textView.contentDescription
                        ?.toString()
                        ?.takeIf(String::isNotBlank)
                        ?.let { textView.id to it }
                }.toMap()
        assertEquals("$description content descriptions", expectedContentDescriptions, actualContentDescriptions)

        case.addButtonId?.let { addButtonId ->
            val addButton = root.findViewById<TextView>(addButtonId)
            val addBounds = descendantBounds(root, addButton)
            val addSize = (48 * density + 0.5f).toInt()
            assertEquals("$description add width", addSize, addBounds.width())
            assertEquals("$description add height", addSize, addBounds.height())
            assertTextFits("$description add", addButton)
        }

        val representedContent =
            buildList {
                textViews.forEach { textView ->
                    add(textView.text.toString())
                    textView.contentDescription
                        ?.toString()
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }.joinToString(" | ")
        assertFalse("$description advertises failure: $representedContent", representedContent.contains("Unavailable", true))
        assertFalse("$description claims a real zero: $representedContent", representedContent.contains("\$0.00"))
        listOf("merchant", "account", "credential", "access url", "routing number", "private note").forEach {
            assertFalse("$description exposes private $it content", representedContent.contains(it, true))
        }

        if (case.layoutId == R.layout.widget_penny_preview) {
            assertTrue("$description must identify synthetic content", representedContent.contains("sample", true))
        } else {
            val recoveryGuidance = context.getString(R.string.widget_initial_guidance)
            assertTrue(
                "$description must show recovery guidance: $representedContent",
                representedContent.contains(recoveryGuidance, true),
            )
            assertTrue("$description must name Penny", representedContent.contains("Penny", true))
            assertTrue("$description must say how to load", representedContent.contains("load", true))
            assertFalse("$description must not reuse picker samples", representedContent.contains("sample", true))
            assertFalse("$description must differ from runtime failure", representedContent.contains("Open app", true))
            assertFalse(
                "$description must not claim a monetary value: $representedContent",
                Regex("[$€£]\\s*\\d").containsMatchIn(representedContent),
            )
        }
    }

    private fun widgetInfoLayout(
        context: Context,
        attributeName: String,
    ): Int = xmlRootResource(context, R.xml.penny_widget_info, "appwidget-provider", attributeName)

    private fun layoutRootResource(
        context: Context,
        layoutId: Int,
        attributeName: String,
    ): Int = xmlRootResource(context, layoutId, "LinearLayout", attributeName)

    private fun xmlRootResource(
        context: Context,
        xmlId: Int,
        rootName: String,
        attributeName: String,
    ): Int {
        val parser = context.resources.getXml(xmlId)
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == rootName) {
                    return parser.getAttributeResourceValue(ANDROID_NAMESPACE, attributeName, 0)
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return 0
    }

    private fun visibleDescendants(root: ViewGroup): List<View> =
        buildList {
            fun addVisibleChildren(parent: ViewGroup) {
                repeat(parent.childCount) { index ->
                    val child = parent.getChildAt(index)
                    if (child.visibility == View.VISIBLE) {
                        add(child)
                        if (child is ViewGroup) addVisibleChildren(child)
                    }
                }
            }
            addVisibleChildren(root)
        }

    private fun assertTextFits(
        description: String,
        textView: TextView,
    ) {
        val layout = requireNotNull(textView.layout) { "$description has no text layout" }
        assertTrue("$description has no laid-out text", layout.lineCount > 0)
        assertTrue("$description exceeds maxLines", layout.lineCount <= textView.maxLines)
        assertTrue(
            "$description text height ${layout.height} exceeds content height " +
                "${textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom}",
            layout.height <= textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom,
        )
        repeat(layout.lineCount) { line ->
            assertEquals("$description ellipsized line $line", 0, layout.getEllipsisCount(line))
        }
        assertEquals("$description did not lay out all text", textView.text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private fun assertTextDoesNotOverlap(
        description: String,
        root: ViewGroup,
        textViews: List<TextView>,
    ) {
        textViews.indices.forEach { firstIndex ->
            for (secondIndex in firstIndex + 1 until textViews.size) {
                val first = textViews[firstIndex]
                val second = textViews[secondIndex]
                val firstBounds = descendantBounds(root, first)
                val secondBounds = descendantBounds(root, second)
                assertFalse(
                    "$description ${viewDescription(root.context, first)} overlaps " +
                        "${viewDescription(root.context, second)}: $firstBounds / $secondBounds",
                    Rect.intersects(firstBounds, secondBounds),
                )
            }
        }
    }

    private fun assertInitialTextGeometry(
        description: String,
        root: ViewGroup,
        textViews: List<TextView>,
        density: Float,
    ) {
        val minimumPadding = dpToPx(MINIMUM_INITIAL_VERTICAL_PADDING_DP, density)
        val maximumPadding = dpToPx(MAXIMUM_INITIAL_VERTICAL_PADDING_DP, density)
        assertTrue(
            "$description top padding ${root.paddingTop}px is outside the safe range",
            root.paddingTop in minimumPadding..maximumPadding,
        )
        assertTrue(
            "$description bottom padding ${root.paddingBottom}px is outside the safe range",
            root.paddingBottom in minimumPadding..maximumPadding,
        )

        val radius = dpToPx(COMPACT_CORNER_RADIUS_DP, density)
        textViews.forEach { textView ->
            val bounds = descendantBounds(root, textView)
            listOf(
                bounds.left to bounds.top,
                bounds.right to bounds.top,
                bounds.left to bounds.bottom,
                bounds.right to bounds.bottom,
            ).forEach { (x, y) ->
                val nearestX = x.coerceIn(radius, root.width - radius)
                val nearestY = y.coerceIn(radius, root.height - radius)
                val deltaX = x - nearestX
                val deltaY = y - nearestY
                assertTrue(
                    "$description ${viewDescription(root.context, textView)} bounds $bounds " +
                        "cross the ${COMPACT_CORNER_RADIUS_DP}dp rounded safe inset at ($x, $y)",
                    deltaX * deltaX + deltaY * deltaY <= radius * radius,
                )
            }
        }

        val guidance = root.findViewById<TextView>(R.id.widget_initial_guidance)
        assertEquals("$description guidance max lines", 2, guidance.maxLines)
    }

    private fun dpToPx(
        dp: Int,
        density: Float,
    ): Int = (dp * density + 0.5f).toInt()

    private fun descendantBounds(
        root: ViewGroup,
        child: View,
    ): Rect =
        Rect(0, 0, child.width, child.height).also {
            root.offsetDescendantRectToMyCoords(child, it)
        }

    private fun viewDescription(
        context: Context,
        view: View,
    ): String =
        if (view.id == View.NO_ID) {
            view.javaClass.simpleName
        } else {
            context.resources.getResourceEntryName(view.id)
        }

    private data class StaticWidgetLayoutCase(
        val name: String,
        val layoutId: Int,
        val widthDp: Int,
        val heightDp: Int,
        val expectedText: Map<Int, Int>,
        val expectedTextColors: Map<Int, Int>,
        val expectedContentDescriptions: Map<Int, Int> = emptyMap(),
        val addButtonId: Int? = null,
    )

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val COMPACT_CORNER_RADIUS_DP = 8
        private const val MINIMUM_INITIAL_VERTICAL_PADDING_DP = 2
        private const val MAXIMUM_INITIAL_VERTICAL_PADDING_DP = 6
    }
}
