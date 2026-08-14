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
    fun pickerPreviewAndInitialFrameStayBoundedAndPrivacySafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases =
            listOf(
                StaticWidgetLayoutCase(
                    name = "picker preview",
                    layoutId = R.layout.widget_penny_preview,
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
                    name = "initial frame",
                    layoutId = R.layout.widget_penny_initial,
                    expectedText =
                        mapOf(
                            R.id.widget_initial_label to R.string.widget_initial_label,
                            R.id.widget_initial_status to R.string.widget_initial_status,
                            R.id.widget_initial_detail to R.string.widget_initial_detail,
                        ),
                    expectedTextColors =
                        mapOf(
                            R.id.widget_initial_label to R.color.widget_on_surface_inverse_muted,
                            R.id.widget_initial_status to R.color.widget_on_surface_inverse,
                            R.id.widget_initial_detail to R.color.widget_on_surface_inverse_muted,
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
        val size = (110 * density + 0.5f).toInt()
        val description = "${case.name}, fontScale $fontScale, nightMode $nightMode"
        val root = RemoteViews(context.packageName, case.layoutId).apply(context, null) as ViewGroup

        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals("$description width", size, root.width)
        assertEquals("$description height", size, root.height)
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

        if (case.layoutId == R.layout.widget_penny_preview) {
            assertTrue("$description must identify synthetic content", representedContent.contains("sample", true))
            listOf("merchant", "account", "credential", "access url", "routing number", "private note").forEach {
                assertFalse("$description exposes private $it content", representedContent.contains(it, true))
            }
        } else {
            assertTrue("$description must be neutral/loading", representedContent.contains("loading", true))
            assertFalse("$description must not reuse picker samples", representedContent.contains("sample", true))
            assertFalse(
                "$description must not claim a monetary value: $representedContent",
                Regex("[$€£]\\s*\\d").containsMatchIn(representedContent),
            )
        }
    }

    private fun widgetInfoLayout(
        context: Context,
        attributeName: String,
    ): Int {
        val parser = context.resources.getXml(R.xml.penny_widget_info)
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
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
        val expectedText: Map<Int, Int>,
        val expectedTextColors: Map<Int, Int>,
        val expectedContentDescriptions: Map<Int, Int> = emptyMap(),
        val addButtonId: Int? = null,
    )

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
