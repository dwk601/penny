package com.dwk.flowmoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-shot backfill that gives already-synced rows a location. It runs against real rows the
 * user already owns, so it must never widen past `source = 'simplefin'`, must terminate on
 * descriptors it cannot parse, and must only record completion after a full successful pass.
 */
@RunWith(AndroidJUnit4::class)
class TransactionLocationBackfillTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearState() = reset()

    @After fun cleanUp() = reset()

    @Test fun backfillsParseableSyncedRowsAndLeavesUnparseableOnesNull() =
        runBlocking {
            withDatabase { database ->
                seed(database)

                TransactionLocationBackfill.run(context, database)

                val rows = database.transactionDao().getAll().associateBy { it.id }
                val parsed = rows.getValue("synced-parseable")
                assertEquals("PORTLAND", parsed.locationCity)
                assertEquals("OR", parsed.locationState)
                assertNull(parsed.locationCountry)

                val unparseable = rows.getValue("synced-unparseable")
                assertNull(unparseable.locationCity)
                assertNull(unparseable.locationState)
                assertNull(unparseable.locationCountry)

                val nullDescriptor = rows.getValue("synced-null-description")
                assertNull(nullDescriptor.locationCity)
                assertNull(nullDescriptor.locationState)

                assertTrue(isComplete())
            }
        }

    @Test fun neverTouchesLocalRowsEvenWhenTheirDescriptionWouldParse() =
        runBlocking {
            withDatabase { database ->
                seed(database)

                TransactionLocationBackfill.run(context, database)

                val rows = database.transactionDao().getAll().associateBy { it.id }
                listOf("manual-local", "csv-local", "other-source").forEach { id ->
                    val row = rows.getValue(id)
                    assertNull("$id must not gain a city", row.locationCity)
                    assertNull("$id must not gain a state", row.locationState)
                    assertNull("$id must not gain a country", row.locationCountry)
                }
                // The whole non-location payload of every row is untouched.
                assertEquals("Rent", rows.getValue("manual-local").merchant)
                assertEquals("local", rows.getValue("manual-local").source)
                assertEquals(-120000, rows.getValue("manual-local").cents)
            }
        }

    @Test fun sourceGuardBlocksTheUpdateStatementItselfForNonSimpleFinRows() =
        runBlocking {
            withDatabase { database ->
                seed(database)
                val dao = database.transactionLocationDao()

                assertEquals(0, dao.updateSimpleFinLocation("manual-local", "PORTLAND", "OR", null))
                assertEquals(0, dao.updateSimpleFinLocation("csv-local", "AUSTIN", "TX", null))
                assertEquals(0, dao.updateSimpleFinLocation("other-source", "AUSTIN", "TX", null))
                assertEquals(0, dao.updateSimpleFinLocation("missing-row", "AUSTIN", "TX", null))
                assertEquals(1, dao.updateSimpleFinLocation("synced-parseable", "PORTLAND", "OR", null))

                val rows = database.transactionDao().getAll().associateBy { it.id }
                assertNull(rows.getValue("manual-local").locationCity)
                assertNull(rows.getValue("csv-local").locationCity)
                assertNull(rows.getValue("other-source").locationCity)
                assertEquals("PORTLAND", rows.getValue("synced-parseable").locationCity)

                // The keyset page is also SimpleFIN-only.
                val page = dao.simpleFinBackfillKeyset("", 100)
                assertEquals(
                    listOf("synced-null-description", "synced-parseable", "synced-populated", "synced-unparseable"),
                    page.map { it.id }.sorted(),
                )
            }
        }

    @Test fun alreadyPopulatedRowsKeepTheirLocationWhenTheDescriptorNoLongerParses() =
        runBlocking {
            withDatabase { database ->
                seed(database)

                TransactionLocationBackfill.run(context, database)

                val populated = database.transactionDao().getAll().single { it.id == "synced-populated" }
                assertEquals("EXISTING CITY", populated.locationCity)
                assertEquals("NV", populated.locationState)
                assertEquals("US", populated.locationCountry)
            }
        }

    @Test fun completionIsNotRecordedWhenAPassFailsAndTheRetryFinishesTheWork() =
        runBlocking {
            withDatabase { database ->
                seed(database)
                val sql = database.openHelper.writableDatabase
                sql.execSQL(
                    "CREATE TRIGGER fail_location_update BEFORE UPDATE OF locationCity ON transactions " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic backfill failure'); END",
                )

                val failure = runCatching { TransactionLocationBackfill.run(context, database) }.exceptionOrNull()

                assertNotNull(failure)
                assertFalse("an interrupted pass must not record completion", isComplete())
                assertNull(database.transactionDao().getAll().single { it.id == "synced-parseable" }.locationCity)

                sql.execSQL("DROP TRIGGER fail_location_update")
                TransactionLocationBackfill.run(context, database)

                assertTrue(isComplete())
                assertEquals(
                    "PORTLAND",
                    database.transactionDao().getAll().single { it.id == "synced-parseable" }.locationCity,
                )
            }
        }

    @Test fun startSwallowsFailuresSoAnInterruptedBackfillNeverCrashesStartup() =
        runBlocking {
            withDatabase { database ->
                seed(database)
                val sql = database.openHelper.writableDatabase
                sql.execSQL(
                    "CREATE TRIGGER fail_location_update BEFORE UPDATE OF locationCity ON transactions " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic backfill failure'); END",
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    // Startup must survive a failing pass without throwing out of start().
                    TransactionLocationBackfill.start(context, database, scope)
                    scope.coroutineContext.job.children.toList().joinAll()

                    assertFalse("an interrupted pass must not record completion", isComplete())
                    assertNull(database.transactionDao().getAll().single { it.id == "synced-parseable" }.locationCity)

                    sql.execSQL("DROP TRIGGER fail_location_update")
                    TransactionLocationBackfill.start(context, database, scope)
                    scope.coroutineContext.job.children.toList().joinAll()

                    assertTrue(isComplete())
                    assertEquals(
                        "PORTLAND",
                        database.transactionDao().getAll().single { it.id == "synced-parseable" }.locationCity,
                    )
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test fun recordedCompletionPreventsASecondPassOverRowsSyncedLater() =
        runBlocking {
            withDatabase { database ->
                seed(database)
                TransactionLocationBackfill.run(context, database)
                assertTrue(isComplete())

                database.transactionDao().upsert(
                    entity(
                        id = "synced-later",
                        source = "simplefin",
                        providerDescription = "TARGET #1234 MINNEAPOLIS MN",
                    ),
                )

                TransactionLocationBackfill.run(context, database)

                val later = database.transactionDao().getAll().single { it.id == "synced-later" }
                assertNull("a completed backfill must not run again", later.locationCity)
                // Rows migrated in the first pass are still populated.
                assertEquals(
                    "PORTLAND",
                    database.transactionDao().getAll().single { it.id == "synced-parseable" }.locationCity,
                )
            }
        }

    @Test fun keysetPagingWalksPastTheBatchSizeAndTerminates() =
        runBlocking {
            withDatabase { database ->
                val rows =
                    (1..205).map { index ->
                        entity(
                            id = "synced-%04d".format(index),
                            source = "simplefin",
                            providerDescription = "TARGET #$index MINNEAPOLIS MN",
                        )
                    }
                database.transactionDao().upsertAll(rows)

                TransactionLocationBackfill.run(context, database)

                val backfilled = database.transactionDao().getAll()
                assertEquals(205, backfilled.size)
                assertEquals(205, backfilled.count { it.locationCity == "MINNEAPOLIS" && it.locationState == "MN" })
                assertTrue(isComplete())
            }
        }

    private fun reset() {
        context
            .getSharedPreferences(TransactionLocationBackfill.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun isComplete(): Boolean =
        context
            .getSharedPreferences(TransactionLocationBackfill.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(TransactionLocationBackfill.COMPLETE, false)

    private inline fun withDatabase(block: (FlowMoneyDatabase) -> Unit) {
        val database =
            Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun seed(database: FlowMoneyDatabase) {
        database.transactionDao().upsertAll(
            listOf(
                entity(
                    id = "synced-parseable",
                    source = "simplefin",
                    providerDescription = "TRADER JOES #123, PORTLAND OR",
                ),
                entity(
                    id = "synced-unparseable",
                    source = "simplefin",
                    providerDescription = "STARBUCKS STORE SEATTLE WA",
                ),
                entity(id = "synced-null-description", source = "simplefin", providerDescription = null),
                entity(
                    id = "synced-populated",
                    source = "simplefin",
                    providerDescription = "STARBUCKS STORE SEATTLE WA",
                    locationCity = "EXISTING CITY",
                    locationState = "NV",
                    locationCountry = "US",
                ),
                entity(
                    id = "manual-local",
                    source = "local",
                    merchant = "Rent",
                    cents = -120000,
                    providerDescription = "TRADER JOES #123, PORTLAND OR",
                ),
                entity(
                    id = "csv-local",
                    source = "local",
                    merchant = "AUTOPAY PYMT #4321, AUSTIN TX",
                    providerDescription = "AUTOPAY PYMT #4321, AUSTIN TX",
                ),
                entity(
                    id = "other-source",
                    source = "other",
                    providerDescription = "TARGET #1234 MINNEAPOLIS MN",
                ),
            ),
        )
    }

    private fun entity(
        id: String,
        source: String,
        providerDescription: String?,
        merchant: String = id,
        cents: Int = -100,
        locationCity: String? = null,
        locationState: String? = null,
        locationCountry: String? = null,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = 1_766_145_600_000,
        merchant = merchant,
        category = "Other",
        note = "",
        cents = cents,
        source = source,
        providerDescription = providerDescription,
        locationCity = locationCity,
        locationState = locationState,
        locationCountry = locationCountry,
    )
}
