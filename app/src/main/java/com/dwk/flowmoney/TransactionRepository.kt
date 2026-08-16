package com.dwk.flowmoney

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.ZoneId

interface TransactionGateway {
    /** Emits transactions newest-first by [Transaction.occurredAtEpochMillis]. */
    val transactions: Flow<List<Transaction>>

    suspend fun load(): List<Transaction>

    val unreviewedCount: Flow<Int>
        get() = flowOf(0)

    val unreviewedTransactions: Flow<List<Transaction>>
        get() = flowOf(emptyList())

    val merchantRules: Flow<List<MerchantRuleEntity>>
        get() = flowOf(emptyList())

    suspend fun getUnreviewedTransactions(): List<Transaction> = emptyList()

    suspend fun upsert(transaction: Transaction)

    suspend fun importTransactions(transactions: List<Transaction>): Int

    suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int

    suspend fun delete(id: String)

    suspend fun restoreDeletedTransaction(transaction: Transaction): Unit =
        throw UnsupportedOperationException("Exact delete restore is not supported by this gateway")

    suspend fun categorizeAndReview(
        ids: List<String>,
        category: String,
    ): ReviewUndoToken = throw UnsupportedOperationException("Review is not supported by this gateway")

    suspend fun acceptAsOther(ids: List<String>): ReviewUndoToken = categorizeAndReview(ids, "Other")

    suspend fun restoreReview(token: ReviewUndoToken): Unit =
        throw UnsupportedOperationException("Review undo is not supported by this gateway")

    suspend fun getMerchantRules(): List<MerchantRuleEntity> = emptyList()

    suspend fun deleteMerchantRule(normalizedKey: String): MerchantRuleEntity? =
        throw UnsupportedOperationException("Merchant rules are not supported by this gateway")

    suspend fun saveAndApplyMerchantRule(
        originatingTransactionId: String,
        category: String,
        merchantOverride: String?,
        overwriteConflict: Boolean = false,
        editorTransaction: Transaction? = null,
    ): MerchantRuleSaveResult = throw UnsupportedOperationException("Merchant rules are not supported by this gateway")

    suspend fun undoMerchantRuleSave(token: MerchantRuleUndoToken): Unit =
        throw UnsupportedOperationException("Merchant rule undo is not supported by this gateway")

    /**
     * Range defaults keep transaction-only test gateways source-compatible. Gateways used for
     * range reset must override all three operations with one coherent storage implementation.
     */
    suspend fun countRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
    ): TransactionRangeCount = throw UnsupportedOperationException("Range reset is not supported by this gateway")

    suspend fun resetRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
        expectedCount: TransactionRangeCount,
    ): TransactionRangeResetSnapshot = throw UnsupportedOperationException("Range reset is not supported by this gateway")

    suspend fun restoreRange(snapshot: TransactionRangeResetSnapshot): TransactionRangeCount =
        throw UnsupportedOperationException("Range reset is not supported by this gateway")
}

class TransactionRepository(
    private val dao: TransactionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : TransactionGateway {
    override val transactions: Flow<List<Transaction>> =
        dao
            .observeAll()
            .map { entities -> entities.map { it.toTransaction() } }

    override suspend fun load(): List<Transaction> = dao.getAll().map { it.toTransaction() }

    override val unreviewedCount: Flow<Int> = dao.observeUnreviewedCount()

    override val unreviewedTransactions: Flow<List<Transaction>> =
        dao.observeUnreviewedTransactions().map { rows -> rows.map { it.toTransaction() } }

    override val merchantRules: Flow<List<MerchantRuleEntity>> = dao.observeMerchantRules()

    override suspend fun getUnreviewedTransactions(): List<Transaction> = dao.getUnreviewedTransactions().map { it.toTransaction() }

    override suspend fun upsert(transaction: Transaction) {
        val reviewed =
            if (transaction.source != "simplefin" && transaction.reviewedAtEpochMillis == null) {
                transaction.copy(reviewedAtEpochMillis = now())
            } else {
                transaction
            }
        dao.upsertAndClearIgnored(reviewed.toEntity())
    }

    override suspend fun importTransactions(transactions: List<Transaction>): Int {
        require(transactions.all { CsvCodec.isImportId(it.id) }) { "CSV import identity required" }
        return dao.importIgnoringConflicts(
            transactions.map {
                it
                    .copy(
                        source = "local",
                        accountKey = null,
                        accountName = null,
                        reviewedAtEpochMillis = it.reviewedAtEpochMillis ?: now(),
                        providerDescription = null,
                        merchantOverride = null,
                        providerMerchant = null,
                    ).toEntity()
            },
        )
    }

    override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = importTransactions(transactions)

    override suspend fun delete(id: String) {
        dao.deleteWithSimpleFinTombstone(id)
    }

    override suspend fun restoreDeletedTransaction(transaction: Transaction) {
        dao.restoreDeletedTransaction(transaction.toEntity())
    }

    override suspend fun categorizeAndReview(
        ids: List<String>,
        category: String,
    ): ReviewUndoToken = dao.categorizeAndReview(ids, category, now())

    override suspend fun acceptAsOther(ids: List<String>): ReviewUndoToken = dao.categorizeAndReview(ids, "Other", now())

    override suspend fun restoreReview(token: ReviewUndoToken) = dao.restoreReview(token)

    override suspend fun getMerchantRules(): List<MerchantRuleEntity> = dao.getMerchantRules()

    override suspend fun deleteMerchantRule(normalizedKey: String): MerchantRuleEntity? = dao.deleteMerchantRule(normalizedKey)

    override suspend fun saveAndApplyMerchantRule(
        originatingTransactionId: String,
        category: String,
        merchantOverride: String?,
        overwriteConflict: Boolean,
        editorTransaction: Transaction?,
    ): MerchantRuleSaveResult {
        val reviewedAtEpochMillis = now()
        val editorUpdate =
            editorTransaction?.copy(
                reviewedAtEpochMillis = editorTransaction.reviewedAtEpochMillis ?: reviewedAtEpochMillis,
            )
        return dao.saveAndApplyMerchantRule(
            originatingTransactionId = originatingTransactionId,
            category = category,
            merchantOverride = merchantOverride,
            reviewedAtEpochMillis = reviewedAtEpochMillis,
            overwriteConflict = overwriteConflict,
            editorUpdate = editorUpdate?.toEntity(),
        )
    }

    override suspend fun undoMerchantRuleSave(token: MerchantRuleUndoToken) = dao.undoMerchantRuleSave(token)

    override suspend fun countRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
    ): TransactionRangeCount {
        val instantRange = range.toInstantRange(zoneId)
        return dao.countInRange(
            startInclusiveEpochMillis = instantRange.startInclusive.toEpochMilli(),
            endExclusiveEpochMillis = instantRange.endExclusive.toEpochMilli(),
        )
    }

    override suspend fun resetRange(
        range: PennyLocalDateRange,
        zoneId: ZoneId,
        expectedCount: TransactionRangeCount,
    ): TransactionRangeResetSnapshot {
        val instantRange = range.toInstantRange(zoneId)
        return dao.snapshotAndDeleteInRange(
            startInclusiveEpochMillis = instantRange.startInclusive.toEpochMilli(),
            endExclusiveEpochMillis = instantRange.endExclusive.toEpochMilli(),
            expectedCount = expectedCount,
        )
    }

    override suspend fun restoreRange(snapshot: TransactionRangeResetSnapshot): TransactionRangeCount = dao.restoreRange(snapshot)
}
