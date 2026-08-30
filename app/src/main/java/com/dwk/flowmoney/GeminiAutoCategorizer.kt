package com.dwk.flowmoney

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val GEMINI_CATEGORIZE_CHUNK_SIZE = 25

internal class GeminiAutoCategorizer internal constructor(
    private val db: FlowMoneyDatabase,
    private val readApiKey: suspend () -> String?,
    private val requestCategories: suspend (String, List<GeminiCategorizationItem>) -> Map<Int, String>,
) {
    constructor(context: Context) : this(
        FlowMoneyDatabase.get(context),
        { withContext(Dispatchers.IO) { GeminiApiKeyStore(context.applicationContext).read() } },
        GeminiClient()::categorize,
    )

    suspend fun categorizeOneChunk(): Int {
        val apiKey = readApiKey() ?: return 0
        val rows = db.transactionDao().uncategorizedSyncedTransactions(GEMINI_CATEGORIZE_CHUNK_SIZE)
        if (rows.isEmpty()) return 0
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
        val byRef = requestCategories(apiKey, items)
        val categoriesById =
            buildMap {
                for ((ref, category) in byRef) {
                    if (ref in 1..rows.size) put(rows[ref - 1].id, category)
                }
            }
        return db.transactionDao().applyAutoCategories(categoriesById)
    }
}
