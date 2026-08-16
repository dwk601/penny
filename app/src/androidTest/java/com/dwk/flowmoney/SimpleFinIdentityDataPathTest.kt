package com.dwk.flowmoney

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SimpleFinIdentityDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun version6MigratesTo7WithoutRewritingIdentitiesAndAddsSafeState() =
        runBlocking {
            val name = "simplefin-v6-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                createVersion6Schema(raw)
                raw.execSQL(
                    "INSERT INTO simplefin_profile (id, connectionId, automaticSyncsPerDay) " +
                        "VALUES ('default', '$CONNECTION_ONE', 4)",
                )
                raw.execSQL(
                    "INSERT INTO transactions " +
                        "(id, occurredAtEpochMillis, merchant, category, note, cents, source, accountKey) " +
                        "VALUES ('legacy-simplefin-id', 1234, 'Merchant', 'Edited', 'note', -100, 'simplefin', 'legacy-account')",
                )
                raw.execSQL(
                    "INSERT INTO simplefin_accounts (accountId, name, lastSeenAtEpochMillis) " +
                        "VALUES ('legacy-account', 'Checking', 99)",
                )
                raw.execSQL(
                    "INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis) " +
                        "VALUES ('legacy-tombstone', 88)",
                )
                raw.version = 6
            }

            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(
                        FlowMoneyDatabase.MIGRATION_6_7,
                        FlowMoneyDatabase.MIGRATION_7_8,
                        FlowMoneyDatabase.MIGRATION_8_9,
                        FlowMoneyDatabase.MIGRATION_9_10,
                    ).build()
            try {
                assertEquals(
                    "legacy-simplefin-id",
                    migrated
                        .transactionDao()
                        .getAll()
                        .single()
                        .id,
                )
                assertEquals(
                    "legacy-account",
                    migrated
                        .simpleFinIdentityDao()
                        .accounts()
                        .single()
                        .accountId,
                )
                val tombstone = migrated.simpleFinIdentityDao().tombstones().single()
                assertEquals("legacy-tombstone", tombstone.transactionId)
                assertEquals(88L, tombstone.ignoredAtEpochMillis)
                assertNull(tombstone.occurredAtEpochMillis)
                assertFalse(migrated.simpleFinIdentityDao().isReconciliationComplete()!!)
                assertEquals(4, migrated.simpleFinDao().getProfile()!!.automaticSyncsPerDay)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun firstSuccessfulSyncDatesMigratedTombstoneOnlyRowAndMarksCompletionOnlyOnSuccess() =
        runBlocking {
            val name = "simplefin-v6-tombstone-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            context.deleteDatabase(name)
            val legacyTombstoneId = legacyTransactionId(CONNECTION_ONE, DELETED_TRANSACTION_ID)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                createVersion6Schema(raw)
                raw.execSQL(
                    "INSERT INTO simplefin_profile (id, connectionId, automaticSyncsPerDay) " +
                        "VALUES ('default', '$CONNECTION_ONE', 1)",
                )
                raw.execSQL(
                    "INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis) " +
                        "VALUES ('$legacyTombstoneId', 88)",
                )
                raw.version = 6
            }
            val migrated =
                Room
                    .databaseBuilder(context, FlowMoneyDatabase::class.java, name)
                    .addMigrations(
                        FlowMoneyDatabase.MIGRATION_6_7,
                        FlowMoneyDatabase.MIGRATION_7_8,
                        FlowMoneyDatabase.MIGRATION_8_9,
                        FlowMoneyDatabase.MIGRATION_9_10,
                    ).build()
            val fake = FakeFunctions()
            fake.credential = CONNECTION_ONE to FIRST_ACCESS_URL
            fake.accountsResult = accountsResult(DELETED_TRANSACTION_ID)
            val repository = SimpleFinSyncRepository(migrated, fake.bundle())
            try {
                assertEquals(emptyList<TransactionEntity>(), migrated.transactionDao().getAll())
                assertNull(
                    migrated
                        .simpleFinIdentityDao()
                        .tombstones()
                        .single()
                        .occurredAtEpochMillis,
                )
                assertFalse(migrated.simpleFinIdentityDao().isReconciliationComplete()!!)
                migrated.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_tombstone_marker BEFORE INSERT ON simplefin_identity_state " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic marker failure'); END",
                )

                assertTrue(repository.syncNow() is SimpleFinSyncResult.Failure)
                assertFalse(migrated.simpleFinIdentityDao().isReconciliationComplete()!!)
                assertEquals(
                    legacyTombstoneId,
                    migrated
                        .simpleFinIdentityDao()
                        .tombstones()
                        .single()
                        .transactionId,
                )
                assertNull(
                    migrated
                        .simpleFinIdentityDao()
                        .tombstones()
                        .single()
                        .occurredAtEpochMillis,
                )
                assertEquals(emptyList<TransactionEntity>(), migrated.transactionDao().getAll())

                migrated.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_tombstone_marker")
                fake.currentTime += SIMPLEFIN_RETRY_INTERVAL_MILLIS
                assertTrue(repository.syncNow() is SimpleFinSyncResult.Success)

                val origin = SimpleFinServerOrigin.fromAccessUrl(FIRST_ACCESS_URL)
                val stableTombstoneId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        PROVIDER_CONNECTION_ID,
                        REMOTE_ACCOUNT_ID,
                        DELETED_TRANSACTION_ID,
                    )
                assertEquals(
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = stableTombstoneId,
                        ignoredAtEpochMillis = 88,
                        occurredAtEpochMillis = DELETED_OCCURRED_AT,
                    ),
                    migrated.simpleFinIdentityDao().tombstones().single(),
                )
                assertEquals(emptyList<TransactionEntity>(), migrated.transactionDao().getAll())
                assertTrue(migrated.simpleFinIdentityDao().isReconciliationComplete()!!)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun firstSuccessfulSyncReconcilesCollisionsAccountsAndTombstonesBeforePayloadUpsert() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = CONNECTION_ONE))
                fake.credential = CONNECTION_ONE to FIRST_ACCESS_URL
                val oldAccountOne = legacyAccountId(CONNECTION_ONE)
                val oldAccountTwo = legacyAccountId(CONNECTION_TWO)
                db.simpleFinDao().upsertAccounts(
                    listOf(
                        accountEntity(oldAccountOne, "Old checking", 10),
                        accountEntity(oldAccountTwo, "Recent checking", 20),
                    ),
                )
                db.transactionDao().upsertAll(
                    listOf(
                        transactionEntity(legacyTransactionId(CONNECTION_ONE, KEPT_TRANSACTION_ID), oldAccountOne).copy(
                            category = "User category",
                        ),
                        transactionEntity(legacyTransactionId(CONNECTION_TWO, KEPT_TRANSACTION_ID), oldAccountTwo).copy(
                            note = "User note",
                        ),
                        transactionEntity(legacyTransactionId(CONNECTION_ONE, DELETED_TRANSACTION_ID), oldAccountOne).copy(
                            occurredAtEpochMillis = DELETED_OCCURRED_AT,
                        ),
                    ),
                )
                db.simpleFinDao().insertIgnored(
                    SimpleFinIgnoredTransactionEntity(
                        transactionId = legacyTransactionId(CONNECTION_TWO, DELETED_TRANSACTION_ID),
                        ignoredAtEpochMillis = 500L,
                    ),
                )
                fake.accountsResult = accountsResult(KEPT_TRANSACTION_ID, DELETED_TRANSACTION_ID)

                assertTrue(repository.syncNow() is SimpleFinSyncResult.Success)

                val origin = SimpleFinServerOrigin.fromAccessUrl(FIRST_ACCESS_URL)
                val stableAccountId = SimpleFinIdentity.accountId(origin, PROVIDER_CONNECTION_ID, REMOTE_ACCOUNT_ID)
                val stableKeptId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        PROVIDER_CONNECTION_ID,
                        REMOTE_ACCOUNT_ID,
                        KEPT_TRANSACTION_ID,
                    )
                val stableDeletedId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        PROVIDER_CONNECTION_ID,
                        REMOTE_ACCOUNT_ID,
                        DELETED_TRANSACTION_ID,
                    )
                val transactions = db.transactionDao().getAll()
                assertEquals(listOf(stableKeptId), transactions.map { it.id })
                assertEquals("User category", transactions.single().category)
                assertEquals("User note", transactions.single().note)
                assertEquals(stableAccountId, transactions.single().accountKey)
                assertEquals(listOf(stableAccountId), db.simpleFinIdentityDao().accounts().map { it.accountId })
                val tombstone = db.simpleFinIdentityDao().tombstones().single()
                assertEquals(stableDeletedId, tombstone.transactionId)
                assertEquals(DELETED_OCCURRED_AT, tombstone.occurredAtEpochMillis)
                assertTrue(db.simpleFinIdentityDao().isReconciliationComplete()!!)
            }
        }

    @Test
    fun failedAtomicWriteRollsBackReconciliationAndMarkerThenRetryCompletes() =
        runBlocking {
            withRepository { db, fake, repository ->
                db.simpleFinDao().upsertProfile(SimpleFinProfileEntity(connectionId = CONNECTION_ONE))
                fake.credential = CONNECTION_ONE to FIRST_ACCESS_URL
                val oldAccountId = legacyAccountId(CONNECTION_ONE)
                val oldTransactionId = legacyTransactionId(CONNECTION_ONE, KEPT_TRANSACTION_ID)
                db.simpleFinDao().upsertAccounts(listOf(accountEntity(oldAccountId, "Checking", 10)))
                db.transactionDao().upsertAll(
                    listOf(
                        transactionEntity(oldTransactionId, oldAccountId).copy(
                            category = "User category",
                            note = "User note",
                        ),
                    ),
                )
                fake.accountsResult = accountsResult(KEPT_TRANSACTION_ID)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_identity_marker BEFORE INSERT ON simplefin_identity_state " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic marker failure'); END",
                )

                assertTrue(repository.syncNow() is SimpleFinSyncResult.Failure)
                assertNull(db.simpleFinIdentityDao().isReconciliationComplete())
                assertEquals(listOf(oldTransactionId), db.transactionDao().getAll().map { it.id })
                assertEquals(listOf(oldAccountId), db.simpleFinIdentityDao().accounts().map { it.accountId })

                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_identity_marker")
                fake.currentTime += SIMPLEFIN_RETRY_INTERVAL_MILLIS
                assertTrue(repository.syncNow() is SimpleFinSyncResult.Success)

                val origin = SimpleFinServerOrigin.fromAccessUrl(FIRST_ACCESS_URL)
                val expectedTransactionId =
                    SimpleFinIdentity.transactionId(
                        origin,
                        PROVIDER_CONNECTION_ID,
                        REMOTE_ACCOUNT_ID,
                        KEPT_TRANSACTION_ID,
                    )
                val reconciled = db.transactionDao().getAll().single()
                assertEquals(expectedTransactionId, reconciled.id)
                assertEquals("User category", reconciled.category)
                assertEquals("User note", reconciled.note)
                assertTrue(db.simpleFinIdentityDao().isReconciliationComplete()!!)
            }
        }

    @Test
    fun reconnectWithNewLocalIdentityAndNormalizedUrlIsIdempotent() =
        runBlocking {
            withRepository { db, fake, repository ->
                fake.claimUrl = FIRST_ACCESS_URL
                fake.accountsResult = accountsResult(KEPT_TRANSACTION_ID)
                assertTrue(repository.connect("first-token") is SimpleFinSyncResult.Success)
                val firstProfileId = db.simpleFinDao().getProfile()!!.connectionId
                val firstTransactionId =
                    db
                        .transactionDao()
                        .getAll()
                        .single()
                        .id

                repository.disconnect()
                fake.claimUrl = SECOND_ACCESS_URL
                assertTrue(repository.connect("second-token") is SimpleFinSyncResult.Success)

                val secondProfileId = db.simpleFinDao().getProfile()!!.connectionId
                val transactions = db.transactionDao().getAll()
                assertFalse(firstProfileId == secondProfileId)
                assertEquals(1, transactions.size)
                assertEquals(firstTransactionId, transactions.single().id)
                assertFalse(transactions.single().id.contains(firstProfileId))
                assertFalse(transactions.single().id.contains(secondProfileId))
                assertFalse(transactions.single().id.contains("second-password"))
                assertTrue(db.simpleFinIdentityDao().isReconciliationComplete()!!)
            }
        }

    private suspend fun withRepository(block: suspend (FlowMoneyDatabase, FakeFunctions, SimpleFinSyncRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        val fake = FakeFunctions()
        try {
            block(db, fake, SimpleFinSyncRepository(db, fake.bundle()))
        } finally {
            db.close()
        }
    }

    private class FakeFunctions {
        var credential: Pair<String, String>? = null
        var pending: SimpleFinPendingCredential? = null
        var claimUrl = FIRST_ACCESS_URL
        var accountsResult = SimpleFinAccountsResult(emptyList())
        var currentTime = 1_700_000_000_000L

        fun bundle() =
            SimpleFinSyncFunctions(
                claim = { claimUrl },
                accounts = { _, _, _ -> accountsResult },
                saveCredential = { connectionId, accessUrl -> credential = connectionId to accessUrl },
                readCredential = { connectionId -> credential?.takeIf { it.first == connectionId }?.second },
                deleteCredential = { credential = null },
                stagePendingCredential = { connectionId, accessUrl ->
                    pending = SimpleFinPendingCredential(connectionId, accessUrl)
                },
                readPendingCredential = { pending },
                promotePendingCredential = { connectionId, _, _ ->
                    val staged = checkNotNull(pending)
                    check(staged.connectionId == connectionId)
                    credential = connectionId to staged.accessUrl
                },
                restoreRollbackCredential = { false },
                deletePendingCredential = { pending = null },
                deleteRollbackCredential = {},
                scheduleWork = {},
                cancelWork = {},
                now = { currentTime },
            )
    }

    private fun accountsResult(vararg transactionIds: String) =
        SimpleFinAccountsResult(
            listOf(
                SimpleFinAccount(
                    providerConnectionId = PROVIDER_CONNECTION_ID,
                    id = REMOTE_ACCOUNT_ID,
                    name = "Synced checking",
                    orgName = "Synced bank",
                    currency = "USD",
                    balance = "100.00",
                    availableBalance = "90.00",
                    transactions =
                        transactionIds.map { transactionId ->
                            SimpleFinTransaction(
                                id = transactionId,
                                posted = DELETED_OCCURRED_AT / 1000L,
                                amount = "-2.00",
                                description = "Synced merchant",
                                pending = false,
                            )
                        },
                ),
            ),
        )

    private fun accountEntity(
        id: String,
        name: String,
        lastSeen: Long,
    ) = SimpleFinAccountEntity(
        accountId = id,
        name = name,
        currency = "USD",
        institutionName = "Bank",
        balanceAmount = "10.00",
        availableBalanceAmount = "9.00",
        balanceDateEpochSeconds = null,
        lastSeenAtEpochMillis = lastSeen,
    )

    private fun transactionEntity(
        id: String,
        accountId: String,
    ) = TransactionEntity(
        id = id,
        occurredAtEpochMillis = 1_700_000_000_000L,
        merchant = "Old merchant",
        category = "Other",
        note = "",
        cents = -100,
        source = "simplefin",
        accountKey = accountId,
        accountName = "Checking",
    )

    private fun legacyAccountId(connectionId: String): String =
        "simplefin:$connectionId:${encode(PROVIDER_CONNECTION_ID)}:${encode(REMOTE_ACCOUNT_ID)}"

    private fun legacyTransactionId(
        connectionId: String,
        transactionId: String,
    ): String = legacyAccountId(connectionId) + ":" + encode(transactionId)

    private fun encode(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun createVersion6Schema(raw: SQLiteDatabase) {
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
                "ignoredAtEpochMillis INTEGER NOT NULL)",
        )
    }

    private companion object {
        const val CONNECTION_ONE = "11111111-1111-4111-8111-111111111111"
        const val CONNECTION_TWO = "22222222-2222-4222-8222-222222222222"
        const val PROVIDER_CONNECTION_ID = "provider-connection"
        const val REMOTE_ACCOUNT_ID = "remote-account"
        const val KEPT_TRANSACTION_ID = "kept-transaction"
        const val DELETED_TRANSACTION_ID = "deleted-transaction"
        const val DELETED_OCCURRED_AT = 1_700_000_123_000L
        const val FIRST_ACCESS_URL =
            "https://first-user:first-password@BRIDGE.SimpleFIN.org:443/private/access?credential=first-secret"
        const val SECOND_ACCESS_URL =
            "https://second-user:second-password@bridge.simplefin.org/other/access?credential=second-secret"
    }
}
