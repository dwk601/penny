package com.dwk.flowmoney

import java.util.Locale
import kotlin.math.absoluteValue

object MoneyFormatter {
    fun parseAmountToCents(input: String): Int {
        val cleaned = input
            .trim()
            .replace("$", "")
            .replace(",", "")
            .replace("+", "")

        if (cleaned.isBlank() || cleaned == "-" || cleaned == ".") return 0

        val negative = cleaned.startsWith("-")
        val unsigned = cleaned.removePrefix("-")
        val parts = unsigned.split(".", limit = 2)
        val whole = parts.getOrNull(0).orEmpty().filter(Char::isDigit).ifBlank { "0" }
        val cents = parts
            .getOrNull(1)
            .orEmpty()
            .filter(Char::isDigit)
            .padEnd(2, '0')
            .take(2)
            .ifBlank { "00" }

        val total = whole.toLong() * 100L + cents.toLong()
        val signed = if (negative) -total else total
        return signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun formatUsd(cents: Int): String = formatUsd(cents.toLong())

    fun formatUsd(cents: Long): String {
        val digits = cents.toString().removePrefix("-").padStart(3, '0')
        val dollars = digits.dropLast(2).trimStart('0').ifEmpty { "0" }
        val formatted = "\$${dollars.withGrouping()}.${digits.takeLast(2)}"
        return if (cents < 0) "-$formatted" else formatted
    }

    fun formatAmountText(cents: Int): String {
        val amount = cents.absoluteValue / 100.0
        return "%.2f".format(Locale.US, amount)
    }

    private fun String.withGrouping(): String {
        return this
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    }
}
