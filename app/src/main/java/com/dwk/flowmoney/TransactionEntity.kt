package com.dwk.flowmoney

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAtEpochMillis"]),
        Index(value = ["source"]),
        Index(value = ["accountKey"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val occurredAtEpochMillis: Long,
    val merchant: String,
    val category: String,
    val note: String,
    val cents: Int,
    val recurringInterval: String? = null,
    @ColumnInfo(defaultValue = "'local'") val source: String = "local",
    val accountKey: String? = null,
    val accountName: String? = null,
)

fun Transaction.toEntity(): TransactionEntity {
    return TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchant,
        category = category,
        note = note,
        cents = cents,
        recurringInterval = recurringInterval?.name,
        source = source,
        accountKey = accountKey,
        accountName = accountName,
    )
}

fun TransactionEntity.toTransaction(): Transaction {
    return Transaction(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchant,
        category = category,
        note = note,
        cents = cents,
        recurringInterval = recurringInterval?.toRecurrenceIntervalOrNull(),
        source = source,
        accountKey = accountKey,
        accountName = accountName,
    )
}

private fun String.toRecurrenceIntervalOrNull(): RecurrenceInterval? {
    return RecurrenceInterval.entries.firstOrNull { it.name == this }
}
