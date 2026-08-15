package com.dwk.flowmoney

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
        require(transactions.all { CsvCodec.isImportId(it.id) }) { "CSV import identity required" }
        return dao.importIgnoringConflicts(transactions.map {
            it.copy(source = "local", accountKey = null, accountName = null).toEntity()
        })
    }

    override suspend fun importTrustedLegacyTransactions(transactions: List<Transaction>): Int {
        return importTransactions(transactions)
    }

    override suspend fun delete(id: String) {
        dao.deleteWithSimpleFinTombstone(id)
    }
}
