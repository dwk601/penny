package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class TransactionRepositoryTest {
    @Test fun sameCsvReimportIsIdempotentAndKeepsDuplicateOccurrences() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val local = transaction(id = "local", merchant = "Local", occurredAtEpochMillis = 1766145500000)
            val csv =
                """
                id,occurredAtEpochMillis,merchant,category,note,cents,recurring
                simplefin:forged,1766145600000,Imported,Food,duplicate,-100,Monthly
                simplefin:forged,1766145600000,Imported,Food,duplicate,-100,Monthly
                """.trimIndent()
            val imported =
                CsvCodec.decode(csv).map {
                    it.copy(source = "simplefin", accountKey = "key", accountName = "name")
                }

            repository.upsert(local)
            assertThat(imported.map { it.id }.toSet()).hasSize(2)
            assertThat(repository.importTransactions(imported)).isEqualTo(2)
            assertThat(repository.importTransactions(imported)).isEqualTo(0)

            val transactions = repository.transactions.first()
            assertThat(transactions).hasSize(3)
            assertThat(transactions.single { it.id == "local" }.merchant).isEqualTo("Local")
            assertThat(
                transactions.filter { it.merchant == "Imported" }.all {
                    it.source == "local" && it.accountKey == null &&
                        it.accountName == null
                },
            ).isTrue()
        }

    @Test fun deleteRemovesOnlyRequestedTransaction() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)

            repository.upsert(transaction(id = "keep", merchant = "Keep"))
            repository.upsert(transaction(id = "delete", merchant = "Delete"))
            repository.delete("delete")

            assertThat(repository.transactions.first().map { it.id }).containsExactly("keep")
        }

    @Test fun transactionsAreNewestFirst() =
        runTest {
            val repository = TransactionRepository(FakeTransactionDao())

            repository.upsert(transaction(id = "old", merchant = "Old", occurredAtEpochMillis = 1))
            repository.upsert(transaction(id = "new", merchant = "New", occurredAtEpochMillis = 3))
            repository.upsert(transaction(id = "middle", merchant = "Middle", occurredAtEpochMillis = 2))

            assertThat(repository.transactions.first().map { it.id })
                .containsExactly("new", "middle", "old")
                .inOrder()
        }

    @Test fun importPreservesRecurringInterval() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val imported =
                CsvCodec.decode(
                    CsvCodec.encode(listOf(transaction(id = "rent", merchant = "Rent", recurringInterval = RecurrenceInterval.Monthly))),
                )

            repository.importTransactions(imported)

            assertThat(
                repository.transactions
                    .first()
                    .single()
                    .recurringInterval,
            ).isEqualTo(RecurrenceInterval.Monthly)
        }

    @Test fun legacyImportUsesGeneratedIdAndCountsOnlyNewRows() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val original = transaction(id = "legacy", merchant = "Legacy")
            val imported = CsvCodec.decode(CsvCodec.encode(listOf(original)))

            assertThat(repository.importTrustedLegacyTransactions(imported)).isEqualTo(1)
            assertThat(repository.importTrustedLegacyTransactions(imported)).isEqualTo(0)
            assertThat(repository.load().single().id).isEqualTo(imported.single().id)
            assertThat(repository.load().single().id).isNotEqualTo(original.id)
        }

    @Test fun syncedUpsertAppliesProviderCorrectionsAndPreservesUserFields() =
        runTest {
            val dao = FakeTransactionDao()
            val synced = syncedEntity(id = "simplefin:acct:tx", merchant = "Original")
            dao.upsert(
                synced.copy(
                    category = "User category",
                    note = "User note",
                    recurringInterval = RecurrenceInterval.Monthly.name,
                    reviewedAtEpochMillis = 123,
                    providerDescription = "Old raw description",
                    merchantOverride = "User merchant",
                ),
            )

            val result =
                dao.upsertSyncedTransactionsIgnoringTombstones(
                    listOf(
                        synced.copy(
                            occurredAtEpochMillis = 1766145700000,
                            merchant = "Server edit",
                            cents = -250,
                            accountKey = "new-acct",
                            accountName = "Savings",
                            providerDescription = "Refreshed raw description",
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
                    reviewedAtEpochMillis = 123,
                    providerDescription = "Refreshed raw description",
                    merchantOverride = "User merchant",
                ),
            )
        }

    @Test fun deletingSyncedRowTombstonesAndExactUndoClearsTombstone() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val synced =
                transaction(id = "simplefin:acct:tx", merchant = "Synced").copy(
                    source = "simplefin",
                    accountKey = "acct",
                    accountName = "Checking",
                    reviewedAtEpochMillis = null,
                    providerDescription = "RAW SYNCED",
                    merchantOverride = "Display synced",
                    providerMerchant = "Synced",
                )
            val exactSnapshot = synced.toEntity()

            repository.upsert(synced)
            repository.delete(synced.id)

            assertThat(dao.ignoredIds()).containsExactly(synced.id)
            assertThat(dao.ignoredTombstone(synced.id)?.occurredAtEpochMillis).isEqualTo(synced.occurredAtEpochMillis)
            assertThat(repository.load()).isEmpty()

            repository.restoreDeletedTransaction(synced)

            assertThat(dao.ignoredIds()).isEmpty()
            assertThat(dao.getAll().single()).isEqualTo(exactSnapshot)
            assertThat(dao.getAll().single().reviewedAtEpochMillis).isNull()
        }

    @Test fun tombstoneAwareSyncedInsertPreventsReimport() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val synced = syncedEntity(id = "simplefin:acct:tx")

            repository.upsert(synced.toTransaction())
            repository.delete(synced.id)
            val result = dao.upsertSyncedTransactionsIgnoringTombstones(listOf(synced))

            assertThat(repository.load()).isEmpty()
            assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 0, updated = 0, skipped = 1))
        }

    @Test fun syncedUpsertSkipsLocalIdCollision() =
        runTest {
            val dao = FakeTransactionDao()
            dao.upsert(syncedEntity(id = "collision", merchant = "Local").copy(source = "local"))

            val result =
                dao.upsertSyncedTransactionsIgnoringTombstones(
                    listOf(syncedEntity(id = "collision", merchant = "Provider")),
                )

            assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 0, updated = 0, skipped = 1))
            assertThat(dao.getAll().single().merchant).isEqualTo("Local")
        }

    @Test fun syncedUpsertDeduplicatesBeforeCounting() =
        runTest {
            val dao = FakeTransactionDao()
            dao.upsert(syncedEntity(id = "existing"))

            val result =
                dao.upsertSyncedTransactionsIgnoringTombstones(
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

    @Test fun syncedUpsertQueriesExistingRowsInBoundedChunks() =
        runTest {
            val dao = FakeTransactionDao()
            dao.upsert(syncedEntity(id = "id-0", merchant = "Original"))
            dao.upsert(syncedEntity(id = "id-1", merchant = "Local").copy(source = "local"))
            dao.insertIgnoredTransaction(SimpleFinIgnoredTransactionEntity("id-904"))
            val incoming =
                (0..904).map { syncedEntity(id = "id-$it", merchant = "Provider-$it") } +
                    syncedEntity(id = "id-2", merchant = "Duplicate")

            val result = dao.upsertSyncedTransactionsIgnoringTombstones(incoming)

            assertThat(dao.queriedBatchSizes).containsExactly(900, 4).inOrder()
            assertThat(dao.queriedBatchSizes.all { it <= 900 }).isTrue()
            assertThat(result).isEqualTo(SyncedTransactionWriteResult(inserted = 902, updated = 1, skipped = 2))
            assertThat(dao.getAll().first { it.id == "id-0" }.merchant).isEqualTo("Provider-0")
            assertThat(dao.getAll().first { it.id == "id-1" }.merchant).isEqualTo("Local")
            assertThat(dao.getAll().none { it.id == "id-904" }).isTrue()
        }

    @Test fun rangeResetUsesHalfOpenDstBoundsAndRestoresExactRowsAndTombstones() =
        runTest {
            val dao = FakeTransactionDao()
            val repository = TransactionRepository(dao)
            val zoneId = ZoneId.of("America/New_York")
            val range = PennyLocalDateRange(LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 11))
            val instantRange = range.toInstantRange(zoneId)
            val start = instantRange.startInclusive.toEpochMilli()
            val end = instantRange.endExclusive.toEpochMilli()
            val atStart =
                syncedEntity(id = "shared", merchant = "At start").copy(
                    occurredAtEpochMillis = start,
                    recurringInterval = "future-value",
                )
            val beforeEnd = syncedEntity(id = "before-end", merchant = "Before end").copy(occurredAtEpochMillis = end - 1)
            val beforeStart = syncedEntity(id = "before-start").copy(occurredAtEpochMillis = start - 1)
            val atEnd = syncedEntity(id = "at-end").copy(occurredAtEpochMillis = end)
            dao.upsertAll(listOf(beforeStart, atStart, beforeEnd, atEnd))
            val sharedTombstone =
                SimpleFinIgnoredTransactionEntity(
                    transactionId = atStart.id,
                    ignoredAtEpochMillis = 11,
                    occurredAtEpochMillis = start,
                )
            val beforeEndTombstone =
                SimpleFinIgnoredTransactionEntity(
                    transactionId = "before-end-tombstone",
                    ignoredAtEpochMillis = 22,
                    occurredAtEpochMillis = end - 1,
                )
            val unknownTombstone =
                SimpleFinIgnoredTransactionEntity(
                    transactionId = "unknown",
                    ignoredAtEpochMillis = 33,
                    occurredAtEpochMillis = null,
                )
            val atEndTombstone =
                SimpleFinIgnoredTransactionEntity(
                    transactionId = "at-end-tombstone",
                    ignoredAtEpochMillis = 44,
                    occurredAtEpochMillis = end,
                )
            listOf(sharedTombstone, beforeEndTombstone, unknownTombstone, atEndTombstone)
                .forEach { dao.insertIgnoredTransaction(it) }

            assertThat(Duration.between(instantRange.startInclusive, instantRange.endExclusive))
                .isEqualTo(Duration.ofHours(23))
            assertThat(repository.countRange(range, zoneId))
                .isEqualTo(TransactionRangeCount(transactionCount = 2, tombstoneCount = 2))

            val expectedCount = TransactionRangeCount(transactionCount = 2, tombstoneCount = 2)
            val snapshot = repository.resetRange(range, zoneId, expectedCount)

            assertThat(snapshot.count).isEqualTo(TransactionRangeCount(transactionCount = 2, tombstoneCount = 2))
            assertThat(snapshot.affectedCount).isEqualTo(4)
            assertThat(dao.getAll()).containsExactly(atEnd, beforeStart).inOrder()
            assertThat(dao.ignoredTombstones()).containsExactly(atEndTombstone, unknownTombstone)

            assertThat(repository.restoreRange(snapshot)).isEqualTo(snapshot.count)
            assertThat(dao.getAll()).containsExactly(atEnd, beforeEnd, atStart, beforeStart).inOrder()
            assertThat(dao.ignoredTombstones())
                .containsExactly(sharedTombstone, beforeEndTombstone, unknownTombstone, atEndTombstone)
            assertThat(dao.ignoredTombstone(atStart.id)).isEqualTo(sharedTombstone)
            assertThat(dao.getAll().single { it.id == atStart.id }).isEqualTo(atStart)
        }

    @Test fun localDateRangeRejectsEmptyOrReversedInput() {
        assertThat(
            runCatching {
                PennyLocalDateRange(LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 10))
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching {
                PennyLocalDateRange(LocalDate.of(2024, 3, 11), LocalDate.of(2024, 3, 10))
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun transaction(
        id: String,
        merchant: String,
        occurredAtEpochMillis: Long = 1766145600000,
        recurringInterval: RecurrenceInterval? = null,
    ): Transaction =
        Transaction(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            merchant = merchant,
            category = "Food",
            note = "",
            cents = -100,
            recurringInterval = recurringInterval,
        )

    private fun syncedEntity(
        id: String,
        merchant: String = "Synced",
    ) = TransactionEntity(
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
        private val ignored = LinkedHashMap<String, SimpleFinIgnoredTransactionEntity>()
        private val rules = LinkedHashMap<String, MerchantRuleEntity>()
        private val rows = MutableStateFlow<List<TransactionEntity>>(emptyList())
        private val unreviewedRows = MutableStateFlow<List<TransactionEntity>>(emptyList())
        private val unreviewedCount = MutableStateFlow(0)
        private val ruleRows = MutableStateFlow<List<MerchantRuleEntity>>(emptyList())
        val queriedBatchSizes = mutableListOf<Int>()

        fun ignoredIds(): Set<String> = ignored.keys

        fun ignoredTombstone(id: String): SimpleFinIgnoredTransactionEntity? = ignored[id]

        fun ignoredTombstones(): List<SimpleFinIgnoredTransactionEntity> = ignored.values.toList()

        override fun observeAll(): Flow<List<TransactionEntity>> = rows

        override suspend fun getAll(): List<TransactionEntity> = rows.value

        override fun observeUnreviewedCount(): Flow<Int> = unreviewedCount

        override fun observeUnreviewedTransactions(): Flow<List<TransactionEntity>> = unreviewedRows

        override suspend fun getUnreviewedTransactions(): List<TransactionEntity> = unreviewedRows.value

        override fun observeMerchantRules(): Flow<List<MerchantRuleEntity>> = ruleRows

        override suspend fun getMerchantRules(): List<MerchantRuleEntity> = ruleRows.value

        override suspend fun merchantRuleForKey(key: String): MerchantRuleEntity? = rules[key]

        override suspend fun insertMerchantRule(rule: MerchantRuleEntity) {
            check(rules.putIfAbsent(rule.normalizedProviderMerchant, rule) == null)
            publishRules()
        }

        override suspend fun updateMerchantRule(
            key: String,
            category: String,
            merchantOverride: String?,
        ): Int {
            val current = rules[key] ?: return 0
            rules[key] = current.copy(category = category, merchantOverride = merchantOverride)
            publishRules()
            return 1
        }

        override suspend fun deleteMerchantRuleByKey(key: String): Int {
            val removed = rules.remove(key) ?: return 0
            publishRules()
            return if (removed.normalizedProviderMerchant == key) 1 else 0
        }

        override suspend fun getInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): List<TransactionEntity> =
            rows.value.filter {
                it.occurredAtEpochMillis >= startInclusiveEpochMillis &&
                    it.occurredAtEpochMillis < endExclusiveEpochMillis
            }

        override suspend fun countInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): TransactionRangeCount =
            TransactionRangeCount(
                transactionCount = getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis).size.toLong(),
                tombstoneCount = tombstonesInRange(startInclusiveEpochMillis, endExclusiveEpochMillis).size.toLong(),
            )

        override suspend fun tombstonesInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): List<SimpleFinIgnoredTransactionEntity> =
            ignored.values
                .filter { tombstone ->
                    tombstone.occurredAtEpochMillis?.let { occurrence ->
                        occurrence >= startInclusiveEpochMillis && occurrence < endExclusiveEpochMillis
                    } == true
                }.sortedWith(
                    compareByDescending<SimpleFinIgnoredTransactionEntity> { it.occurredAtEpochMillis }
                        .thenBy { it.transactionId },
                )

        override suspend fun deleteTransactionsInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): Int {
            val ids = getInRange(startInclusiveEpochMillis, endExclusiveEpochMillis).map { it.id }
            ids.forEach(entities::remove)
            publish()
            return ids.size
        }

        override suspend fun deleteTombstonesInRange(
            startInclusiveEpochMillis: Long,
            endExclusiveEpochMillis: Long,
        ): Int {
            val ids = tombstonesInRange(startInclusiveEpochMillis, endExclusiveEpochMillis).map { it.transactionId }
            ids.forEach(ignored::remove)
            return ids.size
        }

        override suspend fun restoreTransactions(transactions: List<TransactionEntity>) {
            upsertAll(transactions)
        }

        override suspend fun restoreTombstones(tombstones: List<SimpleFinIgnoredTransactionEntity>) {
            tombstones.forEach { tombstone -> ignored[tombstone.transactionId] = tombstone }
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
            val result =
                transactions.map { transaction ->
                    if (entities.containsKey(transaction.id)) {
                        -1L
                    } else {
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

        override suspend fun categorizeAndReviewBatch(
            ids: List<String>,
            category: String,
            reviewedAtEpochMillis: Long,
        ): Int {
            var changed = 0
            ids.forEach { id ->
                val current = entities[id]
                if (current?.isUnreviewed == true) {
                    entities[id] = current.copy(category = category, reviewedAtEpochMillis = reviewedAtEpochMillis)
                    changed++
                }
            }
            publish()
            return changed
        }

        override suspend fun restoreReviewState(
            id: String,
            category: String,
            reviewedAtEpochMillis: Long?,
            merchantOverride: String?,
        ): Int {
            val current = entities[id] ?: return 0
            entities[id] =
                current.copy(
                    category = category,
                    reviewedAtEpochMillis = reviewedAtEpochMillis,
                    merchantOverride = merchantOverride,
                )
            publish()
            return 1
        }

        override suspend fun applyRuleToOrigin(
            id: String,
            category: String,
            merchantOverride: String?,
            reviewedAtEpochMillis: Long,
        ): Int {
            val current = entities[id]?.takeIf { it.source == "simplefin" } ?: return 0
            entities[id] =
                current.copy(
                    category = category,
                    merchantOverride = merchantOverride,
                    reviewedAtEpochMillis = current.reviewedAtEpochMillis ?: reviewedAtEpochMillis,
                )
            publish()
            return 1
        }

        override suspend fun sourceForId(id: String): String? = entities[id]?.source

        override suspend fun deleteById(id: String) {
            entities.remove(id)
            publish()
        }

        override suspend fun insertIgnoredTransaction(ignored: SimpleFinIgnoredTransactionEntity) {
            this.ignored[ignored.transactionId] = ignored
        }

        override suspend fun deleteIgnoredTransaction(id: String) {
            ignored.remove(id)
        }

        override suspend fun ignoredTransactionIds(ids: List<String>): List<String> = ids.filter(ignored::containsKey)

        private fun publish() {
            rows.value = entities.values.sortedByDescending { it.occurredAtEpochMillis }
            unreviewedRows.value = rows.value.filter { it.isUnreviewed }
            unreviewedCount.value = unreviewedRows.value.size
        }

        private fun publishRules() {
            ruleRows.value = rules.values.sortedBy { it.normalizedProviderMerchant }
        }
    }
}
