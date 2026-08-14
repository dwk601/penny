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
    ],
    version = 6,
    exportSchema = true,
)
abstract class FlowMoneyDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun simpleFinDao(): SimpleFinDao

    companion object {
        @Volatile private var instance: FlowMoneyDatabase? = null

        fun get(context: Context): FlowMoneyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowMoneyDatabase::class.java,
                    "flow_money.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringInterval TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_occurredAtEpochMillis " +
                        "ON transactions(occurredAtEpochMillis)",
                )
            }
        }

        internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountKey TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountName TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_source ON transactions(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountKey ON transactions(accountKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS simplefin_profile (id TEXT NOT NULL PRIMARY KEY DEFAULT 'default', connectedAtEpochMillis INTEGER, lastSyncAttemptAtEpochMillis INTEGER, lastSuccessfulSyncAtEpochMillis INTEGER, lastError TEXT, isPaused INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS simplefin_accounts (accountId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT, institutionName TEXT, balanceAmount TEXT, availableBalanceAmount TEXT, balanceDateEpochSeconds INTEGER, lastSeenAtEpochMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS simplefin_ignored_transactions (transactionId TEXT NOT NULL PRIMARY KEY, ignoredAtEpochMillis INTEGER NOT NULL)")
            }
        }

        internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE simplefin_profile ADD COLUMN connectionId TEXT NOT NULL DEFAULT 'legacy'")
                // v4 discarded provider conn_id, so its identifiers cannot be reconciled safely.
                db.execSQL("DELETE FROM transactions WHERE source = 'simplefin'")
                db.execSQL("DELETE FROM simplefin_accounts")
                db.execSQL("DELETE FROM simplefin_ignored_transactions")
                db.execSQL("DELETE FROM simplefin_profile")
            }
        }

        internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE simplefin_profile ADD COLUMN automaticSyncsPerDay INTEGER NOT NULL DEFAULT 1")
            }
        }

        internal fun resetForTest() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
