package com.dwk.flowmoney

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TransactionGateway {
    /** Emits transactions newest-first by [Transaction.occurredAtEpochMillis]. */
    val transactions: Flow<List<Transaction>>
    suspend fun load(): List<Transaction>
    suspend fun upsert(transaction: Transaction)
    suspend fun importTransactions(transactions: List<Transaction>): Int
    suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int
    suspend fun delete(id: String)
}

class TransactionRepository(private val dao: TransactionDao) : TransactionGateway {
    override val transactions: Flow<List<Transaction>> = dao.observeAll()
        .map { entities -> entities.map { it.toTransaction() } }

    override suspend fun load(): List<Transaction> {
        return dao.getAll().map { it.toTransaction() }
    }

    override suspend fun upsert(transaction: Transaction) {
        dao.upsertAndClearIgnored(transaction.toEntity())
    }

    override suspend fun importTransactions(transactions: List<Transaction>): Int {
        return dao.importIgnoringConflicts(transactions.map {
            it.copy(id = UUID.randomUUID().toString(), source = "local", accountKey = null, accountName = null).toEntity()
        })
    }

    override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int {
        return dao.importIgnoringConflicts(transactions.map { it.toEntity() })
    }

    override suspend fun delete(id: String) {
        dao.deleteWithSimpleFinTombstone(id)
    }
}
