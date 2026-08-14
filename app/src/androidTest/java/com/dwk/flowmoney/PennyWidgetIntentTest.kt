package com.dwk.flowmoney

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PennyWidgetIntentTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun everyWidgetLayoutFitsAtSupportedFontScales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val categoryRows = listOf("Food \$80.00", "Travel \$43.45", "Bills \$12.34")
        val fallback =
            runBlocking {
                PennyWidgetProvider.summaryOrFallback(context) { _ ->
                    throw IOException("Room read failed")
                }
            }
        val summaries =
            listOf(
                WidgetSummary(
                    label = "This month",
                    amount = "\$123.45",
                    compactAmount = "\$123.45",
                    count = "4 txns",
                    topCategory = categoryRows.first(),
                    topCategories = categoryRows,
                ),
                WidgetSummary(
                    label = "This month",
                    amount = "\$1,234.56",
                    compactAmount = "\$1.2K",
                    count = "4 txns",
                    topCategory = categoryRows.first(),
                    topCategories = categoryRows,
                ),
                WidgetSummary(
                    label = "This month",
                    amount = "\$12,345.67",
                    compactAmount = "\$12K",
                    count = "4 txns",
                    topCategory = categoryRows.first(),
                    topCategories = categoryRows,
                ),
                WidgetSummary(
                    label = "This month",
                    amount = "\$123,456.78",
                    compactAmount = "\$123K",
                    count = "4 txns",
                    topCategory = categoryRows.first(),
                    topCategories = categoryRows,
                ),
                fallback,
            )
        val cases =
            listOf(
                WidgetLayoutCase(
                    name = "compact 110x48",
                    layoutId = R.layout.widget_penny_compact,
                    widthDp = 110,
                    heightDp = 48,
                    requiredText =
                        mapOf(
                            R.id.widget_spent_label to "This month",
                            R.id.widget_add_button to "+",
                        ),
                    permittedEllipsisIds = setOf(R.id.widget_spent_label),
                ),
                WidgetLayoutCase(
                    name = "summary 110x110",
                    layoutId = R.layout.widget_penny_summary,
                    widthDp = 110,
                    heightDp = 110,
                    requiredText =
                        mapOf(
                            R.id.widget_spent_label to "This month",
                            R.id.widget_add_button to "+",
                        ),
                ),
                WidgetLayoutCase(
                    name = "wide 220x110",
                    layoutId = R.layout.widget_penny_wide,
                    widthDp = 220,
                    heightDp = 110,
                    requiredText =
                        mapOf(
                            R.id.widget_spent_label to "This month",
                            R.id.widget_add_button to "+",
                        ),
                ),
            )

        listOf(1f, 1.5f, 2f).forEach { fontScale ->
            val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val configuredContext = context.createConfigurationContext(configuration)
            val density = configuredContext.resources.displayMetrics.density
            summaries.forEach { summary ->
                cases.forEach { case ->
                    val root =
                        PennyWidgetProvider
                            .viewsForLayout(configuredContext, summary, case.layoutId)
                            .apply(configuredContext, null) as ViewGroup
                    val width = (case.widthDp * density + 0.5f).toInt()
                    val height = (case.heightDp * density + 0.5f).toInt()
                    val addSize = (48 * density + 0.5f).toInt()
                    val description = "${case.name}, ${summary.amount}, at fontScale $fontScale"
                    val expectedAmount =
                        if (case.layoutId == R.layout.widget_penny_compact) summary.compactAmount else summary.amount
                    val supportingText =
                        when (case.layoutId) {
                            R.layout.widget_penny_summary -> {
                                mapOf(
                                    R.id.widget_count to
                                        configuredContext.getString(
                                            R.string.widget_supporting_metrics,
                                            summary.count,
                                            summary.topCategory,
                                        ),
                                )
                            }

                            R.layout.widget_penny_wide -> {
                                mapOf(
                                    R.id.widget_count to summary.count,
                                    R.id.widget_categories to summary.topCategories.joinToString("\n"),
                                )
                            }

                            else -> {
                                emptyMap()
                            }
                        }
                    val requiredText =
                        case.requiredText +
                            (R.id.widget_amount to expectedAmount) +
                            supportingText

                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                    assertEquals("$description width", width, root.measuredWidth)
                    assertEquals("$description height", height, root.measuredHeight)

                    val visibleChildren = visibleDescendants(root)
                    visibleChildren.forEach { child ->
                        val bounds = descendantBounds(root, child)
                        val childDescription = "$description ${viewDescription(configuredContext, child)}"
                        assertTrue("$childDescription has empty bounds $bounds", bounds.width() > 0 && bounds.height() > 0)
                        assertTrue("$childDescription starts outside the root: $bounds", bounds.left >= 0 && bounds.top >= 0)
                        assertTrue("$childDescription exceeds the right edge: $bounds", bounds.right <= root.width)
                        assertTrue("$childDescription exceeds the bottom edge: $bounds", bounds.bottom <= root.height)
                    }

                    val visibleText = visibleChildren.filterIsInstance<TextView>()
                    assertEquals("$description visible text IDs", requiredText.keys, visibleText.map { it.id }.toSet())
                    visibleText.forEach { textView ->
                        val textDescription = "$description ${viewDescription(configuredContext, textView)}"
                        assertEquals("$textDescription text", requiredText.getValue(textView.id), textView.text.toString())
                        assertTextFits(
                            description = textDescription,
                            textView = textView,
                            ellipsisPermitted = textView.id in case.permittedEllipsisIds,
                        )
                        val minimumSp =
                            when (textView.id) {
                                R.id.widget_spent_label -> {
                                    if (case.layoutId == R.layout.widget_penny_wide) 9f else 8f
                                }

                                R.id.widget_amount -> {
                                    7f
                                }

                                R.id.widget_count -> {
                                    if (case.layoutId == R.layout.widget_penny_wide) 8f else 7f
                                }

                                R.id.widget_categories -> {
                                    7f
                                }

                                else -> {
                                    null
                                }
                            }
                        if (minimumSp != null) {
                            assertUniformTextMinimum(textDescription, textView, configuredContext, minimumSp)
                        }
                        if (textView.id == R.id.widget_categories && summary.topCategories.size == 3) {
                            assertEquals("$textDescription rows", 3, textView.layout.lineCount)
                        }
                    }

                    visibleText.indices.forEach { firstIndex ->
                        for (secondIndex in firstIndex + 1 until visibleText.size) {
                            val first = visibleText[firstIndex]
                            val second = visibleText[secondIndex]
                            val firstBounds = descendantBounds(root, first)
                            val secondBounds = descendantBounds(root, second)
                            assertFalse(
                                "$description ${viewDescription(configuredContext, first)} overlaps " +
                                    "${viewDescription(configuredContext, second)}: $firstBounds / $secondBounds",
                                Rect.intersects(firstBounds, secondBounds),
                            )
                        }
                    }

                    val amount = root.findViewById<TextView>(R.id.widget_amount)
                    assertNoEllipsis("$description amount", amount)
                    assertEquals("$description full TalkBack amount", summary.amount, amount.contentDescription.toString())

                    val addButton = root.findViewById<TextView>(R.id.widget_add_button)
                    val addBounds = descendantBounds(root, addButton)
                    assertEquals("$description add width", addSize, addBounds.width())
                    assertEquals("$description add height", addSize, addBounds.height())
                    assertUniformAddButtonAutoSize(description, addButton, configuredContext)
                    if (case.layoutId == R.layout.widget_penny_compact) {
                        assertCompactRoundedCardGeometry(description, root, visibleText, density)
                    }
                }
            }
        }
    }

    @Test
    fun worstCaseSupportingTextUsesBoundedReadableTruncation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val categoryRows =
            listOf(
                "Entertainment \$123,456.78",
                "Subscriptions \$12,345.67",
                "Transportation \$1,234.56",
            )
        val summary =
            WidgetSummary(
                label = "This month",
                amount = "\$123,456.78",
                compactAmount = "\$123K",
                count = "12 txns",
                topCategory = categoryRows.first(),
                topCategories = categoryRows,
            )
        val cases =
            listOf(
                WidgetLayoutCase(
                    name = "summary 110x110 worst-case support",
                    layoutId = R.layout.widget_penny_summary,
                    widthDp = 110,
                    heightDp = 110,
                    requiredText =
                        mapOf(
                            R.id.widget_spent_label to "This month",
                            R.id.widget_amount to summary.amount,
                            R.id.widget_count to "12 txns · Entertainment \$123,456.78",
                            R.id.widget_add_button to "+",
                        ),
                    permittedEllipsisIds = setOf(R.id.widget_count),
                ),
                WidgetLayoutCase(
                    name = "wide 220x110 worst-case support",
                    layoutId = R.layout.widget_penny_wide,
                    widthDp = 220,
                    heightDp = 110,
                    requiredText =
                        mapOf(
                            R.id.widget_spent_label to "This month",
                            R.id.widget_amount to summary.amount,
                            R.id.widget_count to "12 txns",
                            R.id.widget_categories to categoryRows.joinToString("\n"),
                            R.id.widget_add_button to "+",
                        ),
                    permittedEllipsisIds = setOf(R.id.widget_count, R.id.widget_categories),
                ),
            )

        listOf(1f, 1.5f, 2f).forEach { fontScale ->
            val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val configuredContext = context.createConfigurationContext(configuration)
            val density = configuredContext.resources.displayMetrics.density
            cases.forEach { case ->
                val description = "${case.name} at fontScale $fontScale"
                val root =
                    PennyWidgetProvider
                        .viewsForLayout(configuredContext, summary, case.layoutId)
                        .apply(configuredContext, null) as ViewGroup
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((case.widthDp * density + 0.5f).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((case.heightDp * density + 0.5f).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                val visibleChildren = visibleDescendants(root)
                visibleChildren.forEach { child ->
                    val bounds = descendantBounds(root, child)
                    val childDescription = "$description ${viewDescription(configuredContext, child)}"
                    assertTrue("$childDescription has empty bounds $bounds", bounds.width() > 0 && bounds.height() > 0)
                    assertTrue("$childDescription starts outside the root: $bounds", bounds.left >= 0 && bounds.top >= 0)
                    assertTrue("$childDescription exceeds the right edge: $bounds", bounds.right <= root.width)
                    assertTrue("$childDescription exceeds the bottom edge: $bounds", bounds.bottom <= root.height)
                }

                val visibleText = visibleChildren.filterIsInstance<TextView>()
                assertEquals("$description visible text IDs", case.requiredText.keys, visibleText.map { it.id }.toSet())
                visibleText.forEach { textView ->
                    val textDescription = "$description ${viewDescription(configuredContext, textView)}"
                    assertEquals(
                        "$textDescription keeps its complete bound value",
                        case.requiredText.getValue(textView.id),
                        textView.text.toString(),
                    )
                    assertTextFits(textDescription, textView, textView.id in case.permittedEllipsisIds)
                }
                visibleText.indices.forEach { firstIndex ->
                    for (secondIndex in firstIndex + 1 until visibleText.size) {
                        val first = visibleText[firstIndex]
                        val second = visibleText[secondIndex]
                        assertFalse(
                            "$description ${viewDescription(configuredContext, first)} overlaps " +
                                "${viewDescription(configuredContext, second)}",
                            Rect.intersects(descendantBounds(root, first), descendantBounds(root, second)),
                        )
                    }
                }

                val amount = root.findViewById<TextView>(R.id.widget_amount)
                assertEquals("$description exact amount", summary.amount, amount.text.toString())
                assertNoEllipsis("$description amount", amount)
                assertUniformTextMinimum("$description amount", amount, configuredContext, minimumSp = 7f)

                val label = root.findViewById<TextView>(R.id.widget_spent_label)
                val labelFloor = if (case.layoutId == R.layout.widget_penny_wide) 9f else 8f
                assertUniformTextMinimum("$description label", label, configuredContext, labelFloor)

                val count = root.findViewById<TextView>(R.id.widget_count)
                val countFloor = if (case.layoutId == R.layout.widget_penny_wide) 8f else 7f
                assertUniformTextMinimum("$description support", count, configuredContext, countFloor)
                if (fontScale == 1f) {
                    assertNoEllipsis("$description support at the default font scale", count)
                } else if (case.layoutId == R.layout.widget_penny_summary) {
                    assertHasEllipsis("$description support uses safe end truncation", count)
                }

                if (case.layoutId == R.layout.widget_penny_wide) {
                    val categories = root.findViewById<TextView>(R.id.widget_categories)
                    assertEquals("$description keeps all bound category rows", categoryRows, categories.text.lines())
                    assertUniformTextMinimum("$description categories", categories, configuredContext, minimumSp = 7f)
                    if (fontScale == 1f) {
                        assertNoEllipsis("$description categories at the default font scale", categories)
                    } else {
                        assertHasEllipsis("$description categories use safe end truncation", categories)
                    }
                }
            }

            val compactRoot =
                PennyWidgetProvider
                    .viewsForLayout(configuredContext, summary, R.layout.widget_penny_compact)
                    .apply(configuredContext, null) as ViewGroup
            val compactText = visibleDescendants(compactRoot).filterIsInstance<TextView>().joinToString("\n") { it.text }
            categoryRows.forEach { row ->
                assertFalse("compact widget exposed private category row $row", compactText.contains(row))
            }
            assertTrue(compactRoot.findViewById<View>(R.id.widget_count) == null)
            assertTrue(compactRoot.findViewById<View>(R.id.widget_categories) == null)
        }
    }

    @Test
    fun pre31SizeSelectionUsesLandscapeAndPortraitOptionBounds() {
        assertEquals(R.layout.widget_penny_compact, PennyWidgetProvider.layoutForSize(110, 48))
        assertEquals(R.layout.widget_penny_compact, PennyWidgetProvider.layoutForSize(220, 48))
        assertEquals(R.layout.widget_penny_summary, PennyWidgetProvider.layoutForSize(110, 110))
        assertEquals(R.layout.widget_penny_summary, PennyWidgetProvider.layoutForSize(219, 110))
        assertEquals(R.layout.widget_penny_wide, PennyWidgetProvider.layoutForSize(220, 110))

        val heightSensitiveOptions =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            }
        assertEquals(
            R.layout.widget_penny_compact,
            PennyWidgetProvider.landscapeLayoutForOptions(heightSensitiveOptions),
        )
        assertEquals(
            R.layout.widget_penny_summary,
            PennyWidgetProvider.portraitLayoutForOptions(heightSensitiveOptions),
        )

        val widthSensitiveOptions =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 220)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            }
        assertEquals(
            R.layout.widget_penny_wide,
            PennyWidgetProvider.landscapeLayoutForOptions(widthSensitiveOptions),
        )
        assertEquals(
            R.layout.widget_penny_summary,
            PennyWidgetProvider.portraitLayoutForOptions(widthSensitiveOptions),
        )
    }

    @Test
    fun pre31OrientationContainerSelectsLandscapeThenPortraitChildren() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary =
            WidgetSummary(
                label = "This month",
                amount = "\$123.45",
                count = "4 txns",
                topCategory = "Food \$80.00",
                compactAmount = "\$123.45",
            )
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            }
        val orientationViews = PennyWidgetProvider.orientationViewsFor(context, summary, options)
        val landscapeContext =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                },
            )
        val portraitContext =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_PORTRAIT
                },
            )

        val landscapeRoot = orientationViews.apply(landscapeContext, null) as ViewGroup
        val portraitRoot = orientationViews.apply(portraitContext, null) as ViewGroup

        assertTrue(landscapeRoot.findViewById<View>(R.id.widget_count) == null)
        assertEquals(
            "4 txns · Food \$80.00",
            portraitRoot.findViewById<TextView>(R.id.widget_count).text.toString(),
        )
        listOf(landscapeRoot, portraitRoot).forEach { root ->
            assertEquals(summary.label, root.findViewById<TextView>(R.id.widget_spent_label).text.toString())
            assertEquals(summary.amount, root.findViewById<TextView>(R.id.widget_amount).text.toString())
            assertTrue(root.hasOnClickListeners())
            assertTrue(root.findViewById<View>(R.id.widget_add_button).hasOnClickListeners())
        }
    }

    @Test
    fun optionsChangedUpdatesOnlyBeforeApi31() {
        assertTrue(PennyWidgetProvider.shouldUpdateForOptionsChange(26))
        assertTrue(PennyWidgetProvider.shouldUpdateForOptionsChange(30))
        assertFalse(PennyWidgetProvider.shouldUpdateForOptionsChange(31))
        assertFalse(PennyWidgetProvider.shouldUpdateForOptionsChange(36))
    }

    @Test
    fun everyWidgetVariantReappliesTextAndClickTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary =
            WidgetSummary(
                label = "This month",
                amount = "\$1,234.56",
                count = "4 txns",
                topCategory = "Food \$80.00",
                topCategories = listOf("Food \$80.00", "Travel \$43.45", "Bills \$12.34"),
                compactAmount = "\$1.2K",
            )

        listOf(
            R.layout.widget_penny_compact,
            R.layout.widget_penny_summary,
            R.layout.widget_penny_wide,
        ).forEach { layoutId ->
            val root =
                PennyWidgetProvider
                    .viewsForLayout(context, summary, layoutId)
                    .apply(context, null) as ViewGroup

            assertEquals(summary.label, root.findViewById<TextView>(R.id.widget_spent_label).text.toString())
            val amount = root.findViewById<TextView>(R.id.widget_amount)
            assertEquals(
                if (layoutId == R.layout.widget_penny_compact) summary.compactAmount else summary.amount,
                amount.text.toString(),
            )
            assertEquals(summary.amount, amount.contentDescription.toString())
            assertEquals("+", root.findViewById<TextView>(R.id.widget_add_button).text.toString())
            assertTrue(root.hasOnClickListeners())
            assertTrue(root.findViewById<View>(R.id.widget_add_button).hasOnClickListeners())

            when (layoutId) {
                R.layout.widget_penny_summary -> {
                    assertEquals(
                        "4 txns · Food \$80.00",
                        root.findViewById<TextView>(R.id.widget_count).text.toString(),
                    )
                }

                R.layout.widget_penny_wide -> {
                    assertEquals("4 txns", root.findViewById<TextView>(R.id.widget_count).text.toString())
                    assertEquals(
                        "Food \$80.00\nTravel \$43.45\nBills \$12.34",
                        root.findViewById<TextView>(R.id.widget_categories).text.toString(),
                    )
                }
            }
        }
    }

    @Test
    fun failedSummaryLoadBindsLocalizedUnavailableIncludingCompactTalkBack() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val unavailable = context.getString(R.string.widget_unavailable)
            val compactUnavailable = context.getString(R.string.widget_unavailable_compact)
            val openApp = context.getString(R.string.widget_open_app)
            val fallback =
                PennyWidgetProvider.summaryOrFallback(context) { _ ->
                    throw IOException("Room read failed")
                }

            assertEquals(unavailable, fallback.amount)
            assertEquals(compactUnavailable, fallback.compactAmount)
            assertEquals(openApp, fallback.count)
            assertEquals(unavailable, fallback.topCategory)
            assertEquals(listOf(unavailable), fallback.topCategories)
            assertFalse(fallback.toString().contains("\$0.00"))

            listOf(
                R.layout.widget_penny_compact,
                R.layout.widget_penny_summary,
                R.layout.widget_penny_wide,
            ).forEach { layoutId ->
                val layoutName = context.resources.getResourceEntryName(layoutId)
                val expectedAmount =
                    if (layoutId == R.layout.widget_penny_compact) compactUnavailable else unavailable
                val defaultRoot = RemoteViews(context.packageName, layoutId).apply(context, null) as ViewGroup
                val defaultAmount = defaultRoot.findViewById<TextView>(R.id.widget_amount)
                assertEquals("layout $layoutName default amount", expectedAmount, defaultAmount.text.toString())

                when (layoutId) {
                    R.layout.widget_penny_compact -> {
                        assertEquals(
                            "layout $layoutName default TalkBack amount",
                            unavailable,
                            defaultAmount.contentDescription.toString(),
                        )
                        assertTrue(defaultRoot.findViewById<View>(R.id.widget_count) == null)
                        assertTrue(defaultRoot.findViewById<View>(R.id.widget_categories) == null)
                    }

                    R.layout.widget_penny_summary -> {
                        assertEquals(
                            "layout $layoutName default supporting text",
                            openApp,
                            defaultRoot.findViewById<TextView>(R.id.widget_count).text.toString(),
                        )
                        assertTrue(defaultRoot.findViewById<View>(R.id.widget_categories) == null)
                    }

                    R.layout.widget_penny_wide -> {
                        assertEquals(
                            "layout $layoutName default count",
                            openApp,
                            defaultRoot.findViewById<TextView>(R.id.widget_count).text.toString(),
                        )
                        assertEquals(
                            "layout $layoutName default categories",
                            unavailable,
                            defaultRoot.findViewById<TextView>(R.id.widget_categories).text.toString(),
                        )
                    }
                }

                val root =
                    PennyWidgetProvider
                        .viewsForLayout(context, fallback, layoutId)
                        .apply(context, null) as ViewGroup
                val amount = root.findViewById<TextView>(R.id.widget_amount)
                assertEquals(expectedAmount, amount.text.toString())
                assertEquals("fallback TalkBack amount", unavailable, amount.contentDescription.toString())
                when (layoutId) {
                    R.layout.widget_penny_compact -> {
                        assertTrue(root.findViewById<View>(R.id.widget_count) == null)
                        assertTrue(root.findViewById<View>(R.id.widget_categories) == null)
                    }

                    R.layout.widget_penny_summary -> {
                        assertEquals(
                            "$openApp · $unavailable",
                            root.findViewById<TextView>(R.id.widget_count).text.toString(),
                        )
                    }

                    R.layout.widget_penny_wide -> {
                        assertEquals(openApp, root.findViewById<TextView>(R.id.widget_count).text.toString())
                        assertEquals(
                            unavailable,
                            root.findViewById<TextView>(R.id.widget_categories).text.toString(),
                        )
                    }
                }
            }
        }

    @Test
    fun successfulZeroSummaryStillBindsExactZero() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val zero =
                WidgetSummary(
                    label = context.getString(R.string.widget_spent_this_month),
                    amount = MoneyFormatter.formatUsd(0),
                    compactAmount = formatCompactWidgetUsd(0),
                    count = context.resources.getQuantityString(R.plurals.widget_transaction_count, 0, 0),
                    topCategory = context.getString(R.string.widget_no_spend),
                )
            val loaded = PennyWidgetProvider.summaryOrFallback(context) { zero }

            assertEquals(zero, loaded)
            listOf(
                R.layout.widget_penny_compact,
                R.layout.widget_penny_summary,
                R.layout.widget_penny_wide,
            ).forEach { layoutId ->
                val root =
                    PennyWidgetProvider
                        .viewsForLayout(context, loaded, layoutId)
                        .apply(context, null) as ViewGroup
                val amount = root.findViewById<TextView>(R.id.widget_amount)
                assertEquals("\$0.00", amount.text.toString())
                assertEquals("\$0.00", amount.contentDescription.toString())
                when (layoutId) {
                    R.layout.widget_penny_summary -> {
                        assertEquals(
                            "0 txns · No spend",
                            root.findViewById<TextView>(R.id.widget_count).text.toString(),
                        )
                    }

                    R.layout.widget_penny_wide -> {
                        assertEquals("0 txns", root.findViewById<TextView>(R.id.widget_count).text.toString())
                        assertEquals(
                            "No spend",
                            root.findViewById<TextView>(R.id.widget_categories).text.toString(),
                        )
                    }
                }
            }
        }

    @Test
    fun manifestRefreshActionsResolveAndRouteExplicitlyToWidgetProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, PennyWidgetProvider::class.java)
        val provider = RecordingPennyWidgetProvider()
        val refreshActions =
            listOf(
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED,
            )

        // Apps cannot forge these protected system broadcasts, so resolve the real manifest filter
        // and invoke the explicitly targeted receiver to cover the app-owned delivery path.
        refreshActions.forEachIndexed { index, action ->
            assertReceiverDeclaresAction(context, component, action)
            val explicitIntent = Intent(action).setComponent(component)
            assertEquals(component, explicitIntent.component)
            provider.onReceive(context, explicitIntent)
            assertEquals(index + 1, provider.refreshDispatchCount)
            assertEquals(0, provider.frameworkUpdateCount)
        }

        assertReceiverDeclaresAction(context, component, AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        provider.onReceive(
            context,
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(42)),
        )
        assertEquals(refreshActions.size, provider.refreshDispatchCount)
        assertEquals(1, provider.frameworkUpdateCount)
        assertArrayEquals(intArrayOf(42), provider.frameworkUpdateWidgetIds)
    }

    @Test
    fun refreshBroadcastsExcludeAppWidgetUpdateFromCustomProcessing() {
        assertTrue(PennyWidgetProvider.isRefreshBroadcast(Intent.ACTION_DATE_CHANGED))
        assertTrue(PennyWidgetProvider.isRefreshBroadcast(Intent.ACTION_TIME_CHANGED))
        assertTrue(PennyWidgetProvider.isRefreshBroadcast(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(PennyWidgetProvider.isRefreshBroadcast(Intent.ACTION_LOCALE_CHANGED))
        assertFalse(PennyWidgetProvider.isRefreshBroadcast(AppWidgetManager.ACTION_APPWIDGET_UPDATE))
    }

    @Test
    fun widgetDayNightColorsMeetWcagTextContrast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val surfaces = mutableListOf<Int>()
        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
            val configuration =
                Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            val configuredContext = context.createConfigurationContext(configuration)
            val surface = configuredContext.getColor(R.color.widget_surface_inverse)
            val quickAdd = configuredContext.getColor(R.color.widget_quick_add)
            surfaces += surface
            assertContrastAtLeast4Point5(
                configuredContext.getColor(R.color.widget_on_surface_inverse),
                surface,
            )
            assertContrastAtLeast4Point5(
                configuredContext.getColor(R.color.widget_on_surface_inverse_muted),
                surface,
            )
            assertContrastAtLeast4Point5(
                configuredContext.getColor(R.color.widget_on_quick_add),
                quickAdd,
            )
        }
        assertFalse(surfaces[0] == surfaces[1])
    }

    @Test
    fun widgetDestinationsUseDistinctExplicitImmutableIntents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overview = PennyWidgetProvider.overviewIntent(context)
        val add = PennyWidgetProvider.addTransactionIntent(context)
        val expectedFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        assertEquals(ComponentName(context, MainActivity::class.java), overview.component)
        assertEquals(ComponentName(context, MainActivity::class.java), add.component)
        assertEquals(expectedFlags, overview.flags and expectedFlags)
        assertEquals(expectedFlags, add.flags and expectedFlags)
        assertTrue(overview.getBooleanExtra(EXTRA_OPEN_OVERVIEW, false))
        assertFalse(overview.hasExtra(PennyWidgetProvider.EXTRA_OPEN_ADD_TRANSACTION))
        assertTrue(add.getBooleanExtra(PennyWidgetProvider.EXTRA_OPEN_ADD_TRANSACTION, false))
        assertFalse(add.hasExtra(EXTRA_OPEN_OVERVIEW))
        assertTrue(PennyWidgetProvider.OVERVIEW_REQUEST_CODE != PennyWidgetProvider.ADD_REQUEST_CODE)

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val overviewPending =
            PendingIntent.getActivity(
                context,
                PennyWidgetProvider.OVERVIEW_REQUEST_CODE,
                overview,
                pendingFlags,
            )
        val addPending =
            PendingIntent.getActivity(
                context,
                PennyWidgetProvider.ADD_REQUEST_CODE,
                add,
                pendingFlags,
            )
        assertTrue(overviewPending.isImmutable)
        assertTrue(addPending.isImmutable)
        assertFalse(overviewPending == addPending)
        overviewPending.cancel()
        addPending.cancel()
    }

    @Test
    fun coldStartQuickAddWaitsForLearnedHistoryAndRequiresAnAmount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("room_migrated", true)
            .commit()
        val database = FlowMoneyDatabase.get(context)
        runBlocking {
            database.transactionDao().upsertAll(
                listOf(
                    TransactionEntity(
                        id = "learned-travel",
                        occurredAtEpochMillis = 1_765_000_000_000,
                        merchant = "Should not be seeded",
                        category = "Travel",
                        note = "Should not be seeded",
                        cents = -4_200,
                        recurringInterval = null,
                    ),
                ),
            )
        }
        val sqlDatabase = database.openHelper.writableDatabase
        sqlDatabase.beginTransaction()
        var transactionOpen = true
        val scenario = ActivityScenario.launch<MainActivity>(PennyWidgetProvider.addTransactionIntent(context))

        try {
            composeRule.onNodeWithTag("app_loading_state").assertIsDisplayed()
            composeRule.onNodeWithTag("transaction_editor").assertDoesNotExist()

            sqlDatabase.endTransaction()
            transactionOpen = false

            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
                }.isSuccess
            }
            composeRule.onNodeWithTag("merchant_field").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            composeRule.onNodeWithTag("category_chip_travel").performScrollTo().assertIsSelected()
            composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("amount_display"))
            composeRule.onNodeWithTag("amount_display").assertTextContains("-\$0.00")
            composeRule.onNodeWithTag("save_transaction_button").performClick()
            composeRule.onNodeWithText("Enter an amount").assertIsDisplayed()
            assertTrue(runBlocking { database.transactionDao().getAll() }.size == 1)
        } finally {
            if (transactionOpen) sqlDatabase.endTransaction()
            scenario.close()
            FlowMoneyDatabase.resetForTest()
        }
    }

    @Test
    fun restoredLaunchIntentDoesNotReplayQuickAddAndLiveTapStillRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("room_migrated", true)
            .commit()
        val scenario = ActivityScenario.launch<MainActivity>(PennyWidgetProvider.addTransactionIntent(context))

        try {
            composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
            composeRule.onNodeWithTag("amount_key_1").performClick()
            composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Restored widget draft")

            // Process restoration can recreate the framework's original launch intent, including its route extra.
            scenario.onActivity { activity ->
                activity.intent.putExtra(PennyWidgetProvider.EXTRA_OPEN_ADD_TRANSACTION, true)
            }
            scenario.recreate()

            composeRule.onNodeWithTag("merchant_field").assertTextContains("Restored widget draft")
            composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()

            scenario.onActivity { activity ->
                activity.onNewIntent(PennyWidgetProvider.addTransactionIntent(activity))
            }
            composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
            composeRule.onNodeWithText("Keep editing").performClick()
            composeRule.onNodeWithTag("merchant_field").assertTextContains("Restored widget draft")
        } finally {
            scenario.close()
            FlowMoneyDatabase.resetForTest()
        }
    }

    @Test
    fun restoredLaunchIntentDoesNotReplayOverviewAndLiveTapStillRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("room_migrated", true)
            .commit()
        val scenario = ActivityScenario.launch<MainActivity>(PennyWidgetProvider.overviewIntent(context))

        try {
            composeRule.onNodeWithTag("tab_transactions").performClick().assertIsSelected()

            // Mirror a process-restored launch intent rather than the in-process object whose extra was removed.
            scenario.onActivity { activity ->
                activity.intent.putExtra(EXTRA_OPEN_OVERVIEW, true)
            }
            scenario.recreate()

            composeRule.onNodeWithTag("tab_transactions").assertIsSelected()
            scenario.onActivity { activity ->
                activity.onNewIntent(PennyWidgetProvider.overviewIntent(activity))
            }
            composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        } finally {
            scenario.close()
            FlowMoneyDatabase.resetForTest()
        }
    }

    @Test
    fun onNewIntentRoutesExistingActivityToDistinctDestinations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("room_migrated", true)
            .commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithTag("tab_transactions").performClick().assertIsSelected()
        scenario.onActivity { activity ->
            activity.onNewIntent(PennyWidgetProvider.overviewIntent(activity))
        }
        composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        scenario.onActivity { activity ->
            activity.onNewIntent(PennyWidgetProvider.addTransactionIntent(activity))
        }
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("amount_key_1").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Dirty widget draft")

        scenario.onActivity { activity ->
            activity.onNewIntent(PennyWidgetProvider.addTransactionIntent(activity))
        }
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithTag("merchant_field").assertTextContains("Dirty widget draft")

        scenario.onActivity { activity ->
            activity.onNewIntent(PennyWidgetProvider.addTransactionIntent(activity))
        }
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithTag("merchant_field").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("amount_display"))
        composeRule.onNodeWithTag("amount_display").assertTextContains("-$0.00")
        scenario.onActivity { it.finishAndRemoveTask() }
    }

    private class RecordingPennyWidgetProvider : PennyWidgetProvider() {
        var refreshDispatchCount = 0
            private set
        var frameworkUpdateCount = 0
            private set
        var frameworkUpdateWidgetIds = intArrayOf()
            private set

        override fun launchBroadcastUpdate(update: suspend () -> Unit) {
            refreshDispatchCount += 1
        }

        override fun onUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            frameworkUpdateCount += 1
            frameworkUpdateWidgetIds = appWidgetIds.copyOf()
        }
    }

    @Suppress("DEPRECATION")
    private fun assertReceiverDeclaresAction(
        context: Context,
        component: ComponentName,
        action: String,
    ) {
        val match =
            context.packageManager
                .queryBroadcastReceivers(
                    Intent(action).setPackage(context.packageName),
                    PackageManager.GET_RESOLVED_FILTER,
                ).firstOrNull {
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name) == component
                }
        assertTrue("$action is not declared for $component", match != null)
        assertTrue("$action did not resolve through its manifest filter", match?.filter?.hasAction(action) == true)
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
        ellipsisPermitted: Boolean,
    ) {
        val layout = requireNotNull(textView.layout) { "$description has no text layout" }
        assertTrue("$description exceeds maxLines", layout.lineCount <= textView.maxLines)
        assertTrue(
            "$description text layout height ${layout.height} exceeds content height " +
                "${textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom}",
            layout.height <= textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom,
        )
        if (ellipsisPermitted) {
            assertEquals("$description truncation policy", TextUtils.TruncateAt.END, textView.ellipsize)
        } else {
            repeat(layout.lineCount) { line ->
                assertEquals("$description ellipsized line $line", 0, layout.getEllipsisCount(line))
            }
            assertEquals(
                "$description did not lay out all required text",
                textView.text.length,
                layout.getLineEnd(layout.lineCount - 1),
            )
        }
    }

    private fun assertNoEllipsis(
        description: String,
        textView: TextView,
    ) {
        val layout = requireNotNull(textView.layout) { "$description has no text layout" }
        repeat(layout.lineCount) { line ->
            assertEquals("$description ellipsized line $line", 0, layout.getEllipsisCount(line))
        }
        assertEquals(
            "$description did not lay out all bound text",
            textView.text.length,
            layout.getLineEnd(layout.lineCount - 1),
        )
    }

    private fun assertHasEllipsis(
        description: String,
        textView: TextView,
    ) {
        val layout = requireNotNull(textView.layout) { "$description has no text layout" }
        assertEquals("$description truncation policy", TextUtils.TruncateAt.END, textView.ellipsize)
        assertTrue(
            "$description unexpectedly fit; the worst-case fixture must exercise truncation",
            (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 },
        )
    }

    private fun assertUniformTextMinimum(
        description: String,
        textView: TextView,
        context: Context,
        minimumSp: Float,
    ) {
        val metrics = context.resources.displayMetrics
        val configuredMinSp = pixelsToSp(textView.autoSizeMinTextSize.toFloat(), metrics)
        val actualSp = pixelsToSp(textView.textSize, metrics)
        assertEquals(
            "$description autosize type",
            TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM,
            textView.autoSizeTextType,
        )
        assertTrue(
            "$description configured minimum was ${configuredMinSp}sp",
            configuredMinSp + 0.25f >= minimumSp,
        )
        assertTrue(
            "$description rendered below ${minimumSp}sp at ${actualSp}sp",
            actualSp + 0.25f >= minimumSp,
        )
    }

    private fun assertCompactRoundedCardGeometry(
        description: String,
        root: ViewGroup,
        visibleText: List<TextView>,
        density: Float,
    ) {
        val requiredInset = (8 * density + 0.5f).toInt()
        val requiredGap = (4 * density + 0.5f).toInt()
        assertTrue("$description left inset", root.paddingLeft >= requiredInset)
        assertTrue("$description right inset", root.paddingRight >= requiredInset)

        val textColumnBounds = descendantBounds(root, root.getChildAt(0))
        val addBounds = descendantBounds(root, root.findViewById(R.id.widget_add_button))
        assertTrue(
            "$description add pill gap was ${addBounds.left - textColumnBounds.right}px",
            addBounds.left - textColumnBounds.right >= requiredGap,
        )

        val background = root.background as GradientDrawable
        val cornerRadius = background.cornerRadius
        val safeInset = minOf(root.paddingLeft, root.paddingRight).toFloat()
        assertTrue(
            "$description corner radius $cornerRadius exceeds safe inset $safeInset",
            cornerRadius <= safeInset + 0.5f,
        )
        visibleText.forEach { textView ->
            val bounds = descendantBounds(root, textView)
            assertTrue(
                "$description ${viewDescription(root.context, textView)} can draw left of the rounded card: $bounds",
                bounds.left + 0.5f >= cornerRadius,
            )
            assertTrue(
                "$description ${viewDescription(root.context, textView)} can draw right of the rounded card: $bounds",
                bounds.right - 0.5f <= root.width - cornerRadius,
            )
        }
    }

    private fun assertUniformAddButtonAutoSize(
        description: String,
        addButton: TextView,
        context: Context,
    ) {
        val metrics = context.resources.displayMetrics
        val minSp = pixelsToSp(addButton.autoSizeMinTextSize.toFloat(), metrics)
        val maxSp = pixelsToSp(addButton.autoSizeMaxTextSize.toFloat(), metrics)
        assertEquals("$description add autosize type", TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, addButton.autoSizeTextType)
        assertEquals("$description add autosize minimum", 14f, minSp, 0.25f)
        assertEquals("$description add autosize maximum", 23f, maxSp, 0.25f)
        assertTrue(
            "$description add text below autosize minimum",
            addButton.textSize + 0.5f >= addButton.autoSizeMinTextSize,
        )
        assertTrue(
            "$description add text above autosize maximum",
            addButton.textSize - 0.5f <= addButton.autoSizeMaxTextSize,
        )
    }

    private fun pixelsToSp(
        pixels: Float,
        metrics: android.util.DisplayMetrics,
    ): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, pixels, metrics)
        } else {
            @Suppress("DEPRECATION")
            pixels / metrics.scaledDensity
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

    private fun descendantBounds(
        root: ViewGroup,
        child: View,
    ): Rect =
        Rect(0, 0, child.width, child.height).also {
            root.offsetDescendantRectToMyCoords(child, it)
        }

    private data class WidgetLayoutCase(
        val name: String,
        val layoutId: Int,
        val widthDp: Int,
        val heightDp: Int,
        val requiredText: Map<Int, String>,
        val permittedEllipsisIds: Set<Int> = emptySet(),
    )

    private fun assertContrastAtLeast4Point5(
        foreground: Int,
        background: Int,
    ) {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        val contrast = (lighter + 0.05) / (darker + 0.05)
        assertTrue("Expected contrast >= 4.5:1, was $contrast:1", contrast >= 4.5)
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
