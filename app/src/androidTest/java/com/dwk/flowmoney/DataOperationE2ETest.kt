package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class DataOperationE2ETest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val cleanState = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                FlowMoneyDatabase.resetForTest()
                context.deleteDatabase("flow_money.db")
                File(context.noBackupFilesDir, "simplefin_access_url.bin").deleteRecursively()
                context.getSharedPreferences("flow_money", Context.MODE_PRIVATE).edit()
                    .clear().putBoolean("room_migrated", true).commit()
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(cleanState).around(composeRule)

    @Test
    fun cancelledCsvPickersReenableDataControls() {
        openData()
        cancelPicker("csv_import_button")
        cancelPicker("csv_export_button")
    }

    @Test
    fun disconnectConfirmationCancelLeavesConnectionStateAndConfirmPreservesTransactions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = TransactionEntity(
            id = "local-transaction",
            occurredAtEpochMillis = 2_000L,
            merchant = "Local cafe",
            category = "Food",
            note = "",
            cents = -450,
        )
        val simpleFin = TransactionEntity(
            id = "simplefin-transaction",
            occurredAtEpochMillis = 1_000L,
            merchant = "Bank market",
            category = "Groceries",
            note = "",
            cents = -1200,
            source = "simplefin",
            accountKey = "account-1",
            accountName = "Checking",
        )
        runBlocking {
            FlowMoneyDatabase.get(context).apply {
                simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "phase3"))
                simpleFinDao().upsertAccounts(
                    listOf(
                        SimpleFinAccountEntity(
                            accountId = "account-1",
                            name = "Checking",
                            currency = "USD",
                            institutionName = "Test Bank",
                            balanceAmount = "100.00",
                            availableBalanceAmount = "100.00",
                            balanceDateEpochSeconds = 1L,
                            lastSeenAtEpochMillis = 1_000L,
                        ),
                    ),
                )
                transactionDao().upsertAll(listOf(local, simpleFin))
            }
        }
        SimpleFinCredentialStore(context).save("phase3", "https://example.test/access")
        composeRule.activityRule.scenario.recreate()
        openData()
        composeRule.onNodeWithTag("simplefin_disconnect_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Disconnect bank?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertNotNull(runBlocking { FlowMoneyDatabase.get(context).simpleFinDao().getProfile() })

        composeRule.onNodeWithTag("simplefin_disconnect_button").performClick()
        composeRule.onNodeWithTag("confirm_disconnect_button").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { FlowMoneyDatabase.get(context).simpleFinDao().getProfile() } == null
        }
        assertNull(runBlocking { FlowMoneyDatabase.get(context).simpleFinDao().getProfile() })
        assertEquals(emptyList<SimpleFinAccountEntity>(), runBlocking {
            FlowMoneyDatabase.get(context).simpleFinDao().observeAccounts().first()
        })
        assertNull(SimpleFinCredentialStore(context).read("phase3"))
        assertEquals(listOf(local, simpleFin), runBlocking {
            FlowMoneyDatabase.get(context).transactionDao().getAll()
        })
    }

    private fun openData() {
        composeRule.onNodeWithText("Data").performClick()
        composeRule.onNodeWithTag("local_data_card").assertIsDisplayed()
    }

    private fun cancelPicker(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).assertIsEnabled()
    }
}
