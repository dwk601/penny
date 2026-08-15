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

                val snapshot = repository.resetRange(range, zoneId)

                assertEquals(TransactionRangeCount(transactionCount = 2, tombstoneCount = 2), snapshot.count)
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

                assertNotNull(runCatching { repository.resetRange(range, zoneId) }.exceptionOrNull())
                assertEquals(listOf(transaction), dao.getAll())
                assertEquals(listOf(tombstone), database.simpleFinIdentityDao().tombstones())

                sql.execSQL("DROP TRIGGER fail_range_delete")
                val snapshot = repository.resetRange(range, zoneId)
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
