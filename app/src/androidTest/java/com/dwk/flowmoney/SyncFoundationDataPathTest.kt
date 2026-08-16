package com.dwk.flowmoney

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncFoundationDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun version7MigratesTo8WithReviewBackfillRulesAndIndexes() =
        runBlocking {
            val name = "sync-foundation-v7-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                createVersion7Schema(raw)
                insertVersion7Transaction(raw, "manual", "local", "Other", "", null)
                insertVersion7Transaction(raw, "imported", "local", "Other", "", null)
                insertVersion7Transaction(raw, "queued", "simplefin", "Other", " \t\n", null)
                insertVersion7Transaction(raw, "categorized", "simplefin", "Food", "", null)
                insertVersion7Transaction(raw, "noted", "simplefin", "Other", "organized", null)
                insertVersion7Transaction(raw, "recurring", "simplefin", "Other", "", "Monthly")
                raw.version = 7
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_7_8)
                    .build()
            try {
                val rows = migrated.transactionDao().getAll().associateBy { it.id }
                assertNotNull(rows.getValue("manual").reviewedAtEpochMillis)
                assertNotNull(rows.getValue("imported").reviewedAtEpochMillis)
                assertNull(rows.getValue("queued").reviewedAtEpochMillis)
                assertNotNull(rows.getValue("categorized").reviewedAtEpochMillis)
                assertNotNull(rows.getValue("noted").reviewedAtEpochMillis)
                assertNotNull(rows.getValue("recurring").reviewedAtEpochMillis)
                assertEquals("queued raw description", rows.getValue("queued").providerDescription)
                assertNull(rows.getValue("queued").merchantOverride)
                assertEquals(listOf("queued"), migrated.transactionDao().getUnreviewedTransactions().map { it.id })
                assertTrue(migrated.transactionDao().getMerchantRules().isEmpty())

                val indexNames =
                    migrated.openHelper.writableDatabase
                        .query("SELECT name FROM sqlite_master WHERE type = 'index'")
                        .use { cursor ->
                            buildSet {
                                while (cursor.moveToNext()) add(cursor.getString(0))
                            }
                        }
                assertTrue("index_transactions_source_reviewedAtEpochMillis_occurredAtEpochMillis" in indexNames)
                assertTrue("index_simplefin_ignored_transactions_occurredAtEpochMillis" in indexNames)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun bulkReviewIsNewestFirstChunkedAtomicAndUndoable() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 9_999L }
                val queued =
                    (0..1000).map { index ->
                        syncedEntity(
                            id = "queued-${index.toString().padStart(4, '0')}",
                            occurredAtEpochMillis = index.toLong(),
                        )
                    }
                val local = syncedEntity("local", 2_000).copy(source = "local")
                val reviewed = syncedEntity("reviewed", 2_001).copy(reviewedAtEpochMillis = 10)
                dao.upsertAll(queued + local + reviewed)

                assertEquals(1001, dao.observeUnreviewedCount().first())
                assertEquals(
                    "queued-1000",
                    dao
                        .observeUnreviewedTransactions()
                        .first()
                        .first()
                        .id,
                )
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_second_review_chunk BEFORE UPDATE OF category ON transactions " +
                        "WHEN OLD.id = 'queued-0750' AND NEW.category = 'Food' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic bulk review failure'); END",
                )

                assertNotNull(
                    runCatching { repository.categorizeAndReview(queued.map { it.id }, "Food") }.exceptionOrNull(),
                )
                assertEquals(1001, dao.getUnreviewedTransactions().size)
                assertTrue(dao.getUnreviewedTransactions().all { it.category == "Other" })

                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_review_chunk")
                val undo = repository.categorizeAndReview(queued.map { it.id }, "Food")
                assertEquals(1001, undo.transactionStates.size)
                assertTrue(dao.getUnreviewedTransactions().isEmpty())
                assertTrue(dao.getAll().filter { it.id.startsWith("queued-") }.all { it.reviewedAtEpochMillis == 9_999L })
                assertNull(dao.getAll().single { it.id == "local" }.reviewedAtEpochMillis)
                assertEquals(10L, dao.getAll().single { it.id == "reviewed" }.reviewedAtEpochMillis)

                repository.restoreReview(undo)
                assertEquals(1001, dao.getUnreviewedTransactions().size)
                val accepted = repository.acceptAsOther(queued.take(2).map { it.id })
                assertEquals(2, accepted.transactionStates.size)
                assertEquals(999, dao.getUnreviewedTransactions().size)
            }
        }

    @Test
    fun ruleConflictOverwriteUndoAndOriginUpdateAreAtomic() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 5_000L }
                val origin = syncedEntity("origin", 10).copy(merchant = "Store #77   Downtown")
                dao.upsert(origin)

                val first =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = "Food",
                        merchantOverride = "Corner Store",
                    ) as MerchantRuleSaveResult.Applied
                assertEquals("store #77 downtown", first.rule.normalizedProviderMerchant)
                assertEquals(
                    "Corner Store",
                    dao
                        .getAll()
                        .single()
                        .toTransaction()
                        .merchant,
                )
                assertEquals(5_000L, dao.getAll().single().reviewedAtEpochMillis)

                val conflict =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = "Travel",
                        merchantOverride = "Travel Stop",
                    )
                assertTrue(conflict is MerchantRuleSaveResult.Conflict)
                assertEquals(first.rule, dao.getMerchantRules().single())
                assertEquals("Food", dao.getAll().single().category)

                val overwritten =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = "Travel",
                        merchantOverride = "Travel Stop",
                        overwriteConflict = true,
                    ) as MerchantRuleSaveResult.Applied
                assertEquals("Travel", dao.getMerchantRules().single().category)
                repository.undoMerchantRuleSave(overwritten.undoToken)
                assertEquals(first.rule, dao.getMerchantRules().single())
                assertEquals("Food", dao.getAll().single().category)
                assertEquals("Corner Store", dao.getAll().single().merchantOverride)

                repository.undoMerchantRuleSave(first.undoToken)
                assertTrue(dao.getMerchantRules().isEmpty())
                assertEquals(origin, dao.getAll().single())

                val atomicOrigin = syncedEntity("atomic-origin", 11).copy(merchant = "Atomic Store 12")
                dao.upsert(atomicOrigin)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_origin_rule_update BEFORE INSERT ON transactions " +
                        "WHEN NEW.id = 'atomic-origin' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic origin update failure'); END",
                )
                assertNotNull(
                    runCatching {
                        repository.saveAndApplyMerchantRule(atomicOrigin.id, "Food", "Atomic Display")
                    }.exceptionOrNull(),
                )
                assertTrue(dao.getMerchantRules().isEmpty())
                assertEquals(atomicOrigin, dao.getAll().single { it.id == atomicOrigin.id })
            }
        }

    @Test
    fun syncedEditorRuleSaveCommitsCompleteEditorUpdateAtomically() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 7_000L }
                val origin =
                    syncedEntity("editor-atomic", 10).copy(
                        merchant = "Provider Market",
                        accountKey = "provider-account",
                        accountName = "Provider Checking",
                        providerDescription = "RAW BEFORE",
                    )
                dao.upsert(origin)
                val editorUpdate =
                    origin
                        .toTransaction()
                        .copy(
                            occurredAtEpochMillis = 20,
                            merchant = "Market Display",
                            category = "Food",
                            note = "User note",
                            cents = -250,
                            recurringInterval = RecurrenceInterval.Monthly,
                            merchantOverride = "Market Display",
                        )

                val applied =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = editorUpdate.category,
                        merchantOverride = editorUpdate.merchantOverride,
                        editorTransaction = editorUpdate,
                    ) as MerchantRuleSaveResult.Applied

                assertEquals(
                    origin.copy(
                        occurredAtEpochMillis = 20,
                        category = "Food",
                        note = "User note",
                        cents = -250,
                        recurringInterval = "Monthly",
                        reviewedAtEpochMillis = 7_000L,
                        merchantOverride = "Market Display",
                    ),
                    dao.getAll().single(),
                )
                assertEquals(
                    MerchantRuleEntity("provider market", "Food", "Market Display"),
                    applied.rule,
                )
                assertEquals(applied.rule, dao.getMerchantRules().single())
            }
        }

    @Test
    fun syncedEditorRuleConflictLeavesTransactionAndRuleUntouched() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 7_100L }
                val origin = syncedEntity("editor-conflict", 11).copy(merchant = "Conflict Market")
                val existingRule = MerchantRuleEntity("conflict market", "Travel", "Old display")
                dao.upsert(origin)
                dao.insertMerchantRule(existingRule)
                val editorUpdate =
                    origin
                        .toTransaction()
                        .copy(
                            merchant = "New display",
                            category = "Food",
                            note = "Must not persist",
                            merchantOverride = "New display",
                        )

                val result =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = editorUpdate.category,
                        merchantOverride = editorUpdate.merchantOverride,
                        editorTransaction = editorUpdate,
                    )

                assertTrue(result is MerchantRuleSaveResult.Conflict)
                assertEquals(origin, dao.getAll().single())
                assertEquals(existingRule, dao.getMerchantRules().single())
            }
        }

    @Test
    fun syncedEditorRuleUndoRestoresBothAndPreservesLaterProviderRefresh() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 7_200L }
                val origin =
                    syncedEntity("editor-undo", 12).copy(
                        merchant = "Undo Market",
                        accountKey = "old-account",
                        accountName = "Old Checking",
                        providerDescription = "RAW OLD",
                    )
                val previousRule = MerchantRuleEntity("undo market", "Travel", "Old rule display")
                dao.upsert(origin)
                dao.insertMerchantRule(previousRule)
                val editorUpdate =
                    origin
                        .toTransaction()
                        .copy(
                            occurredAtEpochMillis = 22,
                            merchant = "New display",
                            category = "Food",
                            note = "New note",
                            cents = -222,
                            recurringInterval = RecurrenceInterval.Monthly,
                            merchantOverride = "New display",
                        )

                val firstSave =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = editorUpdate.category,
                        merchantOverride = editorUpdate.merchantOverride,
                        overwriteConflict = true,
                        editorTransaction = editorUpdate,
                    ) as MerchantRuleSaveResult.Applied
                repository.undoMerchantRuleSave(firstSave.undoToken)
                assertEquals(origin, dao.getAll().single())
                assertEquals(previousRule, dao.getMerchantRules().single())

                val secondSave =
                    repository.saveAndApplyMerchantRule(
                        originatingTransactionId = origin.id,
                        category = editorUpdate.category,
                        merchantOverride = editorUpdate.merchantOverride,
                        overwriteConflict = true,
                        editorTransaction = editorUpdate,
                    ) as MerchantRuleSaveResult.Applied
                val refreshed =
                    origin.copy(
                        occurredAtEpochMillis = 99,
                        merchant = "Provider Market Refreshed",
                        cents = -999,
                        accountKey = "new-account",
                        accountName = "New Checking",
                        providerDescription = "RAW REFRESHED",
                    )
                dao.upsertSyncedTransactionsIgnoringTombstones(listOf(refreshed))
                repository.undoMerchantRuleSave(secondSave.undoToken)

                assertEquals(previousRule, dao.getMerchantRules().single())
                assertEquals(refreshed, dao.getAll().single())
            }
        }

    @Test
    fun syncedEditorRuleFailureRollsBackRuleAndOriginTogether() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao) { 7_300L }
                val origin = syncedEntity("editor-rollback", 13).copy(merchant = "Rollback Market")
                dao.upsert(origin)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_editor_origin_write BEFORE INSERT ON transactions " +
                        "WHEN NEW.id = 'editor-rollback' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic editor write failure'); END",
                )
                val editorUpdate =
                    origin
                        .toTransaction()
                        .copy(
                            merchant = "Rollback display",
                            category = "Food",
                            note = "Must roll back",
                            merchantOverride = "Rollback display",
                        )

                assertNotNull(
                    runCatching {
                        repository.saveAndApplyMerchantRule(
                            originatingTransactionId = origin.id,
                            category = editorUpdate.category,
                            merchantOverride = editorUpdate.merchantOverride,
                            editorTransaction = editorUpdate,
                        )
                    }.exceptionOrNull(),
                )
                assertTrue(dao.getMerchantRules().isEmpty())
                assertEquals(origin, dao.getAll().single())
            }
        }

    @Test
    fun exactDeleteRestorePreservesNullReviewAndClearsSimpleFinTombstone() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val repository = TransactionRepository(dao)
                val original =
                    syncedEntity("delete-restore", 14).copy(
                        category = "Food",
                        note = "Exact snapshot",
                        merchantOverride = "Exact display",
                        reviewedAtEpochMillis = null,
                    )
                dao.upsert(original)

                repository.delete(original.id)
                assertTrue(dao.getAll().isEmpty())
                assertEquals(listOf(original.id), dao.ignoredTransactionIds(listOf(original.id)))

                repository.restoreDeletedTransaction(original.toTransaction())
                assertEquals(original, dao.getAll().single())
                assertNull(dao.getAll().single().reviewedAtEpochMillis)
                assertTrue(dao.ignoredTransactionIds(listOf(original.id)).isEmpty())
            }
        }

    @Test
    fun syncRefreshesProviderFieldsPreservesUserFieldsAndRulesOnlyNewRows() =
        runBlocking {
            withDatabase { db ->
                val origin = SimpleFinServerOrigin.fromAccessUrl(ACCESS_URL)
                val initialAccounts = accounts(existingDescription = "Old raw", newDescription = "New raw")
                val mapped = SimpleFinMapper.map(origin, initialAccounts).transactions.associateBy { it.id }
                val existingIncoming = mapped.values.single { it.id.endsWith(":ZXhpc3Rpbmc") }
                db.transactionDao().upsert(
                    existingIncoming.copy(
                        category = "User category",
                        note = "User note",
                        recurringInterval = "Monthly",
                        reviewedAtEpochMillis = 123,
                        providerDescription = "Stale raw",
                        merchantOverride = "User display",
                    ),
                )
                db.transactionDao().insertMerchantRule(
                    MerchantRuleEntity(
                        normalizedProviderMerchant = normalizedProviderMerchantKey("Market #42 Downtown"),
                        category = "Food",
                        merchantOverride = "Downtown Market",
                    ),
                )
                db.simpleFinIdentityDao().upsertState(SimpleFinIdentityStateEntity(reconciliationComplete = true))
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = "current"))
                val fake = FakeFunctions(accounts(existingDescription = "Refreshed raw", newDescription = "Brand new raw"))
                val repository = SimpleFinSyncRepository(db, fake.bundle())

                val result = repository.syncNow()

                assertTrue(result is SimpleFinSyncResult.Success)
                val rows = db.transactionDao().getAll().associateBy { it.id }
                val existing = rows.getValue(existingIncoming.id)
                assertEquals("Refreshed raw", existing.providerDescription)
                assertEquals("User category", existing.category)
                assertEquals("User note", existing.note)
                assertEquals("Monthly", existing.recurringInterval)
                assertEquals(123L, existing.reviewedAtEpochMillis)
                assertEquals("User display", existing.merchantOverride)

                val inserted = rows.values.single { it.id != existing.id }
                assertEquals("Brand new raw", inserted.providerDescription)
                assertEquals("Food", inserted.category)
                assertEquals("Downtown Market", inserted.merchantOverride)
                assertEquals(fake.currentTime, inserted.reviewedAtEpochMillis)
                assertFalse(inserted.isUnreviewed)
            }
        }

    private suspend fun withDatabase(block: suspend (FlowMoneyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun syncedEntity(
        id: String,
        occurredAtEpochMillis: Long,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = id,
        category = "Other",
        note = "",
        cents = -100,
        source = "simplefin",
        accountKey = "account",
        accountName = "Checking",
        providerDescription = "$id raw",
    )

    private fun accounts(
        existingDescription: String,
        newDescription: String,
    ) = listOf(
        SimpleFinAccount(
            providerConnectionId = "provider",
            id = "account",
            name = "Checking",
            orgName = "Bank",
            currency = "USD",
            balance = "100.00",
            availableBalance = "90.00",
            transactions =
                listOf(
                    SimpleFinTransaction(
                        id = "existing",
                        posted = 10,
                        amount = "-1.00",
                        description = existingDescription,
                        pending = false,
                        payee = "Market #42 Downtown",
                    ),
                    SimpleFinTransaction(
                        id = "new",
                        posted = 11,
                        amount = "-2.00",
                        description = newDescription,
                        pending = false,
                        payee = "Market #42 Downtown",
                    ),
                ),
        ),
    )

    private class FakeFunctions(
        private val accountRows: List<SimpleFinAccount>,
    ) {
        val currentTime = 1_700_000_000_000L

        fun bundle() =
            SimpleFinSyncFunctions(
                claim = { ACCESS_URL },
                accounts = { _, _, _ -> SimpleFinAccountsResult(accountRows) },
                saveCredential = { _, _ -> },
                readCredential = { ACCESS_URL },
                deleteCredential = {},
                stagePendingCredential = { _, _ -> },
                readPendingCredential = { null },
                promotePendingCredential = { _, _, _ -> },
                restoreRollbackCredential = { false },
                deletePendingCredential = {},
                deleteRollbackCredential = {},
                scheduleWork = {},
                cancelWork = {},
                now = { currentTime },
            )
    }

    private fun createVersion7Schema(raw: SQLiteDatabase) {
        raw.execSQL(
            "CREATE TABLE transactions (id TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, " +
                "merchant TEXT NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, cents INTEGER NOT NULL, " +
                "recurringInterval TEXT, source TEXT NOT NULL DEFAULT 'local', accountKey TEXT, accountName TEXT, " +
                "PRIMARY KEY(id))",
        )
        raw.execSQL("CREATE INDEX index_transactions_occurredAtEpochMillis ON transactions(occurredAtEpochMillis)")
        raw.execSQL("CREATE INDEX index_transactions_source ON transactions(source)")
        raw.execSQL("CREATE INDEX index_transactions_accountKey ON transactions(accountKey)")
        raw.execSQL(
            "CREATE TABLE simplefin_profile (id TEXT NOT NULL PRIMARY KEY DEFAULT 'default', " +
                "connectionId TEXT NOT NULL DEFAULT 'legacy', connectedAtEpochMillis INTEGER, " +
                "lastSyncAttemptAtEpochMillis INTEGER, lastSuccessfulSyncAtEpochMillis INTEGER, " +
                "lastError TEXT, isPaused INTEGER NOT NULL DEFAULT 0, automaticSyncsPerDay INTEGER NOT NULL DEFAULT 1)",
        )
        raw.execSQL(
            "CREATE TABLE simplefin_accounts (accountId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT, " +
                "institutionName TEXT, balanceAmount TEXT, availableBalanceAmount TEXT, balanceDateEpochSeconds INTEGER, " +
                "lastSeenAtEpochMillis INTEGER NOT NULL)",
        )
        raw.execSQL(
            "CREATE TABLE simplefin_ignored_transactions (transactionId TEXT NOT NULL PRIMARY KEY, " +
                "ignoredAtEpochMillis INTEGER NOT NULL, occurredAtEpochMillis INTEGER)",
        )
        raw.execSQL(
            "CREATE TABLE simplefin_identity_state (id TEXT NOT NULL PRIMARY KEY DEFAULT 'stable_v2', " +
                "reconciliationComplete INTEGER NOT NULL DEFAULT 0)",
        )
    }

    private fun insertVersion7Transaction(
        raw: SQLiteDatabase,
        id: String,
        source: String,
        category: String,
        note: String,
        recurringInterval: String?,
    ) {
        raw.execSQL(
            "INSERT INTO transactions " +
                "(id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, 1L, "$id raw description", category, note, -100, recurringInterval, source),
        )
    }

    private companion object {
        const val ACCESS_URL = "https://user:password@bridge.simplefin.org/simplefin"
    }
}
