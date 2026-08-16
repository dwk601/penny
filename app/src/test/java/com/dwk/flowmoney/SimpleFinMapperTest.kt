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
    fun payeeIsProviderMerchantAndBlankPayeeFallsBackWhileDescriptionStaysRaw() {
        val result =
            SimpleFinMapper.map(
                origin(),
                listOf(
                    account(
                        id = "payees",
                        transactions =
                            listOf(
                                SimpleFinTransaction(
                                    id = "payee",
                                    posted = 1,
                                    amount = "-1.00",
                                    description = "RAW BANK DESCRIPTION 123",
                                    pending = false,
                                    payee = "  Local  Market #42  ",
                                ),
                                SimpleFinTransaction(
                                    id = "fallback",
                                    posted = 2,
                                    amount = "-2.00",
                                    description = "Fallback Store 99",
                                    pending = false,
                                    payee = "  \t ",
                                ),
                            ),
                    ),
                ),
            )

        val payee = result.transactions.single { it.merchant.contains("Market") }
        assertThat(payee.merchant).isEqualTo("  Local  Market #42  ")
        assertThat(normalizedProviderMerchantKey(payee.merchant)).isEqualTo("local market #42")
        assertThat(payee.providerDescription).isEqualTo("RAW BANK DESCRIPTION 123")
        assertThat(result.transactions.single { it.id.endsWith(":ZmFsbGJhY2s") }.merchant)
            .isEqualTo("Fallback Store 99")
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
    fun mapsProviderFlowKindFromRawDescriptionsWithExpectedSignSemantics() {
        val result =
            SimpleFinMapper.map(
                origin(),
                listOf(
                    account(
                        id = "classification",
                        transactions =
                            listOf(
                                SimpleFinTransaction(
                                    id = "card-side",
                                    posted = 1,
                                    amount = "100.00",
                                    description = "PAYMENT - THANK YOU",
                                    pending = false,
                                    payee = "Ordinary card account",
                                ),
                                SimpleFinTransaction(
                                    id = "automatic-payment",
                                    posted = 2,
                                    amount = "100.00",
                                    description = "AUTOMATIC PAYMENT",
                                    pending = false,
                                ),
                                SimpleFinTransaction(
                                    id = "card-wrong-sign",
                                    posted = 2,
                                    amount = "-100.00",
                                    description = "PAYMENT - THANK YOU",
                                    pending = false,
                                ),
                                SimpleFinTransaction(
                                    id = "bank-side",
                                    posted = 3,
                                    amount = "-100.00",
                                    description = "CREDIT CARD PAYMENT",
                                    pending = false,
                                ),
                                SimpleFinTransaction(
                                    id = "bank-wrong-sign",
                                    posted = 4,
                                    amount = "100.00",
                                    description = "CREDIT CARD PAYMENT",
                                    pending = false,
                                ),
                                SimpleFinTransaction(
                                    id = "payee-is-not-descriptor",
                                    posted = 5,
                                    amount = "100.00",
                                    description = "GROCERY PURCHASE",
                                    pending = false,
                                    payee = "PAYMENT - THANK YOU",
                                ),
                            ),
                    ),
                ),
            )

        assertThat(result.transactions.associate { it.id.substringAfterLast(':') to it.flowKind })
            .containsExactly(
                "Y2FyZC1zaWRl",
                FlowKind.TRANSFER,
                "YXV0b21hdGljLXBheW1lbnQ",
                FlowKind.TRANSFER,
                "Y2FyZC13cm9uZy1zaWdu",
                FlowKind.NORMAL,
                "YmFuay1zaWRl",
                FlowKind.TRANSFER,
                "YmFuay13cm9uZy1zaWdu",
                FlowKind.NORMAL,
                "cGF5ZWUtaXMtbm90LWRlc2NyaXB0b3I",
                FlowKind.NORMAL,
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
