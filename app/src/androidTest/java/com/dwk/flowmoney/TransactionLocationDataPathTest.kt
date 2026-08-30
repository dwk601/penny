package com.dwk.flowmoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor has no location fields, so every editor save round-trips a `Transaction` whose
 * location columns are null. `TransactionRepository.upsert` is the only thing standing between a
 * category edit and silently erasing a parsed place, on both synced and CSV-imported rows.
 */
@RunWith(AndroidJUnit4::class)
class TransactionLocationDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @After fun closeDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun editorSaveOfASyncedRowPreservesTheProviderParsedLocation() =
        runBlocking {
            withRepository { repository, dao ->
                dao.upsert(
                    entity("simplefin:origin:account:tx").copy(
                        merchant = "TRADER JOES",
                        source = "simplefin",
                        accountKey = "simplefin:v2:first",
                        accountName = "Checking",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        reviewedAtEpochMillis = 5L,
                        locationCity = "PORTLAND",
                        locationState = "OR",
                    ),
                )
                val loaded = repository.load().single()
                val draft = loaded.toEditorDraft()

                // The editor draft never carries a location, and neither does what it saves.
                val edited = draft.copy(category = "Groceries", note = "reimbursed").toTransaction()
                assertNull(edited.locationCity)
                assertNull(edited.locationState)
                assertNull(edited.locationCountry)

                repository.upsert(edited)

                val stored = repository.load().single()
                assertEquals("PORTLAND", stored.locationCity)
                assertEquals("OR", stored.locationState)
                assertNull(stored.locationCountry)
                assertEquals("Groceries", stored.category)
                assertEquals("reimbursed", stored.note)
                assertEquals("simplefin", stored.source)
            }
        }

    @Test fun editorSaveOfACsvImportedLocalRowPreservesItsImportedLocation() =
        runBlocking {
            withRepository { repository, _ ->
                val csv =
                    """
                    id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind,locationCity,locationState,locationCountry
                    ignored,1766145600000,Blue Bottle,Coffee,imported,-625,,NORMAL,Portland,OR,US
                    """.trimIndent()
                assertEquals(1, repository.importTransactions(CsvCodec.decode(csv)))

                val imported = repository.load().single()
                assertEquals("Portland", imported.locationCity)
                assertEquals("OR", imported.locationState)
                assertEquals("US", imported.locationCountry)
                assertEquals("local", imported.source)

                repository.upsert(imported.toEditorDraft().copy(category = "Food").toTransaction())

                val stored = repository.load().single()
                assertEquals("Food", stored.category)
                assertEquals("Portland", stored.locationCity)
                assertEquals("OR", stored.locationState)
                assertEquals("US", stored.locationCountry)
            }
        }

    @Test fun editorDraftSaveAndRestoreSurvivesProcessDeathAndStillPreservesLocationOnSave() =
        runBlocking {
            withRepository { repository, dao ->
                dao.upsert(
                    entity("simplefin:origin:account:tx").copy(
                        merchant = "TRADER JOES",
                        source = "simplefin",
                        providerDescription = "TRADER JOES #123, PORTLAND OR",
                        locationCity = "PORTLAND",
                        locationState = "OR",
                    ),
                )
                val draft = repository.load().single().toEditorDraft().copy(category = "Groceries", note = "kept")

                val recreated = restoreEditorDraft(saveEditorDraft(draft))

                assertEquals(draft, recreated)
                repository.upsert(recreated.prepareForEditorSave(candidateId = recreated.id!!, nowEpochMillis = 99L).toTransaction())

                val stored = repository.load().single()
                assertEquals("PORTLAND", stored.locationCity)
                assertEquals("OR", stored.locationState)
                assertEquals("Groceries", stored.category)
                assertEquals("kept", stored.note)
            }
        }

    @Test fun anEditorSaveForANewRowStillWritesNoLocation() =
        runBlocking {
            withRepository { repository, _ ->
                val draft = newEditorDraft().copy(merchant = "Corner Shop", amount = "5.00", category = "Food")

                repository.upsert(draft.prepareForEditorSave(candidateId = "brand-new").toTransaction())

                val stored = repository.load().single()
                assertEquals("brand-new", stored.id)
                assertNull(stored.locationCity)
                assertNull(stored.locationState)
                assertNull(stored.locationCountry)
            }
        }

    @Test fun realRoomCsvEncodeDecodeImportReexportAndReimportIsIdempotent() =
        runBlocking {
            withRepository { repository, dao ->
                dao.upsertAll(
                    listOf(
                        entity("simplefin:origin:account:one").copy(
                            merchant = "TRADER JOES",
                            category = "Food",
                            source = "simplefin",
                            accountKey = "simplefin:v2:first",
                            accountName = "Checking",
                            providerDescription = "TRADER JOES #123, PORTLAND OR",
                            reviewedAtEpochMillis = 5L,
                            locationCity = "PORTLAND",
                            locationState = "OR",
                        ),
                        entity("simplefin:origin:account:two", occurredAtEpochMillis = 1_766_145_600_001).copy(
                            merchant = "STARBUCKS",
                            source = "simplefin",
                            providerDescription = "STARBUCKS STORE SEATTLE WA",
                            reviewedAtEpochMillis = 6L,
                        ),
                        entity("local-row", occurredAtEpochMillis = 1_766_145_600_002).copy(
                            merchant = "Rent",
                            category = "Home",
                            locationCity = "Salt Lake City",
                            locationState = "UT",
                            locationCountry = "US",
                        ),
                    ),
                )

                val exported = CsvCodec.encode(repository.load())
                val decoded = CsvCodec.decode(exported)

                assertTrue(
                    exported.lineSequence().first().endsWith(
                        "locationCity,locationState,locationCountry,providerDescription,accountName,transactedAtEpochMillis,reviewedAtEpochMillis",
                    ),
                )
                assertEquals(3, decoded.size)
                assertEquals(
                    listOf(
                        Triple("Salt Lake City", "UT", "US"),
                        null,
                        Triple("PORTLAND", "OR", null),
                    ),
                    decoded.map { row ->
                        row.locationCity?.let { Triple(it, row.locationState, row.locationCountry) }
                    },
                )

                // Import into a clean database, then re-export and re-import: nothing new appears.
                withRepository { target, _ ->
                    assertEquals(3, target.importTransactions(decoded))
                    val reExported = CsvCodec.encode(target.load())
                    val reDecoded = CsvCodec.decode(reExported)

                    assertEquals(decoded.map { it.id }.toSet(), reDecoded.map { it.id }.toSet())
                    assertEquals(0, target.importTransactions(reDecoded))
                    assertEquals(3, target.load().size)
                    assertEquals(
                        setOf(
                            Triple("PORTLAND", "OR", null),
                            Triple("Salt Lake City", "UT", "US"),
                        ),
                        target
                            .load()
                            .mapNotNull { row -> row.locationCity?.let { Triple(it, row.locationState, row.locationCountry) } }
                            .toSet(),
                    )
                    assertEquals(setOf("local"), target.load().map { it.source }.toSet())
                    assertEquals(CsvCodec.encode(target.load()), reExported)
                }
            }
        }

    @Test fun csvImportedLocationsChangeTheImportIdentitySoLocatedRowsDoNotCollide() =
        runBlocking {
            withRepository { repository, _ ->
                val csv =
                    """
                    id,occurredAtEpochMillis,merchant,category,note,cents,recurring,flowKind,locationCity,locationState,locationCountry
                    ignored,1766145600000,Blue Bottle,Coffee,same,-625,,NORMAL,Portland,OR,
                    ignored,1766145600000,Blue Bottle,Coffee,same,-625,,NORMAL,Seattle,WA,
                    ignored,1766145600000,Blue Bottle,Coffee,same,-625,,NORMAL,,,
                    """.trimIndent()

                val decoded = CsvCodec.decode(csv)

                assertEquals(3, decoded.map { it.id }.toSet().size)
                assertNotEquals(decoded[0].id, decoded[1].id)
                assertNotEquals(decoded[0].id, decoded[2].id)
                assertEquals(3, repository.importTransactions(decoded))
                assertEquals(0, repository.importTransactions(CsvCodec.decode(csv)))
                assertEquals(
                    setOf("Portland", "Seattle", null),
                    repository.load().map { it.locationCity }.toSet(),
                )
            }
        }

    private inline fun withRepository(block: (TransactionRepository, TransactionDao) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(TransactionRepository(database.transactionDao()) { FIXED_NOW }, database.transactionDao())
        } finally {
            database.close()
        }
    }

    private fun entity(
        id: String,
        occurredAtEpochMillis: Long = 1_766_145_600_000,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = id,
        category = "Other",
        note = "",
        cents = -625,
    )

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000
    }
}
