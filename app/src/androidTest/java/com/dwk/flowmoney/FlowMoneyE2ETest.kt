package com.dwk.flowmoney

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class FlowMoneyE2ETest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val cleanPreLaunchState =
        TestRule { base, _: Description ->
            object : Statement() {
                override fun evaluate() {
                    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                    FlowMoneyDatabase.resetForTest()
                    context.deleteDatabase("flow_money.db")
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME).deleteRecursively()
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME).deleteRecursively()
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.ROLLBACK_FILE_NAME).deleteRecursively()
                    clearPreferences(context, "flow_money")
                    clearPreferences(context, SimpleFinMigrationCleanup.PREFERENCES)
                    clearPreferences(context, SIMPLEFIN_SCHEDULE_PREFERENCES)
                    check(readPreferredSyncTime(context) == null) { "A stale preferred sync time leaked into the test" }
                    check(!context.getDatabasePath("flow_money.db").exists())
                    check(!File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME).exists())
                    check(!File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME).exists())
                    check(!File(context.noBackupFilesDir, SimpleFinCredentialStore.ROLLBACK_FILE_NAME).exists())
                    base.evaluate()
                }
            }
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(cleanPreLaunchState).around(composeRule)

    @Test
    fun automaticSyncFailureMessagesDistinguishPersistedSchedulingFailure() {
        assertEquals(
            "Schedule saved, but automatic scheduling could not be updated. Try again.",
            automaticSyncScheduleUpdateFailureMessage(
                SimpleFinAutomaticSchedulingException(IllegalStateException("schedule failed")),
            ),
        )
        assertEquals(
            "Could not update automatic sync schedule",
            automaticSyncScheduleUpdateFailureMessage(IllegalStateException("not persisted")),
        )
    }

    @Test
    fun appAndWidgetInstallSmokeTestOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(PackageName)
        assertNotNull("Penny launch intent should exist", launchIntent)

        val widgetIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(PackageName)
        val widgetReceivers = context.packageManager.queryBroadcastReceivers(widgetIntent, 0)
        assertTrue(
            "Penny widget provider should be registered in the installed APK",
            widgetReceivers.any { it.activityInfo.name == PennyWidgetProvider::class.java.name },
        )

        val widgetComponent = ComponentName(context, PennyWidgetProvider::class.java)
        assertNotNull("Penny widget component should resolve", context.packageManager.getReceiverInfo(widgetComponent, 0))

        composeRule.onNodeWithText("Penny").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_token").assertDoesNotExist()
        composeRule.onNodeWithTag("simplefin_reconnect_message").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_insights").performClick()
        composeRule.onNodeWithText("No spending in this range").assertIsDisplayed()
        composeRule.onNodeWithTag("spending_timeline_chart").assertDoesNotExist()
    }

    @Test
    fun simpleFinFlowReachesSetupTokenEntryWithoutCredentials() {
        composeRule.onNodeWithText("Data").performClick()

        val localData = composeRule.onNodeWithTag("local_data_card").assertIsDisplayed().fetchSemanticsNode()
        val csvImport = composeRule.onNodeWithTag("csv_import_button").assertIsDisplayed().fetchSemanticsNode()
        val csvExport = composeRule.onNodeWithTag("csv_export_button").assertIsDisplayed().fetchSemanticsNode()
        val setup = composeRule.onNodeWithTag("simplefin_setup_card").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(localData.boundsInRoot.top < setup.boundsInRoot.top)
        assertTrue(csvImport.boundsInRoot.top < setup.boundsInRoot.top)
        assertTrue(csvExport.boundsInRoot.top < setup.boundsInRoot.top)
        composeRule.onNodeWithTag("simplefin_setup_token").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bank_sync_card").assertDoesNotExist()
        composeRule.onNodeWithTag("connected_accounts_card").assertDoesNotExist()
    }

    @Test
    fun activeSimpleFinProfileShowsBankAccountAndLocalDataWithoutCredential() {
        val accountName = "Synthetic Active Checking"
        seedSimpleFinProfile(isPaused = false, accountName = accountName)
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Data").performClick()
        composeRule.onNodeWithTag("bank_sync_card").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_sync_button").assertIsDisplayed()
        // The connected card is tall enough that the cards below it are not composed yet, so walk
        // the lazy list rather than scrolling within an already-composed subtree.
        composeRule.onNodeWithTag("data_sheet_list").performScrollToNode(hasTestTag("connected_accounts_card"))
        composeRule.onNodeWithTag("connected_accounts_card").assertIsDisplayed()
        composeRule.onNodeWithText(accountName).assertIsDisplayed()
        composeRule.onNodeWithTag("data_sheet_list").performScrollToNode(hasTestTag("local_data_card"))
        composeRule.onNodeWithTag("local_data_card").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_import_button").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_export_button").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_card").assertDoesNotExist()
        composeRule.onNodeWithTag("simplefin_setup_token").assertDoesNotExist()
    }

    @Test
    fun activeSimpleFinProfileShowsAndPersistsAutomaticSyncFrequency() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        seedSimpleFinProfile(isPaused = false, automaticSyncsPerDay = 4)
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Data").performClick()
        composeRule
            .onNodeWithTag("simplefin_auto_sync_frequency_value")
            .performScrollTo()
            .assertTextEquals("4 syncs per day")
        composeRule.onNodeWithTag("simplefin_auto_sync_frequency_action").performClick()
        composeRule.onNodeWithTag("simplefin_auto_sync_frequency_option_7").performClick()

        composeRule.waitUntil(5_000) {
            runBlocking {
                FlowMoneyDatabase
                    .get(context)
                    .simpleFinDao()
                    .getProfile()
                    ?.automaticSyncsPerDay == 7
            }
        }
        composeRule
            .onNodeWithTag("simplefin_auto_sync_frequency_value")
            .assertTextEquals("7 syncs per day")
    }

    /**
     * The preferred sync time is the only schedule input that lives outside the profile row, so the
     * picker, the visible value, and the SharedPreferences record all have to agree — including
     * after the user clears it back to "any time".
     */
    @Test
    fun activeSimpleFinProfileShowsAndPersistsPreferredSyncTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        seedSimpleFinProfile(isPaused = false, automaticSyncsPerDay = 4)
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Data").performClick()
        composeRule
            .onNodeWithTag("simplefin_sync_time_value")
            .performScrollTo()
            .assertTextEquals("Any time")
        composeRule.onNodeWithTag("simplefin_sync_time_clear").assertDoesNotExist()

        // The picker opens on the current clock time, so the accepted value must land inside the
        // window the dialog was on screen.
        val beforePicker = LocalTime.now().withSecond(0).withNano(0)
        composeRule.onNodeWithTag("simplefin_sync_time_action").performClick()
        composeRule.onNodeWithText("Select time").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        val afterPicker = LocalTime.now().withSecond(0).withNano(0)

        composeRule.waitUntil(5_000) { readPreferredSyncTime(context) != null }
        val persisted = readPreferredSyncTime(context)!!
        assertTrue(
            "Preferred sync time $persisted should be the time the picker opened on ($beforePicker..$afterPicker)",
            persisted == beforePicker || persisted == afterPicker,
        )
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule
                    .onNodeWithTag("simplefin_sync_time_value")
                    .assertTextEquals(persisted.format(SyncTimeFormatter))
            }.isSuccess
        }
        composeRule
            .onNodeWithTag("simplefin_sync_time_value")
            .performScrollTo()
            .assertTextEquals(persisted.format(SyncTimeFormatter))

        composeRule.onNodeWithTag("simplefin_sync_time_clear").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { readPreferredSyncTime(context) == null }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("simplefin_sync_time_value").assertTextEquals("Any time")
            }.isSuccess
        }
        composeRule.onNodeWithTag("simplefin_sync_time_clear").assertDoesNotExist()
        // Clearing the time must not disturb the cadence stored on the profile.
        assertEquals(
            4,
            runBlocking { FlowMoneyDatabase.get(context).simpleFinDao().getProfile()?.automaticSyncsPerDay },
        )
    }

    @Test
    fun pausedSimpleFinProfileShowsReconnectAndLocalDataWithoutCredential() {
        seedSimpleFinProfile(isPaused = true)
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Data").performClick()
        composeRule.onNodeWithTag("simplefin_setup_card").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reconnect_message").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_setup_token").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bank_sync_card").assertDoesNotExist()
        composeRule.onNodeWithTag("simplefin_sync_button").assertDoesNotExist()
        composeRule.onNodeWithTag("connected_accounts_card").assertDoesNotExist()
        composeRule.onNodeWithTag("local_data_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("csv_import_button").assertIsDisplayed()
        composeRule.onNodeWithTag("csv_export_button").assertIsDisplayed()
    }

    @Test
    fun transactionFiltersShowMatchingMerchantWithoutClearingData() {
        val merchant = "Credential-free Cafe"

        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_1").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput(merchant)
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.onNodeWithTag("tab_transactions").performClick()
        waitForMerchant(merchant)
        composeRule.activityRule.scenario.recreate()
        waitForMerchant(merchant)
        composeRule.onNodeWithTag("transaction_where_filter").assertIsDisplayed().performTextInput(merchant)
        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        composeRule.onNodeWithTag("transaction_filter_week").performClick()
        composeRule.onNodeWithTag("transaction_category_filter_food").performClick()

        composeRule.onAllNodesWithText(merchant).onFirst().assertIsDisplayed()
    }

    private fun seedSimpleFinProfile(
        isPaused: Boolean,
        accountName: String? = null,
        automaticSyncsPerDay: Int = 1,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        runBlocking {
            val dao = FlowMoneyDatabase.get(context).simpleFinDao()
            dao.upsertProfile(
                SimpleFinProfileEntity(
                    connectionId = "synthetic-${if (isPaused) "paused" else "active"}",
                    connectedAtEpochMillis = now,
                    lastSyncAttemptAtEpochMillis = now,
                    lastSuccessfulSyncAtEpochMillis = now,
                    lastError = if (isPaused) "SimpleFIN reconnect required" else null,
                    isPaused = isPaused,
                    automaticSyncsPerDay = automaticSyncsPerDay,
                ),
            )
            accountName?.let {
                dao.upsertAccounts(
                    listOf(
                        SimpleFinAccountEntity(
                            accountId = "synthetic-active-account",
                            name = it,
                            currency = "USD",
                            institutionName = "Synthetic Bank",
                            balanceAmount = null,
                            availableBalanceAmount = null,
                            balanceDateEpochSeconds = null,
                            lastSeenAtEpochMillis = now,
                        ),
                    ),
                )
            }
        }
    }

    private fun waitForMerchant(merchant: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(merchant).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(merchant).onFirst().assertIsDisplayed()
    }

    private companion object {
        const val PackageName = "com.dwk.flowmoney"
        val SyncTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun clearPreferences(
            context: Context,
            name: String,
        ) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            check(preferences.edit().clear().commit()) { "Could not clear $name preferences" }
            check(preferences.all.isEmpty()) { "$name preferences were not cleared" }
        }
    }
}
