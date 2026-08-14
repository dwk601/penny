package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimpleFinMapperTest {
    @Test fun mapsUsdPostedTransactionsAndWarnsOnBadAmount() {
        val result = SimpleFinMapper.map(
            "connection-a",
            listOf(
                SimpleFinAccount(
                    providerConnectionId = "provider-a",
                    id = "acct",
                    name = "Checking",
                    orgName = "Bank",
                    currency = "USD",
                    balance = "1.00",
                    availableBalance = "0.50",
                    transactions = listOf(
                        SimpleFinTransaction("ok", posted = 10, amount = "-12.34", description = "Store", pending = false),
                        SimpleFinTransaction("bad", posted = 11, amount = "nope", description = "Bad", pending = false),
                    ),
                ),
            ),
        )

        assertThat(result.transactions.map { it.id }).containsExactly("simplefin:connection-a:cHJvdmlkZXItYQ:YWNjdA:b2s")
        assertThat(result.transactions.single().cents).isEqualTo(-1234)
        assertThat(result.warnings.single()).contains("malformed amount")
    }

    @Test fun mapsSecondsToMillisAndKeepsSameTransactionIdDistinctByAccount() {
        val result = SimpleFinMapper.map(
            "connection-a",
            listOf(
                account(id = "a1", transactions = listOf(tx(id = "same", posted = 123, amount = "1.23"))),
                account(id = "a2", transactions = listOf(tx(id = "same", posted = 124, amount = "-4.56"))),
            ),
        )

        assertThat(result.transactions.map { it.id })
            .containsExactly(
                "simplefin:connection-a:cHJvdmlkZXItYQ:YTE:c2FtZQ",
                "simplefin:connection-a:cHJvdmlkZXItYQ:YTI:c2FtZQ",
            )
        assertThat(result.transactions.first().occurredAtEpochMillis).isEqualTo(123_000L)
        assertThat(result.transactions.last().cents).isEqualTo(-456)
    }

    @Test fun skipsNonUsdMissingCurrencyPendingAndUnposted() {
        val result = SimpleFinMapper.map(
            "connection-a",
            listOf(
                account(id = "eur", currency = "EUR", transactions = listOf(tx())),
                account(id = "missing", currency = null, transactions = listOf(tx())),
                account(id = "usd", transactions = listOf(
                    tx(id = "pending", pending = true),
                    tx(id = "zero", posted = 0),
                    tx(id = "good", posted = 2),
                )),
            ),
        )

        assertThat(result.transactions.map { it.id }).containsExactly("simplefin:connection-a:cHJvdmlkZXItYQ:dXNk:Z29vZA")
    }

    @Test fun scopesOpaqueAccountAndTransactionIdsToConnection() {
        val first = SimpleFinMapper.map(
            "connection-a",
            listOf(account(id = "account:one", transactions = listOf(tx(id = "transaction:one")))),
        )
        val second = SimpleFinMapper.map(
            "connection-b",
            listOf(account(id = "account:one", transactions = listOf(tx(id = "transaction:one")))),
        )

        assertThat(first.accounts.single().accountId).isNotEqualTo(second.accounts.single().accountId)
        assertThat(first.transactions.single().id).isNotEqualTo(second.transactions.single().id)
        assertThat(first.transactions.single().accountKey).isEqualTo(first.accounts.single().accountId)
        assertThat(first.transactions.single().id).doesNotContain("account:one")
    }

    @Test fun scopesDuplicateProviderLocalIdsToProviderConnection() {
        val first = SimpleFinMapper.map(
            "connection-a",
            listOf(account(id = "same-account", providerConnectionId = "provider-a", transactions = listOf(tx(id = "same-tx")))),
        )
        val second = SimpleFinMapper.map(
            "connection-a",
            listOf(account(id = "same-account", providerConnectionId = "provider-b", transactions = listOf(tx(id = "same-tx")))),
        )

        assertThat(first.accounts.single().accountId).isNotEqualTo(second.accounts.single().accountId)
        assertThat(first.transactions.single().id).isNotEqualTo(second.transactions.single().id)
    }

    private fun account(
        id: String,
        providerConnectionId: String = "provider-a",
        currency: String? = "USD",
        transactions: List<SimpleFinTransaction>,
    ) = SimpleFinAccount(
        providerConnectionId = providerConnectionId,
        id = id,
        name = id,
        orgName = null,
        currency = currency,
        balance = null,
        availableBalance = null,
        transactions = transactions,
    )

    private fun tx(
        id: String = "tx",
        posted: Long = 1,
        amount: String = "1.00",
        pending: Boolean = false,
    ) = SimpleFinTransaction(id, posted, amount, "Merchant", pending)
}
