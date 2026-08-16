package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SimpleFinIdentityReconcilerTest {
    private val origin = SimpleFinServerOrigin.fromAccessUrl("https://user:password@bridge.simplefin.org/simplefin")

    @Test
    fun planIsDeterministicRegardlessOfDatabaseRowOrder() {
        val accountOne = account(legacyAccountId(CONNECTION_ONE), "Older", lastSeen = 10)
        val accountTwo = account(legacyAccountId(CONNECTION_TWO), "Newer", lastSeen = 20)
        val transactionOne =
            transaction(legacyTransactionId(CONNECTION_ONE), accountOne.accountId).copy(
                merchant = "Older provider value",
                category = "User category",
            )
        val transactionTwo =
            transaction(legacyTransactionId(CONNECTION_TWO), accountTwo.accountId).copy(
                merchant = "Newer provider value",
                note = "User note",
                recurringInterval = RecurrenceInterval.Monthly.name,
            )

        val forward =
            SimpleFinIdentityReconciler.plan(
                origin,
                transactions = listOf(transactionOne, transactionTwo),
                accounts = listOf(accountOne, accountTwo),
                tombstones = emptyList(),
            )
        val reverse =
            SimpleFinIdentityReconciler.plan(
                origin,
                transactions = listOf(transactionTwo, transactionOne),
                accounts = listOf(accountTwo, accountOne),
                tombstones = emptyList(),
            )

        assertThat(reverse).isEqualTo(forward)
    }

    @Test
    fun transactionCollisionPreservesEditedMetadataWithStableRowPrecedence() {
        val stableAccountId = SimpleFinIdentity.accountId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID)
        val stableTransactionId =
            SimpleFinIdentity.transactionId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID, REMOTE_TRANSACTION_ID)
        val legacy =
            transaction(legacyTransactionId(CONNECTION_ONE), legacyAccountId(CONNECTION_ONE)).copy(
                category = "Legacy category",
                note = "Legacy note",
                recurringInterval = RecurrenceInterval.Monthly.name,
            )
        val stable =
            transaction(stableTransactionId, stableAccountId).copy(
                merchant = "Stable provider value",
                category = "Stable category",
            )

        val merged =
            SimpleFinIdentityReconciler
                .plan(
                    origin,
                    transactions = listOf(legacy, stable),
                    accounts = listOf(account(stableAccountId, "Checking", 30)),
                    tombstones = emptyList(),
                ).transactions
                .single()

        assertThat(merged.id).isEqualTo(stableTransactionId)
        assertThat(merged.merchant).isEqualTo("Stable provider value")
        assertThat(merged.category).isEqualTo("Stable category")
        assertThat(merged.note).isEqualTo("Legacy note")
        assertThat(merged.recurringInterval).isEqualTo(RecurrenceInterval.Monthly.name)
        assertThat(merged.accountKey).isEqualTo(stableAccountId)
    }

    @Test
    fun transactionCollisionRetainsFlowCorrectionFromLosingLegacyRow() {
        val stableAccountId = SimpleFinIdentity.accountId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID)
        val stableTransactionId =
            SimpleFinIdentity.transactionId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID, REMOTE_TRANSACTION_ID)
        val legacy =
            transaction(legacyTransactionId(CONNECTION_ONE), legacyAccountId(CONNECTION_ONE)).copy(
                flowKindOverride = FlowKind.TRANSFER,
            )
        val stable = transaction(stableTransactionId, stableAccountId)

        val merged =
            SimpleFinIdentityReconciler
                .plan(
                    origin,
                    transactions = listOf(legacy, stable),
                    accounts = listOf(account(stableAccountId, "Checking", 30)),
                    tombstones = emptyList(),
                ).transactions
                .single()

        assertThat(merged.id).isEqualTo(stableTransactionId)
        assertThat(merged.flowKindOverride).isEqualTo(FlowKind.TRANSFER)
    }

    @Test
    fun tombstoneWinsEveryCollisionAndAcquiresOccurrenceFromDeletedBankRow() {
        val oldTransactionId = legacyTransactionId(CONNECTION_ONE)
        val oldTransaction = transaction(oldTransactionId, legacyAccountId(CONNECTION_ONE)).copy(occurredAtEpochMillis = 12_345L)
        val secondOldTransaction = transaction(legacyTransactionId(CONNECTION_TWO), legacyAccountId(CONNECTION_TWO))
        val tombstones =
            listOf(
                SimpleFinIgnoredTransactionEntity(oldTransactionId, ignoredAtEpochMillis = 100L),
                SimpleFinIgnoredTransactionEntity(secondOldTransaction.id, ignoredAtEpochMillis = 200L),
            )

        val plan =
            SimpleFinIdentityReconciler.plan(
                origin,
                transactions = listOf(oldTransaction, secondOldTransaction),
                accounts =
                    listOf(
                        account(legacyAccountId(CONNECTION_ONE), "Checking", 30),
                        account(legacyAccountId(CONNECTION_TWO), "Checking", 20),
                    ),
                tombstones = tombstones,
            )

        assertThat(plan.transactions).isEmpty()
        assertThat(plan.tombstones).containsExactly(
            SimpleFinIgnoredTransactionEntity(
                transactionId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        PROVIDER_CONNECTION_ID,
                        REMOTE_ACCOUNT_ID,
                        REMOTE_TRANSACTION_ID,
                    ),
                ignoredAtEpochMillis = 200L,
                occurredAtEpochMillis = 12_345L,
            ),
        )
    }

    @Test
    fun duplicateAccountsCollapseToStableIdAndAllTransactionReferencesAreRewritten() {
        val oldAccount = account(legacyAccountId(CONNECTION_ONE), "Old checking", lastSeen = 10)
        val newestAccount =
            account(legacyAccountId(CONNECTION_TWO), "Current checking", lastSeen = 50).copy(
                institutionName = "Current bank",
                balanceAmount = "25.00",
            )
        val oldTransaction = transaction(legacyTransactionId(CONNECTION_ONE), oldAccount.accountId)

        val plan =
            SimpleFinIdentityReconciler.plan(
                origin,
                transactions = listOf(oldTransaction),
                accounts = listOf(oldAccount, newestAccount),
                tombstones = emptyList(),
            )
        val expectedAccountId = SimpleFinIdentity.accountId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID)

        assertThat(plan.accounts).containsExactly(
            newestAccount.copy(accountId = expectedAccountId),
        )
        assertThat(plan.transactions.single().id).isEqualTo(
            SimpleFinIdentity.transactionId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID, REMOTE_TRANSACTION_ID),
        )
        assertThat(plan.transactions.single().accountKey).isEqualTo(expectedAccountId)
        assertThat(plan.transactions.single().accountName).isEqualTo("Current checking")
    }

    private fun account(
        id: String,
        name: String,
        lastSeen: Long,
    ) = SimpleFinAccountEntity(
        accountId = id,
        name = name,
        currency = "USD",
        institutionName = "Bank",
        balanceAmount = "10.00",
        availableBalanceAmount = "9.00",
        balanceDateEpochSeconds = null,
        lastSeenAtEpochMillis = lastSeen,
    )

    private fun transaction(
        id: String,
        accountId: String,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = 12_345L,
        merchant = "Provider merchant",
        category = "Other",
        note = "",
        cents = -100,
        source = "simplefin",
        accountKey = accountId,
        accountName = "Checking",
    )

    private fun legacyAccountId(connectionId: String): String =
        "simplefin:$connectionId:${encode(PROVIDER_CONNECTION_ID)}:${encode(REMOTE_ACCOUNT_ID)}"

    private fun legacyTransactionId(connectionId: String): String = legacyAccountId(connectionId) + ":" + encode(REMOTE_TRANSACTION_ID)

    private fun encode(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CONNECTION_ONE = "11111111-1111-4111-8111-111111111111"
        const val CONNECTION_TWO = "22222222-2222-4222-8222-222222222222"
        const val PROVIDER_CONNECTION_ID = "provider-connection"
        const val REMOTE_ACCOUNT_ID = "remote-account"
        const val REMOTE_TRANSACTION_ID = "remote-transaction"
    }
}
