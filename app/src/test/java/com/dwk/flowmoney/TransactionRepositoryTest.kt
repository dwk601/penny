package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TransactionRepositoryTest {
    @Test fun sameCsvReimportIsIdempotentAndKeepsDuplicateOccurrences() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        val local = transaction(id = "local", merchant = "Local", occurredAtEpochMillis = 1766145500000)
        val csv = """
            id,occurredAtEpochMillis,merchant,category,note,cents,recurring
            simplefin:forged,1766145600000,Imported,Food,duplicate,-100,Monthly
            simplefin:forged,1766145600000,Imported,Food,duplicate,-100,Monthly
        """.trimIndent()
        val imported = CsvCodec.decode(csv).map {
            it.copy(source = "simplefin", accountKey = "key", accountName = "name")
        }

        repository.upsert(local)
        assertThat(imported.map { it.id }.toSet()).hasSize(2)
        assertThat(repository.importTransactions(imported)).isEqualTo(2)
        assertThat(repository.importTransactions(imported)).isEqualTo(0)

        val transactions = repository.transactions.first()
        assertThat(transactions).hasSize(3)
        assertThat(transactions.single { it.id == "local" }.merchant).isEqualTo("Local")
        assertThat(transactions.filter { it.merchant == "Imported" }.all { it.source == "local" && it.accountKey == null && it.accountName == null }).isTrue()
    }

    @Test fun deleteRemovesOnlyRequestedTransaction() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)

        repository.upsert(transaction(id = "keep", merchant = "Keep"))
        repository.upsert(transaction(id = "delete", merchant = "Delete"))
        repository.delete("delete")

        assertThat(repository.transactions.first().map { it.id }).containsExactly("keep")
    }

    @Test fun transactionsAreNewestFirst() = runTest {
        val repository = TransactionRepository(FakeTransactionDao())

        repository.upsert(transaction(id = "old", merchant = "Old", occurredAtEpochMillis = 1))
        repository.upsert(transaction(id = "new", merchant = "New", occurredAtEpochMillis = 3))
        repository.upsert(transaction(id = "middle", merchant = "Middle", occurredAtEpochMillis = 2))

        assertThat(repository.transactions.first().map { it.id })
            .containsExactly("new", "middle", "old")
            .inOrder()
    }

    @Test fun importPreservesRecurringInterval() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        val imported = CsvCodec.decode(
            CsvCodec.encode(listOf(transaction(id = "rent", merchant = "Rent", recurringInterval = RecurrenceInterval.Monthly))),
        )

        repository.importTransactions(imported)

        assertThat(repository.transactions.first().single().recurringInterval)
            .isEqualTo(RecurrenceInterval.Monthly)
    }

    @Test fun legacyImportUsesGeneratedIdAndCountsOnlyNewRows() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        val original = transaction(id = "legacy", merchant = "Legacy")
        val imported = CsvCodec.decode(CsvCodec.encode(listOf(original)))

        assertThat(repository.importTrustedLegacyTransactions(imported)).isEqualTo(1)
        assertThat(repository.importTrustedLegacyTransactions(imported)).isEqualTo(0)
        assertThat(repository.load().single().id).isEqualTo(imported.single().id)
        assertThat(repository.load().single().id).isNotEqualTo(original.id)
    }

    @Test fun syncedUpsertAppliesProviderCorrectionsAndPreservesUserFields() = runTest {
        val dao = FakeTransactionDao()
        val synced = syncedEntity(id = "simplefin:acct:tx", merchant = "Original")
        dao.upsert(
            synced.copy(
                category = "User category",
                note = "User note",
                recurringInterval = RecurrenceInterval.Monthly.name,
            ),
        )

        val result = dao.upsertSyncedTransactionsIgnoringTombstones(
            listOf(
                synced.copy(
                    occurredAtEpochMillis = 1766145700000,
                    merchant = "Server edit",
                    cents = -250,
                    accountKey = "new-acct",
                    accountName = "Savings",
                ),
            ),
        )

        assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 0, updated = 1, skipped = 0))
        assertThat(dao.getAll().single()).isEqualTo(
            synced.copy(
                occurredAtEpochMillis = 1766145700000,
                merchant = "Server edit",
                category = "User category",
                note = "User note",
                cents = -250,
                recurringInterval = RecurrenceInterval.Monthly.name,
                accountKey = "new-acct",
                accountName = "Savings",
            ),
        )
    }

    @Test fun deletingSyncedRowTombstonesAndUndoClearsTombstone() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        val synced = transaction(id = "simplefin:acct:tx", merchant = "Synced").copy(source = "simplefin")

        repository.upsert(synced)
        repository.delete(synced.id)

        assertThat(dao.ignoredIds()).containsExactly(synced.id)
        assertThat(repository.load()).isEmpty()

        repository.upsert(synced)

        assertThat(dao.ignoredIds()).isEmpty()
        assertThat(repository.load().single().id).isEqualTo(synced.id)
    }

    @Test fun tombstoneAwareSyncedInsertPreventsReimport() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        val synced = syncedEntity(id = "simplefin:acct:tx")

        repository.upsert(synced.toTransaction())
        repository.delete(synced.id)
        val result = dao.upsertSyncedTransactionsIgnoringTombstones(listOf(synced))

        assertThat(repository.load()).isEmpty()
        assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 0, updated = 0, skipped = 1))
    }

    @Test fun syncedUpsertSkipsLocalIdCollision() = runTest {
        val dao = FakeTransactionDao()
        dao.upsert(syncedEntity(id = "collision", merchant = "Local").copy(source = "local"))

        val result = dao.upsertSyncedTransactionsIgnoringTombstones(
            listOf(syncedEntity(id = "collision", merchant = "Provider")),
        )

        assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 0, updated = 0, skipped = 1))
        assertThat(dao.getAll().single().merchant).isEqualTo("Local")
    }

    @Test fun syncedUpsertDeduplicatesBeforeCounting() = runTest {
        val dao = FakeTransactionDao()
        dao.upsert(syncedEntity(id = "existing"))

        val result = dao.upsertSyncedTransactionsIgnoringTombstones(
            listOf(
                syncedEntity(id = "new"),
                syncedEntity(id = "new", merchant = "Duplicate"),
                syncedEntity(id = "existing", merchant = "Updated"),
                syncedEntity(id = "existing", merchant = "Duplicate"),
            ),
        )

        assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 1, updated = 1, skipped = 0))
        assertThat(dao.getAll().first { it.id == "new" }.merchant).isEqualTo("Synced")
        assertThat(dao.getAll().first { it.id == "existing" }.merchant).isEqualTo("Updated")
    }

    @Test fun syncedUpsertQueriesExistingRowsInBoundedChunks() = runTest {
        val dao = FakeTransactionDao()
        dao.upsert(syncedEntity(id = "id-0", merchant = "Original"))
        dao.upsert(syncedEntity(id = "id-1", merchant = "Local").copy(source = "local"))
        dao.insertIgnoredTransaction(SimpleFinIgnoredTransactionEntity("id-904"))
        val incoming = (0..904).map { syncedEntity(id = "id-$it", merchant = "Provider-$it") } +
            syncedEntity(id = "id-2", merchant = "Duplicate")

        val result = dao.upsertSyncedTransactionsIgnoringTombstones(incoming)

        assertThat(dao.queriedBatchSizes).containsExactly(900, 4).inOrder()
        assertThat(dao.queriedBatchSizes.all { it <= 900 }).isTrue()
        assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 902, updated = 1, skipped = 2))
        assertThat(dao.getAll().first { it.id == "id-0" }.merchant).isEqualTo("Provider-0")
        assertThat(dao.getAll().first { it.id == "id-1" }.merchant).isEqualTo("Local")
        assertThat(dao.getAll().none { it.id == "id-904" }).isTrue()
    }

    private fun transaction(
        id: String,
        merchant: String,
        occurredAtEpochMillis: Long = 1766145600000,
        recurringInterval: RecurrenceInterval? = null,
    ): Transaction {
        return Transaction(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            merchant = merchant,
            category = "Food",
            note = "",
            cents = -100,
            recurringInterval = recurringInterval,
        )
    }

    private fun syncedEntity(id: String, merchant: String = "Synced") = TransactionEntity(
        id = id,
        occurredAtEpochMillis = 1766145600000,
        merchant = merchant,
        category = "Other",
        note = "",
        cents = -100,
        source = "simplefin",
        accountKey = "acct",
        accountName = "Checking",
    )

    private class FakeTransactionDao : TransactionDao {
        private val entities = LinkedHashMap<String, TransactionEntity>()
        private val ignored = LinkedHashSet<String>()
        private val rows = MutableStateFlow<List<TransactionEntity>>(emptyList())
        val queriedBatchSizes = mutableListOf<Int>()

        fun ignoredIds(): Set<String> = ignored

        override fun observeAll(): Flow<List<TransactionEntity>> = rows

        override suspend fun getAll(): List<TransactionEntity> = rows.value

        override suspend fun getInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): List<TransactionEntity> = rows.value.filter {
            it.occurredAtEpochMillis >= startInclusiveEpochMillis &&
                it.occurredAtEpochMillis < endExclusiveEpochMillis
        }

        override suspend fun upsert(transaction: TransactionEntity) {
            entities[transaction.id] = transaction
            publish()
        }

        override suspend fun upsertAll(transactions: List<TransactionEntity>) {
            transactions.forEach { transaction -> entities[transaction.id] = transaction }
            publish()
        }

        override suspend fun insertImported(transactions: List<TransactionEntity>): List<Long> {
            val result = transactions.map { transaction ->
                if (entities.containsKey(transaction.id)) -1L else {
                    entities[transaction.id] = transaction
                    1L
                }
            }
            publish()
            return result
        }

        override suspend fun transactionsForIds(ids: List<String>): List<TransactionEntity> {
            queriedBatchSizes += ids.size
            return ids.mapNotNull(entities::get)
        }

        override suspend fun sourceForId(id: String): String? = entities[id]?.source

        override suspend fun deleteById(id: String) {
            entities.remove(id)
            publish()
        }

        override suspend fun insertIgnoredTransaction(ignored: SimpleFinIgnoredTransactionEntity) {
            this.ignored += ignored.transactionId
        }

        override suspend fun deleteIgnoredTransaction(id: String) {
            ignored -= id
        }

        override suspend fun ignoredTransactionIds(ids: List<String>): List<String> = ids.filter { it in ignored }

        private fun publish() {
            rows.value = entities.values.sortedByDescending { it.occurredAtEpochMillis }
        }
    }
}
