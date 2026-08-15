package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimpleFinMapperTest {
    @Test
    fun mapsUsdPostedTransactionsWithStableRemoteIdentityAndWarnsOnBadAmount() {
        val result =
            SimpleFinMapper.map(
                origin(),
                listOf(
                    SimpleFinAccount(
                        providerConnectionId = "provider-a",
                        id = "acct",
                        name = "Checking",
                        orgName = "Bank",
                        currency = "USD",
                        balance = "1.00",
                        availableBalance = "0.50",
                        transactions =
                            listOf(
                                SimpleFinTransaction("ok", posted = 10, amount = "-12.34", description = "Store", pending = false),
                                SimpleFinTransaction("bad", posted = 11, amount = "nope", description = "Bad", pending = false),
                            ),
                    ),
                ),
            )

        assertThat(result.transactions.map { it.id }).containsExactly(
            "simplefin:v2:aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZw:cHJvdmlkZXItYQ:YWNjdA:b2s",
        )
        assertThat(result.transactions.single().cents).isEqualTo(-1234)
        assertThat(result.warnings.single()).contains("malformed amount")
    }

    @Test
    fun mapsSecondsToMillisAndKeepsSameTransactionIdDistinctByAccount() {
        val result =
            SimpleFinMapper.map(
                origin(),
                listOf(
                    account(id = "a1", transactions = listOf(tx(id = "same", posted = 123, amount = "1.23"))),
                    account(id = "a2", transactions = listOf(tx(id = "same", posted = 124, amount = "-4.56"))),
                ),
            )

        assertThat(result.transactions.map { it.id })
            .containsExactly(
                "simplefin:v2:aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZw:cHJvdmlkZXItYQ:YTE:c2FtZQ",
                "simplefin:v2:aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZw:cHJvdmlkZXItYQ:YTI:c2FtZQ",
            )
        assertThat(result.transactions.first().occurredAtEpochMillis).isEqualTo(123_000L)
        assertThat(result.transactions.last().cents).isEqualTo(-456)
    }

    @Test
    fun skipsNonUsdMissingCurrencyPendingAndUnposted() {
        val result =
            SimpleFinMapper.map(
                origin(),
                listOf(
                    account(id = "eur", currency = "EUR", transactions = listOf(tx())),
                    account(id = "missing", currency = null, transactions = listOf(tx())),
                    account(
                        id = "usd",
                        transactions =
                            listOf(
                                tx(id = "pending", pending = true),
                                tx(id = "zero", posted = 0),
                                tx(id = "good", posted = 2),
                            ),
                    ),
                ),
            )

        assertThat(result.transactions.map { it.id }).containsExactly(
            "simplefin:v2:aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZw:cHJvdmlkZXItYQ:dXNk:Z29vZA",
        )
    }

    @Test
    fun canonicalOriginIgnoresCredentialsCaseDefaultPortPathAndQuery() {
        val firstOrigin =
            origin(
                "HTTPS://first-user:first-password@BRIDGE.SimpleFIN.org:443/private/access?token=first-secret",
            )
        val secondOrigin =
            origin(
                "https://second-user:second-password@bridge.simplefin.org/another/path?token=second-secret",
            )
        val first = SimpleFinMapper.map(firstOrigin, listOf(account(id = "account", transactions = listOf(tx(id = "transaction")))))
        val second = SimpleFinMapper.map(secondOrigin, listOf(account(id = "account", transactions = listOf(tx(id = "transaction")))))

        assertThat(firstOrigin.value).isEqualTo("https://bridge.simplefin.org")
        assertThat(secondOrigin).isEqualTo(firstOrigin)
        assertThat(first.accounts.single().accountId).isEqualTo(second.accounts.single().accountId)
        assertThat(first.transactions.single().id).isEqualTo(second.transactions.single().id)
        val persistedIds = first.accounts.single().accountId + first.transactions.single().id
        assertThat(persistedIds).doesNotContain("first-user")
        assertThat(persistedIds).doesNotContain("first-password")
        assertThat(persistedIds).doesNotContain("private")
        assertThat(persistedIds).doesNotContain("first-secret")
        assertThat(persistedIds).doesNotContain("local-random-connection-id")
    }

    @Test
    fun scopesRemoteIdsToNonDefaultPortAndProviderConnection() {
        val defaultOrigin = SimpleFinMapper.map(origin(), listOf(account(id = "same", transactions = listOf(tx(id = "same")))))
        val otherPort =
            SimpleFinMapper.map(
                origin("https://user:password@bridge.simplefin.org:8443/simplefin"),
                listOf(account(id = "same", transactions = listOf(tx(id = "same")))),
            )
        val otherProvider =
            SimpleFinMapper.map(
                origin(),
                listOf(account(id = "same", providerConnectionId = "provider-b", transactions = listOf(tx(id = "same")))),
            )

        assertThat(defaultOrigin.accounts.single().accountId).isNotEqualTo(otherPort.accounts.single().accountId)
        assertThat(defaultOrigin.transactions.single().id).isNotEqualTo(otherPort.transactions.single().id)
        assertThat(defaultOrigin.accounts.single().accountId).isNotEqualTo(otherProvider.accounts.single().accountId)
        assertThat(defaultOrigin.transactions.single().id).isNotEqualTo(otherProvider.transactions.single().id)
        assertThat(defaultOrigin.transactions.single().accountKey).isEqualTo(defaultOrigin.accounts.single().accountId)
    }

    private fun origin(url: String = ACCESS_URL) = SimpleFinServerOrigin.fromAccessUrl(url)

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

    private companion object {
        const val ACCESS_URL = "https://user:password@bridge.simplefin.org/simplefin"
    }
}
