package com.dwk.flowmoney

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        SimpleFinProfileEntity::class,
        SimpleFinAccountEntity::class,
        SimpleFinIgnoredTransactionEntity::class,
        SimpleFinIdentityStateEntity::class,
        MerchantRuleEntity::class,
        PlaceGeocodeEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class FlowMoneyDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    abstract fun simpleFinDao(): SimpleFinDao

    abstract fun placeGeocodeDao(): PlaceGeocodeDao

    abstract fun transactionLocationDao(): TransactionLocationDao

    internal abstract fun simpleFinIdentityDao(): SimpleFinIdentityDao

    companion object {
        @Volatile private var instance: FlowMoneyDatabase? = null

        fun get(context: Context): FlowMoneyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        FlowMoneyDatabase::class.java,
                        "flow_money.db",
                    ).addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                    ).build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN recurringInterval TEXT")
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_transactions_occurredAtEpochMillis " +
                            "ON transactions(occurredAtEpochMillis)",
                    )
                }
            }

        internal val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN accountKey TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN accountName TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_source ON transactions(source)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountKey ON transactions(accountKey)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS simplefin_profile (id TEXT NOT NULL PRIMARY KEY DEFAULT 'default', connectedAtEpochMillis INTEGER, lastSyncAttemptAtEpochMillis INTEGER, lastSuccessfulSyncAtEpochMillis INTEGER, lastError TEXT, isPaused INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS simplefin_accounts (accountId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT, institutionName TEXT, balanceAmount TEXT, availableBalanceAmount TEXT, balanceDateEpochSeconds INTEGER, lastSeenAtEpochMillis INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS simplefin_ignored_transactions (transactionId TEXT NOT NULL PRIMARY KEY, ignoredAtEpochMillis INTEGER NOT NULL)",
                    )
                }
            }

        internal val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE simplefin_profile ADD COLUMN connectionId TEXT NOT NULL DEFAULT 'legacy'")
                    // v4 discarded provider conn_id, so its identifiers cannot be reconciled safely.
                    db.execSQL("DELETE FROM transactions WHERE source = 'simplefin'")
                    db.execSQL("DELETE FROM simplefin_accounts")
                    db.execSQL("DELETE FROM simplefin_ignored_transactions")
                    db.execSQL("DELETE FROM simplefin_profile")
                }
            }

        internal val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE simplefin_profile ADD COLUMN automaticSyncsPerDay INTEGER NOT NULL DEFAULT 1")
                }
            }

        internal val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE simplefin_ignored_transactions " +
                            "ADD COLUMN occurredAtEpochMillis INTEGER",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS simplefin_identity_state (" +
                            "id TEXT NOT NULL PRIMARY KEY DEFAULT 'stable_v2', " +
                            "reconciliationComplete INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO simplefin_identity_state (id, reconciliationComplete) " +
                            "VALUES ('stable_v2', 0)",
                    )
                }
            }

        internal val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN reviewedAtEpochMillis INTEGER")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN providerDescription TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN merchantOverride TEXT")
                    db.execSQL(
                        "UPDATE transactions SET providerDescription = merchant " +
                            "WHERE source = 'simplefin'",
                    )
                    db.execSQL(
                        "UPDATE transactions SET reviewedAtEpochMillis = " +
                            "CAST(strftime('%s', 'now') AS INTEGER) * 1000 " +
                            "WHERE source != 'simplefin' OR category != 'Other' " +
                            "OR length(trim(note, ' ' || char(9) || char(10) || char(11) || char(12) || " +
                            "char(13) || char(133) || char(160) || char(5760) || char(8192) || char(8193) || " +
                            "char(8194) || char(8195) || char(8196) || char(8197) || char(8198) || char(8199) || " +
                            "char(8200) || char(8201) || char(8202) || char(8232) || char(8233) || char(8239) || " +
                            "char(8287) || char(12288))) > 0 OR recurringInterval IS NOT NULL",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS merchant_rules (" +
                            "normalizedProviderMerchant TEXT NOT NULL, " +
                            "category TEXT NOT NULL, merchantOverride TEXT, " +
                            "PRIMARY KEY(normalizedProviderMerchant))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_transactions_source_reviewedAtEpochMillis_occurredAtEpochMillis " +
                            "ON transactions(source, reviewedAtEpochMillis, occurredAtEpochMillis)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "index_simplefin_ignored_transactions_occurredAtEpochMillis " +
                            "ON simplefin_ignored_transactions(occurredAtEpochMillis)",
                    )
                }
            }

        internal val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN flowKind TEXT NOT NULL DEFAULT 'NORMAL'")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN flowKindOverride TEXT")
                    db.execSQL(
                        "UPDATE transactions SET flowKind = 'TRANSFER' " +
                            "WHERE source = 'simplefin' AND (" +
                            "(cents > 0 AND (" +
                            "(UPPER(TRIM(providerDescription)) LIKE 'AUTOMATIC PAYMENT %' AND " +
                            "UPPER(TRIM(providerDescription)) LIKE '% THANK') OR " +
                            "UPPER(TRIM(providerDescription)) = 'PAYMENT - THANK YOU' OR " +
                            "UPPER(TRIM(providerDescription)) = 'AUTOPAY PYMT')) OR " +
                            "(cents < 0 AND (" +
                            "UPPER(TRIM(providerDescription)) = 'CREDIT CRD AUTOPAY' OR " +
                            "(UPPER(TRIM(providerDescription)) LIKE 'CARDMEMBER SERVICE %' AND " +
                            "UPPER(TRIM(providerDescription)) LIKE '% PAY') OR " +
                            "UPPER(TRIM(providerDescription)) = 'CREDIT CARD PAYMENT')))",
                    )
                }
            }

        internal val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE transactions SET flowKind = 'TRANSFER' " +
                            "WHERE source = 'simplefin' AND cents > 0 AND flowKind = 'NORMAL' AND " +
                            "UPPER(TRIM(providerDescription)) = 'AUTOMATIC PAYMENT'",
                    )
                }
            }

        internal val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN locationCity TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN locationState TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN locationCountry TEXT")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS place_geocodes (" +
                            "placeKey TEXT NOT NULL, " +
                            "city TEXT, " +
                            "state TEXT, " +
                            "country TEXT, " +
                            "latitude REAL, " +
                            "longitude REAL, " +
                            "resolvedAtEpochMillis INTEGER NOT NULL, " +
                            "PRIMARY KEY(placeKey))",
                    )
                }
            }

        internal fun resetForTest() =
            synchronized(this) {
                instance?.close()
                instance = null
            }
    }
}
