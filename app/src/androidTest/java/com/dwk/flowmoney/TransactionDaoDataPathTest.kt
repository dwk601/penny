package com.dwk.flowmoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TransactionDaoDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @After fun closeDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun realDaoOrdersNewestFirstAndUsesInclusiveExclusiveRange() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val start = 10_000L
                val end = 20_000L
                dao.upsertAll(
                    listOf(
                        entity("old", start - 1),
                        entity("start-boundary", start),
                        entity("current-month", 15_000),
                        entity("end-minus-one", end - 1),
                        entity("end-boundary", end),
                    ),
                )

                assertEquals(
                    listOf("end-boundary", "end-minus-one", "current-month", "start-boundary", "old"),
                    dao.observeAll().first().map { it.id },
                )
                assertEquals(
                    listOf("end-minus-one", "current-month", "start-boundary"),
                    dao.getInRange(start, end).map { it.id },
                )
            } finally {
                database.close()
            }
        }

    @Test fun widgetSummaryReadsOnlyCurrentMonthRows() =
        runBlocking {
            val zoneId = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 6, 15)
            val start =
                today
                    .withDayOfMonth(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val end =
                today
                    .withDayOfMonth(1)
                    .plusMonths(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            FlowMoneyDatabase.get(context).transactionDao().upsertAll(
                listOf(
                    entity("old", start - 1, cents = -90_000, category = "Old"),
                    entity("start-boundary", start, cents = -123_456, category = "Food"),
                    entity("current-month", start + 1, cents = 200, category = "Income"),
                    entity("end-boundary", end, cents = -80_000, category = "Future"),
                ),
            )

            val summary = PennyWidgetProvider.loadSummary(context, today, zoneId)

            assertEquals(
                WidgetSummary(
                    label = "This month",
                    amount = "\$1,234.56",
                    count = "2 txns",
                    topCategory = "Food \$1,234.56",
                    compactAmount = "\$1.2K",
                ),
                summary,
            )
            assertEquals("\$1.2K", summary.compactAmount)
            assertFalse(summary.toString().contains("start-boundary"))
            assertFalse(summary.toString().contains("current-month"))
        }

    @Test fun widgetSummaryUsesLocalMonthBoundariesInOffsetZone() =
        runBlocking {
            val zoneId = ZoneId.of("America/Los_Angeles")
            val today = LocalDate.of(2026, 6, 15)
            val start =
                today
                    .withDayOfMonth(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val end =
                today
                    .withDayOfMonth(1)
                    .plusMonths(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            FlowMoneyDatabase.get(context).transactionDao().upsertAll(
                listOf(
                    entity("before-local-start", start - 1, cents = -90_000, category = "Wrong start"),
                    entity("at-local-start", start, cents = -500, category = "Food"),
                    entity("before-local-end", end - 1, cents = -700, category = "Travel"),
                    entity("at-local-end", end, cents = -80_000, category = "Wrong end"),
                ),
            )

            val summary = PennyWidgetProvider.loadSummary(context, today, zoneId)

            assertEquals(
                WidgetSummary(
                    label = "This month",
                    amount = "\$12.00",
                    count = "2 txns",
                    topCategory = "Travel \$7.00",
                    topCategories = listOf("Travel \$7.00", "Food \$5.00"),
                    compactAmount = "\$12.00",
                ),
                summary,
            )
        }

    @Test fun importIgnorePreservesCollisionAndReturnsActualCount() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                dao.upsert(entity("collision", 1, category = "Original"))

                val count = dao.importIgnoringConflicts(listOf(entity("collision", 2, category = "Replacement"), entity("new", 3)))

                assertEquals(1, count)
                assertEquals("Original", dao.getAll().single { it.id == "collision" }.category)
                assertEquals(2, dao.getAll().size)
            } finally {
                database.close()
            }
        }

    @Test fun realRepositoryReimportIsIdempotentAndRetainsDuplicateRows() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val repository = TransactionRepository(database.transactionDao())
                val csv =
                    """
                    id,occurredAtEpochMillis,merchant,category,note,cents
                    forged,1766145600000,Coffee,Food,duplicate,-625
                    forged,1766145600000,Coffee,Food,duplicate,-625
                    """.trimIndent()
                val imported = CsvCodec.decode(csv)

                assertEquals(2, imported.map { it.id }.toSet().size)
                assertEquals(2, repository.importTransactions(imported))
                assertEquals(0, repository.importTransactions(CsvCodec.decode(csv)))
                assertEquals(imported.map { it.id }.toSet(), repository.load().map { it.id }.toSet())
            } finally {
                database.close()
            }
        }

    @Test fun realRoomRangeResetIsDstSafeAndRestoresExactRowsAndTombstones() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val repository = TransactionRepository(dao)
                val zoneId = ZoneId.of("America/New_York")
                val range = PennyLocalDateRange(LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 11))
                val instantRange = range.toInstantRange(zoneId)
                val start = instantRange.startInclusive.toEpochMilli()
                val end = instantRange.endExclusive.toEpochMilli()
                val beforeStart = entity("before-start", start - 1)
                val atStart =
                    entity("shared", start).copy(
                        recurringInterval = "future-value",
                        source = "simplefin",
                        accountKey = "account-key",
                        accountName = "Checking",
                    )
                val beforeEnd = entity("before-end", end - 1)
                val atEnd = entity("at-end", end)
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
                    .forEach { database.simpleFinDao().insertIgnored(it) }

                assertEquals(Duration.ofHours(23), Duration.between(instantRange.startInclusive, instantRange.endExclusive))
                assertEquals(
                    TransactionRangeCount(transactionCount = 2, tombstoneCount = 2),
                    repository.countRange(range, zoneId),
                )

                val expectedCount = TransactionRangeCount(transactionCount = 2, tombstoneCount = 2)
                val snapshot = repository.resetRange(range, zoneId, expectedCount)

                assertEquals(expectedCount, snapshot.count)
                assertEquals(4L, snapshot.affectedCount)
                assertEquals(listOf(atEnd, beforeStart), dao.getAll())
                assertEquals(
                    setOf(unknownTombstone, atEndTombstone),
                    database.simpleFinIdentityDao().tombstones().toSet(),
                )

                assertEquals(snapshot.count, repository.restoreRange(snapshot))
                assertEquals(listOf(atEnd, beforeEnd, atStart, beforeStart), dao.getAll())
                assertEquals(
                    setOf(sharedTombstone, beforeEndTombstone, unknownTombstone, atEndTombstone),
                    database.simpleFinIdentityDao().tombstones().toSet(),
                )
            } finally {
                database.close()
            }
        }

    @Test fun rangeResetRejectsInsertAfterCountWithoutDeletingAnyRows() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val repository = TransactionRepository(dao)
                val zoneId = ZoneId.of("UTC")
                val range = PennyLocalDateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2))
                val occurredAt =
                    range.startInclusive
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val counted = entity("counted-before-insert", occurredAt)
                val inserted = entity("inserted-after-count", occurredAt + 1)
                val nullTombstone =
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = "undated-migration-tombstone",
                        ignoredAtEpochMillis = 1,
                        occurredAtEpochMillis = null,
                    )
                dao.upsert(counted)
                database.simpleFinDao().insertIgnored(nullTombstone)
                val expected = repository.countRange(range, zoneId)
                dao.upsert(inserted)

                val failure = runCatching { repository.resetRange(range, zoneId, expected) }.exceptionOrNull()

                val changed = failure as TransactionRangeChangedException
                assertEquals(expected, changed.expected)
                assertEquals(TransactionRangeCount(transactionCount = 2, tombstoneCount = 0), changed.actual)
                assertEquals(listOf(inserted, counted), dao.getAll())
                assertEquals(listOf(nullTombstone), database.simpleFinIdentityDao().tombstones())
            } finally {
                database.close()
            }
        }

    @Test fun rangeResetRejectsDeleteAfterCountWithoutDeletingRemainingRowsOrTombstones() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val repository = TransactionRepository(dao)
                val zoneId = ZoneId.of("UTC")
                val range = PennyLocalDateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2))
                val occurredAt =
                    range.startInclusive
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val removed = entity("deleted-after-count", occurredAt)
                val remaining = entity("must-remain-after-mismatch", occurredAt + 1)
                val tombstone =
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = "must-remain-tombstone",
                        ignoredAtEpochMillis = 2,
                        occurredAtEpochMillis = occurredAt,
                    )
                dao.upsertAll(listOf(removed, remaining))
                database.simpleFinDao().insertIgnored(tombstone)
                val expected = repository.countRange(range, zoneId)
                dao.deleteById(removed.id)

                val failure = runCatching { repository.resetRange(range, zoneId, expected) }.exceptionOrNull()

                val changed = failure as TransactionRangeChangedException
                assertEquals(expected, changed.expected)
                assertEquals(TransactionRangeCount(transactionCount = 1, tombstoneCount = 1), changed.actual)
                assertEquals(listOf(remaining), dao.getAll())
                assertEquals(listOf(tombstone), database.simpleFinIdentityDao().tombstones())
            } finally {
                database.close()
            }
        }

    @Test fun realRoomRollsBackBothTablesWhenRangeResetOrRestoreFails() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val repository = TransactionRepository(dao)
                val zoneId = ZoneId.of("UTC")
                val range = PennyLocalDateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2))
                val occurredAt =
                    range.startInclusive
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val transaction = entity("atomic-transaction", occurredAt)
                val tombstone =
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = "atomic-tombstone",
                        ignoredAtEpochMillis = 55,
                        occurredAtEpochMillis = occurredAt,
                    )
                dao.upsert(transaction)
                database.simpleFinDao().insertIgnored(tombstone)
                val sql = database.openHelper.writableDatabase
                sql.execSQL(
                    "CREATE TRIGGER fail_range_delete BEFORE DELETE ON simplefin_ignored_transactions " +
                        "WHEN OLD.transactionId = 'atomic-tombstone' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic range delete failure'); END",
                )

                val expectedCount = TransactionRangeCount(transactionCount = 1, tombstoneCount = 1)
                assertNotNull(runCatching { repository.resetRange(range, zoneId, expectedCount) }.exceptionOrNull())
                assertEquals(listOf(transaction), dao.getAll())
                assertEquals(listOf(tombstone), database.simpleFinIdentityDao().tombstones())

                sql.execSQL("DROP TRIGGER fail_range_delete")
                val snapshot = repository.resetRange(range, zoneId, expectedCount)
                sql.execSQL(
                    "CREATE TRIGGER fail_range_restore BEFORE INSERT ON simplefin_ignored_transactions " +
                        "WHEN NEW.transactionId = 'atomic-tombstone' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic range restore failure'); END",
                )

                assertNotNull(runCatching { repository.restoreRange(snapshot) }.exceptionOrNull())
                assertEquals(emptyList<TransactionEntity>(), dao.getAll())
                assertEquals(emptyList<SimpleFinIgnoredTransactionEntity>(), database.simpleFinIdentityDao().tombstones())

                sql.execSQL("DROP TRIGGER fail_range_restore")
                assertEquals(snapshot.count, repository.restoreRange(snapshot))
                assertEquals(listOf(transaction), dao.getAll())
                assertEquals(listOf(tombstone), database.simpleFinIdentityDao().tombstones())
            } finally {
                database.close()
            }
        }

    @Test fun providerRefreshRewritesLocationWhileEveryLocalEditSurvives() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val existing =
                    entity("simplefin:origin:account:tx", 1).copy(
                        source = "simplefin",
                        accountKey = "simplefin:v2:first",
                        accountName = "Checking",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        category = "Groceries",
                        note = "local note",
                        merchantOverride = "Trader Joe's",
                        reviewedAtEpochMillis = 77L,
                        flowKindOverride = FlowKind.TRANSFER,
                        recurringInterval = "Monthly",
                        locationCity = "PORTLAND",
                        locationState = "OR",
                        locationCountry = null,
                    )
                dao.upsert(existing)

                val incoming =
                    entity(existing.id, 2).copy(
                        merchant = "TRADER JOES",
                        source = "simplefin",
                        accountKey = "simplefin:v2:second",
                        accountName = "Renamed Checking",
                        providerDescription = "TRADER JOES #123, MINNEAPOLIS MN",
                        category = "Other",
                        note = "",
                        cents = -999,
                        locationCity = "MINNEAPOLIS",
                        locationState = "MN",
                        locationCountry = null,
                    )
                val result = dao.upsertSyncedTransactionsIgnoringTombstones(listOf(incoming), reviewedAtEpochMillis = 500L)

                val refreshed = dao.getAll().single()
                assertEquals(SyncedTransactionWriteResult(inserted = 0, updated = 1, skipped = 0), result)
                // Provider-owned halves are refreshed, including the new location columns.
                assertEquals("MINNEAPOLIS", refreshed.locationCity)
                assertEquals("MN", refreshed.locationState)
                assertNull(refreshed.locationCountry)
                assertEquals("simplefin:v2:second", refreshed.accountKey)
                assertEquals("TRADER JOES #123, MINNEAPOLIS MN", refreshed.providerDescription)
                assertEquals(-999, refreshed.cents)
                // Locally-owned halves survive untouched.
                assertEquals("Groceries", refreshed.category)
                assertEquals("local note", refreshed.note)
                assertEquals("Trader Joe's", refreshed.merchantOverride)
                assertEquals(77L, refreshed.reviewedAtEpochMillis!!)
                assertEquals(FlowKind.TRANSFER, refreshed.flowKindOverride)
                assertEquals("Monthly", refreshed.recurringInterval)
            } finally {
                database.close()
            }
        }

    @Test fun providerRefreshClearsAStaleLocationAndNeverWritesOverALocalRow() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val staleSynced =
                    entity("synced", 1).copy(
                        source = "simplefin",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        locationCity = "PORTLAND",
                        locationState = "OR",
                    )
                val localRow =
                    entity("collision", 1).copy(
                        source = "local",
                        category = "Home",
                        locationCity = "AUSTIN",
                        locationState = "TX",
                    )
                dao.upsertAll(listOf(staleSynced, localRow))

                val result =
                    dao.upsertSyncedTransactionsIgnoringTombstones(
                        listOf(
                            entity("synced", 1).copy(
                                source = "simplefin",
                                providerDescription = "STARBUCKS STORE SEATTLE WA",
                                locationCity = null,
                                locationState = null,
                                locationCountry = null,
                            ),
                            entity("collision", 9).copy(
                                source = "simplefin",
                                category = "Provider",
                                locationCity = "MINNEAPOLIS",
                                locationState = "MN",
                            ),
                        ),
                        reviewedAtEpochMillis = 500L,
                    )

                val rows = dao.getAll().associateBy { it.id }
                assertEquals(SyncedTransactionWriteResult(inserted = 0, updated = 1, skipped = 1), result)
                assertNull(rows.getValue("synced").locationCity)
                assertNull(rows.getValue("synced").locationState)
                // The local row is source-guarded: nothing from the provider lands on it.
                assertEquals("AUSTIN", rows.getValue("collision").locationCity)
                assertEquals("TX", rows.getValue("collision").locationState)
                assertEquals("local", rows.getValue("collision").source)
                assertEquals("Home", rows.getValue("collision").category)
            } finally {
                database.close()
            }
        }

    @Test fun merchantRuleUndoTreatsAChangedLocationAsNewProviderStateAndKeepsIt() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val origin =
                    entity("simplefin:origin:account:tx", 1).copy(
                        merchant = "TRADER JOES",
                        source = "simplefin",
                        category = "Other",
                        note = "before",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        locationCity = "PORTLAND",
                        locationState = "OR",
                    )
                dao.upsert(origin)
                val applied =
                    dao.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = "Groceries",
                        merchantOverride = "Trader Joe's",
                        reviewedAtEpochMillis = 10L,
                        overwriteConflict = false,
                    ) as MerchantRuleSaveResult.Applied

                // A later sync moves only the location; nothing else about the row changes.
                dao.upsert(dao.getAll().single().copy(locationCity = "MINNEAPOLIS", locationState = "MN"))

                dao.undoMerchantRuleSave(applied.undoToken)

                val restored = dao.getAll().single()
                assertEquals("MINNEAPOLIS", restored.locationCity)
                assertEquals("MN", restored.locationState)
                assertEquals("Other", restored.category)
                assertEquals("before", restored.note)
                assertNull(restored.merchantOverride)
                assertNull(restored.reviewedAtEpochMillis)
                assertEquals(emptyList<MerchantRuleEntity>(), dao.getMerchantRules())
            } finally {
                database.close()
            }
        }

    @Test fun merchantRuleUndoWithAnUnchangedLocationRestoresTheExactPreviousRow() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                val origin =
                    entity("simplefin:origin:account:tx", 1).copy(
                        merchant = "TRADER JOES",
                        source = "simplefin",
                        category = "Other",
                        note = "before",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        locationCity = "PORTLAND",
                        locationState = "OR",
                    )
                dao.upsert(origin)
                val applied =
                    dao.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = "Groceries",
                        merchantOverride = "Trader Joe's",
                        reviewedAtEpochMillis = 10L,
                        overwriteConflict = false,
                    ) as MerchantRuleSaveResult.Applied

                assertEquals("PORTLAND", dao.getAll().single().locationCity)
                assertEquals("Groceries", dao.getAll().single().category)

                dao.undoMerchantRuleSave(applied.undoToken)

                assertEquals(origin, dao.getAll().single())
            } finally {
                database.close()
            }
        }

    @Test fun locationForIdReadsStoredColumnsAndReturnsNullForUnknownRows() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
            try {
                val dao = database.transactionDao()
                dao.upsertAll(
                    listOf(
                        entity("located", 1).copy(locationCity = "PORTLAND", locationState = "OR", locationCountry = "US"),
                        entity("unlocated", 2),
                    ),
                )

                assertEquals(
                    StoredTransactionLocation(locationCity = "PORTLAND", locationState = "OR", locationCountry = "US"),
                    dao.locationForId("located"),
                )
                assertEquals(
                    StoredTransactionLocation(locationCity = null, locationState = null, locationCountry = null),
                    dao.locationForId("unlocated"),
                )
                assertNull(dao.locationForId("missing"))
            } finally {
                database.close()
            }
        }

    private fun entity(
        id: String,
        occurredAtEpochMillis: Long,
        cents: Int = -100,
        category: String = "Other",
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = id,
        category = category,
        note = "",
        cents = cents,
    )
}
