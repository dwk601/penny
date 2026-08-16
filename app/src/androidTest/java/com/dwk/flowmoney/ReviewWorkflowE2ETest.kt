package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewWorkflowE2ETest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: FlowMoneyDatabase

    @Before
    fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("room_migrated", true)
            .commit()
        database = FlowMoneyDatabase.get(context)
    }

    @After
    fun clearDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        context
            .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun trueColdStartCategorizesWithOneUndoAndRefreshesReactiveQueue() {
        insertUnreviewed("review-one", "Coffee Shop", 1_800_000_000_000L)

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                runCatching { composeRule.onNodeWithTag("tab_review").assertIsSelected() }.isSuccess
            }
            composeRule.onNodeWithTag("tab_review").assertIsSelected()
            composeRule.onNodeWithTag("review_row_review-one").assertIsDisplayed()
            composeRule.onNodeWithTag("review_category_review-one_food").performClick()

            composeRule.waitUntil(5_000) { row("review-one").reviewedAtEpochMillis != null }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().size == 1 }
            assertEquals("Food", row("review-one").category)
            composeRule.onNodeWithText("Undo").performClick()

            composeRule.waitUntil(5_000) { row("review-one").reviewedAtEpochMillis == null }
            assertEquals("Other", row("review-one").category)
            composeRule.onNodeWithTag("review_row_review-one").assertIsDisplayed()
        }
    }

    @Test
    fun conflictingFutureRuleOnlyOverwritesOnConfirmationAndUndoRestoresOriginAndRule() {
        insertUnreviewed("rule-origin", "Coffee Shop", 1_800_000_000_000L)
        runBlocking {
            database.transactionDao().insertMerchantRule(
                MerchantRuleEntity(
                    normalizedProviderMerchant = "coffee shop",
                    category = "Travel",
                    merchantOverride = "Old display",
                ),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Use for future").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("review_use_future_rule-origin").performClick()
            composeRule.onNodeWithTag("review_category_rule-origin_food").performClick()

            composeRule.onNodeWithText("Replace saved rule?").assertIsDisplayed()
            composeRule.onNodeWithText("Travel, display “Old display”", substring = true).assertIsDisplayed()
            assertEquals("Travel", rule("coffee shop")?.category)
            assertNull(row("rule-origin").reviewedAtEpochMillis)

            composeRule.onNodeWithTag("confirm_merchant_rule_overwrite").performClick()
            composeRule.waitUntil(5_000) { rule("coffee shop")?.category == "Food" }
            composeRule.waitUntil(5_000) { row("rule-origin").reviewedAtEpochMillis != null }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().size == 1 }
            composeRule.onNodeWithText("Undo").performClick()

            composeRule.waitUntil(5_000) {
                rule("coffee shop")?.category == "Travel" && row("rule-origin").reviewedAtEpochMillis == null
            }
            assertEquals("Old display", rule("coffee shop")?.merchantOverride)
            assertEquals("Other", row("rule-origin").category)
            assertNull(row("rule-origin").merchantOverride)
        }
    }

    @Test
    fun bulkCategorizeUsesSelectedSemanticsOneTransactionAndOneUndo() {
        insertUnreviewed("bulk-new", "New Merchant", 1_800_000_000_100L)
        insertUnreviewed("bulk-old", "Old Merchant", 1_800_000_000_000L)

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Select").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("review_select_action").performClick()
            listOf("bulk-new", "bulk-old").forEach { id ->
                composeRule
                    .onNodeWithTag("review_list")
                    .performScrollToNode(hasTestTag("review_row_$id"))
                composeRule
                    .onNodeWithTag("review_row_$id")
                    .performClick()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
            }
            composeRule
                .onNodeWithTag("review_list")
                .performScrollToNode(hasTestTag("review_bulk_category_food"))
            composeRule.onNodeWithTag("review_bulk_category_food").performClick()

            composeRule.waitUntil(5_000) {
                row("bulk-new").reviewedAtEpochMillis != null && row("bulk-old").reviewedAtEpochMillis != null
            }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().size == 1 }
            assertEquals(setOf("Food"), listOf(row("bulk-new"), row("bulk-old")).map { it.category }.toSet())
            composeRule.onNodeWithText("Undo").performClick()

            composeRule.waitUntil(5_000) {
                row("bulk-new").reviewedAtEpochMillis == null && row("bulk-old").reviewedAtEpochMillis == null
            }
            assertEquals(setOf("Other"), listOf(row("bulk-new"), row("bulk-old")).map { it.category }.toSet())
        }
    }

    private fun insertUnreviewed(
        id: String,
        merchant: String,
        occurredAt: Long,
    ) = runBlocking {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                occurredAtEpochMillis = occurredAt,
                merchant = merchant,
                category = "Other",
                note = "",
                cents = -500,
                source = "simplefin",
                accountKey = "checking",
                accountName = "Checking",
                reviewedAtEpochMillis = null,
                providerDescription = "RAW $merchant",
                merchantOverride = null,
            ),
        )
    }

    private fun row(id: String): TransactionEntity = runBlocking { database.transactionDao().getAll().single { it.id == id } }

    private fun rule(key: String): MerchantRuleEntity? = runBlocking { database.transactionDao().merchantRuleForKey(key) }
}
