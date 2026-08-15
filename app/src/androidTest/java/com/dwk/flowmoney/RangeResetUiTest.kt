package com.dwk.flowmoney

import android.content.Context
import android.view.KeyEvent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class RangeResetUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: ViewModelStore

    @Before
    fun resetState() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME).deleteRecursively()
        File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME).deleteRecursively()
        File(context.noBackupFilesDir, SimpleFinCredentialStore.ROLLBACK_FILE_NAME).deleteRecursively()
        store = ViewModelStore()
    }

    @After
    fun clearState() {
        store.clear()
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test
    fun resetBusyPreventsDismissThenClosesSheetAndPersistentAppUndoRestoresSnapshot() {
        val today = LocalDate.of(2026, 3, 15)
        val inRange = transaction("reset-one", today, 12, -500)
        val secondInRange = transaction("reset-two", today, 18, 900)
        val outside = transaction("outside", today.minusDays(1), 23, -100)
        val gateway = FakeRangeGateway(listOf(inRange, secondInRange, outside), initialTombstoneCount = 1)
        val resetStarted = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        gateway.beforeReset = {
            resetStarted.complete(Unit)
            releaseReset.await()
        }
        val widgetRefreshes = AtomicInteger()
        val manualSyncs = AtomicInteger()
        val snackbarHostState = SnackbarHostState()
        setApp(gateway, snackbarHostState, widgetRefreshes, manualSyncs)

        openResetConfirmation(gateway, expectedAffectedCount = 3)
        composeRule.onNodeWithTag("simplefin_reset_days_confirmation_dialog").assertIsDisplayed()
        composeRule
            .onNodeWithTag("simplefin_reset_days_confirmation_range")
            .assertTextContains("March 15, 2026")
        composeRule
            .onNodeWithTag("simplefin_reset_days_confirmation_count")
            .assertTextContains("3 items affected: 2 transactions + 1 deleted SimpleFIN record", substring = true)
        composeRule.onNodeWithTag("simplefin_reset_days_confirm_button").performClick()
        composeRule.waitUntil(5_000) { resetStarted.isCompleted }

        composeRule.onNodeWithTag("simplefin_reset_days_busy").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_confirm_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("simplefin_reset_days_cancel_button").assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("simplefin_reset_days_confirmation_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("data_sheet_modal").assertIsDisplayed()

        releaseReset.complete(Unit)
        composeRule.waitUntil(5_000) {
            gateway.rows.value == listOf(outside) && widgetRefreshes.get() == 1
        }
        composeRule.onNodeWithTag("simplefin_reset_days_confirmation_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("data_sheet_modal").assertDoesNotExist()
        composeRule.onNodeWithTag("app_snackbar_host").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.runOnIdle {
            val data = snackbarHostState.currentSnackbarData
            assertNotNull(data)
            val visuals = requireNotNull(data).visuals
            assertEquals(SnackbarDuration.Indefinite, visuals.duration)
            assertEquals("Undo", visuals.actionLabel)
        }
        assertEquals(0, manualSyncs.get())

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitUntil(5_000) {
            gateway.rows.value == listOf(inRange, secondInRange, outside) &&
                gateway.tombstoneCount == 1 &&
                gateway.restoreCalls == 1 &&
                widgetRefreshes.get() == 2
        }
        composeRule.onNodeWithText("Restored March 15, 2026").assertIsDisplayed()
        assertEquals(1, gateway.resetCalls)
        assertEquals(0, manualSyncs.get())
    }

    @Test
    fun explicitUndoDismissalLeavesResetCommittedAndNeverSyncs() {
        val today = LocalDate.of(2026, 3, 15)
        val inRange = transaction("dismissed-undo", today, 12, -500)
        val outside = transaction("dismissed-outside", today.minusDays(1), 23, -100)
        val gateway = FakeRangeGateway(listOf(inRange, outside), initialTombstoneCount = 1)
        val widgetRefreshes = AtomicInteger()
        val manualSyncs = AtomicInteger()
        val snackbarHostState = SnackbarHostState()
        setApp(gateway, snackbarHostState, widgetRefreshes, manualSyncs)

        openResetConfirmation(gateway, expectedAffectedCount = 2)
        composeRule.onNodeWithTag("simplefin_reset_days_confirm_button").performClick()
        composeRule.waitUntil(5_000) {
            gateway.rows.value == listOf(outside) && snackbarHostState.currentSnackbarData != null
        }
        composeRule.runOnIdle { requireNotNull(snackbarHostState.currentSnackbarData).dismiss() }
        composeRule.waitUntil(5_000) { snackbarHostState.currentSnackbarData == null }

        assertEquals(listOf(outside), gateway.rows.value)
        assertEquals(0, gateway.restoreCalls)
        assertEquals(1, widgetRefreshes.get())
        assertEquals(0, manualSyncs.get())
    }

    @Test
    fun resetFailureKeepsConfirmationAndSheetRecoverable() {
        val today = LocalDate.of(2026, 3, 15)
        val inRange = transaction("failed-reset", today, 12, -500)
        val gateway = FakeRangeGateway(listOf(inRange), initialTombstoneCount = 0)
        gateway.beforeReset = { error("synthetic reset failure") }
        val widgetRefreshes = AtomicInteger()
        val manualSyncs = AtomicInteger()
        setApp(gateway, SnackbarHostState(), widgetRefreshes, manualSyncs)

        openResetConfirmation(gateway, expectedAffectedCount = 1)
        composeRule.onNodeWithTag("simplefin_reset_days_confirm_button").performClick()
        composeRule.onNodeWithTag("simplefin_reset_days_failure").assertIsDisplayed()
        composeRule
            .onNodeWithText("Could not reset these days. Your Penny data was not changed.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_confirmation_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("data_sheet_modal").assertIsDisplayed()
        composeRule.onNodeWithTag("simplefin_reset_days_confirm_button").assertIsEnabled()
        composeRule.onNodeWithTag("simplefin_reset_days_cancel_button").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("simplefin_reset_days_confirmation_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("data_sheet_modal").assertIsDisplayed()

        assertEquals(listOf(inRange), gateway.rows.value)
        assertEquals(0, widgetRefreshes.get())
        assertEquals(0, manualSyncs.get())
    }

    private fun setApp(
        gateway: FakeRangeGateway,
        snackbarHostState: SnackbarHostState,
        widgetRefreshes: AtomicInteger,
        manualSyncs: AtomicInteger,
    ) {
        val db = FlowMoneyDatabase.get(context)
        runBlocking {
            db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "range-reset-ui"))
        }
        val viewModel =
            ViewModelProvider(
                store,
                MainViewModel.Factory(
                    repository = gateway,
                    simpleFinRepository = SimpleFinSyncRepository(context),
                    simpleFinAccounts = MutableStateFlow(emptyList()),
                ),
            )[MainViewModel::class.java]
        viewModel.reportInitializationComplete()
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyApp(
                    viewModel = viewModel,
                    transactionWidgetRefresh = { widgetRefreshes.incrementAndGet() },
                    coldStartSimpleFinSync = { null },
                    manualSimpleFinSync = {
                        manualSyncs.incrementAndGet()
                        SimpleFinSyncResult.Success(inserted = 0, updated = 0, skipped = 0)
                    },
                    appSnackbarHostState = snackbarHostState,
                    resetDaysClock = FixedClock,
                    resetDaysZoneId = ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Penny").assertIsDisplayed() }.isSuccess
        }
    }

    private fun openResetConfirmation(
        gateway: FakeRangeGateway,
        expectedAffectedCount: Long,
    ) {
        composeRule.onNodeWithText("Data").performClick()
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_picker_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_picker_button").performClick()
        composeRule.onNodeWithTag("simplefin_reset_days_picker_dialog", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitUntil(5_000) { gateway.lastCountResult != null }
        assertEquals(expectedAffectedCount, requireNotNull(gateway.lastCountResult).affectedCount)
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_count"))
        composeRule
            .onNodeWithTag("simplefin_reset_days_count")
            .assertTextContains(
                "$expectedAffectedCount ${if (expectedAffectedCount == 1L) "item" else "items"} affected",
                substring = true,
            )
        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("simplefin_reset_days_reset_button"))
        composeRule.onNodeWithTag("simplefin_reset_days_reset_button").assertIsEnabled().performClick()
    }

    private class FakeRangeGateway(
        initialTransactions: List<Transaction>,
        initialTombstoneCount: Int,
    ) : TransactionGateway {
        val rows = MutableStateFlow(initialTransactions)
        override val transactions: Flow<List<Transaction>> = rows
        var tombstoneCount = initialTombstoneCount
        var resetCalls = 0
        var restoreCalls = 0
        var lastCountResult: TransactionRangeCount? = null
        var beforeReset: suspend () -> Unit = {}

        override suspend fun load(): List<Transaction> = rows.value

        override suspend fun upsert(transaction: Transaction) {
            rows.value = rows.value.filterNot { it.id == transaction.id } + transaction
        }

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun countRange(
            range: PennyLocalDateRange,
            zoneId: ZoneId,
        ): TransactionRangeCount =
            TransactionRangeCount(
                transactionCount = transactionsInRange(range, zoneId).size.toLong(),
                tombstoneCount = tombstoneCount.toLong(),
            ).also { lastCountResult = it }

        override suspend fun resetRange(
            range: PennyLocalDateRange,
            zoneId: ZoneId,
        ): TransactionRangeResetSnapshot {
            resetCalls += 1
            beforeReset()
            val transactions = transactionsInRange(range, zoneId)
            val instantRange = range.toInstantRange(zoneId)
            val tombstones =
                List(tombstoneCount) { index ->
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = "reset-tombstone-$index",
                        ignoredAtEpochMillis = index.toLong(),
                        occurredAtEpochMillis = instantRange.startInclusive.toEpochMilli(),
                    )
                }
            val snapshot =
                TransactionRangeResetSnapshot(
                    transactionRows = transactions.map(Transaction::toEntity),
                    tombstoneRows = tombstones,
                )
            val removedIds = transactions.mapTo(mutableSetOf()) { it.id }
            rows.value = rows.value.filterNot { it.id in removedIds }
            tombstoneCount = 0
            return snapshot
        }

        override suspend fun restoreRange(snapshot: TransactionRangeResetSnapshot): TransactionRangeCount {
            restoreCalls += 1
            val restoredTransactions = snapshot.transactionRows.map(TransactionEntity::toTransaction)
            val restoredIds = restoredTransactions.mapTo(mutableSetOf()) { it.id }
            rows.value = restoredTransactions + rows.value.filterNot { it.id in restoredIds }
            tombstoneCount = snapshot.tombstoneRows.size
            return snapshot.count
        }

        private fun transactionsInRange(
            range: PennyLocalDateRange,
            zoneId: ZoneId,
        ): List<Transaction> {
            val instantRange = range.toInstantRange(zoneId)
            val start = instantRange.startInclusive.toEpochMilli()
            val end = instantRange.endExclusive.toEpochMilli()
            return rows.value.filter { it.occurredAtEpochMillis in start until end }
        }
    }

    private fun transaction(
        id: String,
        date: LocalDate,
        hour: Int,
        cents: Int,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis =
                date
                    .atTime(hour, 0)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli(),
            merchant = id,
            category = "Other",
            note = "",
            cents = cents,
        )

    private companion object {
        val FixedClock: Clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC)
    }
}
