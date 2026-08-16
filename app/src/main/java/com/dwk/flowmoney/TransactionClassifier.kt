package com.dwk.flowmoney

import java.util.Locale

/** Deterministic classification for transaction descriptions that are safe to recognize in isolation. */
object TransactionClassifier {
    fun classify(
        description: String,
        cents: Int,
    ): FlowKind {
        val normalizedDescription = description.trim(' ').uppercase(Locale.ROOT)
        val isTransfer =
            when {
                cents > 0 -> normalizedDescription.isCardSideStatementPayment()
                cents < 0 -> normalizedDescription.isBankSideStatementPayment()
                else -> false
            }
        return if (isTransfer) FlowKind.TRANSFER else FlowKind.NORMAL
    }

    private fun String.isCardSideStatementPayment(): Boolean =
        this == "AUTOMATIC PAYMENT" ||
            (startsWith("AUTOMATIC PAYMENT ") && endsWith(" THANK")) ||
            this == "PAYMENT - THANK YOU" ||
            this == "AUTOPAY PYMT"

    private fun String.isBankSideStatementPayment(): Boolean =
        this == "CREDIT CRD AUTOPAY" ||
            (startsWith("CARDMEMBER SERVICE ") && endsWith(" PAY")) ||
            this == "CREDIT CARD PAYMENT"
}
