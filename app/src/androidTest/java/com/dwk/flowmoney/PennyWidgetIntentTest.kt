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
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
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

@RunWith(AndroidJUnit4::class)
class PennyWidgetIntentTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun everyWidgetLayoutFitsAtSupportedFontScales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val categoryRows = listOf("Food \$80.00", "Travel \$43.45", "Bills \$12.34")
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
                            R.id.widget_count to "4 txns · Food \$80.00",
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
                            R.id.widget_count to "4 txns",
                            R.id.widget_categories to categoryRows.joinToString("\n"),
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
                    val requiredText = case.requiredText + (R.id.widget_amount to expectedAmount)

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
                        if (textView.id == R.id.widget_amount && fontScale == 1f) {
                            assertTrue(
                                "$textDescription should remain at least 11dp at the default font scale",
                                textView.textSize / density >= 11f,
                            )
                        }
                        if (textView.id == R.id.widget_categories) {
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

                    val addButton = root.findViewById<TextView>(R.id.widget_add_button)
                    val addBounds = descendantBounds(root, addButton)
                    assertEquals("$description add width", addSize, addBounds.width())
                    assertEquals("$description add height", addSize, addBounds.height())
                    assertUniformAddButtonAutoSize(description, addButton, configuredContext)
                }
            }
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
        val summary = WidgetSummary("This month", "\$123.45", "4 txns", "Food \$80.00")
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
            assertEquals(
                if (layoutId == R.layout.widget_penny_compact) summary.compactAmount else summary.amount,
                root.findViewById<TextView>(R.id.widget_amount).text.toString(),
            )
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
