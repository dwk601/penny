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
    suspend fun upsertSyncedTransactionsIgnoringTombstones(
        transactions: List<TransactionEntity>,
    ): SyncedTransactionWriteResult {
        val unique = transactions.distinctBy { it.id }
        val ignored = unique.map { it.id }
            .chunked(SYNCED_TRANSACTION_QUERY_BATCH_SIZE)
            .flatMap { ignoredTransactionIds(it) }
            .toSet()
        val eligible = unique.filterNot { it.id in ignored }
        val existing = if (eligible.isEmpty()) emptyMap() else {
            eligible.map { it.id }
                .chunked(SYNCED_TRANSACTION_QUERY_BATCH_SIZE)
                .flatMap { transactionsForIds(it) }
                .associateBy { it.id }
        }
        var inserted = 0
        var updated = 0
        var collisions = 0
        val merged = eligible.mapNotNull { incoming ->
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
        if (sourceForId(id) == "simplefin") insertIgnoredTransaction(SimpleFinIgnoredTransactionEntity(id))
        deleteById(id)
    }
}
