package com.dwk.flowmoney

data class SimpleFinAccount(
    val providerConnectionId: String,
    val id: String,
    val name: String,
    val orgName: String?,
    val currency: String?,
    val balance: String?,
    val availableBalance: String?,
    val transactions: List<SimpleFinTransaction>,
)

data class SimpleFinTransaction(
    val id: String,
    val posted: Long,
    val amount: String,
    val description: String,
    val pending: Boolean,
)

data class SimpleFinAccountsResult(
    val accounts: List<SimpleFinAccount>,
    val errors: List<String> = emptyList(),
)

data class SimpleFinMappingResult(
    val transactions: List<TransactionEntity>,
    val accounts: List<SimpleFinAccountEntity>,
    val warnings: List<String> = emptyList(),
)

enum class SimpleFinFailureKind(
    val retryableByWorker: Boolean,
) {
    MALFORMED_TOKEN(false),
    AUTHENTICATION(false),
    TIMEOUT(true),
    NETWORK(true),
    TLS(false),
    RATE_LIMIT(false),
    PROVIDER_5XX(true),
    PROTOCOL(false),
    UNKNOWN(false),
}

enum class SimpleFinPendingConnectionState {
    UNKNOWN,
    NONE,
    RETRY_AVAILABLE,
}

sealed class SimpleFinSyncResult {
    data class Success(
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
    ) : SimpleFinSyncResult()

    data class Failure(
        val message: String,
        val retryable: Boolean = false,
        val kind: SimpleFinFailureKind = SimpleFinFailureKind.UNKNOWN,
    ) : SimpleFinSyncResult()

    object Throttled : SimpleFinSyncResult()
}

open class SimpleFinException(
    message: String,
    val kind: SimpleFinFailureKind = SimpleFinFailureKind.PROTOCOL,
    val retryable: Boolean = kind.retryableByWorker,
    cause: Throwable? = null,
) : Exception(message, cause)

class SimpleFinTransientException(
    message: String,
    cause: Throwable? = null,
) : SimpleFinException(
        message = message,
        kind = SimpleFinFailureKind.PROVIDER_5XX,
        cause = cause,
    )

class SimpleFinReconnectException(
    cause: Throwable? = null,
) : SimpleFinException(
        message = "SimpleFIN reconnect required",
        kind = SimpleFinFailureKind.AUTHENTICATION,
        cause = cause,
    )

class SimpleFinQuotaException :
    SimpleFinException(
        message = "SimpleFIN payment or quota issue",
        kind = SimpleFinFailureKind.RATE_LIMIT,
        retryable = false,
    )

internal class SimpleFinRateLimitException :
    SimpleFinException(
        message = "SimpleFIN request was rate limited",
        kind = SimpleFinFailureKind.RATE_LIMIT,
    )

internal class SimpleFinMalformedTokenException(
    cause: Throwable? = null,
) : SimpleFinException(
        message = "SimpleFIN setup token is malformed",
        kind = SimpleFinFailureKind.MALFORMED_TOKEN,
        cause = cause,
    )

internal class SimpleFinAuthenticationException :
    SimpleFinException(
        message = "SimpleFIN rejected the credentials",
        kind = SimpleFinFailureKind.AUTHENTICATION,
    )
