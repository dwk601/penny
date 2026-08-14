package com.dwk.flowmoney

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Base64

object SimpleFinMapper {
    fun map(connectionId: String, accounts: List<SimpleFinAccount>): SimpleFinMappingResult {
        val now = System.currentTimeMillis()
        val accountRows = accounts.map {
            SimpleFinAccountEntity(accountKey(connectionId, it.providerConnectionId, it.id), it.name, it.currency, it.orgName, it.balance, it.availableBalance, null, now)
        }
        val warnings = mutableListOf<String>()
        val transactions = accounts.flatMap { account ->
            if (!account.currency.equals("USD", ignoreCase = true)) return@flatMap emptyList()
            account.transactions.mapNotNull { tx ->
                if (tx.pending || tx.posted <= 0) return@mapNotNull null
                val cents = tx.amount.toCentsOrNull() ?: run {
                    warnings += "Skipped ${account.name} transaction ${tx.id}: malformed amount"
                    return@mapNotNull null
                }
                val accountKey = accountKey(connectionId, account.providerConnectionId, account.id)
                TransactionEntity(
                    id = "$accountKey:${encodeId(tx.id)}",
                    occurredAtEpochMillis = tx.posted * 1000L,
                    merchant = tx.description,
                    category = "Other",
                    note = "",
                    cents = cents,
                    source = "simplefin",
                    accountKey = accountKey,
                    accountName = account.name,
                )
            }
        }
        return SimpleFinMappingResult(transactions, accountRows, warnings)
    }

    private fun String.toCentsOrNull(): Int? = runCatching {
        BigDecimal(this).movePointRight(2).setScale(0).intValueExact()
    }.getOrNull()

    private fun accountKey(connectionId: String, providerConnectionId: String, accountId: String) =
        "simplefin:$connectionId:${encodeId(providerConnectionId)}:${encodeId(accountId)}"

    private fun encodeId(id: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8))
}
