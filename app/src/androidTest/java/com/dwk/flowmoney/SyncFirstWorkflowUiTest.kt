package com.dwk.flowmoney

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SyncFirstWorkflowUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test
    fun reviewQueueGroupsNewestFirstAndCategoryTargetsAreBoundedAccessible() {
        val newer = syncedTransaction("new", LocalDate.of(2026, 7, 10), merchant = "New Cafe")
        val older = syncedTransaction("old", LocalDate.of(2026, 7, 9), merchant = "Old Cafe")
        var categorized: Triple<String, String, Boolean>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(older, newer),
                    onEdit = {},
                    onCategorize = { transaction, category, future ->
                        categorized = Triple(transaction.id, category, future)
                    },
                    onBulkCategorize = { _, _ -> },
                    onBulkAcceptOther = {},
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val newerTop =
            composeRule
                .onNodeWithTag("review_row_new")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val olderTop =
            composeRule
                .onNodeWithTag("review_row_old")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("newer=$newerTop older=$olderTop", newerTop < olderTop)
        composeRule.onNodeWithText("Friday, Jul 10").assertIsDisplayed()
        composeRule.onNodeWithText("Thursday, Jul 9").assertIsDisplayed()

        val food = composeRule.onNodeWithTag("review_category_new_food").assertIsDisplayed()
        val foodBounds = food.fetchSemanticsNode().boundsInRoot
        assertTrue(foodBounds.height / composeRule.density.density >= 48f)
        food.performClick()
        composeRule.runOnIdle { assertEquals(Triple("new", "Food", false), categorized) }

        composeRule
            .onNodeWithTag("review_category_new_more")
            .assertContentDescriptionEquals("More categories for New Cafe")
        assertEquals(
            5,
            composeRule
                .onNodeWithTag("review_category_new_choices", useUnmergedTree = true)
                .fetchSemanticsNode()
                .children.size,
        )
    }

    @Test
    fun reviewSelectionKeepsPresentationGeometryAt300Dp() {
        val transaction = syncedTransaction("stable", LocalDate.of(2026, 7, 10), "A long merchant name for the review queue").copy(accountName = "Checking")
        var edits = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.ForcedSize(DpSize(300.dp, 800.dp))) {
                FlowMoneyTheme(dynamicColor = false) {
                    ReviewPage(listOf(transaction), onEdit = { edits++ }, onCategorize = { _, _, _ -> }, onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false)
                }
            }
        }
        fun geometry(): List<Float> {
            composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_stable"))
            val row = composeRule.onNodeWithTag("review_row_stable").fetchSemanticsNode().boundsInRoot
            val merchant = composeRule.onNodeWithText(transaction.merchant, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val amount = composeRule.onNodeWithText("-\$5.00", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val metadata = composeRule.onNodeWithText("Other · Checking", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return listOf(merchant.left - row.left, merchant.top - row.top, amount.left - row.left, amount.top - row.top, metadata.left - row.left, metadata.width)
        }
        val before = geometry()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_action"))
        composeRule.onNodeWithTag("review_select_action").performClick()
        assertEquals(before, geometry())
        composeRule.onNodeWithTag("review_row_stable").performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertEquals(0, edits)
    }

    @Test
    fun reviewIdentityAnnouncesPostedAndPurchaseDatesAndRecurrence() {
        val posted = syncedTransaction("posted", LocalDate.of(2026, 7, 10), "Posted cafe")
        val purchased = syncedTransaction("purchase", LocalDate.of(2026, 7, 10), "Purchase cafe").copy(
            transactedAtEpochMillis = posted.occurredAtEpochMillis,
            recurringInterval = RecurrenceInterval.Monthly,
        )
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(posted, purchased), onEdit = {}, onCategorize = { _, _, _ -> },
                    onBulkCategorize = { _, _ -> }, onBulkAcceptOther = {}, operationBusy = false,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Posted cafe, -\$5.00, Other, Posted Jul 10, 2026", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Purchase cafe, -\$5.00, Other, Purchase Jul 10, 2026, 12:00 PM, Recurring Monthly", useUnmergedTree = true).assertExists()
    }

    @Test
    fun selectionExposesSelectedStateDisablesEditAndUsesOneBulkCallback() {
        val first = syncedTransaction("first", LocalDate.of(2026, 7, 10))
        val second = syncedTransaction("second", LocalDate.of(2026, 7, 10))
        var edits = 0
        var bulk: Pair<List<String>, String>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(first, second),
                    onEdit = { edits++ },
                    onCategorize = { _, _, _ -> },
                    onBulkCategorize = { ids, category -> bulk = ids to category },
                    onBulkAcceptOther = {},
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule
            .onNodeWithTag("review_row_first")
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        val actions =
            composeRule
                .onNodeWithTag("review_row_first")
                .fetchSemanticsNode()
                .config
                .getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertFalse(actions.any { it.label == "Edit" || it.label == "Delete" })
        assertEquals(0, edits)

        composeRule.onNodeWithTag("review_row_second").performClick()
        composeRule.onNodeWithTag("review_bulk_category_food").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("first", "second").toSet(), bulk?.first?.toSet())
            assertEquals("Food", bulk?.second)
            assertEquals(0, edits)
        }
    }

    @Test
    fun acceptAsOtherRequiresExplicitConfirmationBeforeOneBulkCallback() {
        val transaction = syncedTransaction("accept", LocalDate.of(2026, 7, 10))
        var acceptedIds: List<String>? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                ReviewPage(
                    unreviewedTransactions = listOf(transaction),
                    onEdit = {},
                    onCategorize = { _, _, _ -> },
                    onBulkCategorize = { _, _ -> },
                    onBulkAcceptOther = { acceptedIds = it },
                    operationBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_row_accept").performClick()
        composeRule.onNodeWithTag("review_accept_other_action").performClick()
        composeRule.onNodeWithText("Accept as Other?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, acceptedIds) }
        composeRule.onNodeWithTag("review_confirm_accept_other").performClick()
        composeRule.runOnIdle { assertEquals(listOf("accept"), acceptedIds) }
    }

    @Test
    fun expandedFiltersExposeSourceStableAccountReviewAndEveryPresentCategory() {
        val synced =
            syncedTransaction("synced", LocalDate.of(2026, 7, 10)).copy(
                accountKey = "account-a",
                accountName = "Checking",
            )
        val categories = (1..15).map { "Category $it" }
        val manual =
            categories.mapIndexed { index, category ->
                Transaction(
                    id = "manual-$index",
                    occurredAtEpochMillis = synced.occurredAtEpochMillis - index - 1,
                    merchant = "Manual $index",
                    category = category,
                    note = "",
                    cents = -100,
                )
            }
        val accounts =
            listOf(
                account("account-a", "Checking", "Bank A"),
                account("account-b", "Checking", "Bank B"),
            )
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyScreen(
                    uiState =
                        MainUiState(
                            isLoading = false,
                            sortedTransactions = listOf(synced) + manual,
                            simpleFin = SimpleFinUiState(accounts = accounts),
                        ),
                    selectedTab = DashboardTab.Transactions,
                    onChartRangeModeSelected = {},
                    onSelectedMonthChange = {},
                    onEdit = {},
                    onViewAllTransactions = {},
                    onDelete = {},
                    onAddTransaction = {},
                    onData = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("transaction_filter_toggle").performClick()
        composeRule
            .onNodeWithTag("transaction_source_filter_manual")
            .performClick()
            .assertIsSelected()
        composeRule
            .onNodeWithTag("transactions_list")
            .performScrollToNode(hasTestTag("transaction_account_filter_action"))
        composeRule.onNodeWithTag("transaction_account_filter_action").performClick()
        composeRule.onNodeWithText("Checking (Bank A)").assertIsDisplayed().performClick()
        composeRule
            .onNodeWithTag("transaction_account_filter_action")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Checking (Bank A)",
                ),
            )
        composeRule.onNodeWithTag("transaction_unreviewed_filter").performClick().assertIsSelected()
        composeRule.onNodeWithTag("transaction_category_filter_category15").assertExists()
    }

    @Test
    fun syncHealthRoutesDisconnectedAndDailyStatesToDataAndEligibleStateToManualSync() {
        val now = 1_800_000_000_000L
        var simpleFin by mutableStateOf(SimpleFinUiState())
        var dataClicks = 0
        var syncClicks = 0
        composeRule.setContent {
            MaterialTheme {
                FlowMoneyTopAppBar(
                    onData = { dataClicks++ },
                    onAddTransaction = {},
                    simpleFin = simpleFin,
                    syncHealthNowEpochMillis = now,
                    onManualSync = { syncClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, dataClicks)
            simpleFin =
                SimpleFinUiState(
                    profile =
                        SimpleFinProfileEntity(
                            connectionId = "eligible",
                            lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(4),
                            lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(4),
                            automaticSyncsPerDay = 4,
                        ),
                )
        }
        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, syncClicks)
            simpleFin =
                SimpleFinUiState(
                    profile =
                        SimpleFinProfileEntity(
                            connectionId = "daily",
                            lastSyncAttemptAtEpochMillis = now - automaticSyncIntervalMillis(1),
                            lastSuccessfulSyncAtEpochMillis = now - automaticSyncIntervalMillis(1),
                            automaticSyncsPerDay = 1,
                        ),
                )
        }
        composeRule.onNodeWithTag("sync_health_action").performClick()
        composeRule.runOnIdle {
            assertEquals(2, dataClicks)
            assertEquals(1, syncClicks)
        }
    }

    @Test
    fun syncedEditorLeadsWithRawBankDetailsAndGatesProviderOwnedFieldsBehindAdvanced() {
        val transaction = syncedTransaction("editor", LocalDate.of(2026, 7, 10), merchant = "Effective Cafe")
        var draft by
            mutableStateOf(
                EditorDraft(
                    id = transaction.id,
                    occurredAtEpochMillis = transaction.occurredAtEpochMillis,
                    merchant = transaction.merchant,
                    amount = "5.00",
                    isExpense = true,
                    category = "Other",
                    note = "",
                    recurringIntervalName = "",
                    source = "simplefin",
                    accountKey = "account-a",
                    accountName = "Checking",
                    reviewedAtEpochMillis = null,
                    providerDescription = "RAW PROVIDER DESCRIPTION 123",
                    merchantOverride = null,
                    providerMerchant = "Provider Cafe",
                    flowKind = FlowKind.NORMAL,
                    flowKindOverride = null,
                ),
            )
        var futureSave: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                    onSaveWithFutureRule = { futureSave = it },
                )
            }
        }

        composeRule.onNodeWithTag("synced_provider_summary").assertIsDisplayed()
        composeRule.onNodeWithText("Checking").assertIsDisplayed()
        composeRule.onNodeWithTag("synced_provider_description").assertIsDisplayed()
        composeRule.onNodeWithText("RAW PROVIDER DESCRIPTION 123").assertIsDisplayed()
        composeRule.onNodeWithText("Expense").assertDoesNotExist()

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_use_future"))
        composeRule.onNodeWithTag("synced_use_future").performClick()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_advanced_toggle"))
        composeRule.onNodeWithTag("synced_advanced_toggle").performClick()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_advanced_warning"))
        composeRule.onNodeWithTag("synced_advanced_warning").assertIsDisplayed()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasText("Expense"))
        composeRule.onNodeWithText("Expense").assertIsDisplayed()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals("RAW PROVIDER DESCRIPTION 123", futureSave?.providerDescription)
            assertEquals("Provider Cafe", futureSave?.providerMerchant)
            assertEquals("Checking", futureSave?.accountName)
            assertEquals(FlowKind.TRANSFER, futureSave?.flowKindOverride)
        }
    }

    @Test
    fun providerTransferCanBeExplicitlyCorrectedToNormalWithoutChangingClassification() {
        val transaction =
            syncedTransaction("provider-transfer", LocalDate.of(2026, 7, 10))
                .copy(flowKind = FlowKind.TRANSFER)
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule
            .onNodeWithTag("transfer_status_control")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Treat as transfer",
                ),
            )
        composeRule.onNodeWithTag("transfer_status_transfer").assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_not_transfer").performClick().assertIsSelected()
        composeRule
            .onNodeWithTag("transfer_status_control")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Not a transfer",
                ),
            )
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals(FlowKind.TRANSFER, saved?.flowKind)
            assertEquals(FlowKind.NORMAL, saved?.flowKindOverride)
        }
    }

    @Test
    fun returningToProviderKindClearsOverrideAndAllowsProviderReclassificationAfterRoundTrip() {
        val transaction = syncedTransaction("round-trip-flow", LocalDate.of(2026, 7, 10))
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_not_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            val savedTransaction = requireNotNull(saved)
            assertEquals(FlowKind.NORMAL, savedTransaction.flowKind)
            assertEquals(null, savedTransaction.flowKindOverride)

            val reclassifiedDraft =
                savedTransaction
                    .copy(flowKind = FlowKind.TRANSFER)
                    .toEntity()
                    .toTransaction()
                    .toEditorDraft()
            assertEquals(null, reclassifiedDraft.flowKindOverride)
            assertEquals(FlowKind.TRANSFER, reclassifiedDraft.effectiveFlowKind)
        }
    }

    @Test
    fun normalTransactionCanBeExplicitlyTreatedAsTransfer() {
        val transaction = syncedTransaction("normal-flow", LocalDate.of(2026, 7, 10))
        var draft by mutableStateOf(transaction.toEditorDraft())
        var saved: Transaction? = null
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = { saved = it },
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("transfer_status_control"))
        composeRule.onNodeWithTag("transfer_status_not_transfer").assertIsSelected()
        composeRule.onNodeWithTag("transfer_status_transfer").performClick().assertIsSelected()
        composeRule.onNodeWithTag("save_transaction_button").performClick()

        composeRule.runOnIdle {
            assertEquals(FlowKind.NORMAL, saved?.flowKind)
            assertEquals(FlowKind.TRANSFER, saved?.flowKindOverride)
        }
    }

    @Test
    fun connectedAccountsCardRendersBalanceAvailableBalanceAndAsOfTimestamp() {
        val balanceDateSeconds = 1_766_145_600L
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                DataSheet(
                    simpleFin =
                        SimpleFinUiState(
                            profile = SimpleFinProfileEntity(connectionId = "connected"),
                            accounts =
                                listOf(
                                    account("account-a", "Checking", "Bank A").copy(
                                        balanceAmount = "1234.56",
                                        availableBalanceAmount = "1000.00",
                                        balanceDateEpochSeconds = balanceDateSeconds,
                                    ),
                                ),
                        ),
                    operation = null,
                    onOpenSetup = {},
                    onConnect = {},
                    onSync = {},
                    onImport = {},
                    onExport = {},
                    onDisconnect = {},
                    onClose = {},
                    onRetryConnection = {},
                    onCancelPendingConnection = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("data_sheet_list")
            .performScrollToNode(hasTestTag("connected_accounts_card"))
        composeRule.onNodeWithTag("connected_accounts_card").assertIsDisplayed()
        composeRule.onNodeWithText("Checking").assertIsDisplayed()
        composeRule.onNodeWithText("$1,234.56").assertIsDisplayed()
        composeRule.onNodeWithText("Available $1,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("as of ${listDateText(balanceDateSeconds * 1000L)}").assertIsDisplayed()
    }

    @Test
    fun overviewReviewStaysSeparateFromTopBarConnectionStatus() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        val dao = FlowMoneyDatabase.get(context).simpleFinDao()
        val healthy =
            SimpleFinProfileEntity(
                connectionId = "healthy",
                connectedAtEpochMillis = 1_800_000_000_000L,
                lastSyncAttemptAtEpochMillis = 1_800_000_000_000L,
                lastSuccessfulSyncAtEpochMillis = 1_800_000_000_000L,
                automaticSyncsPerDay = 4,
            )
        runBlocking { dao.upsertProfile(healthy) }
        val pending =
            listOf(
                syncedTransaction("pending-a", LocalDate.of(2026, 7, 10), merchant = "Pending Cafe"),
                syncedTransaction("pending-b", LocalDate.of(2026, 7, 9), merchant = "Pending Diner"),
            )
        val gateway = BannerGateway(pending)
        val viewModel =
            MainViewModel(
                repository = gateway,
                simpleFinRepository = SimpleFinSyncRepository(context),
                simpleFinAccounts = MutableStateFlow(emptyList()),
            )
        viewModel.reportInitializationComplete()
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyApp(
                    viewModel = viewModel,
                    coldStartSimpleFinSync = { null },
                    transactionWidgetRefresh = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("overview_pending_review_count", useUnmergedTree = true).assertTextEquals("Review 2") }.isSuccess
        }
        composeRule.onNodeWithTag("overview_review_action").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_health_action").assertIsDisplayed()
        composeRule.onNodeWithText("Last successful sync", substring = true).assertDoesNotExist()

        fun assertNoOverviewSyncActions() {
            composeRule.onAllNodesWithContentDescription("Bank sync status").assertCountEquals(1)
            composeRule.onNodeWithText("Connect bank").assertDoesNotExist()
            composeRule.onNodeWithText("Open Data").assertDoesNotExist()
            composeRule.onNodeWithText("Adjust sync cadence", substring = true).assertDoesNotExist()
        }
        runBlocking { dao.upsertProfile(healthy.copy(isPaused = true, lastError = "SimpleFIN reconnect required")) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bank reconnect required; open Data")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        runBlocking { dao.clearProfile() }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bank sync disconnected; open Data to connect")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        runBlocking { dao.upsertProfile(healthy.copy(automaticSyncsPerDay = 1, lastSuccessfulSyncAtEpochMillis = 1, lastSyncAttemptAtEpochMillis = 1)) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("sync_health_action").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Connected and idle; automatic sync is once daily; open Data to change cadence")) }.isSuccess
        }
        assertNoOverviewSyncActions()
        composeRule.onNodeWithTag("overview_pending_review_count", useUnmergedTree = true).assertTextEquals("Review 2")
        composeRule.runOnIdle {
            gateway.rows.value = gateway.rows.value.map { it.copy(reviewedAtEpochMillis = 1L) }
            gateway.pending.value = emptyList()
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("overview_review_action").assertDoesNotExist() }.isSuccess
        }
    }

    private class BannerGateway(
        initial: List<Transaction>,
    ) : TransactionGateway {
        val rows = MutableStateFlow(initial)
        val pending = MutableStateFlow(initial)
        override val transactions: Flow<List<Transaction>> = rows
        override val unreviewedTransactions: Flow<List<Transaction>> = pending

        override suspend fun load(): List<Transaction> = rows.value

        override suspend fun getUnreviewedTransactions(): List<Transaction> = pending.value

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    @Test
    fun syncedEditorShowsTransactedTimeOnlyWhenItDiffersFromThePostedTime() {
        val transaction = syncedTransaction("transacted", LocalDate.of(2026, 7, 10))
        val transactedAt = transaction.occurredAtEpochMillis - 86_400_000L
        val sameDayDifferentTime = transaction.occurredAtEpochMillis - 3_600_000L
        var draft by mutableStateOf(transaction.copy(transactedAtEpochMillis = transactedAt).toEditorDraft())
        composeRule.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                TransactionEditor(
                    transaction = transaction,
                    draft = draft,
                    suggestionHistory = TransactionSuggestionHistory.Empty,
                    onDraftChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onCancel = {},
                    persistenceBusy = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("synced_provider_summary"))
        composeRule.onNodeWithTag("synced_transacted_at").assertIsDisplayed()
        assertTransactedAtShows(transactedAt)
        composeRule.onNodeWithText("Posted").assertIsDisplayed()

        // Same calendar day, different clock time still deserves the disambiguating line.
        composeRule.runOnIdle { draft = draft.copy(transactedAtEpochMillis = sameDayDifferentTime) }
        composeRule.onNodeWithTag("synced_transacted_at").assertIsDisplayed()
        assertTransactedAtShows(sameDayDifferentTime)

        // Exactly equal timestamps carry no information, so the line disappears entirely.
        composeRule.runOnIdle {
            draft = draft.copy(transactedAtEpochMillis = transaction.occurredAtEpochMillis)
        }
        composeRule.onNodeWithTag("synced_transacted_at").assertDoesNotExist()

        composeRule.runOnIdle { draft = draft.copy(transactedAtEpochMillis = null) }
        composeRule.onNodeWithTag("synced_transacted_at").assertDoesNotExist()
    }

    /** `StatusLine` does not merge its label and value, so match the tagged row's descendants. */
    private fun assertTransactedAtShows(epochMillis: Long) {
        composeRule
            .onNode(
                hasTestTag("synced_transacted_at") and
                    hasAnyDescendant(hasText(listDateText(epochMillis))) and
                    hasAnyDescendant(hasText("Transacted")),
            ).assertIsDisplayed()
    }

    /** Mirrors MainActivity's private `ListDateFormatter` so the assertion reads the same text. */
    private fun listDateText(epochMillis: Long): String =
        Instant
            .ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))

    private fun syncedTransaction(
        id: String,
        date: LocalDate,
        merchant: String = id,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis =
            LocalDateTime
                .of(date, LocalTime.NOON)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        merchant = merchant,
        category = "Other",
        note = "",
        cents = -500,
        source = "simplefin",
        reviewedAtEpochMillis = null,
        providerDescription = "RAW $merchant",
        providerMerchant = merchant,
    )

    private fun account(
        id: String,
        name: String,
        institution: String,
    ) = SimpleFinAccountEntity(
        accountId = id,
        name = name,
        currency = "USD",
        institutionName = institution,
        balanceAmount = null,
        availableBalanceAmount = null,
        balanceDateEpochSeconds = null,
        lastSeenAtEpochMillis = 1L,
    )
}
