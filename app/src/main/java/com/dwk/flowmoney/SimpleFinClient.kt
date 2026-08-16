package com.dwk.flowmoney

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLException

class SimpleFinClient(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection
            ?: throw SimpleFinException("SimpleFIN did not open an HTTP connection")
    },
) {
    suspend fun claim(setupToken: String): String =
        withContext(Dispatchers.IO) {
            protectRequest {
                val claimUrl = decodeSetupToken(setupToken)
                val connection =
                    openConnection(URL(claimUrl)).apply {
                        requestMethod = "POST"
                        instanceFollowRedirects = false
                        connectTimeout = CONNECT_TIMEOUT_MILLIS
                        readTimeout = CONNECT_TIMEOUT_MILLIS
                        doOutput = true
                    }
                try {
                    val code = connection.responseCode
                    claimStatusException(code)?.let { throw it }
                    if (code in 300..399) {
                        throw SimpleFinException("SimpleFIN claim redirect was refused")
                    }
                    if (code !in 200..299) {
                        throw SimpleFinException("SimpleFIN claim was rejected")
                    }
                    claimedAccessUrl(connection.readBody(CLAIM_BODY_LIMIT))
                } finally {
                    connection.disconnect()
                }
            }
        }

    suspend fun accounts(
        accessUrl: String,
        startSeconds: Long,
        endSeconds: Long,
    ): SimpleFinAccountsResult =
        withContext(Dispatchers.IO) {
            protectRequest {
                validateAccessUrl(accessUrl)
                val uri = URI(accessUrl)
                val auth =
                    "Basic " +
                        Base64
                            .getEncoder()
                            .encodeToString(uri.userInfo.orEmpty().toByteArray(StandardCharsets.UTF_8))
                val path = (uri.rawPath ?: "").trimEnd('/') + "/accounts"
                val query = "version=2&start-date=$startSeconds&end-date=$endSeconds"
                val accountsUri = URI(uri.scheme, null, uri.host, uri.port, path, query, null)
                var requestUri = accountsUri
                repeat(MAX_ACCOUNT_REDIRECTS + 1) { redirectCount ->
                    val connection =
                        openConnection(requestUri.toURL()).apply {
                            requestMethod = "GET"
                            instanceFollowRedirects = false
                            connectTimeout = CONNECT_TIMEOUT_MILLIS
                            readTimeout = ACCOUNTS_TIMEOUT_MILLIS
                            setRequestProperty("Authorization", auth)
                        }
                    try {
                        val code = connection.responseCode
                        accountsStatusException(code)?.let { throw it }
                        if (code in 300..399) {
                            if (redirectCount == MAX_ACCOUNT_REDIRECTS) {
                                throw SimpleFinException("SimpleFIN sync redirect limit was exceeded")
                            }
                            requestUri = resolveAccountsRedirect(requestUri, connection.getHeaderField("Location"))
                            return@repeat
                        }
                        if (code !in 200..299) {
                            throw SimpleFinException("SimpleFIN sync request was rejected")
                        }
                        return@withContext parseAccounts(connection.readBody(ACCOUNTS_BODY_LIMIT))
                    } finally {
                        connection.disconnect()
                    }
                }
                throw SimpleFinException("SimpleFIN sync redirect limit was exceeded")
            }
        }

    internal fun decodeSetupToken(token: String): String {
        if (token.length > MAX_SETUP_TOKEN_ENCODED_CHARS) throw SimpleFinMalformedTokenException()
        val encoded = token.trim()
        if (encoded.isEmpty()) throw SimpleFinMalformedTokenException()
        val bytes =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                try {
                    Base64.getUrlDecoder().decode(encoded)
                } catch (urlSafeFailure: IllegalArgumentException) {
                    throw SimpleFinMalformedTokenException(urlSafeFailure)
                }
            }
        val claimUrl =
            try {
                StrictUtf8Reader.read(
                    ByteArrayInputStream(bytes),
                    maxBytes = MAX_SETUP_CLAIM_URL_BYTES,
                    advertisedLength = bytes.size.toLong(),
                )
            } catch (failure: IOException) {
                throw SimpleFinMalformedTokenException(failure)
            }
        validateClaimUrl(claimUrl)
        return claimUrl
    }

    private fun HttpURLConnection.readBody(maxBytes: Int): String =
        try {
            StrictUtf8Reader.read(inputStream, maxBytes, contentLengthLong)
        } catch (failure: InputTooLargeException) {
            throw SimpleFinException("SimpleFIN response exceeds allowed limits", cause = failure)
        } catch (failure: MalformedUtf8Exception) {
            throw SimpleFinException("SimpleFIN response was not valid UTF-8", cause = failure)
        } catch (failure: InputReadException) {
            throw failure.cause ?: failure
        }

    internal fun parseErrors(body: String): String? {
        val json =
            try {
                JSONObject(body)
            } catch (_: Exception) {
                return null
            }
        validateProtocolErrorLimits(json)
        return json.protocolErrors().joinToString("; ").takeIf { it.isNotBlank() }
    }

    internal fun claimedAccessUrl(body: String): String =
        body.trim().also {
            validateProviderString(it)
            validateAccessUrl(it)
        }

    internal fun parseAccounts(body: String): SimpleFinAccountsResult =
        try {
            val json = JSONObject(body)
            validateResponseLimits(json)
            val errors = json.protocolErrors()
            val accounts =
                json.optJSONArray("accounts").orEmptyObjects().map { account ->
                    val accountId = account.requiredId("id")
                    SimpleFinAccount(
                        providerConnectionId = account.requiredId("conn_id"),
                        id = accountId,
                        name = account.optString("name", accountId),
                        orgName = account.optJSONObject("org")?.optString("name"),
                        currency = account.optNullableString("currency"),
                        balance = account.optNullableString("balance"),
                        availableBalance = account.optNullableString("available-balance"),
                        transactions =
                            account.optJSONArray("transactions").orEmptyObjects().map { tx ->
                                SimpleFinTransaction(
                                    id = tx.requiredId("id"),
                                    posted = tx.optLong("posted", 0L),
                                    amount = tx.getString("amount"),
                                    description = tx.optString("description", ""),
                                    pending = tx.optBoolean("pending", false),
                                    payee = validateProviderPayee(tx.optNullableString("payee")),
                                )
                            },
                    )
                }
            SimpleFinAccountsResult(accounts, errors)
        } catch (failure: SimpleFinException) {
            throw failure
        } catch (failure: JSONException) {
            throw SimpleFinException("SimpleFIN response was invalid", cause = failure)
        }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> opt(index).toString() }
    }

    private fun JSONObject.protocolErrors(): List<String> = errorStrings(opt("errlist")) + errorStrings(opt("errors"))

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
                validateProviderPayee(transaction.optNullableString("payee"))
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

                null, JSONObject.NULL -> {
                    Unit
                }

                else -> {
                    errorCount++
                    if (errorCount > MAX_PROTOCOL_ERRORS) responseLimitExceeded()
                    validateProviderString(value.toString())
                }
            }
        }
    }

    private fun validateOptionalProviderString(
        json: JSONObject,
        name: String,
    ) {
        if (json.has(name) && !json.isNull(name)) validateProviderString(json.optString(name))
    }

    internal fun validateProviderPayee(payee: String?): String? {
        payee?.let(::validateProviderString)
        return payee
    }

    private fun validateProviderString(value: String) {
        if (value.length > MAX_PROVIDER_STRING_CHARS) responseLimitExceeded()
    }

    private fun responseLimitExceeded(): Nothing = throw SimpleFinException("SimpleFIN response exceeds allowed limits")

    private fun errorStrings(value: Any?): List<String> =
        when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> value.toStringList()
            else -> listOf(value.toString())
        }.filter { it.isNotBlank() }

    private fun JSONArray?.orEmptyObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONObject.optNullableString(name: String): String? = if (!has(name) || isNull(name)) null else optString(name)

    private fun JSONObject.requiredId(name: String): String =
        optString(name)
            .takeIf { it.isNotBlank() }
            ?: throw SimpleFinException("SimpleFIN response is missing a required identifier")

    private inline fun <T> protectRequest(block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SimpleFinException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            throw SimpleFinException(
                message = "SimpleFIN request timed out",
                kind = SimpleFinFailureKind.TIMEOUT,
                cause = failure,
            )
        } catch (failure: SSLException) {
            throw SimpleFinException(
                message = "SimpleFIN secure connection failed",
                kind = SimpleFinFailureKind.TLS,
                cause = failure,
            )
        } catch (failure: IOException) {
            throw SimpleFinException(
                message = "SimpleFIN network request failed",
                kind = SimpleFinFailureKind.NETWORK,
                cause = failure,
            )
        } catch (failure: Throwable) {
            throw SimpleFinException(
                message = "SimpleFIN request failed",
                kind = SimpleFinFailureKind.UNKNOWN,
                cause = failure,
            )
        }

    companion object {
        private const val MAX_ACCOUNT_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val ACCOUNTS_TIMEOUT_MILLIS = 30_000
        private const val CLAIM_BODY_LIMIT = 64 * 1024
        private const val ACCOUNTS_BODY_LIMIT = 8 * 1024 * 1024
        internal const val MAX_SETUP_CLAIM_URL_BYTES = 16 * 1024
        internal const val MAX_SETUP_TOKEN_ENCODED_CHARS = ((MAX_SETUP_CLAIM_URL_BYTES + 2) / 3) * 4
        private const val MAX_ACCOUNTS = 1_000
        private const val MAX_TRANSACTIONS = 50_000
        private const val MAX_PROTOCOL_ERRORS = 100
        private const val MAX_PROVIDER_STRING_CHARS = 16_384

        internal fun claimStatusException(statusCode: Int): SimpleFinException? =
            when {
                statusCode == 401 || statusCode == 403 -> {
                    SimpleFinAuthenticationException()
                }

                statusCode == 408 -> {
                    SimpleFinException(
                        message = "SimpleFIN claim timed out",
                        kind = SimpleFinFailureKind.TIMEOUT,
                    )
                }

                statusCode == 429 -> {
                    SimpleFinRateLimitException()
                }

                statusCode >= 500 -> {
                    SimpleFinTransientException("SimpleFIN provider was unavailable")
                }

                else -> {
                    null
                }
            }

        private fun accountsStatusException(statusCode: Int): SimpleFinException? =
            when {
                statusCode == 401 || statusCode == 403 -> {
                    SimpleFinReconnectException()
                }

                statusCode == 402 -> {
                    SimpleFinQuotaException()
                }

                statusCode == 408 -> {
                    SimpleFinException(
                        message = "SimpleFIN sync timed out",
                        kind = SimpleFinFailureKind.TIMEOUT,
                    )
                }

                statusCode == 429 -> {
                    SimpleFinRateLimitException()
                }

                statusCode >= 500 -> {
                    SimpleFinTransientException("SimpleFIN provider was unavailable")
                }

                else -> {
                    null
                }
            }

        fun validateAccessUrl(accessUrl: String) {
            val uri =
                runCatching { URI(accessUrl) }
                    .getOrElse { throw SimpleFinException("SimpleFIN access URL is invalid", cause = it) }
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.userInfo.isNullOrBlank() ||
                uri.fragment != null
            ) {
                throw SimpleFinException("SimpleFIN access URL is invalid")
            }
        }

        internal fun resolveAccountsRedirect(
            current: URI,
            location: String?,
        ): URI {
            val next =
                runCatching { current.resolve(location ?: "") }
                    .getOrElse { throw SimpleFinException("SimpleFIN returned an invalid redirect", cause = it) }
            if (
                location.isNullOrBlank() ||
                !next.scheme.equals("https", ignoreCase = true) ||
                next.host.isNullOrBlank() ||
                next.userInfo != null ||
                !sameOrigin(current, next)
            ) {
                throw SimpleFinException("SimpleFIN refused an unsafe authenticated redirect")
            }
            return next
        }

        private fun sameOrigin(
            first: URI,
            second: URI,
        ): Boolean =
            first.scheme.equals(second.scheme, ignoreCase = true) &&
                first.host.equals(second.host, ignoreCase = true) &&
                effectivePort(first) == effectivePort(second)

        private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

        private fun validateClaimUrl(url: String) {
            val uri = runCatching { URI(url) }.getOrElse { throw SimpleFinMalformedTokenException(it) }
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.fragment != null
            ) {
                throw SimpleFinMalformedTokenException()
            }
        }
    }
}
