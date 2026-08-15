package com.dwk.flowmoney

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId

interface TransactionGateway {
    /** Emits transactions newest-first by [Transaction.occurredAtEpochMillis]. */
    val transactions: Flow<List<Transaction>>

    suspend fun load(): List<Transaction>

    suspend fun upsert(transaction: Transaction)

    suspend fun importTransactions(transactions: List<Transaction>): Int

    suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int

    suspend fun delete(id: String)

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
) : TransactionGateway {
    override val transactions: Flow<List<Transaction>> =
        dao
            .observeAll()
            .map { entities -> entities.map { it.toTransaction() } }

    override suspend fun load(): List<Transaction> = dao.getAll().map { it.toTransaction() }

    override suspend fun upsert(transaction: Transaction) {
        dao.upsertAndClearIgnored(transaction.toEntity())
    }

    override suspend fun importTransactions(transactions: List<Transaction>): Int {
        require(transactions.all { CsvCodec.isImportId(it.id) }) { "CSV import identity required" }
        return dao.importIgnoringConflicts(
            transactions.map {
                it.copy(source = "local", accountKey = null, accountName = null).toEntity()
            },
        )
    }

    override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int = importTransactions(transactions)

    override suspend fun delete(id: String) {
        dao.deleteWithSimpleFinTombstone(id)
    }

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
