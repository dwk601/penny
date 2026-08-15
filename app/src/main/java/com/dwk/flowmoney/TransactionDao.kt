package com.dwk.flowmoney

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val SYNCED_TRANSACTION_QUERY_BATCH_SIZE = 900
private const val IMPORT_TRANSACTION_BATCH_SIZE = 500

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
    ): TransactionRangeResetSnapshot {
        require(startInclusiveEpochMillis <= endExclusiveEpochMillis) {
            "Range end must not precede range start"
        }
        val transactions = getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        val tombstones = tombstonesInRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
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

    @Transaction
    suspend fun upsertSyncedTransactionsIgnoringTombstones(transactions: List<TransactionEntity>): SyncedTransactionWriteResult {
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
                        incoming.copy(source = "simplefin")
                    }

                    current.source == "simplefin" -> {
                        updated++
                        current.copy(
                            occurredAtEpochMillis = incoming.occurredAtEpochMillis,
                            merchant = incoming.merchant,
                            cents = incoming.cents,
                            source = "simplefin",
                            accountKey = incoming.accountKey,
                            accountName = incoming.accountName,
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
}
