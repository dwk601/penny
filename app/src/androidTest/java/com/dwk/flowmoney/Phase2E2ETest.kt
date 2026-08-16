package com.dwk.flowmoney

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class Phase2E2ETest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val cleanPreLaunchState =
        TestRule { base, _: Description ->
            object : Statement() {
                override fun evaluate() {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    FlowMoneyDatabase.resetForTest()
                    context.deleteDatabase("flow_money.db")
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.CREDENTIAL_FILE_NAME).deleteRecursively()
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.PENDING_FILE_NAME).deleteRecursively()
                    File(context.noBackupFilesDir, SimpleFinCredentialStore.ROLLBACK_FILE_NAME).deleteRecursively()
                    context
                        .getSharedPreferences("flow_money", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .putBoolean("room_migrated", true)
                        .commit()
                    context
                        .getSharedPreferences(SimpleFinMigrationCleanup.PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                    base.evaluate()
                }
            }
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(cleanPreLaunchState).around(composeRule)

    @Test
    fun existingSimpleFinEditSurvivesRecreationAndPreservesIdentity() {
        val original =
            transaction(
                id = "simplefin:account:phase2",
                merchant = "Original merchant",
                source = "simplefin",
                accountKey = "account-key",
                accountName = "Checking",
            ).copy(
                providerDescription = "RAW PROVIDER DESCRIPTION",
                flowKind = FlowKind.TRANSFER,
            )
        seed(original)
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("tab_overview").assertIsSelected() }.isSuccess
        }
        waitForMerchant(original.merchant)

        composeRule.onNodeWithTag("tab_overview").assertIsSelected()
        composeRule.onNodeWithTag("tab_review").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_transactions").performClick()
        composeRule.onNodeWithTag("transaction_content_${original.id}").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("RAW PROVIDER DESCRIPTION").assertIsDisplayed()
        composeRule.onNodeWithTag("merchant_override_field").performScrollTo().performTextInput("Edited merchant")
        composeRule.activityRule.scenario.recreate()

        composeRule
            .onNodeWithTag("merchant_override_field", useUnmergedTree = true)
            .assertTextContains("Edited merchant")
        composeRule.onNodeWithText("RAW PROVIDER DESCRIPTION").assertIsDisplayed()
        composeRule.onNodeWithTag("save_transaction_button").performClick()
        awaitRows { it.singleOrNull()?.merchant == "Edited merchant" }

        val saved = rows().single()
        assertEquals(original.id, saved.id)
        assertEquals("Edited merchant", saved.merchant)
        assertEquals("Edited merchant", saved.merchantOverride)
        assertEquals("Original merchant", saved.providerMerchant)
        assertEquals("RAW PROVIDER DESCRIPTION", saved.providerDescription)
        assertEquals(original.source, saved.source)
        assertEquals(original.accountKey, saved.accountKey)
        assertEquals(original.accountName, saved.accountName)
        assertEquals(original.category, saved.category)
        assertEquals(original.note, saved.note)
        assertEquals(original.cents, saved.cents)
        assertEquals(original.flowKind, saved.flowKind)
        assertEquals(original.flowKindOverride, saved.flowKindOverride)
        assertNotNull(saved.reviewedAtEpochMillis)
    }

    @Test
    fun rapidAmountDigitsAccumulateWithoutBeingDropped() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        val one =
            composeRule
                .onNodeWithTag("amount_key_1")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val two =
            composeRule
                .onNodeWithTag("amount_key_2")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val three =
            composeRule
                .onNodeWithTag("amount_key_3")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!

        composeRule.runOnIdle {
            one()
            two()
            three()
        }

        composeRule.onNodeWithTag("amount_display").assertTextContains("-$123.00")
    }

    @Test
    fun merchantFieldKeepsFocusWhileTypingTransactionRecord() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        val merchantField = composeRule.onNodeWithTag("merchant_field").performScrollTo()

        merchantField.performTextInput("A")
        merchantField.assertIsFocused().assertTextContains("A")
        merchantField.performTextInput("B")
        merchantField.assertIsFocused().assertTextContains("AB")
        merchantField.performTextInput("C")
        merchantField.assertIsFocused().assertTextContains("ABC")
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
    }

    @Test
    fun newDraftSurvivesRecreationAndRapidSavePersistsOnce() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        val keypad = composeRule.onNodeWithTag("amount_key_1").fetchSemanticsNode()
        assertTrue(keypad.boundsInRoot.height / composeRule.density.density >= 48f)
        composeRule.onNodeWithTag("amount_key_1").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("New draft merchant")
        val category =
            composeRule
                .onNodeWithTag("category_chip_food")
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
                ).fetchSemanticsNode()
        assertTrue(category.boundsInRoot.height / composeRule.density.density >= 48f)

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("merchant_field").assertTextContains("New draft merchant")
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("amount_display"))
        composeRule.onNodeWithTag("amount_display").assertTextContains("-$1.00")

        val click =
            composeRule
                .onNodeWithTag("save_transaction_button")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            click()
            click()
        }
        awaitRows { it.size == 1 }
        val saved = rows().single()
        assertEquals("New draft merchant", saved.merchant)
        assertEquals(-100, saved.cents)
        assertEquals("local", saved.source)
    }

    @Test
    fun dirtyCloseSupportsKeepEditingAndDiscard() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_1").performClick()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertDoesNotExist()
        assertTrue(rows().isEmpty())
    }

    @Test
    fun dirtySystemBackShowsSaveableDiscardDialog() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_2").performClick()
        composeRule
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertDoesNotExist()
        assertTrue(rows().isEmpty())
    }

    @Test
    fun dirtySheetDragUsesSharedDiscardDialog() {
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_3").performClick()
        composeRule.onNodeWithTag("editor_sheet").performTouchInput { swipeDown() }

        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithTag("transaction_editor").assertIsDisplayed()
    }

    @Test
    fun labelledDateAndTimePickersPreserveValidDraftOnSave() {
        val expectedDate = LocalDate.now()
        composeRule.onNodeWithTag("add_transaction_fab").performClick()
        composeRule.onNodeWithTag("amount_key_4").performClick()
        composeRule.onNodeWithTag("merchant_field").performScrollTo().performTextInput("Picker merchant")

        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("more_details_toggle"))
        composeRule.onNodeWithTag("more_details_toggle").performClick()
        composeRule
            .onNodeWithTag("transaction_editor_form")
            .performScrollToNode(hasTestTag("date_picker_button"))
        composeRule.onNodeWithTag("date_picker_button").performClick()
        composeRule.onNodeWithText("Select date").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithTag("time_picker_button").performScrollTo().performClick()
        composeRule.onNodeWithText("Select time").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        composeRule.onNodeWithTag("save_transaction_button").performClick()
        awaitRows { it.singleOrNull()?.merchant == "Picker merchant" }
        val saved = rows().single()
        assertEquals(-400, saved.cents)
        assertEquals(expectedDate, saved.localDate())
        assertTrue(saved.occurredAtEpochMillis > 0)
    }

    @Test
    fun editorDeleteConfirmsThenDeletesAndUndoRestoresExactRow() {
        val dateTime = LocalDateTime.of(LocalDate.of(2026, 7, 10), LocalTime.of(9, 30))
        val original =
            transaction(
                id = "delete-phase2",
                merchant = "Delete Cafe",
                occurredAtEpochMillis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                cents = -1_234,
            )
        seed(original)
        waitForMerchant(original.merchant)

        composeRule.onNodeWithTag("transaction_content_${original.id}").performClick()
        composeRule.onNodeWithTag("delete_transaction_button").performClick()
        composeRule.onNodeWithText("Delete transaction?").assertIsDisplayed()
        assertEquals(original, rows().single())
        composeRule.onNodeWithTag("confirm_delete_transaction").performClick()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        awaitRows { it.isEmpty() }

        composeRule.onNodeWithText("Undo").performClick()
        awaitRows { it.singleOrNull()?.id == original.id }
        assertEquals(original, rows().single())
    }

    @Test
    fun talkBackDeleteActionRequiresConfirmationThenOffersUndo() {
        val dateTime = LocalDateTime.of(LocalDate.of(2026, 7, 11), LocalTime.of(10, 45))
        val original =
            transaction(
                id = "talkback-delete",
                merchant = "Accessible Cafe",
                occurredAtEpochMillis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                cents = -2_345,
            )
        seed(original)
        waitForMerchant(original.merchant)
        val action =
            composeRule
                .onNodeWithTag("transaction_content_${original.id}")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Delete" }
                .action

        composeRule.runOnIdle { action() }

        composeRule.onNodeWithText("Delete transaction?").assertIsDisplayed()
        assertEquals(original, rows().single())
        composeRule.onNodeWithTag("confirm_delete_transaction").performClick()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        awaitRows { it.isEmpty() }
    }

    private fun seed(vararg transactions: Transaction) {
        runBlocking {
            FlowMoneyDatabase.get(context()).transactionDao().upsertAll(transactions.map { it.toEntity() })
        }
    }

    private fun rows(): List<Transaction> =
        runBlocking {
            FlowMoneyDatabase
                .get(context())
                .transactionDao()
                .getAll()
                .map { it.toTransaction() }
        }

    private fun awaitRows(predicate: (List<Transaction>) -> Boolean) {
        composeRule.waitUntil(5_000) { predicate(rows()) }
    }

    private fun waitForMerchant(merchant: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(merchant).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(merchant).onFirst().assertIsDisplayed()
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun transaction(
        id: String,
        merchant: String,
        occurredAtEpochMillis: Long = Instant.now().toEpochMilli(),
        cents: Int = -500,
        source: String = "local",
        accountKey: String? = null,
        accountName: String? = null,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchant,
        category = "Food",
        note = "Phase 2",
        cents = cents,
        source = source,
        accountKey = accountKey,
        accountName = accountName,
    )
}
