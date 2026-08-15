package com.dwk.flowmoney

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

@JvmInline
internal value class SimpleFinServerOrigin private constructor(
    val value: String,
) {
    companion object {
        fun fromAccessUrl(accessUrl: String): SimpleFinServerOrigin {
            SimpleFinClient.validateAccessUrl(accessUrl)
            val uri = URI(accessUrl)
            val host =
                requireNotNull(uri.host)
                    .removePrefix("[")
                    .removeSuffix("]")
                    .trimEnd('.')
                    .lowercase(Locale.ROOT)
            require(host.isNotBlank()) { "SimpleFIN access URL has no server host" }
            val authorityHost = if (':' in host) "[$host]" else host
            val port = uri.port.takeUnless { it == -1 || it == DEFAULT_HTTPS_PORT }
            return SimpleFinServerOrigin(
                buildString {
                    append("https://")
                    append(authorityHost)
                    if (port != null) append(":$port")
                },
            )
        }

        private const val DEFAULT_HTTPS_PORT = 443
    }
}

internal data class SimpleFinRemoteAccountIdentity(
    val providerConnectionId: String,
    val accountId: String,
)

internal data class SimpleFinRemoteTransactionIdentity(
    val providerConnectionId: String,
    val accountId: String,
    val transactionId: String,
) {
    val accountIdentity = SimpleFinRemoteAccountIdentity(providerConnectionId, accountId)
}

internal object SimpleFinIdentity {
    fun accountId(
        origin: SimpleFinServerOrigin,
        providerConnectionId: String,
        accountId: String,
    ): String =
        listOf(
            PREFIX,
            STABLE_VERSION,
            encode(origin.value),
            encode(providerConnectionId),
            encode(accountId),
        ).joinToString(":")

    fun transactionId(
        origin: SimpleFinServerOrigin,
        providerConnectionId: String,
        accountId: String,
        transactionId: String,
    ): String = accountId(origin, providerConnectionId, accountId) + ":" + encode(transactionId)

    fun parseAccountId(
        id: String,
        origin: SimpleFinServerOrigin,
    ): SimpleFinRemoteAccountIdentity? {
        val parts = id.split(':')
        return when {
            parts.size == LEGACY_ACCOUNT_PARTS && parts[0] == PREFIX && isLocalConnectionId(parts[1]) -> {
                val providerConnectionId = decode(parts[2]) ?: return null
                val accountId = decode(parts[3]) ?: return null
                SimpleFinRemoteAccountIdentity(providerConnectionId, accountId)
            }

            parts.size == STABLE_ACCOUNT_PARTS &&
                parts[0] == PREFIX &&
                parts[1] == STABLE_VERSION &&
                decode(parts[2]) == origin.value -> {
                val providerConnectionId = decode(parts[3]) ?: return null
                val accountId = decode(parts[4]) ?: return null
                SimpleFinRemoteAccountIdentity(providerConnectionId, accountId)
            }

            else -> {
                null
            }
        }
    }

    fun parseTransactionId(
        id: String,
        origin: SimpleFinServerOrigin,
    ): SimpleFinRemoteTransactionIdentity? {
        val parts = id.split(':')
        return when {
            parts.size == LEGACY_TRANSACTION_PARTS && parts[0] == PREFIX && isLocalConnectionId(parts[1]) -> {
                val providerConnectionId = decode(parts[2]) ?: return null
                val accountId = decode(parts[3]) ?: return null
                val transactionId = decode(parts[4]) ?: return null
                SimpleFinRemoteTransactionIdentity(providerConnectionId, accountId, transactionId)
            }

            parts.size == STABLE_TRANSACTION_PARTS &&
                parts[0] == PREFIX &&
                parts[1] == STABLE_VERSION &&
                decode(parts[2]) == origin.value -> {
                val providerConnectionId = decode(parts[3]) ?: return null
                val accountId = decode(parts[4]) ?: return null
                val transactionId = decode(parts[5]) ?: return null
                SimpleFinRemoteTransactionIdentity(providerConnectionId, accountId, transactionId)
            }

            else -> {
                null
            }
        }
    }

    private fun isLocalConnectionId(value: String): Boolean {
        val uuid = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
        return uuid.toString().equals(value, ignoreCase = true)
    }

    private fun encode(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
        val decoded =
            runCatching {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull() ?: return null
        return decoded.takeIf { it.isNotEmpty() && encode(it) == value }
    }

    private const val PREFIX = "simplefin"
    private const val STABLE_VERSION = "v2"
    private const val LEGACY_ACCOUNT_PARTS = 4
    private const val LEGACY_TRANSACTION_PARTS = 5
    private const val STABLE_ACCOUNT_PARTS = 5
    private const val STABLE_TRANSACTION_PARTS = 6
}
