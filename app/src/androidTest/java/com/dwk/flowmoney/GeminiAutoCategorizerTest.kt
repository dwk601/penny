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
                val requests = mutableListOf<List<GeminiCategorizationItem>>()
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { null },
                        requestCategories = { _, items ->
                            requests += items
                            items.associate { it.ref to "Groceries" }
                        },
                    )

                assertEquals(0, categorizer.categorizeOneChunk())
                assertEquals(emptyList<List<GeminiCategorizationItem>>(), requests)
                assertTrue(db.transactionDao().getAll().all { it.category == "Other" })
            }
        }

    @Test
    fun anEmptyQueueMakesNoRequest() =
        runBlocking {
            withDatabase { db ->
                db.transactionDao().upsert(eligible("reviewed", 1).copy(reviewedAtEpochMillis = 5L))
                var requestCount = 0
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ ->
                            requestCount++
                            emptyMap()
                        },
                    )

                assertEquals(0, categorizer.categorizeOneChunk())
                assertEquals(0, requestCount)
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
                    )

                assertEquals(3, categorizer.categorizeOneChunk())

                // The queue is newest-first, so ref 1 is the newest eligible row.
                val expected = db.transactionDao().getAll().filter { it.category != "Other" }.associateBy { it.id }
                assertEquals(setOf("row-39", "row-27", "row-15"), expected.keys)
                assertEquals("Groceries", expected.getValue("row-39").category)
                assertEquals("Coffee", expected.getValue("row-27").category)
                assertEquals("Travel", expected.getValue("row-15").category)
                assertEquals(25, sentRows.size)
                assertTrue(expected.values.all { it.reviewedAtEpochMillis == null })
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
                    )

                assertEquals(1, categorizer.categorizeOneChunk())

                val rows = db.transactionDao().getAll().associateBy { it.id }
                assertEquals("Food", rows.getValue("row-1").category)
                assertEquals("Other", rows.getValue("row-2").category)
                assertEquals("Other", rows.getValue("row-0").category)
            }
        }

    @Test
    fun aFailingRequestPropagatesAndWritesNothing() =
        runBlocking {
            withDatabase { db ->
                seedEligible(db, count = 4)
                val categorizer =
                    GeminiAutoCategorizer(
                        db = db,
                        readApiKey = { API_KEY },
                        requestCategories = { _, _ -> throw IOException("Gemini request was rejected") },
                    )

                assertThrows(IOException::class.java) {
                    runBlocking { categorizer.categorizeOneChunk() }
                }
                assertTrue(db.transactionDao().getAll().all { it.category == "Other" })
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
    }
}
