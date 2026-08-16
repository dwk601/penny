package com.dwk.flowmoney

import java.math.BigDecimal

object SimpleFinMapper {
    internal fun map(
        origin: SimpleFinServerOrigin,
        accounts: List<SimpleFinAccount>,
    ): SimpleFinMappingResult {
        val now = System.currentTimeMillis()
        val accountRows =
            accounts.map {
                SimpleFinAccountEntity(
                    SimpleFinIdentity.accountId(origin, it.providerConnectionId, it.id),
                    it.name,
                    it.currency,
                    it.orgName,
                    it.balance,
                    it.availableBalance,
                    null,
                    now,
                )
            }
        val warnings = mutableListOf<String>()
        val transactions =
            accounts.flatMap { account ->
                if (!account.currency.equals("USD", ignoreCase = true)) return@flatMap emptyList()
                account.transactions.mapNotNull { tx ->
                    if (tx.pending || tx.posted <= 0) return@mapNotNull null
                    val cents =
                        tx.amount.toCentsOrNull() ?: run {
                            warnings += "Skipped ${account.name} transaction ${tx.id}: malformed amount"
                            return@mapNotNull null
                        }
                    val accountKey = SimpleFinIdentity.accountId(origin, account.providerConnectionId, account.id)
                    val providerMerchant = tx.payee?.takeIf { it.isNotBlank() } ?: tx.description
                    TransactionEntity(
                        id =
                            SimpleFinIdentity.transactionId(
                                origin,
                                account.providerConnectionId,
                                account.id,
                                tx.id,
                            ),
                        occurredAtEpochMillis = tx.posted * 1000L,
                        merchant = providerMerchant,
                        category = "Other",
                        note = "",
                        cents = cents,
                        source = "simplefin",
                        accountKey = accountKey,
                        accountName = account.name,
                        providerDescription = tx.description,
                    )
                }
            }
        return SimpleFinMappingResult(transactions, accountRows, warnings)
    }

    private fun String.toCentsOrNull(): Int? =
        runCatching {
            BigDecimal(this).movePointRight(2).setScale(0).intValueExact()
        }.getOrNull()
}
