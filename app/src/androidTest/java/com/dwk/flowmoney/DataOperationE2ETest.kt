package com.dwk.flowmoney

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
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
import java.util.UUID
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val pickerPackage = resolvePickerPackage(targetContext, pickerIntent(tag))

        composeRule.onNodeWithTag(tag).performClick()
        assertDeviceState(
            waitForForegroundPackage(device, pickerPackage, 5_000),
            device,
            pickerPackage,
        )
        device.pressBack()
        assertDeviceState(
            waitForForegroundPackage(device, targetContext.packageName, 5_000),
            device,
            targetContext.packageName,
        )

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).assertIsEnabled()
    }

    private fun pickerIntent(tag: String): Intent = when (tag) {
        "csv_import_button" -> Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, IMPORT_MIME_TYPES)
        "csv_export_button" -> Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(CSV_MIME_TYPE)
        else -> error("No picker expectation configured for $tag")
    }

    private fun resolvePickerPackage(context: Context, intent: Intent): String {
        val packageManager = context.packageManager
        val resolvedActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        resolvedActivity?.activityInfo?.packageName?.let { return it }

        // Android 11+ package visibility can filter this test-only query. The pm command
        // performs the same PackageManager resolution without changing the production manifest.
        val action = requireNotNull(intent.action)
        val type = requireNotNull(intent.type)
        val output = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand(
                "pm resolve-activity --brief " +
                    "-a ${action.shellArgument()} " +
                    "-c ${Intent.CATEGORY_OPENABLE.shellArgument()} " +
                    "-t ${type.shellArgument()}",
            )
        return requireNotNull(
            output.lineSequence()
                .map(String::trim)
                .mapNotNull(ComponentName::unflattenFromString)
                .firstOrNull()
                ?.packageName,
        ) { "No installed activity resolves $action" }
    }

    private fun String.shellArgument(): String {
        require(all { it.isLetterOrDigit() || it in "._/*" })
        return this
    }

    private fun waitForForegroundPackage(
        device: UiDevice,
        expectedPackage: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (device.currentPackageName == expectedPackage) return true
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun assertDeviceState(
        condition: Boolean,
        device: UiDevice,
        expectedPackage: String,
    ) {
        if (condition) return
        val artifact = writeWindowHierarchy(device)
        throw AssertionError(
            "Expected package: $expectedPackage\n" +
                "Current package: ${device.currentPackageName}\n" +
                "Hierarchy artifact: ${artifact.absolutePath}",
        )
    }

    private fun writeWindowHierarchy(device: UiDevice): File {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val artifactName = "picker-hierarchy-${UUID.randomUUID()}.xml"
        val artifact = File(targetContext.filesDir, artifactName)
        targetContext.openFileOutput(artifactName, Context.MODE_PRIVATE).use { output ->
            runCatching { device.dumpWindowHierarchy(output) }
        }
        Os.chmod(artifact.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        return artifact
    }

    private companion object {
        const val CSV_MIME_TYPE = "text/csv"
        val IMPORT_MIME_TYPES = arrayOf("text/*", CSV_MIME_TYPE, "application/csv")
    }
}
