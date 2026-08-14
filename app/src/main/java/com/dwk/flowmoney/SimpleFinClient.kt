package com.dwk.flowmoney

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class SimpleFinClient {
    suspend fun claim(setupToken: String): String = withContext(Dispatchers.IO) {
        val claimUrl = decodeSetupToken(setupToken)
        val connection = (URL(claimUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        try {
            val code = connection.responseCode
            claimStatusException(code)?.let { throw it }
            if (code in 300..399) {
                throw SimpleFinException("SimpleFIN claim redirected and was not replayed; create a new setup token")
            }
            val body = connection.readBody(code, CLAIM_BODY_LIMIT)
            parseErrors(body)?.let { throw SimpleFinException(it) }
            if (code !in 200..299) throw SimpleFinException("SimpleFIN claim failed ($code)")
            claimedAccessUrl(body)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun accounts(accessUrl: String, startSeconds: Long, endSeconds: Long): SimpleFinAccountsResult = withContext(Dispatchers.IO) {
        validateAccessUrl(accessUrl)
        val uri = URI(accessUrl)
        val auth = "Basic " + Base64.encodeToString(uri.userInfo.orEmpty().toByteArray(), Base64.NO_WRAP)
        val path = (uri.rawPath ?: "").trimEnd('/') + "/accounts"
        val query = "version=2&start-date=$startSeconds&end-date=$endSeconds"
        val accountsUri = URI(uri.scheme, null, uri.host, uri.port, path, query, null)
        var requestUri = accountsUri
        repeat(MAX_ACCOUNT_REDIRECTS + 1) { redirectCount ->
            val connection = (requestUri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", auth)
            }
            try {
                val code = connection.responseCode
                when (code) {
                    402 -> throw SimpleFinQuotaException()
                    403 -> throw SimpleFinReconnectException()
                }
                if (code in 300..399) {
                    if (redirectCount == MAX_ACCOUNT_REDIRECTS) {
                        throw SimpleFinException("SimpleFIN sync redirected too many times")
                    }
                    requestUri = resolveAccountsRedirect(requestUri, connection.getHeaderField("Location"))
                    return@repeat
                }
                val body = connection.readBody(code, ACCOUNTS_BODY_LIMIT)
                if (code !in 200..299) {
                    val message = parseErrors(body) ?: "SimpleFIN sync failed ($code)"
                    if (code >= 500) throw SimpleFinTransientException(message)
                    throw SimpleFinException(message)
                }
                return@withContext parseAccounts(body)
            } finally {
                connection.disconnect()
            }
        }
        throw SimpleFinException("SimpleFIN sync redirected too many times")
    }

    private fun decodeSetupToken(token: String): String {
        val bytes = runCatching { Base64.decode(token, Base64.DEFAULT) }
            .getOrElse { Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP) }
        return String(bytes).also {
            validateHttpsUrl(it)
        }
    }

    private fun HttpURLConnection.readBody(code: Int, maxBytes: Int): String {
        val stream = if (code in 200..299) inputStream else errorStream ?: inputStream
        return try {
            StrictUtf8Reader.read(stream, maxBytes, contentLengthLong)
        } catch (_: InputTooLargeException) {
            throw SimpleFinException("SimpleFIN response body is too large")
        } catch (_: MalformedUtf8Exception) {
            throw SimpleFinException("SimpleFIN response body is not valid UTF-8")
        } catch (_: InputReadException) {
            throw SimpleFinException("SimpleFIN response body could not be read")
        }
    }

    internal fun parseErrors(body: String): String? {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        validateProtocolErrorLimits(json)
        return json.protocolErrors().joinToString("; ").takeIf { it.isNotBlank() }
    }

    internal fun claimedAccessUrl(body: String): String = body.trim().also {
        validateProviderString(it)
        validateAccessUrl(it)
    }

    internal fun parseAccounts(body: String): SimpleFinAccountsResult {
        val json = JSONObject(body)
        validateResponseLimits(json)
        val errors = json.protocolErrors()
        val accounts = json.optJSONArray("accounts").orEmptyObjects().map { account ->
            val accountId = account.requiredId("id")
            SimpleFinAccount(
                providerConnectionId = account.requiredId("conn_id"),
                id = accountId,
                name = account.optString("name", accountId),
                orgName = account.optJSONObject("org")?.optString("name"),
                currency = account.optNullableString("currency"),
                balance = account.optNullableString("balance"),
                availableBalance = account.optNullableString("available-balance"),
                transactions = account.optJSONArray("transactions").orEmptyObjects().map { tx ->
                    SimpleFinTransaction(
                        id = tx.requiredId("id"),
                        posted = tx.optLong("posted", 0L),
                        amount = tx.getString("amount"),
                        description = tx.optString("description", ""),
                        pending = tx.optBoolean("pending", false),
                    )
                },
            )
        }
        return SimpleFinAccountsResult(accounts, errors)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> opt(index).toString() }
    }

    private fun JSONObject.protocolErrors(): List<String> {
        return errorStrings(opt("errlist")) + errorStrings(opt("errors"))
    }

    private fun validateResponseLimits(json: JSONObject) {
        validateProtocolErrorLimits(json)

        val accounts = json.optJSONArray("accounts") ?: return
        if (accounts.length() > MAX_ACCOUNTS) responseLimitExceeded()
        var transactionCount = 0
        for (accountIndex in 0 until accounts.length()) {
            val account = accounts.optJSONObject(accountIndex) ?: continue
            validateProviderString(account.optString("id"))
            validateProviderString(account.optString("conn_id"))
            validateOptionalProviderString(account, "name")
            account.optJSONObject("org")?.let { validateOptionalProviderString(it, "name") }
            validateOptionalProviderString(account, "currency")
            validateOptionalProviderString(account, "balance")
            validateOptionalProviderString(account, "available-balance")
            val transactions = account.optJSONArray("transactions") ?: continue
            transactionCount += transactions.length()
            if (transactionCount > MAX_TRANSACTIONS) responseLimitExceeded()
            for (transactionIndex in 0 until transactions.length()) {
                val transaction = transactions.optJSONObject(transactionIndex) ?: continue
                validateProviderString(transaction.optString("id"))
                validateProviderString(transaction.optString("amount"))
                validateOptionalProviderString(transaction, "description")
            }
        }
    }

    private fun validateProtocolErrorLimits(json: JSONObject) {
        var errorCount = 0
        listOf(json.opt("errlist"), json.opt("errors")).forEach { value ->
            when (value) {
                is JSONArray -> {
                    errorCount += value.length()
                    if (errorCount > MAX_PROTOCOL_ERRORS) responseLimitExceeded()
                    for (index in 0 until value.length()) validateProviderString(value.opt(index).toString())
                }
                null, JSONObject.NULL -> Unit
                else -> {
                    errorCount++
                    if (errorCount > MAX_PROTOCOL_ERRORS) responseLimitExceeded()
                    validateProviderString(value.toString())
                }
            }
        }
    }

    private fun validateOptionalProviderString(json: JSONObject, name: String) {
        if (json.has(name) && !json.isNull(name)) validateProviderString(json.optString(name))
    }

    private fun validateProviderString(value: String) {
        if (value.length > MAX_PROVIDER_STRING_CHARS) responseLimitExceeded()
    }

    private fun responseLimitExceeded(): Nothing =
        throw SimpleFinException("SimpleFIN response exceeds allowed limits")

    private fun errorStrings(value: Any?): List<String> {
        return when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> value.toStringList()
            else -> listOf(value.toString())
        }.filter { it.isNotBlank() }
    }

    private fun JSONArray?.orEmptyObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONObject.optNullableString(name: String): String? = if (!has(name) || isNull(name)) null else optString(name)

    private fun JSONObject.requiredId(name: String): String = optString(name)
        .takeIf { it.isNotBlank() }
        ?: throw SimpleFinException("SimpleFIN response is missing $name")

    companion object {
        private const val MAX_ACCOUNT_REDIRECTS = 5
        private const val CLAIM_BODY_LIMIT = 64 * 1024
        private const val ACCOUNTS_BODY_LIMIT = 8 * 1024 * 1024
        private const val MAX_ACCOUNTS = 1_000
        private const val MAX_TRANSACTIONS = 50_000
        private const val MAX_PROTOCOL_ERRORS = 100
        private const val MAX_PROVIDER_STRING_CHARS = 16_384

        internal fun claimStatusException(statusCode: Int): SimpleFinException? =
            if (statusCode == 403) {
                SimpleFinException(
                    "This one-use SimpleFIN setup token is invalid or has already been used. " +
                        "Do not retry it; create a new setup token. " +
                        "If you did not use it, treat this as a possible compromise.",
                )
            } else {
                null
            }

        fun validateAccessUrl(accessUrl: String) {
            val uri = runCatching { URI(accessUrl) }.getOrElse { throw SimpleFinException("SimpleFIN access URL is invalid") }
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo.isNullOrBlank()) {
                throw SimpleFinException("SimpleFIN access URL is invalid")
            }
        }

        internal fun resolveAccountsRedirect(current: URI, location: String?): URI {
            val next = runCatching { current.resolve(location ?: "") }
                .getOrElse { throw SimpleFinException("SimpleFIN returned an invalid redirect") }
            if (
                location.isNullOrBlank() ||
                next.scheme != "https" ||
                next.host.isNullOrBlank() ||
                next.userInfo != null ||
                !sameOrigin(current, next)
            ) {
                throw SimpleFinException("SimpleFIN refused an unsafe authenticated redirect")
            }
            return next
        }

        private fun sameOrigin(first: URI, second: URI): Boolean =
            first.scheme.equals(second.scheme, ignoreCase = true) &&
                first.host.equals(second.host, ignoreCase = true) &&
                effectivePort(first) == effectivePort(second)

        private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

        private fun validateHttpsUrl(url: String) {
            val uri = runCatching { URI(url) }.getOrElse { throw SimpleFinException("Setup token is not an HTTPS claim URL") }
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                throw SimpleFinException("Setup token is not an HTTPS claim URL")
            }
        }
    }
}
