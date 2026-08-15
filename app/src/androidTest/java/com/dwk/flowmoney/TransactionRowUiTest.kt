package com.dwk.flowmoney

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TransactionRowUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun compactWidthAtTwoXFontKeepsFullAmountVisibleAndRowAccessible() {
        val transaction = transaction(id = "compact", cents = Int.MIN_VALUE)
        setTransactionScreen(transaction = transaction, width = 280, fontScale = 2f)
        scrollToTransaction(transaction.id)

        val rowTag = "transaction_content_${transaction.id}"
        val row = composeRule.onNodeWithTag(rowTag).assertIsDisplayed().assertHasClickAction()
        val rowNode = row.fetchSemanticsNode()
        assertTrue(rowNode.boundsInRoot.height / composeRule.density.density >= 48f)
        assertEquals(
            listOf("Edit", "Delete"),
            rowNode.config[SemanticsActions.CustomActions].map { it.label },
        )

        val amountText = "-\$21,474,836.48"
        val amount =
            composeRule
                .onNode(
                    hasText(amountText) and hasAnyAncestor(hasTestTag(rowTag)),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        val amountBounds = amount.fetchSemanticsNode().boundsInRoot
        assertContained(amountText, amountBounds, rowTag, rowNode.boundsInRoot)

        val textLayoutResults = mutableListOf<TextLayoutResult>()
        amount.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(textLayoutResults))
        }
        val textLayout = textLayoutResults.single()
        assertFalse(
            "Amount was clipped at compact width and 2x font scale: $textLayout",
            textLayout.hasVisualOverflow,
        )
        val minimumLayoutWidth =
            rowNode.boundsInRoot.width - with(composeRule.density) { 30.dp.toPx() }
        assertTrue(
            "Amount layout width=${textLayout.size.width} did not use the compact row width=${rowNode.boundsInRoot.width}",
            textLayout.size.width >= minimumLayoutWidth,
        )
    }

    @Test
    fun swipeLabelsAreAbsentAtRestAndOnlyShownForActiveDirection() {
        val transaction = transaction(id = "swipe", cents = -1_250)
        setTransactionScreen(transaction = transaction)
        scrollToTransaction(transaction.id)
        val rowTag = "transaction_content_${transaction.id}"

        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()

        composeRule.onNodeWithTag(rowTag).performTouchInput {
            down(center)
            moveBy(Offset(width.toFloat() * 0.25f, 0f))
        }
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
        composeRule.onNodeWithTag(rowTag).performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()

        composeRule.onNodeWithTag(rowTag).performTouchInput {
            down(center)
            moveBy(Offset(-width.toFloat() * 0.25f, 0f))
        }
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithTag(rowTag).performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    private fun setTransactionScreen(
        transaction: Transaction,
        width: Int = 360,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.Companion.ForcedSize(DpSize(width.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Companion.FontScale(fontScale)) {
                    FlowMoneyTheme(dynamicColor = false) {
                        FlowMoneyScreen(
                            uiState = MainUiState(isLoading = false, sortedTransactions = listOf(transaction)),
                            selectedTab = DashboardTab.Transactions,
                            onChartRangeModeSelected = {},
                            onSelectedMonthChange = {},
                            onEdit = {},
                            onViewAllTransactions = {},
                            onDelete = {},
                            onAddTransaction = {},
                            onData = {},
                        )
                    }
                }
            }
        }
    }

    private fun scrollToTransaction(id: String) {
        composeRule
            .onNodeWithTag("transactions_list")
            .performScrollToNode(hasTestTag("transaction_content_$id"))
    }

    private fun assertContained(
        text: String,
        child: Rect,
        parentTag: String,
        parent: Rect,
    ) {
        assertTrue(
            "$text bounds=$child escaped $parentTag bounds=$parent",
            child.left >= parent.left && child.top >= parent.top &&
                child.right <= parent.right && child.bottom <= parent.bottom,
        )
    }

    private fun transaction(
        id: String,
        cents: Int,
    ) = Transaction(
        id = id,
        occurredAtEpochMillis = 1L,
        merchant = "A merchant name that yields before the amount",
        category = "Other",
        note = "Secondary row text",
        cents = cents,
    )
}
