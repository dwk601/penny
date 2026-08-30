package com.dwk.flowmoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class GeminiAutoCategorizerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun missingKeyMakesNoRequestAndWritesNothing() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 5)
                // A pre-change labelled row: the backlog sweep must not run without a key either.
                db.transactionDao().upsert(eligible("labelled-backlog", 99).copy(category = "Groceries"))
                val requests = mutableListOf<List<GeminiCategorizationItem>>()
                val status = GeminiRunStatusStore.InMemory()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { null },
                        requestCategories = { _, items ->
                            requests += items
                            items.associate { it.ref to "Groceries" }
                        },
                        now = { FIXED_NOW },
                        status = status,
                    )

                assertEquals(0, categorizer.categorizeOneChunk())
                assertEquals(emptyList<List<GeminiCategorizationItem>>(), requests)
                val rows = db.transactionDao().getAll().associateBy { it.id }
                assertTrue(rows.values.filter { it.id != "labelled-backlog" }.all { it.category == "Other" })
                assertTrue(rows.values.all { it.reviewedAtEpochMillis == null })
                assertEquals("Groceries", rows.getValue("labelled-backlog").category)
                assertEquals(GeminiRunStatus(), status.load())
            }
        }

    @Test
    fun anEmptyQueueMakesNoRequest() =
        runBlocking {
            withDatabase { db ->
                db.transactionDao().upsert(eligible("reviewed", 1).copy(reviewedAtEpochMillis = 5L))
                // Not in the categorize queue (already labelled) but still in the review backlog.
                db.transactionDao().upsert(eligible("labelled-backlog", 2).copy(category = "Groceries"))
                var requestCount = 0
                val status = GeminiRunStatusStore.InMemory()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ ->
                            requestCount++
                            emptyMap()
                        },
                        now = { FIXED_NOW },
                        status = status,
                    )

                assertEquals(0, categorizer.categorizeOneChunk())
                assertEquals(0, requestCount)
                val rows = db.transactionDao().getAll().associateBy { it.id }
                // The backlog sweep still runs on the empty-queue path.
                assertEquals(FIXED_NOW, rows.getValue("labelled-backlog").reviewedAtEpochMillis)
                assertEquals("Groceries", rows.getValue("labelled-backlog").category)
                assertEquals(5L, rows.getValue("reviewed").reviewedAtEpochMillis)
                assertEquals(
                    GeminiRunStatus(
                        lastRunAtEpochMillis = FIXED_NOW,
                        lastLabeled = 0,
                        lastQueueEmpty = true,
                        lastFailed = false,
                    ),
                    status.load(),
                )
            }
        }

    @Test
    fun oneChunkSendsExactlyOneBoundedRequestForALargerQueue() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 40)
                val requests = mutableListOf<List<GeminiCategorizationItem>>()
                val keys = mutableListOf<String>()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { key, items ->
                            keys += key
                            requests += items
                            emptyMap()
                        },
                        now = { FIXED_NOW },
                        status = GeminiRunStatusStore.InMemory(),
                    )

                assertEquals(0, categorizer.categorizeOneChunk())

                assertEquals(1, requests.size)
                assertEquals(listOf(API_KEY), keys)
                assertEquals(GEMINI_CATEGORIZE_CHUNK_SIZE, requests.single().size)
                assertEquals(25, requests.single().size)
                assertEquals((1..25).toList(), requests.single().map { it.ref })
            }
        }

    @Test
    fun refsMapPositionallyOntoTheRowsThatWereSent() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 40)
                var sentRows: List<GeminiCategorizationItem> = emptyList()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, items ->
                            sentRows = items
                            mapOf(1 to "Groceries", 13 to "Coffee", 25 to "Travel")
                        },
                        now = { FIXED_NOW },
                        status = GeminiRunStatusStore.InMemory(),
                    )

                assertEquals(3, categorizer.categorizeOneChunk())

                // The queue is newest-first, so ref 1 is the newest eligible row.
                val all = db.transactionDao().getAll().associateBy { it.id }
                val expected = all.values.filter { it.category != "Other" }.associateBy { it.id }
                assertEquals(setOf("row-39", "row-27", "row-15"), expected.keys)
                assertEquals("Groceries", expected.getValue("row-39").category)
                assertEquals("Coffee", expected.getValue("row-27").category)
                assertEquals("Travel", expected.getValue("row-15").category)
                assertEquals(25, sentRows.size)
                // Labelled rows are auto-confirmed in the same write.
                assertTrue(expected.values.all { it.reviewedAtEpochMillis == FIXED_NOW })
                // Rows in the same chunk that Gemini did not label stay in Review.
                val unlabelledInChunk = (16..38).map { "row-$it" } - setOf("row-27")
                assertTrue(
                    unlabelledInChunk.all { id ->
                        all.getValue(id).category == "Other" && all.getValue(id).reviewedAtEpochMillis == null
                    },
                )
                // Rows beyond the chunk are untouched too.
                assertTrue(
                    (0..14).all { index ->
                        val row = all.getValue("row-$index")
                        row.category == "Other" && row.reviewedAtEpochMillis == null
                    },
                )
                assertNull(expected.getValue("row-39").merchantOverride)
            }
        }

    @Test
    fun refsOutsideTheItemRangeAreIgnored() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 3)
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ ->
                            mapOf(0 to "Groceries", -1 to "Coffee", 4 to "Travel", 99 to "Rent", 2 to "Food")
                        },
                        now = { FIXED_NOW },
                        status = GeminiRunStatusStore.InMemory(),
                    )

                assertEquals(1, categorizer.categorizeOneChunk())

                val rows = db.transactionDao().getAll().associateBy { it.id }
                assertEquals("Food", rows.getValue("row-1").category)
                assertEquals(FIXED_NOW, rows.getValue("row-1").reviewedAtEpochMillis)
                assertEquals("Other", rows.getValue("row-2").category)
                assertNull(rows.getValue("row-2").reviewedAtEpochMillis)
                assertEquals("Other", rows.getValue("row-0").category)
                assertNull(rows.getValue("row-0").reviewedAtEpochMillis)
            }
        }

    @Test
    fun aFailingRequestPropagatesAndWritesNothing() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 4)
                val status = GeminiRunStatusStore.InMemory()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ -> throw IOException("Gemini request was rejected") },
                        now = { FIXED_NOW },
                        status = status,
                    )

                assertThrows(IOException::class.java) {
                    runBlocking { categorizer.categorizeOneChunk() }
                }
                val rows = db.transactionDao().getAll()
                assertTrue(rows.all { it.category == "Other" })
                // Queue rows are 'Other', so the backlog sweep could not have reviewed any of them.
                assertTrue(rows.all { it.reviewedAtEpochMillis == null })
                assertEquals(
                    GeminiRunStatus(lastRunAtEpochMillis = FIXED_NOW, lastFailed = true),
                    status.load(),
                )
            }
        }

    @Test
    fun previouslyLabelledUnreviewedRowsAreDrainedWhenTheQueueIsEmpty() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                dao.upsertAll(
                    listOf(
                        eligible("labelled-new", 3).copy(category = "Groceries"),
                        eligible("labelled-old", 2).copy(category = "Coffee"),
                        eligible("local-labelled", 1).copy(category = "Travel", source = "local", accountKey = null),
                    ),
                )
                var requestCount = 0
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ ->
                            requestCount++
                            emptyMap()
                        },
                        now = { FIXED_NOW },
                        status = GeminiRunStatusStore.InMemory(),
                    )

                assertEquals(0, categorizer.categorizeOneChunk())

                assertEquals(0, requestCount)
                val rows = dao.getAll().associateBy { it.id }
                assertEquals(FIXED_NOW, rows.getValue("labelled-new").reviewedAtEpochMillis)
                assertEquals(FIXED_NOW, rows.getValue("labelled-old").reviewedAtEpochMillis)
                assertEquals("Groceries", rows.getValue("labelled-new").category)
                assertEquals("Coffee", rows.getValue("labelled-old").category)
                // Local rows are never auto-confirmed.
                assertNull(rows.getValue("local-labelled").reviewedAtEpochMillis)
                assertEquals("Travel", rows.getValue("local-labelled").category)
                assertEquals("local", rows.getValue("local-labelled").source)
            }
        }

    @Test
    fun runStatusRecordsLabelledFailedAndNoWorkRuns() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 2)
                val status = GeminiRunStatusStore.InMemory()
                assertEquals(GeminiRunStatus(), status.load())

                var clock = FIXED_NOW
                var response: Map<Int, String> = mapOf(1 to "Groceries", 2 to "Coffee")
                var fail = false
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ ->
                            if (fail) throw IOException("Gemini request was rejected")
                            response
                        },
                        now = { clock },
                        status = status,
                    )

                assertEquals(2, categorizer.categorizeOneChunk())
                assertEquals(
                    GeminiRunStatus(
                        lastRunAtEpochMillis = FIXED_NOW,
                        lastLabeled = 2,
                        lastQueueEmpty = false,
                        lastFailed = false,
                    ),
                    status.load(),
                )

                // The queue is empty now that both rows are labelled and confirmed.
                clock = FIXED_NOW + 60_000L
                assertEquals(0, categorizer.categorizeOneChunk())
                assertEquals(
                    GeminiRunStatus(
                        lastRunAtEpochMillis = FIXED_NOW + 60_000L,
                        lastLabeled = 0,
                        lastQueueEmpty = true,
                        lastFailed = false,
                    ),
                    status.load(),
                )

                // A fresh queue row plus a failing request records the failure.
                db.transactionDao().upsert(eligible("row-later", 500))
                clock = FIXED_NOW + 120_000L
                fail = true
                response = emptyMap()
                assertThrows(IOException::class.java) {
                    runBlocking { categorizer.categorizeOneChunk() }
                }
                assertEquals(
                    GeminiRunStatus(
                        lastRunAtEpochMillis = FIXED_NOW + 120_000L,
                        lastLabeled = 0,
                        lastQueueEmpty = false,
                        lastFailed = true,
                    ),
                    status.load(),
                )
            }
        }

    private suspend fun seedEligible(
        db: FlowMoneyDatabase,
        count: Int,
    ) {
        db.transactionDao().upsertAll((0 until count).map { eligible("row-$it", it.toLong() + 1) })
    }

    private fun eligible(
        id: String,
        occurredAtEpochMillis: Long,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = "MERCHANT $id",
        category = "Other",
        note = "",
        cents = -1_234,
        source = "simplefin",
        accountKey = "account",
        accountName = "Checking",
        reviewedAtEpochMillis = null,
        providerDescription = "RAW $id PORTLAND OR",
    )

    private suspend fun withDatabase(block: suspend (FlowMoneyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val API_KEY = "test-gemini-api-key-fixture"
        const val FIXED_NOW = 1_767_000_000_000L
    }
}
