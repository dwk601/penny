package com.dwk.flowmoney

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ColdStartNavigationUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
    }

    @Test
    fun startupSyncRowsKeepColdStartOnOverviewAndOfferOptionalCorrections() {
        FlowMoneyDatabase.resetForTest()
        val pending = pendingTransaction()
        val gateway = StartupSyncGateway()
        val store = ViewModelStore()
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
                    coldStartSimpleFinSync = {
                        gateway.pending.value = listOf(pending)
                        gateway.rows.value = listOf(pending)
                        SimpleFinSyncResult.Success(inserted = 1, updated = 0, skipped = 0)
                    },
                    transactionWidgetRefresh = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("overview_review_action").assertExists() }.isSuccess
        }
        composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        composeRule.onNodeWithTag("tab_review").assertDoesNotExist()
        composeRule.onNodeWithTag("review_row_pending-after-restore").assertDoesNotExist()

        composeRule.onNodeWithTag("overview_review_action").performClick()
        composeRule.onNodeWithTag("review_row_pending-after-restore").assertExists()
        store.clear()
    }

    @Test
    fun restoredExplicitTabChoiceBeforeLoadingIsNeverOverriddenByPendingReview() {
        FlowMoneyDatabase.resetForTest()
        val gateway = LoadingGateway()
        val store = ViewModelStore()
        val viewModel =
            MainViewModel(
                repository = gateway,
                simpleFinRepository = SimpleFinSyncRepository(context),
                simpleFinAccounts = MutableStateFlow(emptyList()),
            )
        viewModel.reportInitializationComplete()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            FlowMoneyTheme(dynamicColor = false) {
                FlowMoneyApp(
                    viewModel = viewModel,
                    coldStartSimpleFinSync = { null },
                    transactionWidgetRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("app_loading_state").assertExists()
        composeRule.onNodeWithTag("tab_transactions").performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("tab_transactions").assertIsSelected()

        val pending = pendingTransaction()
        gateway.pending.value = listOf(pending)
        runBlocking { gateway.rows.emit(listOf(pending)) }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("tab_transactions").assertIsSelected() }.isSuccess
        }
        composeRule.onNodeWithTag("tab_transactions").assertIsSelected()
        store.clear()
    }

    @Test fun reviewBackClearsSelectionThenReturnsToOverview() {
        val gateway = StartupSyncGateway()
        val pending = pendingTransaction()
        gateway.rows.value = listOf(pending)
        gateway.pending.value = listOf(pending)
        val viewModel = MainViewModel(gateway, SimpleFinSyncRepository(context), MutableStateFlow(emptyList()))
        viewModel.reportInitializationComplete()
        composeRule.setContent { FlowMoneyTheme(dynamicColor = false) { FlowMoneyApp(viewModel, coldStartSimpleFinSync = { null }, transactionWidgetRefresh = {}) } }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("overview_review_action").assertExists() }.isSuccess }
        composeRule.onNodeWithTag("overview_review_action").performClick()
        composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_row_pending-after-restore"))
        composeRule.onNodeWithTag("review_row_pending-after-restore").performClick()
        composeRule.onNodeWithTag("review_selected_count").assertExists()
        composeRule.onNodeWithTag("review_accept_other_action").performClick()
        composeRule.onNodeWithText("Accept as Other?").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithText("Accept as Other?").assertDoesNotExist()
        composeRule.onNodeWithTag("review_selected_count").assertExists()
        pressBack()
        composeRule.onNodeWithTag("review_list").assertExists()
        composeRule.onNodeWithTag("review_selected_count").assertDoesNotExist()
        composeRule.onNodeWithTag("review_select_action").performClick()
        composeRule.onNodeWithText("0 selected").assertExists()
        pressBack()
        pressBack()
        composeRule.onNodeWithTag("overview_list").assertExists()
        composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        composeRule.onNodeWithTag("overview_review_action").performClick()
        composeRule.onNodeWithTag("tab_transactions").performClick().assertIsSelected()
        composeRule.onNodeWithTag("transactions_list").assertExists()
    }

    @Test fun insightsOriginSurvivesRestorationAndBackReturnsWithoutClosing() {
        val gateway = StartupSyncGateway()
        val pending = pendingTransaction().copy(occurredAtEpochMillis = System.currentTimeMillis())
        val reviewed = listOf("Food", "Housing", "Travel", "Bills", "Coffee").mapIndexed { index, category ->
            pending.copy(id = "reviewed-$index", category = category, cents = -(index + 1) * 50, reviewedAtEpochMillis = 1)
        }
        gateway.rows.value = listOf(pending) + reviewed
        gateway.pending.value = listOf(pending)
        val viewModel = MainViewModel(gateway, SimpleFinSyncRepository(context), MutableStateFlow(emptyList()))
        viewModel.reportInitializationComplete()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { FlowMoneyTheme(dynamicColor = false) { FlowMoneyApp(viewModel, coldStartSimpleFinSync = { null }, transactionWidgetRefresh = {}) } }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("overview_review_action").assertExists() }.isSuccess }
        composeRule.onNodeWithTag("tab_insights").performClick()
        composeRule.onNodeWithTag("range_week").performClick().assertIsSelected()
        val bucketTag = "insight_bucket_${java.time.LocalDate.now()}"
        composeRule.onNodeWithTag(bucketTag).performClick().assertIsSelected()
        composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag("insight_category_other"))
        composeRule.onNodeWithTag("insight_category_other").performClick().assertIsSelected()
        composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag("insight_categories_expand"))
        composeRule.onNodeWithTag("insight_categories_expand").performClick()
        composeRule.onNodeWithText("Show less").assertExists()
        composeRule.onNodeWithTag("insights_list").performScrollToNode(hasTestTag("insights_pending_review_action"))
        composeRule.onNodeWithTag("insights_pending_review_action").performClick()
        composeRule.onNodeWithTag("tab_insights").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("tab_insights").assertIsSelected()
        composeRule.onNodeWithTag("review_list").assertExists()
        pressBack()
        composeRule.onNodeWithTag("insights_list").assertExists()
        composeRule.onNodeWithTag("tab_insights").assertIsSelected()
        composeRule.onNodeWithTag("range_week").assertIsSelected()
        composeRule.onNodeWithTag(bucketTag).assertIsNotSelected()
        composeRule.onNodeWithTag("insight_category_other").assertIsNotSelected()
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    @Test fun legacyAndInvalidReviewOriginsRestoreToOverview() {
        val legacy = DashboardNavigationStateSaver.restore(listOf("Review", true, false, false))!!
        val invalid = DashboardNavigationStateSaver.restore(listOf("Review", true, false, false, "Review"))!!
        val unknown = DashboardNavigationStateSaver.restore(listOf("Review", true, false, false, "unknown"))!!
        assertEquals(DashboardTab.Overview, legacy.reviewOriginTab)
        assertEquals(DashboardTab.Overview, invalid.reviewOriginTab)
        assertEquals(DashboardTab.Overview, unknown.reviewOriginTab)
        assertEquals(DashboardTab.Transactions, DashboardNavigationStateSaver.restore(listOf("Review", true, false, false, "Transactions"))!!.reviewOriginTab)
    }

    @Test fun modalLayersTakeBackBeforeReviewNavigation() {
        val gateway = StartupSyncGateway()
        val pending = pendingTransaction()
        gateway.rows.value = listOf(pending)
        gateway.pending.value = listOf(pending)
        val viewModel = MainViewModel(gateway, SimpleFinSyncRepository(context), MutableStateFlow(emptyList()))
        viewModel.reportInitializationComplete()
        composeRule.setContent { FlowMoneyTheme(dynamicColor = false) { FlowMoneyApp(viewModel, coldStartSimpleFinSync = { null }, transactionWidgetRefresh = {}) } }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("overview_review_action").assertExists() }.isSuccess }
        composeRule.onNodeWithTag("overview_review_action").performClick()
        composeRule.onNodeWithTag("top_bar_data").performClick()
        composeRule.onNodeWithTag("data_sheet_modal").assertExists()
        pressBack()
        composeRule.onNodeWithTag("data_sheet_modal").assertDoesNotExist()
        composeRule.onNodeWithTag("review_list").assertExists()
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("more_details_toggle"))
        composeRule.onNodeWithTag("more_details_toggle").performClick()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("date_picker_button"))
        composeRule.onNodeWithTag("date_picker_button").performClick()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("editor_sheet").assertExists()
        composeRule.onNodeWithTag("transaction_editor_form").performScrollToNode(hasTestTag("merchant_field"))
        composeRule.onNodeWithTag("merchant_field").performTextInput("Dirty")
        pressBack() // Dismiss the IME before testing the editor's Back behavior.
        composeRule.onNodeWithTag("editor_sheet").assertExists()
        pressBack()
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Discard changes?").assertIsDisplayed() }.isSuccess }
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("editor_sheet").assertExists()
        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        pressBack()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithTag("review_list").assertExists()
        pressBack()
        composeRule.onNodeWithTag("overview_list").assertExists()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    private class StartupSyncGateway : TransactionGateway {
        val rows = MutableStateFlow<List<Transaction>>(emptyList())
        val pending = MutableStateFlow<List<Transaction>>(emptyList())
        override val transactions: Flow<List<Transaction>> = rows
        override val unreviewedTransactions: Flow<List<Transaction>> = pending

        override suspend fun load(): List<Transaction> = rows.value

        override suspend fun getUnreviewedTransactions(): List<Transaction> = pending.value

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    private class LoadingGateway : TransactionGateway {
        val rows = MutableSharedFlow<List<Transaction>>(replay = 1)
        val pending = MutableStateFlow<List<Transaction>>(emptyList())
        override val transactions: Flow<List<Transaction>> = rows
        override val unreviewedTransactions: Flow<List<Transaction>> = pending

        override suspend fun load(): List<Transaction> = emptyList()

        override suspend fun getUnreviewedTransactions(): List<Transaction> = pending.value

        override suspend fun upsert(transaction: Transaction) = Unit

        override suspend fun importTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = transactions.size

        override suspend fun delete(id: String) = Unit
    }

    private fun pendingTransaction() =
        Transaction(
            id = "pending-after-restore",
            occurredAtEpochMillis =
                LocalDateTime
                    .of(2026, 7, 10, 12, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            merchant = "Pending merchant",
            category = "Other",
            note = "",
            cents = -500,
            source = "simplefin",
            reviewedAtEpochMillis = null,
            providerDescription = "RAW pending",
            providerMerchant = "Pending merchant",
        )
}
