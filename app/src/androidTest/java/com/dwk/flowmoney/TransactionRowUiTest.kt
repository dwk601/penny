package com.dwk.flowmoney

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import java.time.LocalDateTime
import java.time.ZoneId

class TransactionRowUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun compactWidthAtTwoXFontKeepsFullAmountVisibleAndRowAccessible() {
        val transaction = transaction(id = "compact", cents = Int.MIN_VALUE)
        setTransactionScreen(transaction = transaction, width = 240, fontScale = 2f)
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
        assertEquals(1, textLayout.lineCount)
        assertTrue("Expected reduced font for row width ${rowNode.boundsInRoot.width}: $textLayout", textLayout.layoutInput.style.fontSize.value < 16f)
        val minimumLayoutWidth =
            rowNode.boundsInRoot.width - with(composeRule.density) { 30.dp.toPx() }
        assertTrue(
            "Amount layout width=${textLayout.size.width} did not use the compact row width=${rowNode.boundsInRoot.width}",
            textLayout.size.width >= minimumLayoutWidth,
        )
    }

    @Test
    fun defaultWidthShowsAmountBesideMerchantAndPlaceOnSecondLine() {
        val transaction = transaction("place", -783).copy(
            merchant = "Starbucks", category = "Coffee", source = "simplefin",
            accountName = "Checking", locationCity = "New York", locationState = "NY",
            transactedAtEpochMillis = null,
        )
        setTransactionScreen(transaction)
        scrollToTransaction(transaction.id)
        composeRule.onNodeWithTag("transaction_content_place").assertContentDescriptionEquals(
            "Starbucks, -\$7.83, Coffee, New York, NY, Checking, Posted " +
                java.time.Instant.ofEpochMilli(1L).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")),
        )
        val merchant = composeRule.onNodeWithText("Starbucks", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val amount = composeRule.onNodeWithText("-\$7.83", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("amount should share the merchant line", amount.top < merchant.bottom && merchant.top < amount.bottom)
        composeRule.onNodeWithText("Coffee · New York, NY", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun bankRowUsesPurchaseTimeRatherThanPostingTime() {
        val posted = LocalDateTime.of(2026, 7, 11, 2, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val purchased = LocalDateTime.of(2026, 7, 10, 14, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val transaction = transaction("purchase-time", -100).copy(
            source = "simplefin", accountName = "Checking", occurredAtEpochMillis = posted,
            transactedAtEpochMillis = purchased,
        )
        setTransactionScreen(transaction)
        scrollToTransaction(transaction.id)
        composeRule.onNodeWithText("Other · Checking", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Jul 10, 2:15 PM", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun localRowWithoutPurchaseTimeUsesOccurrenceTime() {
        val occurred = LocalDateTime.of(2026, 7, 10, 9, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val transaction = transaction("local-time", -100).copy(occurredAtEpochMillis = occurred)
        setTransactionScreen(transaction)
        scrollToTransaction(transaction.id)
        composeRule.onNodeWithText("Other", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("9:45 AM", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun rowAbove280DpDoesNotStack() = assertAmountPlacement(screenWidth = 324, stacked = false)

    @Test fun longMerchantYieldsToAmount() = assertAmountPlacement(screenWidth = 300, stacked = false, merchantName = "A very long merchant name")

    @Test fun largeFontRemainsInlineWhenReservationFits() = assertAmountPlacement(screenWidth = 360, fontScale = 1.5f, stacked = false)

    @Test fun amountCandidatesAreBoundedForBothSigns() {
        val transactions = (1..10000).map { transaction("$it", if (it % 2 == 0) it else -it) } + transaction("min", Int.MIN_VALUE)
        assertEquals(listOf(Int.MIN_VALUE, 10000), transactionAmountCandidates(transactions))
        val measured = mutableListOf<String>()
        transactionAmountWidth(transactions) { measured += it; it.length }
        assertEquals(listOf("-\$21,474,836.48", "\$100.00"), measured)
        assertEquals(0, transactionAmountWidth(emptyList()) { error("empty list measured") })
    }

    @Test fun amazonNamesEllipsizeInsteadOfStackingAt360Dp() {
        val names = listOf("AMAZON MKTPLACE PMTS WA", "AMAZON MKTPLACE PMTS WA SEATTLE MARKETPLACE")
        val rows = names.mapIndexed { i, name -> transaction("amazon$i", -12300 - i).copy(merchant = name, occurredAtEpochMillis = 86_400_000L * (i + 1)) }
        setTransactionScreen(rows)
        rows.forEach { row ->
            scrollToTransaction(row.id)
            val merchant = composeRule.onNodeWithText(row.merchant, useUnmergedTree = true)
            val merchantBounds = merchant.fetchSemanticsNode().boundsInRoot
            val amount = composeRule.onNodeWithText(MoneyFormatter.formatUsd(row.cents), useUnmergedTree = true)
            assertTrue(amount.fetchSemanticsNode().boundsInRoot.top < merchantBounds.bottom)
            val merchantLayout = mutableListOf<TextLayoutResult>()
            merchant.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(merchantLayout) }
            assertTrue(merchantLayout.single().isLineEllipsized(0))
            val amountLayout = mutableListOf<TextLayoutResult>()
            amount.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(amountLayout) }
            assertEquals(16f, amountLayout.single().layoutInput.style.fontSize.value)
        }
    }

    @Test fun wideAmountStacksAllRowsAcrossDaysAtOneX() {
        val rows = listOf(transaction("wide", Int.MIN_VALUE).copy(merchant = "Wide", occurredAtEpochMillis = 172_800_000L), transaction("short", -100).copy(merchant = "Short", occurredAtEpochMillis = 86_400_000L))
        setTransactionScreen(rows, width = 300)
        rows.forEach { row ->
            scrollToTransaction(row.id)
            val merchant = composeRule.onNodeWithText(row.merchant, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val amount = composeRule.onNodeWithText(MoneyFormatter.formatUsd(row.cents), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("${row.id} amount must stack", amount.top >= merchant.bottom)
        }
    }

    @Test fun mixedListAtOnePointFiveXStaysInlineWhenWidestAmountFits() {
        val rows = listOf(transaction("mixed-wide", -1200).copy(merchant = "Longer merchant name"), transaction("mixed-short", -100).copy(merchant = "Short"))
        setTransactionScreen(rows, width = 360, fontScale = 1.5f)
        rows.forEach { row ->
            scrollToTransaction(row.id)
            val merchant = composeRule.onNodeWithText(row.merchant, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val amount = composeRule.onNodeWithText(MoneyFormatter.formatUsd(row.cents), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("${row.id} should be inline", amount.top < merchant.bottom)
        }
    }

    @Test fun shortAmountsGiveMerchantMoreRoomWithinSameList() {
        val rows = listOf(transaction("long-amount", -12_345_678).copy(merchant = "First"), transaction("short-amount", -100).copy(merchant = "Second"))
        setTransactionScreen(rows)
        scrollToTransaction("short-amount")
        val first = composeRule.onNodeWithText("First", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithText("Second", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("short amount should leave more merchant width", second.width > first.width)
    }

    @Test fun metadataDropsPlaceInsteadOfLeavingSeparatorAndEllipsis() {
        val occurred = LocalDateTime.of(2026, 7, 10, 9, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val row = transaction("no-dangling", -100).copy(category = "Subscriptions", accountName = "Checking", occurredAtEpochMillis = occurred)
        setTransactionScreen(row, width = 300, fontScale = 2f)
        scrollToTransaction(row.id)
        composeRule.onNodeWithText("Subscriptions", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Subscriptions · Checking", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("9:45 AM", useUnmergedTree = true).assertExists()
    }

    @Test fun metadataKeepsTimeCompleteAndEllipsizesPlaceAtOneX() = assertMetadata(1f)
    @Test fun metadataKeepsTimeCompleteAndEllipsizesPlaceAtTwoX() = assertMetadata(2f)

    private fun assertMetadata(fontScale: Float) {
        val posted = LocalDateTime.of(2026, 7, 11, 2, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val purchased = LocalDateTime.of(2026, 7, 10, 14, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val row = transaction("metadata", -100).copy(source = "simplefin", occurredAtEpochMillis = posted, transactedAtEpochMillis = purchased, locationCity = "An exceptionally long municipality name that must yield", locationState = "NY")
        setTransactionScreen(row, width = 300, fontScale = fontScale)
        scrollToTransaction(row.id)
        val flexible = composeRule.onNodeWithText("Other · An exceptionally long municipality name that must yield, NY", useUnmergedTree = true)
        val temporal = composeRule.onNodeWithText("Jul 10, 2:15 PM", useUnmergedTree = true)
        val flexLayout = mutableListOf<TextLayoutResult>()
        val timeLayout = mutableListOf<TextLayoutResult>()
        flexible.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(flexLayout) }
        temporal.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(timeLayout) }
        assertTrue(flexLayout.single().isLineEllipsized(0))
        assertFalse("temporal layout: ${timeLayout.single()} size=${timeLayout.single().size} input=${timeLayout.single().layoutInput}", timeLayout.single().hasVisualOverflow)
        repeat(timeLayout.single().lineCount) { assertFalse(timeLayout.single().isLineEllipsized(it)) }
        val flexBounds = flexible.fetchSemanticsNode().boundsInRoot
        val timeBounds = temporal.fetchSemanticsNode().boundsInRoot
        if (timeBounds.top < flexBounds.bottom) assertTrue(flexBounds.width / composeRule.density.density >= 64f)
        else assertTrue(timeBounds.top >= flexBounds.bottom)
    }

    private fun assertAmountPlacement(screenWidth: Int, fontScale: Float = 1f, stacked: Boolean, merchantName: String = "Shop") {
        val transaction = transaction("threshold", -783).copy(merchant = merchantName)
        setTransactionScreen(transaction, width = screenWidth, fontScale = fontScale)
        scrollToTransaction(transaction.id)
        val row = composeRule.onNodeWithTag("transaction_content_threshold").fetchSemanticsNode()
        val rowWidthDp = row.boundsInRoot.width / row.layoutInfo.density.density
        val merchant = composeRule.onNodeWithText(merchantName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val amount = composeRule.onNodeWithText("-\$7.83", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("unexpected row width $rowWidthDp", kotlin.math.abs(rowWidthDp - (screenWidth - 40)) < 1f)
        assertEquals(stacked, amount.top >= merchant.bottom)
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

    private fun setTransactionScreen(transaction: Transaction, width: Int = 360, fontScale: Float = 1f) =
        setTransactionScreen(listOf(transaction), width, fontScale)

    private fun setTransactionScreen(
        transactions: List<Transaction>,
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
                            uiState = MainUiState(isLoading = false, sortedTransactions = transactions),
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
