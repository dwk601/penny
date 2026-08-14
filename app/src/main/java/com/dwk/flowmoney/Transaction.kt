package com.dwk.flowmoney

data class Transaction(
    val id: String,
    val occurredAtEpochMillis: Long,
    val merchant: String,
    val category: String,
    val note: String,
    val cents: Int,
    val recurringInterval: RecurrenceInterval? = null,
    val source: String = "local",
    val accountKey: String? = null,
    val accountName: String? = null,
)

enum class RecurrenceInterval(val label: String) {
    Weekly("Weekly"),
    Monthly("Monthly"),
    Yearly("Yearly"),
}
