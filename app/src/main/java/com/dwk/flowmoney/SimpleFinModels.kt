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

sealed class SimpleFinSyncResult {
    data class Success(val inserted: Int, val updated: Int, val skipped: Int) : SimpleFinSyncResult()
    data class Failure(val message: String, val retryable: Boolean = false) : SimpleFinSyncResult()
    object Throttled : SimpleFinSyncResult()
}

class SimpleFinException(message: String) : Exception(message)
class SimpleFinTransientException(message: String) : Exception(message)
class SimpleFinReconnectException : Exception("SimpleFIN reconnect required")
class SimpleFinQuotaException : Exception("SimpleFIN payment or quota issue")
