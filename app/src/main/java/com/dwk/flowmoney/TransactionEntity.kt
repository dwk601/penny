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
        Index(value = ["source", "reviewedAtEpochMillis", "occurredAtEpochMillis"]),
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
    val reviewedAtEpochMillis: Long? = null,
    val providerDescription: String? = null,
    val merchantOverride: String? = null,
) {
    val isUnreviewed: Boolean
        get() = source == "simplefin" && reviewedAtEpochMillis == null
}

fun Transaction.toEntity(): TransactionEntity =
    TransactionEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = providerMerchant ?: merchant,
        category = category,
        note = note,
        cents = cents,
        recurringInterval = recurringInterval?.name,
        source = source,
        accountKey = accountKey,
        accountName = accountName,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
        providerDescription = providerDescription,
        merchantOverride = merchantOverride,
    )

fun TransactionEntity.toTransaction(): Transaction =
    Transaction(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        merchant = merchantOverride ?: merchant,
        category = category,
        note = note,
        cents = cents,
        recurringInterval = recurringInterval?.toRecurrenceIntervalOrNull(),
        source = source,
        accountKey = accountKey,
        accountName = accountName,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
        providerDescription = providerDescription,
        merchantOverride = merchantOverride,
        providerMerchant = merchant.takeIf { source == "simplefin" },
    )

private fun String.toRecurrenceIntervalOrNull(): RecurrenceInterval? = RecurrenceInterval.entries.firstOrNull { it.name == this }
