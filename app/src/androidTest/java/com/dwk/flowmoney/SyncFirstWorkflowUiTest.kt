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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class SyncFirstWorkflowUiTest {
    @get:Rule val composeRule = createComposeRule()

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
                    selectedTab = DashboardTab.Overview,
                    onData = { dataClicks++ },
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
