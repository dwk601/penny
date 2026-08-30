package com.dwk.flowmoney

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val SYNCED_TRANSACTION_QUERY_BATCH_SIZE = 900
private const val IMPORT_TRANSACTION_BATCH_SIZE = 500
private const val REVIEW_TRANSACTION_BATCH_SIZE = 500

data class SyncedTransactionWriteResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

data class TransactionRangeCount(
    val transactionCount: Long,
    val tombstoneCount: Long,
) {
    init {
        require(transactionCount >= 0) { "Transaction count must not be negative" }
        require(tombstoneCount >= 0) { "Tombstone count must not be negative" }
    }
}

val TransactionRangeCount.affectedCount: Long
    get() = Math.addExact(transactionCount, tombstoneCount)

class TransactionRangeChangedException(
    val expected: TransactionRangeCount,
    val actual: TransactionRangeCount,
) : IllegalStateException("Transaction range changed after confirmation")

/** Opaque, exact database rows removed by one atomic range reset. */
class TransactionRangeResetSnapshot internal constructor(
    transactionRows: List<TransactionEntity>,
    tombstoneRows: List<SimpleFinIgnoredTransactionEntity>,
) {
    internal val transactionRows: List<TransactionEntity> = transactionRows.toList()
    internal val tombstoneRows: List<SimpleFinIgnoredTransactionEntity> = tombstoneRows.toList()

    val count =
        TransactionRangeCount(
            transactionCount = transactionRows.size.toLong(),
            tombstoneCount = tombstoneRows.size.toLong(),
        )
    val affectedCount: Long
        get() = count.affectedCount
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE source = 'simplefin' AND reviewedAtEpochMillis IS NULL")
    fun observeUnreviewedCount(): Flow<Int>

    @Query(
        "SELECT * FROM transactions WHERE source = 'simplefin' " +
            "AND reviewedAtEpochMillis IS NULL ORDER BY occurredAtEpochMillis DESC",
    )
    fun observeUnreviewedTransactions(): Flow<List<TransactionEntity>>

    @Query(
        "SELECT * FROM transactions WHERE source = 'simplefin' " +
            "AND reviewedAtEpochMillis IS NULL ORDER BY occurredAtEpochMillis DESC",
    )
    suspend fun getUnreviewedTransactions(): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE source = 'simplefin' " +
            "AND reviewedAtEpochMillis IS NULL AND category = 'Other' " +
            "ORDER BY occurredAtEpochMillis DESC LIMIT :limit",
    )
    suspend fun uncategorizedSyncedTransactions(limit: Int): List<TransactionEntity>

    @Query(
        "UPDATE transactions SET category = :category WHERE id = :id " +
            "AND source = 'simplefin' AND reviewedAtEpochMillis IS NULL AND category = 'Other'",
    )
    suspend fun applyAutoCategory(id: String, category: String): Int

    @Transaction
    suspend fun applyAutoCategories(categoriesById: Map<String, String>): Int {
        require(categoriesById.size <= GEMINI_CATEGORIZE_CHUNK_SIZE)
        return categoriesById.entries.sumOf { (id, category) ->
            require(category.isNotBlank())
            applyAutoCategory(id, category)
        }
    }

    @Query("SELECT * FROM merchant_rules ORDER BY normalizedProviderMerchant")
    fun observeMerchantRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules ORDER BY normalizedProviderMerchant")
    suspend fun getMerchantRules(): List<MerchantRuleEntity>

    @Query("SELECT * FROM merchant_rules WHERE normalizedProviderMerchant = :key")
    suspend fun merchantRuleForKey(key: String): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMerchantRule(rule: MerchantRuleEntity)

    @Query(
        "UPDATE merchant_rules SET category = :category, merchantOverride = :merchantOverride " +
            "WHERE normalizedProviderMerchant = :key",
    )
    suspend fun updateMerchantRule(
        key: String,
        category: String,
        merchantOverride: String?,
    ): Int

    @Query("DELETE FROM merchant_rules WHERE normalizedProviderMerchant = :key")
    suspend fun deleteMerchantRuleByKey(key: String): Int

    @Transaction
    suspend fun deleteMerchantRule(key: String): MerchantRuleEntity? {
        val rule = merchantRuleForKey(key) ?: return null
        check(deleteMerchantRuleByKey(key) == 1) { "Merchant rule changed during deletion" }
        return rule
    }

    @Query(
        "SELECT * FROM transactions " +
            "WHERE occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis " +
            "ORDER BY occurredAtEpochMillis DESC",
    )
    suspend fun getInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<TransactionEntity>

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM transactions " +
            "WHERE occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis) AS transactionCount, " +
            "(SELECT COUNT(*) FROM simplefin_ignored_transactions " +
            "WHERE occurredAtEpochMillis IS NOT NULL " +
            "AND occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis) AS tombstoneCount",
    )
    suspend fun countInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): TransactionRangeCount

    @Query(
        "SELECT * FROM simplefin_ignored_transactions " +
            "WHERE occurredAtEpochMillis IS NOT NULL " +
            "AND occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis " +
            "ORDER BY occurredAtEpochMillis DESC, transactionId ASC",
    )
    suspend fun tombstonesInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<SimpleFinIgnoredTransactionEntity>

    @Query(
        "DELETE FROM transactions " +
            "WHERE occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis",
    )
    suspend fun deleteTransactionsInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): Int

    @Query(
        "DELETE FROM simplefin_ignored_transactions " +
            "WHERE occurredAtEpochMillis IS NOT NULL " +
            "AND occurredAtEpochMillis >= :startInclusiveEpochMillis " +
            "AND occurredAtEpochMillis < :endExclusiveEpochMillis",
    )
    suspend fun deleteTombstonesInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTransactions(transactions: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTombstones(tombstones: List<SimpleFinIgnoredTransactionEntity>)

    @Transaction
    suspend fun snapshotAndDeleteInRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        expectedCount: TransactionRangeCount,
    ): TransactionRangeResetSnapshot {
        require(startInclusiveEpochMillis <= endExclusiveEpochMillis) {
            "Range end must not precede range start"
        }
        val transactions = getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        val tombstones = tombstonesInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        val actualCount =
            TransactionRangeCount(
                transactionCount = transactions.size.toLong(),
                tombstoneCount = tombstones.size.toLong(),
            )
        if (actualCount != expectedCount) throw TransactionRangeChangedException(expectedCount, actualCount)
        val deletedTransactions = deleteTransactionsInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        val deletedTombstones = deleteTombstonesInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        check(deletedTransactions == transactions.size) { "Transaction range changed during reset" }
        check(deletedTombstones == tombstones.size) { "Tombstone range changed during reset" }
        return TransactionRangeResetSnapshot(transactions, tombstones)
    }

    /** Restores snapshot rows directly so transaction upserts cannot clear restored tombstones. */
    @Transaction
    suspend fun restoreRange(snapshot: TransactionRangeResetSnapshot): TransactionRangeCount {
        if (snapshot.transactionRows.isNotEmpty()) restoreTransactions(snapshot.transactionRows)
        if (snapshot.tombstoneRows.isNotEmpty()) restoreTombstones(snapshot.tombstoneRows)
        return snapshot.count
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: TransactionEntity)

    @Transaction
    suspend fun upsertAndClearIgnored(transaction: TransactionEntity) {
        upsert(transaction)
        if (transaction.source == "simplefin") deleteIgnoredTransaction(transaction.id)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImported(transactions: List<TransactionEntity>): List<Long>

    @Transaction
    suspend fun importIgnoringConflicts(transactions: List<TransactionEntity>): Int =
        transactions.chunked(IMPORT_TRANSACTION_BATCH_SIZE).sumOf { batch ->
            insertImported(batch).count { it != -1L }
        }

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun transactionsForIds(ids: List<String>): List<TransactionEntity>

    @Query(
        "UPDATE transactions SET category = :category, reviewedAtEpochMillis = :reviewedAtEpochMillis " +
            "WHERE id IN (:ids) AND source = 'simplefin' AND reviewedAtEpochMillis IS NULL",
    )
    suspend fun categorizeAndReviewBatch(
        ids: List<String>,
        category: String,
        reviewedAtEpochMillis: Long,
    ): Int

    @Query(
        "UPDATE transactions SET category = :category, reviewedAtEpochMillis = :reviewedAtEpochMillis, " +
            "merchantOverride = :merchantOverride WHERE id = :id",
    )
    suspend fun restoreReviewState(
        id: String,
        category: String,
        reviewedAtEpochMillis: Long?,
        merchantOverride: String?,
    ): Int

    @Query(
        "UPDATE transactions SET category = :category, merchantOverride = :merchantOverride, " +
            "reviewedAtEpochMillis = COALESCE(reviewedAtEpochMillis, :reviewedAtEpochMillis) " +
            "WHERE id = :id AND source = 'simplefin'",
    )
    suspend fun applyRuleToOrigin(
        id: String,
        category: String,
        merchantOverride: String?,
        reviewedAtEpochMillis: Long,
    ): Int

    @Transaction
    suspend fun categorizeAndReview(
        ids: List<String>,
        category: String,
        reviewedAtEpochMillis: Long,
    ): ReviewUndoToken {
        require(category.isNotBlank()) { "Category must not be blank" }
        val uniqueIds = ids.distinct()
        val rows =
            uniqueIds
                .chunked(REVIEW_TRANSACTION_BATCH_SIZE)
                .flatMap { transactionsForIds(it) }
                .filter { it.isUnreviewed }
        val rowIds = rows.map { it.id }
        val changed =
            rowIds
                .chunked(REVIEW_TRANSACTION_BATCH_SIZE)
                .sumOf { categorizeAndReviewBatch(it, category, reviewedAtEpochMillis) }
        check(changed == rows.size) { "Review queue changed during update" }
        return ReviewUndoToken(rows.map { it.reviewState() })
    }

    @Transaction
    suspend fun restoreReview(token: ReviewUndoToken) {
        token.transactionStates.forEach { state ->
            check(
                restoreReviewState(
                    id = state.id,
                    category = state.category,
                    reviewedAtEpochMillis = state.reviewedAtEpochMillis,
                    merchantOverride = state.merchantOverride,
                ) == 1,
            ) { "Reviewed transaction no longer exists" }
        }
    }

    /** Derives the key from the current provider merchant and commits the rule and origin together. */
    @Transaction
    suspend fun saveAndApplyMerchantRule(
        originatingTransactionId: String,
        category: String,
        merchantOverride: String?,
        reviewedAtEpochMillis: Long,
        overwriteConflict: Boolean,
        editorUpdate: TransactionEntity? = null,
    ): MerchantRuleSaveResult {
        require(category.isNotBlank()) { "Category must not be blank" }
        val origin =
            transactionsForIds(listOf(originatingTransactionId)).singleOrNull()
                ?: error("Originating transaction does not exist")
        require(origin.source == "simplefin") { "Merchant rules require a SimpleFIN transaction" }
        val key = normalizedProviderMerchantKey(origin.merchant)
        require(key.isNotBlank()) { "Provider merchant must not be blank" }
        val proposed = MerchantRuleEntity(key, category, merchantOverride)
        val previous = merchantRuleForKey(key)
        if (previous != null && previous != proposed && !overwriteConflict) {
            return MerchantRuleSaveResult.Conflict(previous, proposed)
        }

        val updatedOrigin =
            if (editorUpdate == null) {
                origin.copy(
                    category = category,
                    reviewedAtEpochMillis = origin.reviewedAtEpochMillis ?: reviewedAtEpochMillis,
                    merchantOverride = merchantOverride,
                )
            } else {
                require(editorUpdate.id == origin.id) { "Editor transaction must match the origin" }
                require(editorUpdate.source == "simplefin") { "Editor transaction must remain SimpleFIN-owned" }
                require(editorUpdate.category == category) { "Editor category must match the rule" }
                require(editorUpdate.merchantOverride == merchantOverride) { "Editor merchant must match the rule" }
                origin.copy(
                    occurredAtEpochMillis = editorUpdate.occurredAtEpochMillis,
                    category = category,
                    note = editorUpdate.note,
                    cents = editorUpdate.cents,
                    recurringInterval = editorUpdate.recurringInterval,
                    reviewedAtEpochMillis =
                        editorUpdate.reviewedAtEpochMillis
                            ?: origin.reviewedAtEpochMillis
                            ?: reviewedAtEpochMillis,
                    merchantOverride = merchantOverride,
                    flowKindOverride = editorUpdate.flowKindOverride,
                )
            }

        if (previous == null) {
            insertMerchantRule(proposed)
        } else {
            check(updateMerchantRule(key, proposed.category, proposed.merchantOverride) == 1) {
                "Merchant rule changed while it was being saved"
            }
        }
        upsert(updatedOrigin)
        return MerchantRuleSaveResult.Applied(
            rule = proposed,
            undoToken =
                MerchantRuleUndoToken(
                    transactionBeforeSave = origin,
                    transactionAfterSave = updatedOrigin,
                    previousRule = previous,
                    appliedRule = proposed,
                ),
        )
    }

    @Transaction
    suspend fun undoMerchantRuleSave(token: MerchantRuleUndoToken) {
        check(merchantRuleForKey(token.appliedRule.normalizedProviderMerchant) == token.appliedRule) {
            "Merchant rule changed after it was saved"
        }
        token.previousRule?.let { previous ->
            check(
                updateMerchantRule(
                    previous.normalizedProviderMerchant,
                    previous.category,
                    previous.merchantOverride,
                ) == 1,
            ) { "Merchant rule disappeared before undo" }
        } ?: check(deleteMerchantRuleByKey(token.appliedRule.normalizedProviderMerchant) == 1) {
            "Merchant rule disappeared before undo"
        }

        val current =
            transactionsForIds(listOf(token.transactionBeforeSave.id)).singleOrNull()
                ?: error("Originating transaction no longer exists")
        val restored =
            if (current.hasSameProviderStateAs(token.transactionAfterSave)) {
                token.transactionBeforeSave
            } else {
                // A later sync owns these refreshed columns; only restore the user-owned editor state.
                current.copy(
                    category = token.transactionBeforeSave.category,
                    note = token.transactionBeforeSave.note,
                    recurringInterval = token.transactionBeforeSave.recurringInterval,
                    reviewedAtEpochMillis = token.transactionBeforeSave.reviewedAtEpochMillis,
                    merchantOverride = token.transactionBeforeSave.merchantOverride,
                    flowKindOverride = token.transactionBeforeSave.flowKindOverride,
                )
            }
        upsert(restored)
    }

    @Transaction
    suspend fun upsertSyncedTransactionsIgnoringTombstones(
        transactions: List<TransactionEntity>,
        merchantRules: List<MerchantRuleEntity> = emptyList(),
        reviewedAtEpochMillis: Long = System.currentTimeMillis(),
    ): SyncedTransactionWriteResult {
        val ruleLookup = MerchantRuleLookup.from(merchantRules)
        val unique = transactions.distinctBy { it.id }
        val ignored =
            unique
                .map { it.id }
                .chunked(SYNCED_TRANSACTION_QUERY_BATCH_SIZE)
                .flatMap { ignoredTransactionIds(it) }
                .toSet()
        val eligible = unique.filterNot { it.id in ignored }
        val existing =
            if (eligible.isEmpty()) {
                emptyMap()
            } else {
                eligible
                    .map { it.id }
                    .chunked(SYNCED_TRANSACTION_QUERY_BATCH_SIZE)
                    .flatMap { transactionsForIds(it) }
                    .associateBy { it.id }
            }
        var inserted = 0
        var updated = 0
        var collisions = 0
        val merged =
            eligible.mapNotNull { incoming ->
                val current = existing[incoming.id]
                when {
                    current == null -> {
                        inserted++
                        val rule = ruleLookup.ruleFor(incoming.merchant)
                        val shouldAutoReview =
                            rule != null || incoming.effectiveFlowKind == FlowKind.TRANSFER
                        incoming.copy(
                            category = rule?.category ?: incoming.category,
                            source = "simplefin",
                            reviewedAtEpochMillis =
                                incoming.reviewedAtEpochMillis
                                    ?: reviewedAtEpochMillis.takeIf { shouldAutoReview },
                            merchantOverride =
                                if (rule == null) incoming.merchantOverride else rule.merchantOverride,
                        )
                    }

                    current.source == "simplefin" -> {
                        updated++
                        val providerRefreshed =
                            current.copy(
                                occurredAtEpochMillis = incoming.occurredAtEpochMillis,
                                merchant = incoming.merchant,
                                cents = incoming.cents,
                                source = "simplefin",
                                accountKey = incoming.accountKey,
                                accountName = incoming.accountName,
                                providerDescription = incoming.providerDescription,
                                flowKind = incoming.flowKind,
                                locationCity = incoming.locationCity,
                                locationState = incoming.locationState,
                                locationCountry = incoming.locationCountry,
                                transactedAtEpochMillis = incoming.transactedAtEpochMillis,
                            )
                        providerRefreshed.copy(
                            reviewedAtEpochMillis =
                                current.reviewedAtEpochMillis
                                    ?: reviewedAtEpochMillis.takeIf {
                                        providerRefreshed.effectiveFlowKind == FlowKind.TRANSFER
                                    },
                        )
                    }

                    else -> {
                        collisions++
                        null
                    }
                }
            }
        if (merged.isNotEmpty()) upsertAll(merged)
        return SyncedTransactionWriteResult(
            inserted = inserted,
            updated = updated,
            skipped = unique.size - eligible.size + collisions,
        )
    }

    private fun TransactionEntity.reviewState() =
        TransactionReviewState(
            id = id,
            category = category,
            reviewedAtEpochMillis = reviewedAtEpochMillis,
            merchantOverride = merchantOverride,
        )

    suspend fun providerOwnedForId(id: String): StoredProviderOwnedFields? {
        val row = transactionsForIds(listOf(id)).singleOrNull() ?: return null
        return StoredProviderOwnedFields(
            locationCity = row.locationCity,
            locationState = row.locationState,
            locationCountry = row.locationCountry,
            transactedAtEpochMillis = row.transactedAtEpochMillis,
        )
    }

    @Query("SELECT source FROM transactions WHERE id = :id")
    suspend fun sourceForId(id: String): String?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoredTransaction(ignored: SimpleFinIgnoredTransactionEntity)

    @Query("DELETE FROM simplefin_ignored_transactions WHERE transactionId = :id")
    suspend fun deleteIgnoredTransaction(id: String)

    @Query("SELECT transactionId FROM simplefin_ignored_transactions WHERE transactionId IN (:ids)")
    suspend fun ignoredTransactionIds(ids: List<String>): List<String>

    @Transaction
    suspend fun deleteWithSimpleFinTombstone(id: String) {
        val transaction = transactionsForIds(listOf(id)).singleOrNull()
        if (transaction?.source == "simplefin") {
            insertIgnoredTransaction(
                SimpleFinIgnoredTransactionEntity(
                    transactionId = id,
                    occurredAtEpochMillis = transaction.occurredAtEpochMillis,
                ),
            )
        }
        deleteById(id)
    }

    /** Restores a delete snapshot verbatim and removes only its matching SimpleFIN tombstone. */
    @Transaction
    suspend fun restoreDeletedTransaction(transaction: TransactionEntity) {
        upsert(transaction)
        if (transaction.source == "simplefin") deleteIgnoredTransaction(transaction.id)
    }
}

private data class MerchantRuleLookup(
    private val exactRules: Map<String, MerchantRuleEntity>,
    private val canonicalRules: Map<String, MerchantRuleEntity>,
) {
    fun ruleFor(providerMerchant: String): MerchantRuleEntity? {
        val exactKey = normalizedProviderMerchantKey(providerMerchant)
        return exactRules[exactKey]
            ?: canonicalProviderMerchantKey(exactKey)?.let(canonicalRules::get)
    }

    companion object {
        fun from(rules: List<MerchantRuleEntity>): MerchantRuleLookup {
            val rulesByExactKey =
                rules.groupBy { normalizedProviderMerchantKey(it.normalizedProviderMerchant) }
            val conflictingRule =
                rulesByExactKey.values.firstOrNull { exactRules -> exactRules.distinct().size > 1 }
            require(conflictingRule == null) { "Conflicting normalized merchant rules" }

            val exactRules =
                rulesByExactKey.mapValues { (_, exactRules) -> exactRules.distinct().single() }
            val canonicalRules =
                exactRules.values
                    .mapNotNull { rule ->
                        canonicalProviderMerchantKey(rule.normalizedProviderMerchant)?.let { key ->
                            key to rule
                        }
                    }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                    .mapNotNull { (key, canonicalMatches) ->
                        canonicalMatches.singleOrNull()?.let { key to it }
                    }.toMap()
            return MerchantRuleLookup(exactRules, canonicalRules)
        }
    }
}

private fun TransactionEntity.hasSameProviderStateAs(other: TransactionEntity): Boolean =
    occurredAtEpochMillis == other.occurredAtEpochMillis &&
        merchant == other.merchant &&
        cents == other.cents &&
        source == other.source &&
        accountKey == other.accountKey &&
        accountName == other.accountName &&
        providerDescription == other.providerDescription &&
        flowKind == other.flowKind &&
        locationCity == other.locationCity &&
        locationState == other.locationState &&
        locationCountry == other.locationCountry &&
        transactedAtEpochMillis == other.transactedAtEpochMillis
