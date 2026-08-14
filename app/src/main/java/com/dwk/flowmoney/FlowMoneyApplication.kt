package com.dwk.flowmoney

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking

class FlowMoneyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ponytail: block startup once so a migrated credential cannot outlive its deleted profile.
        runBlocking { SimpleFinMigrationCleanup.run(this@FlowMoneyApplication) }
    }
}

internal object SimpleFinMigrationCleanup {
    internal const val PREFERENCES = "simplefin_migration_cleanup"
    internal const val COMPLETE = "v5_disconnection_complete"

    suspend fun run(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(COMPLETE, false)) return
        run(context, FlowMoneyDatabase.get(context), preferences)
    }

    suspend fun run(context: Context, db: FlowMoneyDatabase) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(COMPLETE, false)) return
        run(context, db, preferences)
    }

    private suspend fun run(
        context: Context,
        db: FlowMoneyDatabase,
        preferences: android.content.SharedPreferences,
    ) {
        if (db.simpleFinDao().getProfile() == null) {
            SimpleFinCredentialStore(context).delete()
            SimpleFinSyncWorker.cancel(context)
        }
        check(preferences.edit().putBoolean(COMPLETE, true).commit()) {
            "SimpleFIN migration cleanup could not be recorded"
        }
    }
}
