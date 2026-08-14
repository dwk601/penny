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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
                    entity("start-boundary", start, cents = -500, category = "Food"),
                    entity("current-month", start + 1, cents = 200, category = "Income"),
                    entity("end-boundary", end, cents = -80_000, category = "Future"),
                ),
            )

            val summary = PennyWidgetProvider.loadSummary(context, today, zoneId)

            assertEquals(
                WidgetSummary(
                    label = "This month",
                    amount = "\$5.00",
                    count = "2 txns",
                    topCategory = "Food \$5.00",
                ),
                summary,
            )
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
