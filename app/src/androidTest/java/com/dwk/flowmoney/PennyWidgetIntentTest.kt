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
import android.os.Bundle
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
    fun compactWidgetFitsTwoByOneAtTwoHundredPercentFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val configuredContext = context.createConfigurationContext(configuration)
        val root =
            PennyWidgetProvider
                .viewsForLayout(
                    configuredContext,
                    WidgetSummary("This month", "\$123.45", "4 txns", "Food \$80.00"),
                    R.layout.widget_penny_compact,
                ).apply(configuredContext, null) as ViewGroup
        val density = configuredContext.resources.displayMetrics.density
        val width = (110 * density + 0.5f).toInt()
        val height = (48 * density + 0.5f).toInt()
        val addSize = (48 * density + 0.5f).toInt()

        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(width, root.measuredWidth)
        assertEquals(height, root.measuredHeight)

        val month = root.findViewById<View>(R.id.widget_spent_label)
        val amount = root.findViewById<View>(R.id.widget_amount)
        val add = root.findViewById<View>(R.id.widget_add_button)
        val monthBounds = descendantBounds(root, month)
        val amountBounds = descendantBounds(root, amount)
        val addBounds = descendantBounds(root, add)

        listOf(monthBounds, amountBounds, addBounds).forEach { bounds ->
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
            assertTrue(bounds.left >= 0 && bounds.top >= 0)
            assertTrue(bounds.right <= root.width && bounds.bottom <= root.height)
        }
        assertEquals(addSize, addBounds.width())
        assertEquals(addSize, addBounds.height())
        assertTrue(monthBounds.right <= addBounds.left)
        assertTrue(amountBounds.right <= addBounds.left)
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
                amount = "\$123.45",
                count = "4 txns",
                topCategory = "Food \$80.00",
                topCategories = listOf("Food \$80.00", "Travel \$43.45"),
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
            assertEquals(summary.amount, root.findViewById<TextView>(R.id.widget_amount).text.toString())
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
                        "Food \$80.00\nTravel \$43.45",
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

        override fun launchBroadcastUpdate(update: suspend () -> Unit) {
            refreshDispatchCount += 1
        }

        override fun onUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            frameworkUpdateCount += 1
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

    private fun descendantBounds(
        root: ViewGroup,
        child: View,
    ): Rect =
        Rect(0, 0, child.width, child.height).also {
            root.offsetDescendantRectToMyCoords(child, it)
        }

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
