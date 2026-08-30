package com.dwk.flowmoney

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal const val MAX_FIELD_CHARS = 120

internal data class GeminiCategorizationItem(
    val ref: Int,
    val merchant: String,
    val description: String?,
    val amountDollars: String,
    val accountName: String?,
    val city: String?,
    val state: String?,
    val categories: List<String>,
)

internal fun signedDollars(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents.toLong())
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

internal fun geminiField(value: String?): String? {
    val clipped = value?.trim()?.take(MAX_FIELD_CHARS).orEmpty()
    return clipped.takeIf { it.isNotBlank() }
}

internal fun buildRequestBody(items: List<GeminiCategorizationItem>): String {
    val instruction =
        "you are labelling US bank/credit-card statement lines; the description often carries a store number and CITY STATE; choose exactly one label from the provided list for each item; do not invent labels."
    val itemsJson = JSONArray()
    for (item in items) {
        itemsJson.put(
            JSONObject()
                .put("ref", item.ref)
                .put("merchant", item.merchant)
                .put("description", item.description ?: JSONObject.NULL)
                .put("amountDollars", item.amountDollars)
                .put("accountName", item.accountName ?: JSONObject.NULL)
                .put("city", item.city ?: JSONObject.NULL)
                .put("state", item.state ?: JSONObject.NULL)
                .put("categories", JSONArray(item.categories)),
        )
    }
    val prompt = instruction + "\n" + JSONObject().put("items", itemsJson).toString()
    val itemSchema =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("ref", JSONObject().put("type", "INTEGER"))
                    .put("category", JSONObject().put("type", "STRING")),
            )
            .put("required", JSONArray().put("ref").put("category"))
    val responseSchema =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put(
                    "items",
                    JSONObject()
                        .put("type", "ARRAY")
                        .put("items", itemSchema),
                ),
            )
            .put("required", JSONArray().put("items"))
    return JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", prompt)),
                ),
            ),
        )
        .put(
            "generationConfig",
            JSONObject()
                .put("temperature", 0)
                .put("responseMimeType", "application/json")
                .put("responseSchema", responseSchema),
        )
        .toString()
}

internal fun parseCategories(
    body: String,
    items: List<GeminiCategorizationItem>,
): Map<Int, String> {
    val known = items.associateBy { it.ref }
    return runCatching {
        val text =
            JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        val parsedItems = JSONObject(text).getJSONArray("items")
        buildMap {
            for (index in 0 until parsedItems.length()) {
                val entry = parsedItems.getJSONObject(index)
                val ref = entry.getInt("ref")
                val category = entry.getString("category")
                val item = known[ref] ?: continue
                val canonical =
                    item.categories.firstOrNull { it.equals(category, ignoreCase = true) } ?: continue
                if (canonical == "Other") continue
                put(ref, canonical)
            }
        }
    }.getOrDefault(emptyMap())
}

internal class GeminiClient(
    private val openConnection: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) {
    suspend fun categorize(
        apiKey: String,
        items: List<GeminiCategorizationItem>,
    ): Map<Int, String> =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(items).toByteArray(StandardCharsets.UTF_8)
            val connection =
                openConnection(URL(ENDPOINT)).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            try {
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("Gemini request was rejected")
                }
                parseCategories(
                    StrictUtf8Reader.read(connection.inputStream, RESPONSE_BODY_LIMIT, connection.contentLengthLong),
                    items,
                )
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent"
        const val RESPONSE_BODY_LIMIT = 256 * 1024
    }
}
