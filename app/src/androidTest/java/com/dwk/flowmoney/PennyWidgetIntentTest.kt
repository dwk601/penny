package com.dwk.flowmoney

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
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
    fun pennySummaryFitsSmallWidgetAtLargeFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 1.3f }
        val configuredContext = context.createConfigurationContext(configuration)
        val root =
            RemoteViews(context.packageName, R.layout.widget_penny_summary)
                .apply(configuredContext, null) as ViewGroup
        val density = configuredContext.resources.displayMetrics.density
        val size = (110 * density + 0.5f).toInt()
        val addSize = (48 * density + 0.5f).toInt()

        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(size, root.measuredWidth)
        assertEquals(size, root.measuredHeight)

        val month = root.findViewById<View>(R.id.widget_spent_label)
        val amount = root.findViewById<View>(R.id.widget_amount)
        val support = root.findViewById<View>(R.id.widget_count)
        val add = root.findViewById<View>(R.id.widget_add_button)
        val monthBounds =
            Rect(0, 0, month.width, month.height).also {
                root.offsetDescendantRectToMyCoords(month, it)
            }
        val amountBounds =
            Rect(0, 0, amount.width, amount.height).also {
                root.offsetDescendantRectToMyCoords(amount, it)
            }
        val supportBounds =
            Rect(0, 0, support.width, support.height).also {
                root.offsetDescendantRectToMyCoords(support, it)
            }
        val addBounds =
            Rect(0, 0, add.width, add.height).also {
                root.offsetDescendantRectToMyCoords(add, it)
            }

        assertTrue(month.visibility == View.VISIBLE && monthBounds.width() > 0 && monthBounds.height() > 0)
        assertTrue(amount.visibility == View.VISIBLE && amountBounds.width() > 0 && amountBounds.height() > 0)
        assertTrue(support.visibility == View.VISIBLE && supportBounds.width() > 0 && supportBounds.height() > 0)
        assertTrue(add.visibility == View.VISIBLE && addBounds.width() > 0 && addBounds.height() > 0)
        listOf(monthBounds, amountBounds, supportBounds, addBounds).forEach { bounds ->
            assertTrue(bounds.left >= 0 && bounds.top >= 0)
            assertTrue(bounds.right <= root.width && bounds.bottom <= root.height)
        }
        assertEquals(addSize, addBounds.width())
        assertEquals(addSize, addBounds.height())
        assertTrue(monthBounds.right <= addBounds.left)
        assertTrue(
            listOf(monthBounds, amountBounds, supportBounds, addBounds).all {
                it.top >= 0 && it.bottom <= root.height
            },
        )
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
}
