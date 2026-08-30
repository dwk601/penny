package com.dwk.flowmoney

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val GEMINI_CATEGORIZE_CHUNK_SIZE = 25
internal const val GEMINI_BACKLOG_REVIEW_LIMIT = 200

internal data class GeminiRunStatus(
    val lastRunAtEpochMillis: Long? = null,
    val lastLabeled: Int = 0,
    val lastQueueEmpty: Boolean = false,
    val lastFailed: Boolean = false,
)

internal interface GeminiRunStatusStore {
    fun load(): GeminiRunStatus

    fun recordSuccess(atEpochMillis: Long, labeled: Int, queueEmpty: Boolean)

    fun recordFailure(atEpochMillis: Long)

    fun clear()

    class InMemory : GeminiRunStatusStore {
        private var current = GeminiRunStatus()

        override fun load(): GeminiRunStatus = current

        override fun recordSuccess(atEpochMillis: Long, labeled: Int, queueEmpty: Boolean) {
            current =
                GeminiRunStatus(
                    lastRunAtEpochMillis = atEpochMillis,
                    lastLabeled = labeled,
                    lastQueueEmpty = queueEmpty,
                    lastFailed = false,
                )
        }

        override fun recordFailure(atEpochMillis: Long) {
            current =
                GeminiRunStatus(
                    lastRunAtEpochMillis = atEpochMillis,
                    lastFailed = true,
                )
        }

        override fun clear() {
            current = GeminiRunStatus()
        }
    }
}

internal fun GeminiRunStatusStore(context: Context): GeminiRunStatusStore =
    SharedPreferencesGeminiRunStatusStore(context)

private class SharedPreferencesGeminiRunStatusStore(
    context: Context,
) : GeminiRunStatusStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): GeminiRunStatus {
        if (!prefs.contains(KEY_LAST_RUN_AT)) return GeminiRunStatus()
        return GeminiRunStatus(
            lastRunAtEpochMillis = prefs.getLong(KEY_LAST_RUN_AT, 0L),
            lastLabeled = prefs.getInt(KEY_LAST_LABELED, 0),
            lastQueueEmpty = prefs.getBoolean(KEY_LAST_QUEUE_EMPTY, false),
            lastFailed = prefs.getBoolean(KEY_LAST_FAILED, false),
        )
    }

    override fun recordSuccess(atEpochMillis: Long, labeled: Int, queueEmpty: Boolean) {
        prefs
            .edit()
            .putLong(KEY_LAST_RUN_AT, atEpochMillis)
            .putInt(KEY_LAST_LABELED, labeled)
            .putBoolean(KEY_LAST_QUEUE_EMPTY, queueEmpty)
            .putBoolean(KEY_LAST_FAILED, false)
            .apply()
    }

    override fun recordFailure(atEpochMillis: Long) {
        prefs
            .edit()
            .putLong(KEY_LAST_RUN_AT, atEpochMillis)
            .putBoolean(KEY_LAST_FAILED, true)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "gemini_status"
        const val KEY_LAST_RUN_AT = "lastRunAt"
        const val KEY_LAST_LABELED = "lastLabeled"
        const val KEY_LAST_QUEUE_EMPTY = "lastQueueEmpty"
        const val KEY_LAST_FAILED = "lastFailed"
    }
}

internal class GeminiAutoCategorizer internal constructor(
    private val db: FlowMoneyDatabase,
    private val readApiKey: suspend () -> String?,
    private val requestCategories: suspend (String, List<GeminiCategorizationItem>) -> Map<Int, String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val status: GeminiRunStatusStore,
) {
    constructor(context: Context) : this(
        db = FlowMoneyDatabase.get(context),
        readApiKey = { withContext(Dispatchers.IO) { GeminiApiKeyStore(context.applicationContext).read() } },
        requestCategories = GeminiClient()::categorize,
        status = GeminiRunStatusStore(context),
    )

    suspend fun categorizeOneChunk(): Int {
        val apiKey = readApiKey() ?: return 0
        val at = now()
        val dao = db.transactionDao()
        dao.reviewAutoCategorizedBacklog(GEMINI_BACKLOG_REVIEW_LIMIT, at)
        val rows = dao.uncategorizedSyncedTransactions(GEMINI_CATEGORIZE_CHUNK_SIZE)
        if (rows.isEmpty()) {
            status.recordSuccess(at, labeled = 0, queueEmpty = true)
            return 0
        }
        val items =
            rows.mapIndexed { index, row ->
                GeminiCategorizationItem(
                    ref = index + 1,
                    merchant = geminiField(row.merchant).orEmpty(),
                    description = geminiField(row.providerDescription),
                    amountDollars = signedDollars(row.cents),
                    accountName = geminiField(row.accountName),
                    city = geminiField(row.locationCity),
                    state = geminiField(row.locationState),
                    categories = CategoryCatalog.categoriesFor(row.cents < 0),
                )
            }
        val labeled =
            try {
                val byRef = requestCategories(apiKey, items)
                val categoriesById =
                    buildMap {
                        for ((ref, category) in byRef) {
                            if (ref in 1..rows.size) put(rows[ref - 1].id, category)
                        }
                    }
                dao.applyAutoCategories(categoriesById, at)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                status.recordFailure(at)
                throw failure
            }
        status.recordSuccess(at, labeled = labeled, queueEmpty = false)
        return labeled
    }
}
