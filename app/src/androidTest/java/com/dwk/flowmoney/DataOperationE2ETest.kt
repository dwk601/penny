package com.dwk.flowmoney

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val (pickerDescription, pickerResource) = when (tag) {
            "csv_import_button" -> "open-document picker" to "dir_list"
            "csv_export_button" -> "create-document picker" to "container_save"
            else -> error("No picker expectation configured for $tag")
        }

        composeRule.onNodeWithTag(tag).performClick()
        assertDeviceState(
            waitForForegroundPackage(device, DOCUMENTS_UI_PACKAGE, 5_000),
            device,
            "Expected $pickerDescription in $DOCUMENTS_UI_PACKAGE before cancelling $tag",
        )
        assertDeviceState(
            device.currentPackageName == DOCUMENTS_UI_PACKAGE &&
                device.hasObject(By.pkg(DOCUMENTS_UI_PACKAGE)) &&
                device.hasObject(By.res(DOCUMENTS_UI_PACKAGE, pickerResource)),
            device,
            "Expected $pickerDescription resource $pickerResource before cancelling $tag",
        )
        device.pressBack()
        assertDeviceState(
            waitForForegroundPackage(device, APP_PACKAGE, 5_000),
            device,
            "Expected $APP_PACKAGE after cancelling $pickerDescription for $tag",
        )

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).assertIsEnabled()
    }

    private fun waitForForegroundPackage(
        device: UiDevice,
        expectedPackage: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (device.currentPackageName == expectedPackage && device.hasObject(By.pkg(expectedPackage))) {
                return true
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun assertDeviceState(condition: Boolean, device: UiDevice, message: String) {
        if (condition) return
        throw AssertionError(
            "$message\nCurrent package: ${device.currentPackageName}\nUI hierarchy:\n${device.windowHierarchy()}",
        )
    }

    private fun UiDevice.windowHierarchy(): String = runCatching {
        ByteArrayOutputStream().use { output ->
            dumpWindowHierarchy(output)
            output.toString(Charsets.UTF_8.name())
        }
    }.getOrElse { failure -> "<unable to dump hierarchy: ${failure.message}>" }

    private companion object {
        const val APP_PACKAGE = "com.dwk.flowmoney"
        const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
    }
}
