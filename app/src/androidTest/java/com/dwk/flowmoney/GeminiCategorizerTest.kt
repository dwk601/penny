package com.dwk.flowmoney

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class GeminiCategorizerTest {
    @Test
    fun signedDollarsFormatsSignSubUnitsAndZero() {
        assertEquals("-31.11", signedDollars(-3111))
        assertEquals("-0.05", signedDollars(-5))
        assertEquals("0.00", signedDollars(0))
        assertEquals("12.00", signedDollars(1200))
        assertEquals("21474836.47", signedDollars(Int.MAX_VALUE))
        assertEquals("-21474836.48", signedDollars(Int.MIN_VALUE))
    }

    @Test
    fun requestBodyCarriesTheStatementContextWithoutIdsAndWithBoundedFields() {
        val row =
            TransactionEntity(
                id = "simplefin:bridge.simplefin.org:account:tx",
                occurredAtEpochMillis = 1_766_145_600_000L,
                merchant = "T".repeat(400),
                category = "Other",
                note = "",
                cents = -3111,
                source = "simplefin",
                accountKey = "account",
                accountName = "A".repeat(400),
                providerDescription = "D".repeat(400),
                locationCity = "C".repeat(400),
                locationState = "S".repeat(400),
            )
        val items = listOf(itemFor(ref = 1, row = row))

        val body = buildRequestBody(items)
        val sent = promptItems(body).getJSONObject(0)

        assertEquals(1, sent.getInt("ref"))
        assertEquals("-31.11", sent.getString("amountDollars"))
        assertEquals("D".repeat(MAX_FIELD_CHARS), sent.getString("description"))
        assertEquals("C".repeat(MAX_FIELD_CHARS), sent.getString("city"))
        assertEquals("S".repeat(MAX_FIELD_CHARS), sent.getString("state"))
        assertEquals("A".repeat(MAX_FIELD_CHARS), sent.getString("accountName"))
        assertEquals("T".repeat(MAX_FIELD_CHARS), sent.getString("merchant"))
        listOf("merchant", "description", "accountName", "city", "state").forEach { field ->
            assertTrue(field, sent.getString(field).length <= MAX_FIELD_CHARS)
        }
        assertEquals(
            CategoryCatalog.expenseCategories,
            (0 until sent.getJSONArray("categories").length()).map { sent.getJSONArray("categories").getString(it) },
        )
        // The transaction identity never leaves the device.
        assertFalse(body.contains("simplefin:"))
        assertFalse(body.contains("bridge.simplefin.org"))
        assertFalse(body.contains(row.id))
    }

    @Test
    fun requestBodyOmitsBlankProviderFieldsAndKeepsIncomeCategoriesForCredits() {
        val row =
            TransactionEntity(
                id = "simplefin:account:credit",
                occurredAtEpochMillis = 1,
                merchant = "  ACME PAYROLL  ",
                category = "Other",
                note = "",
                cents = 250_000,
                source = "simplefin",
                accountName = "   ",
                providerDescription = null,
            )

        val sent = promptItems(buildRequestBody(listOf(itemFor(ref = 7, row = row)))).getJSONObject(0)

        assertEquals(7, sent.getInt("ref"))
        assertEquals("ACME PAYROLL", sent.getString("merchant"))
        assertEquals("2500.00", sent.getString("amountDollars"))
        assertTrue(sent.isNull("description"))
        assertTrue(sent.isNull("accountName"))
        assertTrue(sent.isNull("city"))
        assertTrue(sent.isNull("state"))
        assertEquals(
            CategoryCatalog.incomeCategories,
            (0 until sent.getJSONArray("categories").length()).map { sent.getJSONArray("categories").getString(it) },
        )
    }

    @Test
    fun parseCategoriesKeepsCanonicalLabelsAndRejectsEverythingElse() {
        val expense = item(ref = 1, isExpense = true)
        val income = item(ref = 2, isExpense = false)
        val items = listOf(expense, income)

        val parsed =
            parseCategories(
                response(
                    """
                    {"items":[
                      {"ref":1,"category":"groceries"},
                      {"ref":2,"category":"Salary"}
                    ]}
                    """.trimIndent(),
                ),
                items,
            )

        assertEquals(mapOf(1 to "Groceries", 2 to "Salary"), parsed)
    }

    @Test
    fun parseCategoriesDropsInventedLabelsCrossFlowLabelsUnknownRefsAndOther() {
        val items = listOf(item(ref = 1, isExpense = true), item(ref = 2, isExpense = true))

        val parsed =
            parseCategories(
                response(
                    """
                    {"items":[
                      {"ref":1,"category":"Spaceships"},
                      {"ref":2,"category":"Salary"},
                      {"ref":3,"category":"Food"},
                      {"ref":1,"category":"Other"}
                    ]}
                    """.trimIndent(),
                ),
                items,
            )

        // An invented label, an income label on an expense row, an out-of-range ref, and "Other" are all dropped.
        assertEquals(emptyMap<Int, String>(), parsed)
    }

    @Test
    fun parseCategoriesReturnsEmptyForMalformedOrIncompleteResponses() {
        val items = listOf(item(ref = 1, isExpense = true))

        assertEquals(emptyMap<Int, String>(), parseCategories("not json at all", items))
        assertEquals(emptyMap<Int, String>(), parseCategories("", items))
        assertEquals(emptyMap<Int, String>(), parseCategories("""{"promptFeedback":{"blockReason":"SAFETY"}}""", items))
        assertEquals(emptyMap<Int, String>(), parseCategories("""{"candidates":[]}""", items))
        assertEquals(emptyMap<Int, String>(), parseCategories(response("{\"items\":\"nope\"}"), items))
        assertEquals(emptyMap<Int, String>(), parseCategories(response("still not json"), items))
    }

    @Test
    fun clientPostsTheKeyInAHeaderAndTheExactRequestBody() {
        val items = listOf(item(ref = 1, isExpense = true))
        var connection: FakeConnection? = null
        val client =
            GeminiClient { url ->
                FakeConnection(url, status = 200, body = response("""{"items":[{"ref":1,"category":"Groceries"}]}"""))
                    .also { connection = it }
            }

        val result = runBlocking { client.categorize(API_KEY, items) }

        val captured = checkNotNull(connection)
        assertEquals(mapOf(1 to "Groceries"), result)
        assertEquals("POST", captured.requestMethod)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            captured.url.toString(),
        )
        assertNull(captured.url.query)
        assertFalse(captured.url.toString().contains(API_KEY))
        assertFalse(captured.url.toString().contains("key="))
        assertEquals(API_KEY, captured.headers["x-goog-api-key"])
        assertEquals("application/json; charset=utf-8", captured.headers["Content-Type"])
        assertFalse(captured.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals(buildRequestBody(items), captured.sentBody())
        assertTrue(captured.disconnected)
    }

    @Test
    fun clientThrowsOnRateLimitWithoutReadingOrReturningAPartialMap() {
        val items = listOf(item(ref = 1, isExpense = true))
        var connection: FakeConnection? = null
        val client =
            GeminiClient { url ->
                FakeConnection(url, status = 429, body = response("""{"items":[{"ref":1,"category":"Groceries"}]}"""))
                    .also { connection = it }
            }

        val failure =
            assertThrows(IOException::class.java) {
                runBlocking { client.categorize(API_KEY, items) }
            }

        val captured = checkNotNull(connection)
        assertNotNull(failure.message)
        assertFalse(checkNotNull(failure.message).contains(API_KEY))
        assertFalse(captured.bodyWasRead)
        assertTrue(captured.disconnected)
    }

    private fun itemFor(
        ref: Int,
        row: TransactionEntity,
    ) = GeminiCategorizationItem(
        ref = ref,
        merchant = geminiField(row.merchant).orEmpty(),
        description = geminiField(row.providerDescription),
        amountDollars = signedDollars(row.cents),
        accountName = geminiField(row.accountName),
        city = geminiField(row.locationCity),
        state = geminiField(row.locationState),
        categories = CategoryCatalog.categoriesFor(row.cents < 0),
    )

    private fun item(
        ref: Int,
        isExpense: Boolean,
    ) = GeminiCategorizationItem(
        ref = ref,
        merchant = "TRADER JOES #123",
        description = "TRADER JOES #123 PORTLAND OR",
        amountDollars = if (isExpense) "-31.11" else "31.11",
        accountName = "Checking",
        city = "PORTLAND",
        state = "OR",
        categories = CategoryCatalog.categoriesFor(isExpense),
    )

    private fun promptItems(body: String) =
        JSONObject(body)
            .getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .let { text -> JSONObject(text.substring(text.indexOf('{'))) }
            .getJSONArray("items")

    private fun response(text: String) =
        JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", text)),
                        ),
                    ),
                ),
            ).toString()

    private class FakeConnection(
        url: URL,
        private val status: Int,
        body: String,
    ) : HttpURLConnection(url) {
        private val responseBytes = body.toByteArray(StandardCharsets.UTF_8)
        private val output = ByteArrayOutputStream()
        val headers = linkedMapOf<String, String>()
        var bodyWasRead = false
        var disconnected = false

        fun sentBody(): String = output.toString(StandardCharsets.UTF_8.name())

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            headers[key] = value
        }

        override fun getOutputStream(): OutputStream = output

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream {
            bodyWasRead = true
            return ByteArrayInputStream(responseBytes)
        }

        override fun getErrorStream(): InputStream {
            bodyWasRead = true
            return ByteArrayInputStream(responseBytes)
        }

        override fun getContentLengthLong(): Long = responseBytes.size.toLong()
    }

    private companion object {
        const val API_KEY = "test-gemini-api-key-fixture"
    }
}
