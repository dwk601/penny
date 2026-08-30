package com.dwk.flowmoney

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * There is exactly one production database in the world. This exercises the real registered
 * `MIGRATION_11_12` against a real v11 file and fails closed on any change to pre-existing data.
 *
 * `transactedAtEpochMillis` is deliberately not backfilled: `transacted_at` is not derivable from
 * `posted`, so NULL is the honest value for every row that predates schema 12.
 */
@RunWith(AndroidJUnit4::class)
class FlowMoneyMigration11To12Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FlowMoneyDatabase::class.java,
        )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabases() = clearDatabases()

    @After fun cleanUpDatabases() = clearDatabases()

    @Test fun migrationAddsNullableTransactedAtColumnWithoutTouchingAnyExistingRow() {
        val before =
            helper.createDatabase(MIGRATION_DB, 11).use { db ->
                seedVersionEleven(db)
                snapshot(db)
            }

        val migrated = helper.runMigrationsAndValidate(MIGRATION_DB, 12, true, FlowMoneyDatabase.MIGRATION_11_12)

        assertEquals(before, snapshot(migrated))
        assertEquals(4, count(migrated, "transactions"))
        assertEquals(1, count(migrated, "simplefin_profile"))
        assertEquals(1, count(migrated, "simplefin_accounts"))
        assertEquals(1, count(migrated, "simplefin_ignored_transactions"))
        assertEquals(1, count(migrated, "simplefin_identity_state"))
        assertEquals(1, count(migrated, "merchant_rules"))
        assertEquals(1, count(migrated, "place_geocodes"))

        // No backfill: every pre-existing row arrives NULL, and nothing else moved.
        migrated.query("SELECT id, transactedAtEpochMillis FROM transactions").use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                rows++
                assertTrue("transactedAtEpochMillis for ${cursor.getString(0)}", cursor.isNull(1))
            }
            assertEquals(4, rows)
        }

        assertEquals(
            ColumnSpec("transactedAtEpochMillis", "INTEGER", notNull = false, primaryKeyPosition = 0),
            columns(migrated, "transactions").single { it.name == "transactedAtEpochMillis" },
        )
        // The provider-owned location columns from v11 are untouched by this migration.
        assertEquals(
            listOf(
                ColumnSpec("locationCity", "TEXT", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("locationState", "TEXT", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("locationCountry", "TEXT", notNull = false, primaryKeyPosition = 0),
            ),
            columns(migrated, "transactions").filter { it.name.startsWith("location") },
        )
        migrated.close()
    }

    @Test fun theRegisteredProductionMigrationOpensARealVersionElevenFileAndKeepsEveryRow() {
        val before =
            helper.createDatabase(PRODUCTION_DB, 11).use { db ->
                seedVersionEleven(db)
                snapshot(db)
            }

        // No explicit addMigrations here: this must go through FlowMoneyDatabase.get's own list.
        val database = FlowMoneyDatabase.get(context)
        try {
            runBlocking {
                val dao = database.transactionDao()
                val rows = dao.getAll().sortedBy { it.id }

                assertEquals(
                    listOf("csv-local", "manual-local", "synced-located", "synced-plain"),
                    rows.map { it.id },
                )
                rows.forEach { row -> assertNull(row.transactedAtEpochMillis) }
                assertEquals(before, snapshot(database.openHelper.readableDatabase))

                val located = rows.single { it.id == "synced-located" }
                assertEquals("simplefin", located.source)
                assertEquals("PORTLAND", located.locationCity)
                assertEquals("OR", located.locationState)
                assertEquals("US", located.locationCountry)
                assertEquals("TRADER JOES #123, PORTLAND OR", located.providerDescription)
                assertEquals("simplefin:v2:origin:account", located.accountKey)
                assertEquals(FlowKind.NORMAL, located.flowKind)

                assertEquals(1, dao.getMerchantRules().size)
                assertEquals(1, database.simpleFinDao().observeAccounts().first().size)
                assertNotNull(database.simpleFinDao().getProfile())
                assertEquals(true, database.simpleFinIdentityDao().isReconciliationComplete())
                assertEquals(1, database.simpleFinIdentityDao().tombstones().size)
                assertNotNull(database.placeGeocodeDao().get("portland|or|us"))

                // The new column is live on the migrated file, not just on a fresh install.
                dao.upsert(located.copy(transactedAtEpochMillis = 1_766_059_200_000L))
                assertEquals(
                    1_766_059_200_000L,
                    dao.getAll().single { it.id == "synced-located" }.transactedAtEpochMillis,
                )
                assertEquals(
                    StoredProviderOwnedFields(
                        locationCity = "PORTLAND",
                        locationState = "OR",
                        locationCountry = "US",
                        transactedAtEpochMillis = 1_766_059_200_000L,
                    ),
                    dao.providerOwnedForId("synced-located"),
                )
            }
        } finally {
            FlowMoneyDatabase.resetForTest()
        }
    }

    private fun clearDatabases() {
        FlowMoneyDatabase.resetForTest()
        listOf(MIGRATION_DB, PRODUCTION_DB).forEach { context.deleteDatabase(it) }
    }

    private fun seedVersionEleven(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride, locationCity, locationState, locationCountry) VALUES " +
                "('synced-located', 1766145600000, 'TRADER JOES', 'Food', 'synced note', -1234, NULL, " +
                "'simplefin', 'simplefin:v2:origin:account', 'Checking', 1766145600001, " +
                "'TRADER JOES #123, PORTLAND OR', 'Trader Joe''s', 'NORMAL', NULL, 'PORTLAND', 'OR', 'US')",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride, locationCity, locationState, locationCountry) VALUES " +
                "('synced-plain', 1766145600002, 'STARBUCKS', 'Coffee', '', -500, NULL, " +
                "'simplefin', 'simplefin:v2:origin:account', 'Checking', NULL, " +
                "'STARBUCKS STORE SEATTLE WA', NULL, 'NORMAL', 'TRANSFER', NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride, locationCity, locationState, locationCountry) VALUES " +
                "('manual-local', 1766145600003, 'Rent', 'Home', 'manual', -120000, 'Monthly', " +
                "'local', NULL, NULL, 1766145600004, NULL, NULL, 'NORMAL', NULL, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride, locationCity, locationState, locationCountry) VALUES " +
                "('csv-local', 1766145600005, 'AUTOPAY PYMT #4321, AUSTIN TX', 'Other', 'imported', 500, 'Weekly', " +
                "'local', NULL, NULL, 1766145600006, NULL, NULL, 'TRANSFER', NULL, 'AUSTIN', 'TX', NULL)",
        )
        db.execSQL(
            "INSERT INTO simplefin_profile (id, connectionId, connectedAtEpochMillis, lastSyncAttemptAtEpochMillis, " +
                "lastSuccessfulSyncAtEpochMillis, lastError, isPaused, automaticSyncsPerDay) VALUES " +
                "('default', 'connection-1', 100, 200, 300, 'previous error', 1, 4)",
        )
        db.execSQL(
            "INSERT INTO simplefin_accounts (accountId, name, currency, institutionName, balanceAmount, " +
                "availableBalanceAmount, balanceDateEpochSeconds, lastSeenAtEpochMillis) VALUES " +
                "('simplefin:v2:origin:account', 'Checking', 'USD', 'First Bank', '12.34', '10.00', 1766145600, 400)",
        )
        db.execSQL(
            "INSERT INTO simplefin_ignored_transactions (transactionId, ignoredAtEpochMillis, occurredAtEpochMillis) " +
                "VALUES ('deleted-synced-row', 500, 1766145600007)",
        )
        db.execSQL("INSERT INTO simplefin_identity_state (id, reconciliationComplete) VALUES ('stable_v2', 1)")
        db.execSQL(
            "INSERT INTO merchant_rules (normalizedProviderMerchant, category, merchantOverride) VALUES " +
                "('trader joes', 'Food', 'Trader Joe''s')",
        )
        db.execSQL(
            "INSERT INTO place_geocodes (placeKey, city, state, country, latitude, longitude, resolvedAtEpochMillis) " +
                "VALUES ('portland|or|us', 'PORTLAND', 'OR', 'US', 45.5152, -122.6784, 1700000000000)",
        )
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        mapOf(
            "transactions" to
                dump(
                    db,
                    "SELECT id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source, " +
                        "accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                        "flowKind, flowKindOverride, locationCity, locationState, locationCountry " +
                        "FROM transactions ORDER BY id",
                    18,
                ),
            "simplefin_profile" to
                dump(
                    db,
                    "SELECT id, connectionId, connectedAtEpochMillis, lastSyncAttemptAtEpochMillis, " +
                        "lastSuccessfulSyncAtEpochMillis, lastError, isPaused, automaticSyncsPerDay " +
                        "FROM simplefin_profile ORDER BY id",
                    8,
                ),
            "simplefin_accounts" to
                dump(
                    db,
                    "SELECT accountId, name, currency, institutionName, balanceAmount, availableBalanceAmount, " +
                        "balanceDateEpochSeconds, lastSeenAtEpochMillis FROM simplefin_accounts ORDER BY accountId",
                    8,
                ),
            "simplefin_ignored_transactions" to
                dump(
                    db,
                    "SELECT transactionId, ignoredAtEpochMillis, occurredAtEpochMillis " +
                        "FROM simplefin_ignored_transactions ORDER BY transactionId",
                    3,
                ),
            "simplefin_identity_state" to
                dump(db, "SELECT id, reconciliationComplete FROM simplefin_identity_state ORDER BY id", 2),
            "merchant_rules" to
                dump(
                    db,
                    "SELECT normalizedProviderMerchant, category, merchantOverride FROM merchant_rules " +
                        "ORDER BY normalizedProviderMerchant",
                    3,
                ),
            "place_geocodes" to
                dump(
                    db,
                    "SELECT placeKey, city, state, country, latitude, longitude, resolvedAtEpochMillis " +
                        "FROM place_geocodes ORDER BY placeKey",
                    7,
                ),
        )

    private fun dump(
        db: SupportSQLiteDatabase,
        sql: String,
        columnCount: Int,
    ): List<List<String?>> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columnCount).map { index -> if (cursor.isNull(index)) null else cursor.getString(index) })
                }
            }
        }

    private fun count(
        db: SupportSQLiteDatabase,
        table: String,
    ): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun columns(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<ColumnSpec> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnSpec(
                            name = cursor.getString(1),
                            type = cursor.getString(2),
                            notNull = cursor.getInt(3) == 1,
                            primaryKeyPosition = cursor.getInt(5),
                        ),
                    )
                }
            }
        }

    private data class ColumnSpec(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val MIGRATION_DB = "migration-11-to-12.db"
        const val PRODUCTION_DB = "flow_money.db"
    }
}
