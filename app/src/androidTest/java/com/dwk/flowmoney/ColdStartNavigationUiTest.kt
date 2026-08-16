package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
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
