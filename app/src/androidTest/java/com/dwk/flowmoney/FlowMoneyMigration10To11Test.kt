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
 * `MIGRATION_10_11` against a real v10 file and fails closed on any change to pre-existing data.
 */
@RunWith(AndroidJUnit4::class)
class FlowMoneyMigration10To11Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FlowMoneyDatabase::class.java,
        )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabases() = clearDatabases()

    @After fun cleanUpDatabases() = clearDatabases()

    @Test fun migrationAddsNullableLocationColumnsWithoutTouchingAnyExistingRow() {
        val before =
            helper.createDatabase(MIGRATION_DB, 10).use { db ->
                seedVersionTen(db)
                snapshot(db)
            }

        val migrated = helper.runMigrationsAndValidate(MIGRATION_DB, 11, true, FlowMoneyDatabase.MIGRATION_10_11)

        assertEquals(before, snapshot(migrated))
        assertEquals(4, count(migrated, "transactions"))
        assertEquals(1, count(migrated, "simplefin_profile"))
        assertEquals(1, count(migrated, "simplefin_accounts"))
        assertEquals(1, count(migrated, "simplefin_ignored_transactions"))
        assertEquals(1, count(migrated, "simplefin_identity_state"))
        assertEquals(1, count(migrated, "merchant_rules"))

        // Nothing is backfilled by the migration itself; the async backfill owns that.
        migrated.query("SELECT id, locationCity, locationState, locationCountry FROM transactions").use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                rows++
                assertTrue("locationCity for ${cursor.getString(0)}", cursor.isNull(1))
                assertTrue("locationState for ${cursor.getString(0)}", cursor.isNull(2))
                assertTrue("locationCountry for ${cursor.getString(0)}", cursor.isNull(3))
            }
            assertEquals(4, rows)
        }

        assertEquals(
            listOf(
                ColumnSpec("placeKey", "TEXT", notNull = true, primaryKeyPosition = 1),
                ColumnSpec("city", "TEXT", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("state", "TEXT", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("country", "TEXT", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("latitude", "REAL", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("longitude", "REAL", notNull = false, primaryKeyPosition = 0),
                ColumnSpec("resolvedAtEpochMillis", "INTEGER", notNull = true, primaryKeyPosition = 0),
            ),
            columns(migrated, "place_geocodes"),
        )
        assertEquals(0, count(migrated, "place_geocodes"))
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

    @Test fun theRegisteredProductionMigrationOpensARealVersionTenFileAndKeepsEveryRow() {
        val before =
            helper.createDatabase(PRODUCTION_DB, 10).use { db ->
                seedVersionTen(db)
                snapshot(db)
            }

        // No explicit addMigrations here: this must go through FlowMoneyDatabase.get's own list.
        val database = FlowMoneyDatabase.get(context)
        try {
            runBlocking {
                val dao = database.transactionDao()
                val rows = dao.getAll().sortedBy { it.id }

                assertEquals(
                    listOf("csv-local", "manual-local", "synced-parseable", "synced-unparseable"),
                    rows.map { it.id },
                )
                rows.forEach { row ->
                    assertNull(row.locationCity)
                    assertNull(row.locationState)
                    assertNull(row.locationCountry)
                }
                assertEquals(before, snapshot(database.openHelper.readableDatabase))

                val synced = rows.single { it.id == "synced-parseable" }
                assertEquals("simplefin", synced.source)
                assertEquals("TRADER JOES #123, PORTLAND OR", synced.providerDescription)
                assertEquals("simplefin:v2:origin:account", synced.accountKey)
                assertEquals(FlowKind.NORMAL, synced.flowKind)

                assertEquals(1, dao.getMerchantRules().size)
                assertEquals(1, database.simpleFinDao().observeAccounts().first().size)
                assertNotNull(database.simpleFinDao().getProfile())
                assertEquals(true, database.simpleFinIdentityDao().isReconciliationComplete())
                assertEquals(1, database.simpleFinIdentityDao().tombstones().size)

                // The new place table is live on the migrated file, not just on a fresh install.
                database.placeGeocodeDao().upsert(
                    PlaceGeocodeEntity(
                        placeKey = "portland|or|",
                        city = "PORTLAND",
                        state = "OR",
                        country = null,
                        latitude = 45.5152,
                        longitude = -122.6784,
                        resolvedAtEpochMillis = 1_700_000_000_000,
                    ),
                )
                assertEquals(45.5152, database.placeGeocodeDao().get("portland|or|")?.latitude ?: 0.0, 0.0)
            }
        } finally {
            FlowMoneyDatabase.resetForTest()
        }
    }

    private fun clearDatabases() {
        FlowMoneyDatabase.resetForTest()
        listOf(MIGRATION_DB, PRODUCTION_DB).forEach { context.deleteDatabase(it) }
    }

    private fun seedVersionTen(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride) VALUES " +
                "('synced-parseable', 1766145600000, 'TRADER JOES', 'Food', 'synced note', -1234, NULL, " +
                "'simplefin', 'simplefin:v2:origin:account', 'Checking', 1766145600001, " +
                "'TRADER JOES #123, PORTLAND OR', 'Trader Joe''s', 'NORMAL', NULL)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride) VALUES " +
                "('synced-unparseable', 1766145600002, 'STARBUCKS', 'Coffee', '', -500, NULL, " +
                "'simplefin', 'simplefin:v2:origin:account', 'Checking', NULL, " +
                "'STARBUCKS STORE SEATTLE WA', NULL, 'NORMAL', 'TRANSFER')",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride) VALUES " +
                "('manual-local', 1766145600003, 'Rent', 'Home', 'manual', -120000, 'Monthly', " +
                "'local', NULL, NULL, 1766145600004, NULL, NULL, 'NORMAL', NULL)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, " +
                "source, accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                "flowKind, flowKindOverride) VALUES " +
                "('csv-local', 1766145600005, 'AUTOPAY PYMT #4321, AUSTIN TX', 'Other', 'imported', 500, 'Weekly', " +
                "'local', NULL, NULL, 1766145600006, NULL, NULL, 'TRANSFER', NULL)",
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
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        mapOf(
            "transactions" to
                dump(
                    db,
                    "SELECT id, occurredAtEpochMillis, merchant, category, note, cents, recurringInterval, source, " +
                        "accountKey, accountName, reviewedAtEpochMillis, providerDescription, merchantOverride, " +
                        "flowKind, flowKindOverride FROM transactions ORDER BY id",
                    15,
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
        const val MIGRATION_DB = "migration-10-to-11.db"
        const val PRODUCTION_DB = "flow_money.db"
    }
}
