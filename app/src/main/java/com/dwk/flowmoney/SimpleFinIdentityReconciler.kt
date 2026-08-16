package com.dwk.flowmoney

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "simplefin_identity_state")
internal data class SimpleFinIdentityStateEntity(
    @PrimaryKey @ColumnInfo(defaultValue = "'stable_v2'") val id: String = STABLE_IDENTITY_STATE_ID,
    @ColumnInfo(defaultValue = "0") val reconciliationComplete: Boolean = false,
)

@Dao
internal interface SimpleFinIdentityDao {
    @Query("SELECT reconciliationComplete FROM simplefin_identity_state WHERE id = 'stable_v2'")
    suspend fun isReconciliationComplete(): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: SimpleFinIdentityStateEntity)

    @Query("SELECT * FROM transactions WHERE source = 'simplefin'")
    suspend fun simpleFinTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM simplefin_accounts")
    suspend fun accounts(): List<SimpleFinAccountEntity>

    @Query("SELECT * FROM simplefin_ignored_transactions")
    suspend fun tombstones(): List<SimpleFinIgnoredTransactionEntity>

    @Query("DELETE FROM transactions WHERE source = 'simplefin'")
    suspend fun clearSimpleFinTransactions()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSimpleFinTransactions(transactions: List<TransactionEntity>)

    @Query("DELETE FROM simplefin_accounts")
    suspend fun clearAccounts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<SimpleFinAccountEntity>)

    @Query("DELETE FROM simplefin_ignored_transactions")
    suspend fun clearTombstones()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<SimpleFinIgnoredTransactionEntity>)
}

internal data class SimpleFinIdentityReconciliationPlan(
    val transactions: List<TransactionEntity>,
    val accounts: List<SimpleFinAccountEntity>,
    val tombstones: List<SimpleFinIgnoredTransactionEntity>,
)

/** Credential-free occurrence metadata mapped from the current SimpleFIN payload. */
internal data class SimpleFinPayloadOccurrence(
    val transactionId: String,
    val occurredAtEpochMillis: Long,
)

internal object SimpleFinIdentityReconciler {
    suspend fun reconcile(
        dao: SimpleFinIdentityDao,
        origin: SimpleFinServerOrigin,
        payloadOccurrences: List<SimpleFinPayloadOccurrence>,
    ) {
        val plan =
            plan(
                origin = origin,
                transactions = dao.simpleFinTransactions(),
                accounts = dao.accounts(),
                tombstones = dao.tombstones(),
                payloadOccurrences = payloadOccurrences,
            )
        dao.clearSimpleFinTransactions()
        if (plan.transactions.isNotEmpty()) dao.insertSimpleFinTransactions(plan.transactions)
        dao.clearAccounts()
        if (plan.accounts.isNotEmpty()) dao.insertAccounts(plan.accounts)
        dao.clearTombstones()
        if (plan.tombstones.isNotEmpty()) dao.insertTombstones(plan.tombstones)
    }

    fun plan(
        origin: SimpleFinServerOrigin,
        transactions: List<TransactionEntity>,
        accounts: List<SimpleFinAccountEntity>,
        tombstones: List<SimpleFinIgnoredTransactionEntity>,
        payloadOccurrences: List<SimpleFinPayloadOccurrence> = emptyList(),
    ): SimpleFinIdentityReconciliationPlan {
        val accountGroups = linkedMapOf<String, MutableList<SimpleFinAccountEntity>>()
        val untouchedAccounts = mutableListOf<SimpleFinAccountEntity>()
        accounts.forEach { account ->
            val remote = SimpleFinIdentity.parseAccountId(account.accountId, origin)
            if (remote == null) {
                untouchedAccounts += account
            } else {
                val stableId = SimpleFinIdentity.accountId(origin, remote.providerConnectionId, remote.accountId)
                accountGroups.getOrPut(stableId, ::mutableListOf) += account
            }
        }
        val mergedAccounts = accountGroups.map { (stableId, rows) -> mergeAccount(stableId, rows) }
        val finalAccounts = (untouchedAccounts + mergedAccounts).sortedBy { it.accountId }
        val accountNames = finalAccounts.associate { it.accountId to it.name }
        val accountLastSeen = accounts.associate { it.accountId to it.lastSeenAtEpochMillis }

        val transactionGroups = linkedMapOf<String, TransactionGroup>()
        val untouchedTransactions = mutableListOf<TransactionEntity>()
        transactions.forEach { transaction ->
            val remote = SimpleFinIdentity.parseTransactionId(transaction.id, origin)
            if (remote == null) {
                untouchedTransactions += rewriteAccountReference(transaction, origin)
            } else {
                val stableId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        remote.providerConnectionId,
                        remote.accountId,
                        remote.transactionId,
                    )
                val group = transactionGroups.getOrPut(stableId) { TransactionGroup(remote) }
                group.rows += transaction
            }
        }

        val tombstoneGroups = linkedMapOf<String, MutableList<SimpleFinIgnoredTransactionEntity>>()
        val untouchedTombstones = mutableListOf<SimpleFinIgnoredTransactionEntity>()
        tombstones.forEach { tombstone ->
            val remote = SimpleFinIdentity.parseTransactionId(tombstone.transactionId, origin)
            if (remote == null) {
                untouchedTombstones += tombstone
            } else {
                val stableId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        remote.providerConnectionId,
                        remote.accountId,
                        remote.transactionId,
                    )
                tombstoneGroups.getOrPut(stableId, ::mutableListOf) += tombstone
            }
        }

        val payloadOccurrencesByStableId =
            payloadOccurrences
                .mapNotNull { occurrence ->
                    val remote = SimpleFinIdentity.parseTransactionId(occurrence.transactionId, origin) ?: return@mapNotNull null
                    val stableId =
                        SimpleFinIdentity.transactionId(
                            origin,
                            remote.providerConnectionId,
                            remote.accountId,
                            remote.transactionId,
                        )
                    stableId to occurrence.occurredAtEpochMillis
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, occurrences) -> occurrences.max() }

        val mergedTransactions =
            transactionGroups
                .filterKeys { it !in tombstoneGroups }
                .map { (stableId, group) ->
                    mergeTransaction(
                        stableId = stableId,
                        group = group,
                        origin = origin,
                        accountNames = accountNames,
                        accountLastSeen = accountLastSeen,
                    )
                }
        val mergedTombstones =
            tombstoneGroups.map { (stableId, rows) ->
                mergeTombstone(
                    stableId = stableId,
                    rows = rows,
                    transactions = transactionGroups[stableId]?.rows.orEmpty(),
                    payloadOccurredAtEpochMillis = payloadOccurrencesByStableId[stableId],
                )
            }

        return SimpleFinIdentityReconciliationPlan(
            transactions = (untouchedTransactions + mergedTransactions).sortedBy { it.id },
            accounts = finalAccounts,
            tombstones = (untouchedTombstones + mergedTombstones).sortedBy { it.transactionId },
        )
    }

    private fun mergeAccount(
        stableId: String,
        rows: List<SimpleFinAccountEntity>,
    ): SimpleFinAccountEntity {
        val ranked =
            rows.sortedWith(
                compareByDescending<SimpleFinAccountEntity> { it.accountId == stableId }
                    .thenByDescending { it.lastSeenAtEpochMillis }
                    .thenBy { it.accountId },
            )
        val base = ranked.first()
        return base.copy(
            accountId = stableId,
            name = ranked.firstOrNull { it.name.isNotBlank() }?.name ?: base.name,
            currency = ranked.firstNotNullOfOrNull { it.currency },
            institutionName = ranked.firstNotNullOfOrNull { it.institutionName },
            balanceAmount = ranked.firstNotNullOfOrNull { it.balanceAmount },
            availableBalanceAmount = ranked.firstNotNullOfOrNull { it.availableBalanceAmount },
            balanceDateEpochSeconds = ranked.firstNotNullOfOrNull { it.balanceDateEpochSeconds },
            lastSeenAtEpochMillis = rows.maxOf { it.lastSeenAtEpochMillis },
        )
    }

    private fun mergeTransaction(
        stableId: String,
        group: TransactionGroup,
        origin: SimpleFinServerOrigin,
        accountNames: Map<String, String>,
        accountLastSeen: Map<String, Long>,
    ): TransactionEntity {
        val stableAccountId =
            SimpleFinIdentity.accountId(
                origin,
                group.remote.providerConnectionId,
                group.remote.accountId,
            )
        val ranked =
            group.rows.sortedWith(
                compareByDescending<TransactionEntity> { it.id == stableId }
                    .thenByDescending { accountLastSeen[it.accountKey] ?: Long.MIN_VALUE }
                    .thenBy { it.id },
            )
        val base = ranked.first()
        return base.copy(
            id = stableId,
            category = ranked.firstOrNull { it.category != DEFAULT_SIMPLEFIN_CATEGORY }?.category ?: base.category,
            note = ranked.firstOrNull { it.note.isNotBlank() }?.note ?: base.note,
            recurringInterval = ranked.firstOrNull { it.recurringInterval != null }?.recurringInterval,
            reviewedAtEpochMillis = ranked.firstOrNull { it.reviewedAtEpochMillis != null }?.reviewedAtEpochMillis,
            merchantOverride = ranked.firstOrNull { it.merchantOverride != null }?.merchantOverride,
            flowKindOverride = ranked.firstOrNull { it.flowKindOverride != null }?.flowKindOverride,
            source = "simplefin",
            accountKey = stableAccountId,
            accountName = accountNames[stableAccountId] ?: base.accountName,
        )
    }

    private fun rewriteAccountReference(
        transaction: TransactionEntity,
        origin: SimpleFinServerOrigin,
    ): TransactionEntity {
        val remote = transaction.accountKey?.let { SimpleFinIdentity.parseAccountId(it, origin) } ?: return transaction
        val stableAccountId = SimpleFinIdentity.accountId(origin, remote.providerConnectionId, remote.accountId)
        return transaction.copy(accountKey = stableAccountId)
    }

    private fun mergeTombstone(
        stableId: String,
        rows: List<SimpleFinIgnoredTransactionEntity>,
        transactions: List<TransactionEntity>,
        payloadOccurredAtEpochMillis: Long?,
    ): SimpleFinIgnoredTransactionEntity {
        val ranked =
            rows.sortedWith(
                compareByDescending<SimpleFinIgnoredTransactionEntity> { it.transactionId == stableId }
                    .thenByDescending { it.ignoredAtEpochMillis }
                    .thenBy { it.transactionId },
            )
        val occurredAt =
            ranked.firstNotNullOfOrNull { it.occurredAtEpochMillis }
                ?: transactions
                    .sortedWith(
                        compareByDescending<TransactionEntity> { it.id == stableId }
                            .thenBy { it.id },
                    ).firstOrNull()
                    ?.occurredAtEpochMillis
                ?: payloadOccurredAtEpochMillis
        return SimpleFinIgnoredTransactionEntity(
            transactionId = stableId,
            ignoredAtEpochMillis = rows.maxOf { it.ignoredAtEpochMillis },
            occurredAtEpochMillis = occurredAt,
        )
    }

    private data class TransactionGroup(
        val remote: SimpleFinRemoteTransactionIdentity,
        val rows: MutableList<TransactionEntity> = mutableListOf(),
    )

    private const val DEFAULT_SIMPLEFIN_CATEGORY = "Other"
}

internal const val STABLE_IDENTITY_STATE_ID = "stable_v2"
