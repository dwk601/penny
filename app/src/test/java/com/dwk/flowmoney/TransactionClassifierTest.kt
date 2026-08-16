package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TransactionClassifierTest {
    @Test
    fun classifiesEveryAnchoredDescriptorWithItsExpectedSign() {
        cardSideDescriptors.forEach { assertKind(it, 12_345, FlowKind.TRANSFER) }
        bankSideDescriptors.forEach { assertKind(it, -12_345, FlowKind.TRANSFER) }
    }

    @Test
    fun automaticPaymentIsCardSideTransferOnlyForPositiveAmounts() {
        assertKind("AUTOMATIC PAYMENT", 100, FlowKind.TRANSFER)
        assertKind("  automatic payment  ", 100, FlowKind.TRANSFER)
        assertKind("AUTOMATIC PAYMENT", -100, FlowKind.NORMAL)
        assertKind("AUTOMATIC PAYMENT", 0, FlowKind.NORMAL)
        assertKind("AUTOMATIC PAYMENT PLAN", 100, FlowKind.NORMAL)
    }

    @Test
    fun signMismatchesAndZeroAmountsStayNormal() {
        cardSideDescriptors.forEach { description ->
            assertKind(description, -12_345, FlowKind.NORMAL)
            assertKind(description, 0, FlowKind.NORMAL)
        }
        bankSideDescriptors.forEach { description ->
            assertKind(description, 12_345, FlowKind.NORMAL)
            assertKind(description, 0, FlowKind.NORMAL)
        }
    }

    @Test
    fun paymentNearMissesStayNormalForEitherSign() {
        val nearMisses =
            listOf(
                "AUTOMATIC PAYMENT PLAN",
                "AUTOMATIC PAYMENT - THANKS",
                "PREFIX AUTOMATIC PAYMENT - THANK",
                "PAYMENT - THANK YOU AGAIN",
                "AUTOPAY PYMT FEE",
                "CREDIT CRD AUTOPAYMENT",
                "CARDMEMBER SERVICE WEB PAYMENT",
                "DEBIT CARDMEMBER SERVICE WEB PAY",
                "CREDIT CARD PAYMENT RECEIVED",
                "UTILITY PAYMENT",
            )

        nearMisses.forEach { description ->
            assertKind(description, 100, FlowKind.NORMAL)
            assertKind(description, -100, FlowKind.NORMAL)
        }
    }

    @Test
    fun p2pDescriptorsAreExplicitlyNotTransfers() {
        val p2pDescriptors =
            listOf(
                "VENMO PAYMENT",
                "ZELLE PAYMENT",
                "CASH APP TRANSFER",
                "PAYPAL INST XFER",
                "APPLE CASH PAYMENT",
            )

        p2pDescriptors.forEach { description ->
            assertKind(description, 100, FlowKind.NORMAL)
            assertKind(description, -100, FlowKind.NORMAL)
        }
    }

    @Test
    fun matchingIsCaseInsensitiveAndIgnoresOuterSpaces() {
        assertThat(TransactionClassifier.classify("  payment - thank you  ", 100))
            .isEqualTo(FlowKind.TRANSFER)
        assertThat(TransactionClassifier.classify("PAYMENT  - THANK YOU", 100))
            .isEqualTo(FlowKind.NORMAL)
    }

    private fun assertKind(
        description: String,
        cents: Int,
        expected: FlowKind,
    ) {
        assertWithMessage("description=%s cents=%s", description, cents)
            .that(TransactionClassifier.classify(description, cents))
            .isEqualTo(expected)
    }

    private companion object {
        val cardSideDescriptors =
            listOf(
                "AUTOMATIC PAYMENT - THANK",
                "AUTOMATIC PAYMENT ONLINE THANK",
                "PAYMENT - THANK YOU",
                "AUTOPAY PYMT",
            )
        val bankSideDescriptors =
            listOf(
                "CREDIT CRD AUTOPAY",
                "CARDMEMBER SERVICE WEB PAY",
                "CARDMEMBER SERVICE ACH PAY",
                "CREDIT CARD PAYMENT",
            )
    }
}
