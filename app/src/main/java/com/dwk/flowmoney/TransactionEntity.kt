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
    @ColumnInfo(defaultValue = "'NORMAL'") val flowKind: FlowKind = FlowKind.NORMAL,
    val flowKindOverride: FlowKind? = null,
    val locationCity: String? = null,
    val locationState: String? = null,
    val locationCountry: String? = null,
) {
    val isUnreviewed: Boolean
        get() = source == "simplefin" && reviewedAtEpochMillis == null

    val effectiveFlowKind: FlowKind
        get() = flowKindOverride ?: flowKind
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
        flowKind = flowKind,
        flowKindOverride = flowKindOverride,
        locationCity = locationCity,
        locationState = locationState,
        locationCountry = locationCountry,
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
        flowKind = flowKind,
        flowKindOverride = flowKindOverride,
        locationCity = locationCity,
        locationState = locationState,
        locationCountry = locationCountry,
    )

private fun String.toRecurrenceIntervalOrNull(): RecurrenceInterval? = RecurrenceInterval.entries.firstOrNull { it.name == this }
