package com.dwk.flowmoney

import android.content.Context
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TransactionLocationBackfillRow(
    val id: String,
    val providerDescription: String?,
)

@Dao
interface TransactionLocationDao {
    @Query(
        "SELECT id, providerDescription FROM transactions " +
            "WHERE source = 'simplefin' AND id > :afterId " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun simpleFinBackfillKeyset(
        afterId: String,
        limit: Int,
    ): List<TransactionLocationBackfillRow>

    @Query(
        "UPDATE transactions SET locationCity = :city, locationState = :state, locationCountry = :country " +
            "WHERE id = :id AND source = 'simplefin'",
    )
    suspend fun updateSimpleFinLocation(
        id: String,
        city: String?,
        state: String?,
        country: String?,
    ): Int
}

object TransactionLocationBackfill {
    internal const val PREFERENCES = "transaction_location_backfill"
    internal const val COMPLETE = "v11_location_backfill_complete"

    fun start(
        context: Context,
        db: FlowMoneyDatabase = FlowMoneyDatabase.get(context),
        scope: CoroutineScope =
            (context.applicationContext as? FlowMoneyApplication)?.applicationScope
                ?: CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) {
        scope.launch {
            try {
                run(context.applicationContext, db)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Completion is recorded only after a full success so the next launch retries.
            }
        }
    }

    suspend fun run(
        context: Context,
        db: FlowMoneyDatabase,
    ) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(COMPLETE, false)) return
        withContext(Dispatchers.IO) {
            val dao = db.transactionLocationDao()
            var afterId = ""
            while (true) {
                val batch = dao.simpleFinBackfillKeyset(afterId, KEYSET_PAGE_SIZE)
                if (batch.isEmpty()) break
                batch.forEach { row ->
                    val parsed = TransactionLocation.parse(row.providerDescription)
                    if (parsed != null) {
                        dao.updateSimpleFinLocation(
                            id = row.id,
                            city = parsed.city,
                            state = parsed.state,
                            country = parsed.country,
                        )
                    }
                }
                afterId = batch.last().id
                if (batch.size < KEYSET_PAGE_SIZE) break
            }
            check(preferences.edit().putBoolean(COMPLETE, true).commit()) {
                "Transaction location backfill could not be recorded"
            }
        }
    }

    private const val KEYSET_PAGE_SIZE = 200
}
