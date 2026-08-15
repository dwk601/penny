package com.dwk.flowmoney

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataOperationE2ETest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val cleanState =
        TestRule { base, _: Description ->
            object : Statement() {
                override fun evaluate() {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
                    // Picker assertion messages include the test-package-private artifact path.
                    // Remove only stale artifacts here so a failure's 0600 artifact remains at that
                    // path until the next test (or the next run of this class) starts.
                    cleanPickerHierarchyArtifacts(instrumentationContext)
                    FlowMoneyDatabase.resetForTest()
                    context.deleteDatabase("flow_money.db")
                    File(context.noBackupFilesDir, "simplefin_access_url.bin").deleteRecursively()
                    context
                        .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .putBoolean("room_migrated", true)
                        .commit()
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
    @SdkSuppress(maxSdkVersion = HIERARCHY_ARTIFACT_MIN_API - 1)
    fun hierarchyArtifactApiFloorReturnsSanitizedUnsupportedResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hierarchy = writeWindowHierarchy(UiDevice.getInstance(instrumentation))

        assertNull(hierarchy.artifactPath)
        assertEquals("UnsupportedApi", hierarchy.failureClass)
        assertEquals(UNSUPPORTED_HIERARCHY_REASON, hierarchy.failureReason)
    }

    @Test
    fun hierarchyApiFloorPureBranchReturnsSanitizedUnsupportedResult() {
        val hierarchy =
            requireNotNull(hierarchyApiFloorFailure(HIERARCHY_ARTIFACT_MIN_API - 1))

        assertNull(hierarchy.artifactPath)
        assertEquals("UnsupportedApi", hierarchy.failureClass)
        assertEquals(UNSUPPORTED_HIERARCHY_REASON, hierarchy.failureReason)
        assertNull(hierarchyApiFloorFailure(HIERARCHY_ARTIFACT_MIN_API))
    }

    @Test
    @SdkSuppress(minSdkVersion = HIERARCHY_ARTIFACT_MIN_API)
    fun hierarchyArtifactsUseTestStoragePrivateModeAndCleanup() {
        if (Build.VERSION.SDK_INT < HIERARCHY_ARTIFACT_MIN_API) return

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val instrumentationContext = instrumentation.context
        val device = UiDevice.getInstance(instrumentation)
        val hierarchy = writeWindowHierarchy(device)
        check(hierarchy.failureClass == null) { hierarchy.assertionMessage() }
        val artifact = File(requireNotNull(hierarchy.artifactPath))

        // These commands run-as each package, proving the artifact is private test-package data
        // rather than merely comparing two File objects constructed by this test.
        assertEquals("700", privateStorageDirectoryMode(instrumentationContext))
        assertEquals("600", privateHierarchyArtifactMode(instrumentationContext, artifact.name))
        assertFalse(privateHierarchyArtifactsExist(instrumentation.targetContext))

        cleanPickerHierarchyArtifacts(instrumentationContext)
        assertFalse(privateHierarchyArtifactExists(instrumentationContext, artifact.name))
    }

    @Test
    fun disconnectConfirmationCancelLeavesConnectionStateAndConfirmPreservesTransactions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local =
            TransactionEntity(
                id = "local-transaction",
                occurredAtEpochMillis = 2_000L,
                merchant = "Local cafe",
                category = "Food",
                note = "",
                cents = -450,
            )
        val simpleFin =
            TransactionEntity(
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
        assertEquals(
            emptyList<SimpleFinAccountEntity>(),
            runBlocking {
                FlowMoneyDatabase
                    .get(context)
                    .simpleFinDao()
                    .observeAccounts()
                    .first()
            },
        )
        assertNull(SimpleFinCredentialStore(context).read("phase3"))
        assertEquals(
            listOf(local, simpleFin),
            runBlocking {
                FlowMoneyDatabase.get(context).transactionDao().getAll()
            },
        )
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
        assertDeviceState(
            waitForPackageObject(device, pickerPackage, 5_000),
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

    private fun pickerIntent(tag: String): Intent =
        when (tag) {
            "csv_import_button" -> {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, IMPORT_MIME_TYPES)
            }

            "csv_export_button" -> {
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(CSV_MIME_TYPE)
            }

            else -> {
                error("No picker expectation configured for $tag")
            }
        }

    private fun resolvePickerPackage(
        context: Context,
        intent: Intent,
    ): String {
        val packageManager = context.packageManager
        val resolvedActivity =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        val output =
            UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand(
                    "pm resolve-activity --brief " +
                        "-a ${action.shellArgument()} " +
                        "-c ${Intent.CATEGORY_OPENABLE.shellArgument()} " +
                        "-t ${type.shellArgument()}",
                )
        return requireNotNull(
            output
                .lineSequence()
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

    private fun waitForPackageObject(
        device: UiDevice,
        expectedPackage: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (
                device.currentPackageName == expectedPackage &&
                device.hasObject(By.pkg(expectedPackage))
            ) {
                return true
            }
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
        val hierarchy = writeWindowHierarchy(device)
        throw AssertionError(
            "Expected package: $expectedPackage\n" +
                "Current package: ${device.currentPackageName}\n" +
                hierarchy.assertionMessage(),
        )
    }

    private fun writeWindowHierarchy(device: UiDevice): HierarchyDumpResult {
        if (Build.VERSION.SDK_INT < HIERARCHY_ARTIFACT_MIN_API) {
            return requireNotNull(hierarchyApiFloorFailure(Build.VERSION.SDK_INT))
        }
        return writeWindowHierarchyApi34(device)
    }

    private fun hierarchyApiFloorFailure(sdkInt: Int): HierarchyDumpResult? =
        if (sdkInt < HIERARCHY_ARTIFACT_MIN_API) {
            HierarchyDumpResult(
                artifactPath = null,
                failureClass = "UnsupportedApi",
                failureReason = UNSUPPORTED_HIERARCHY_REASON,
            )
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun writeWindowHierarchyApi34(device: UiDevice): HierarchyDumpResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val instrumentationContext = instrumentation.context
        val artifactName = "$PICKER_HIERARCHY_PREFIX${UUID.randomUUID()}.xml"
        val artifact = File(instrumentationContext.filesDir, artifactName)
        var operation = "dump window hierarchy"
        return try {
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            operation = "write private hierarchy artifact"
            writePrivateHierarchyArtifact(instrumentation, artifactName, hierarchy.toByteArray())
            HierarchyDumpResult(artifactPath = artifact.absolutePath)
        } catch (failure: Exception) {
            val partialArtifactRemoved =
                tryDeletePrivateHierarchyArtifact(instrumentationContext, artifactName)
            HierarchyDumpResult(
                artifactPath = artifact.absolutePath.takeUnless { partialArtifactRemoved },
                failureClass = failure.javaClass.simpleName.ifBlank { failure.javaClass.name },
                failureReason = sanitizedHierarchyFailureReason(operation, failure),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun writePrivateHierarchyArtifact(
        instrumentation: Instrumentation,
        artifactName: String,
        hierarchy: ByteArray,
    ) {
        val context = instrumentation.context
        val packageName = context.packageName.shellArgument()
        val safeArtifactName = artifactName.hierarchyArtifactShellArgument()
        // Instrumentation code runs under the target UID. Stream through UiAutomation so the
        // test APK UID creates a 0700 directory and 0600 artifact in its own private storage.
        executeTestStorageCommand(context, "mkdir -p files")
        executeTestStorageCommand(context, "chmod 700 files")
        executeTestStorageCommand(
            context,
            "install -m 600 /dev/null files/$safeArtifactName",
        )

        val descriptors =
            executeShellCommandRweApi34(
                instrumentation,
                "run-as $packageName dd of=files/$safeArtifactName",
            )
        if (descriptors.size != 3) {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            throw IOException("Private hierarchy writer unavailable")
        }
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
            output.write(hierarchy)
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { input ->
            input.readBytes()
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).use { input ->
            input.readBytes()
        }

        executeTestStorageCommand(context, "chmod 600 files/$safeArtifactName")
        val size = executeTestStorageCommand(context, "stat -c %s files/$safeArtifactName")
        val mode = executeTestStorageCommand(context, "stat -c %a files/$safeArtifactName")
        if (size != hierarchy.size.toString() || mode != "600") {
            throw IOException("Private hierarchy writer did not complete")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tryDeletePrivateHierarchyArtifact(
        context: Context,
        artifactName: String,
    ): Boolean =
        try {
            val safeArtifactName = artifactName.hierarchyArtifactShellArgument()
            executeTestStorageCommand(context, "rm -f files/$safeArtifactName")
            !privateHierarchyArtifactExists(context, safeArtifactName)
        } catch (_: Exception) {
            false
        }

    private fun sanitizedHierarchyFailureReason(
        operation: String,
        failure: Exception,
    ): String {
        val reason =
            when (failure) {
                is SecurityException -> "permission denied"
                is IOException -> "input/output failure"
                else -> "unsafe details redacted"
            }
        return "$operation failed: $reason"
    }

    private fun cleanPickerHierarchyArtifacts(context: Context) {
        if (Build.VERSION.SDK_INT < HIERARCHY_ARTIFACT_MIN_API) return
        cleanPickerHierarchyArtifactsApi34(context)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun cleanPickerHierarchyArtifactsApi34(context: Context) {
        executeTestStorageCommand(context, "mkdir -p files")
        executeTestStorageCommand(context, "chmod 700 files")
        executeTestStorageCommand(context, "find files -maxdepth 1 -type f -print")
            .lineSequence()
            .map { path -> File(path).name }
            .filter { name -> name.startsWith(PICKER_HIERARCHY_PREFIX) }
            .forEach { name ->
                executeTestStorageCommand(
                    context,
                    "rm -f files/${name.hierarchyArtifactShellArgument()}",
                )
            }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun privateStorageDirectoryMode(context: Context): String = executeTestStorageCommand(context, "stat -c %a files")

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun privateHierarchyArtifactMode(
        context: Context,
        artifactName: String,
    ): String {
        val safeArtifactName = artifactName.hierarchyArtifactShellArgument()
        return executeTestStorageCommand(context, "stat -c %a files/$safeArtifactName")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun privateHierarchyArtifactsExist(context: Context): Boolean =
        executeTestStorageCommand(
            context = context,
            command = "sh",
            standardInput =
                "if [ -d files ]; then find files -maxdepth 1 -type f -print; fi\n",
        ).lineSequence()
            .map { path -> File(path).name }
            .any { name -> name.startsWith(PICKER_HIERARCHY_PREFIX) }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun privateHierarchyArtifactExists(
        context: Context,
        artifactName: String,
    ): Boolean {
        val safeArtifactName = artifactName.hierarchyArtifactShellArgument()
        return executeTestStorageCommand(
            context,
            "find files -maxdepth 1 -name $safeArtifactName -print",
        ).isNotEmpty()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun executeTestStorageCommand(
        context: Context,
        command: String,
        standardInput: String? = null,
    ): String {
        val packageName = context.packageName.shellArgument()
        val descriptors =
            executeShellCommandRweApi34(
                InstrumentationRegistry.getInstrumentation(),
                "run-as $packageName $command",
            )
        if (descriptors.size != 3) {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            throw IOException("Private test storage command unavailable")
        }
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { input ->
            standardInput?.let { input.write(it.toByteArray()) }
        }
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { input ->
                input.readText().trim()
            }
        val error =
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { input ->
                input.readText().trim()
            }
        if (error.isNotEmpty()) {
            throw IOException("Private test storage command failed")
        }
        return output
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun executeShellCommandRweApi34(
        instrumentation: Instrumentation,
        command: String,
    ): Array<ParcelFileDescriptor> = instrumentation.uiAutomation.executeShellCommandRwe(command)

    private fun String.hierarchyArtifactShellArgument(): String {
        require(all { it.isLetterOrDigit() || it in "._-" })
        return this
    }

    private data class HierarchyDumpResult(
        val artifactPath: String?,
        val failureClass: String? = null,
        val failureReason: String? = null,
    ) {
        fun assertionMessage(): String =
            if (failureClass == null) {
                "Hierarchy artifact: $artifactPath"
            } else {
                "Hierarchy artifact: ${artifactPath ?: "none"}\n" +
                    "Hierarchy failure: $failureClass: $failureReason"
            }
    }

    private companion object {
        const val CSV_MIME_TYPE = "text/csv"
        const val HIERARCHY_ARTIFACT_MIN_API = 34
        const val PICKER_HIERARCHY_PREFIX = "picker-hierarchy-"
        const val UNSUPPORTED_HIERARCHY_REASON =
            "window hierarchy artifact unavailable below API 34"
        val IMPORT_MIME_TYPES = arrayOf("text/*", CSV_MIME_TYPE, "application/csv")
    }
}
