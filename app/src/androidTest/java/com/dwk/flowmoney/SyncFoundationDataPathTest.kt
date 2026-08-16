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
                    .addMigrations(
                        FlowMoneyDatabase.MIGRATION_7_8,
                        FlowMoneyDatabase.MIGRATION_8_9,
                        FlowMoneyDatabase.MIGRATION_9_10,
                    ).build()
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
    fun version8MigratesTo9WithSignAwareSimpleFinFlowClassification() =
        runBlocking {
            val name = "flow-kind-v8-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                createVersion8Schema(raw)
                insertVersion8Transaction(raw, "card-automatic", "simplefin", 100, "AUTOMATIC PAYMENT - THANK")
                insertVersion8Transaction(raw, "card-thank-you", "simplefin", 100, " payment - thank you ")
                insertVersion8Transaction(raw, "card-autopay", "simplefin", 100, "AUTOPAY PYMT")
                insertVersion8Transaction(raw, "bank-autopay", "simplefin", -100, "CREDIT CRD AUTOPAY")
                insertVersion8Transaction(raw, "bank-cardmember", "simplefin", -100, "CARDMEMBER SERVICE WEB PAY")
                insertVersion8Transaction(raw, "bank-payment", "simplefin", -100, "CREDIT CARD PAYMENT")
                insertVersion8Transaction(raw, "wrong-card-sign", "simplefin", -100, "AUTOPAY PYMT")
                insertVersion8Transaction(raw, "wrong-bank-sign", "simplefin", 100, "CREDIT CARD PAYMENT")
                insertVersion8Transaction(raw, "near-miss", "simplefin", 100, "AUTOPAY PYMT FEE")
                insertVersion8Transaction(raw, "p2p", "simplefin", -100, "VENMO PAYMENT")
                insertVersion8Transaction(raw, "local", "local", 100, "AUTOPAY PYMT")
                insertVersion8Transaction(raw, "missing-description", "simplefin", 100, null)
                raw.version = 8
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_8_9, FlowMoneyDatabase.MIGRATION_9_10)
                    .build()
            try {
                val rows = migrated.transactionDao().getAll().associateBy { it.id }
                assertEquals(
                    setOf(
                        "card-automatic",
                        "card-thank-you",
                        "card-autopay",
                        "bank-autopay",
                        "bank-cardmember",
                        "bank-payment",
                    ),
                    rows.values.filter { it.flowKind == FlowKind.TRANSFER }.mapTo(mutableSetOf()) { it.id },
                )
                assertTrue(rows.values.all { it.flowKindOverride == null })
                assertEquals(FlowKind.NORMAL, rows.getValue("wrong-card-sign").effectiveFlowKind)
                assertEquals(FlowKind.NORMAL, rows.getValue("wrong-bank-sign").effectiveFlowKind)
                assertEquals(FlowKind.NORMAL, rows.getValue("near-miss").effectiveFlowKind)
                assertEquals(FlowKind.NORMAL, rows.getValue("p2p").effectiveFlowKind)
                assertEquals(FlowKind.NORMAL, rows.getValue("local").effectiveFlowKind)
                assertEquals(FlowKind.NORMAL, rows.getValue("missing-description").effectiveFlowKind)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun version9MigratesTo10ForExactPositiveSimpleFinAutomaticPaymentsOnly() =
        runBlocking {
            val name = "automatic-payment-v9-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                createVersion9Schema(raw)
                insertVersion9Transaction(raw, "exact", "simplefin", 100, "AUTOMATIC PAYMENT")
                insertVersion9Transaction(raw, "normalized", "simplefin", 100, "  automatic payment  ")
                insertVersion9Transaction(
                    raw,
                    "normal-override",
                    "simplefin",
                    100,
                    "Automatic Payment",
                    flowKindOverride = FlowKind.NORMAL,
                )
                insertVersion9Transaction(
                    raw,
                    "transfer-override",
                    "simplefin",
                    100,
                    "AUTOMATIC PAYMENT",
                    flowKindOverride = FlowKind.TRANSFER,
                )
                insertVersion9Transaction(
                    raw,
                    "already-transfer",
                    "simplefin",
                    100,
                    "AUTOMATIC PAYMENT",
                    flowKind = FlowKind.TRANSFER,
                )
                insertVersion9Transaction(raw, "local", "local", 100, "AUTOMATIC PAYMENT")
                insertVersion9Transaction(raw, "wrong-sign", "simplefin", -100, "AUTOMATIC PAYMENT")
                insertVersion9Transaction(raw, "zero", "simplefin", 0, "AUTOMATIC PAYMENT")
                insertVersion9Transaction(raw, "suffix", "simplefin", 100, "AUTOMATIC PAYMENT FEE")
                insertVersion9Transaction(raw, "prefix", "simplefin", 100, "ONLINE AUTOMATIC PAYMENT")
                insertVersion9Transaction(raw, "internal-space", "simplefin", 100, "AUTOMATIC  PAYMENT")
                raw.version = 9
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(FlowMoneyDatabase.MIGRATION_9_10)
                    .build()
            try {
                val rows = migrated.transactionDao().getAll().associateBy { it.id }
                assertEquals(
                    setOf("exact", "normalized", "normal-override", "transfer-override", "already-transfer"),
                    rows.values.filter { it.flowKind == FlowKind.TRANSFER }.mapTo(mutableSetOf()) { it.id },
                )
                assertEquals(
                    setOf("local", "wrong-sign", "zero", "suffix", "prefix", "internal-space"),
                    rows.values.filter { it.flowKind == FlowKind.NORMAL }.mapTo(mutableSetOf()) { it.id },
                )
                assertEquals(FlowKind.NORMAL, rows.getValue("normal-override").flowKindOverride)
                assertEquals(FlowKind.NORMAL, rows.getValue("normal-override").effectiveFlowKind)
                assertEquals(FlowKind.TRANSFER, rows.getValue("transfer-override").flowKindOverride)
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
                            flowKindOverride = FlowKind.TRANSFER,
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
                        flowKindOverride = FlowKind.TRANSFER,
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
                            flowKindOverride = FlowKind.TRANSFER,
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
                        flowKind = FlowKind.TRANSFER,
                        flowKindOverride = FlowKind.NORMAL,
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
                            flowKindOverride = FlowKind.TRANSFER,
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
                        flowKind = FlowKind.NORMAL,
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
    fun syncRefreshesProviderFieldsPreservesUserFieldsAndAutoReviewsNewMatchesAndTransfers() =
        runBlocking {
            withDatabase { db ->
                val origin = SimpleFinServerOrigin.fromAccessUrl(ACCESS_URL)
                val initialAccounts = accounts(existingDescription = "Old raw", newDescription = "New raw")
                val mapped = SimpleFinMapper.map(origin, initialAccounts).transactions.associateBy { it.id }
                val existingIncoming = mapped.values.single { it.id.endsWith(":ZXhpc3Rpbmc") }
                db.transactionDao().upsert(
                    existingIncoming.copy(
                        occurredAtEpochMillis = 1,
                        merchant = "Stale provider merchant",
                        category = "User category",
                        note = "User note",
                        cents = -999,
                        recurringInterval = "Monthly",
                        accountKey = "stale-account",
                        accountName = "Stale account",
                        reviewedAtEpochMillis = 123,
                        providerDescription = "Stale raw",
                        merchantOverride = "User display",
                        flowKindOverride = FlowKind.NORMAL,
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
                val fake =
                    FakeFunctions(
                        accounts(
                            existingDescription = "CREDIT CARD PAYMENT",
                            newDescription = "Brand new raw",
                        ),
                    )
                val repository = SimpleFinSyncRepository(db, fake.bundle())

                val result = repository.syncNow()

                assertTrue(result is SimpleFinSyncResult.Success)
                val rows = db.transactionDao().getAll().associateBy { it.id }
                val existing = rows.getValue(existingIncoming.id)
                assertEquals(10_000L, existing.occurredAtEpochMillis)
                assertEquals("Market #42 Downtown", existing.merchant)
                assertEquals(-100, existing.cents)
                assertEquals(existingIncoming.accountKey, existing.accountKey)
                assertEquals("Checking", existing.accountName)
                assertEquals("CREDIT CARD PAYMENT", existing.providerDescription)
                assertEquals("User category", existing.category)
                assertEquals("User note", existing.note)
                assertEquals("Monthly", existing.recurringInterval)
                assertEquals(123L, existing.reviewedAtEpochMillis)
                assertEquals("User display", existing.merchantOverride)
                assertEquals(FlowKind.TRANSFER, existing.flowKind)
                assertEquals(FlowKind.NORMAL, existing.flowKindOverride)
                assertEquals(FlowKind.NORMAL, existing.effectiveFlowKind)

                val ruleMatch = rows.values.single { it.providerDescription == "Brand new raw" }
                assertEquals("Food", ruleMatch.category)
                assertEquals("Downtown Market", ruleMatch.merchantOverride)
                assertEquals(FlowKind.NORMAL, ruleMatch.flowKind)
                assertEquals(fake.currentTime, ruleMatch.reviewedAtEpochMillis)
                assertFalse(ruleMatch.isUnreviewed)

                val transfer = rows.values.single { it.merchant == "Card payment" }
                assertEquals("Other", transfer.category)
                assertEquals(FlowKind.TRANSFER, transfer.flowKind)
                assertEquals(FlowKind.TRANSFER, transfer.effectiveFlowKind)
                assertEquals(fake.currentTime, transfer.reviewedAtEpochMillis)
                assertFalse(transfer.isUnreviewed)
            }
        }

    @Test
    fun syncedRuleLookupIsExactFirstAndDisablesUnsafeCanonicalFallbacks() =
        runBlocking {
            withDatabase { db ->
                val dao = db.transactionDao()
                val rules =
                    listOf(
                        MerchantRuleEntity("north market *aa11bb22", "Exact", "Exact display"),
                        MerchantRuleEntity("north market", "Stable", "Stable display"),
                        MerchantRuleEntity("coffee roasters *ab12cd34", "Coffee", null),
                        MerchantRuleEntity("collision cafe *ab12cd34", "Food", null),
                        MerchantRuleEntity("collision cafe *zx98yu76", "Travel", null),
                        MerchantRuleEntity("go *ab12cd34", "Too short", null),
                        MerchantRuleEntity("*ab12cd34", "Blank", null),
                    )
                dao.upsertSyncedTransactionsIgnoringTombstones(
                    transactions =
                        listOf(
                            syncedEntity("exact", 1).copy(merchant = "North Market *AA11BB22"),
                            syncedEntity("canonical", 2).copy(merchant = "Coffee Roasters *ZX98YU76"),
                            syncedEntity("collision", 3).copy(merchant = "Collision Cafe *CC44DD55"),
                            syncedEntity("short", 4).copy(merchant = "Go *ZX98YU76"),
                            syncedEntity("blank", 5).copy(merchant = "*ZX98YU76"),
                            syncedEntity("effective-transfer", 6).copy(
                                merchant = "Ordinary unmatched row",
                                flowKindOverride = FlowKind.TRANSFER,
                            ),
                        ),
                    merchantRules = rules,
                    reviewedAtEpochMillis = 8_800L,
                )

                val rows = dao.getAll().associateBy { it.id }
                assertEquals("Exact", rows.getValue("exact").category)
                assertEquals("Exact display", rows.getValue("exact").merchantOverride)
                assertEquals(8_800L, rows.getValue("exact").reviewedAtEpochMillis)
                assertEquals("Coffee", rows.getValue("canonical").category)
                assertEquals(8_800L, rows.getValue("canonical").reviewedAtEpochMillis)
                listOf("collision", "short", "blank").forEach { id ->
                    assertEquals("Other", rows.getValue(id).category)
                    assertNull(rows.getValue(id).reviewedAtEpochMillis)
                }
                assertEquals(FlowKind.TRANSFER, rows.getValue("effective-transfer").effectiveFlowKind)
                assertEquals(8_800L, rows.getValue("effective-transfer").reviewedAtEpochMillis)

                val beforeConflict = dao.getAll()
                val conflictFailure =
                    runCatching {
                        dao.upsertSyncedTransactionsIgnoringTombstones(
                            transactions = listOf(syncedEntity("must-not-write", 7)),
                            merchantRules =
                                listOf(
                                    MerchantRuleEntity("same exact key", "Food", null),
                                    MerchantRuleEntity("same exact key", "Travel", null),
                                ),
                        )
                    }.exceptionOrNull()
                assertTrue(conflictFailure is IllegalArgumentException)
                assertEquals(beforeConflict, dao.getAll())
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
                    SimpleFinTransaction(
                        id = "transfer",
                        posted = 12,
                        amount = "-3.00",
                        description = "CREDIT CARD PAYMENT",
                        pending = false,
                        payee = "Card payment",
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

    private fun createVersion8Schema(raw: SQLiteDatabase) {
        createVersion7Schema(raw)
        raw.execSQL("ALTER TABLE transactions ADD COLUMN reviewedAtEpochMillis INTEGER")
        raw.execSQL("ALTER TABLE transactions ADD COLUMN providerDescription TEXT")
        raw.execSQL("ALTER TABLE transactions ADD COLUMN merchantOverride TEXT")
        raw.execSQL(
            "CREATE TABLE merchant_rules (normalizedProviderMerchant TEXT NOT NULL, " +
                "category TEXT NOT NULL, merchantOverride TEXT, PRIMARY KEY(normalizedProviderMerchant))",
        )
        raw.execSQL(
            "CREATE INDEX index_transactions_source_reviewedAtEpochMillis_occurredAtEpochMillis " +
                "ON transactions(source, reviewedAtEpochMillis, occurredAtEpochMillis)",
        )
        raw.execSQL(
            "CREATE INDEX index_simplefin_ignored_transactions_occurredAtEpochMillis " +
                "ON simplefin_ignored_transactions(occurredAtEpochMillis)",
        )
    }

    private fun createVersion9Schema(raw: SQLiteDatabase) {
        createVersion8Schema(raw)
        raw.execSQL("ALTER TABLE transactions ADD COLUMN flowKind TEXT NOT NULL DEFAULT 'NORMAL'")
        raw.execSQL("ALTER TABLE transactions ADD COLUMN flowKindOverride TEXT")
    }

    private fun insertVersion9Transaction(
        raw: SQLiteDatabase,
        id: String,
        source: String,
        cents: Int,
        providerDescription: String?,
        flowKind: FlowKind = FlowKind.NORMAL,
        flowKindOverride: FlowKind? = null,
    ) {
        raw.execSQL(
            "INSERT INTO transactions " +
                "(id, occurredAtEpochMillis, merchant, category, note, cents, source, providerDescription, " +
                "flowKind, flowKindOverride) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id,
                1L,
                id,
                "Other",
                "",
                cents,
                source,
                providerDescription,
                flowKind.name,
                flowKindOverride?.name,
            ),
        )
    }

    private fun insertVersion8Transaction(
        raw: SQLiteDatabase,
        id: String,
        source: String,
        cents: Int,
        providerDescription: String?,
    ) {
        raw.execSQL(
            "INSERT INTO transactions " +
                "(id, occurredAtEpochMillis, merchant, category, note, cents, source, providerDescription) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, 1L, id, "Other", "", cents, source, providerDescription),
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
