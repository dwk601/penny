package com.dwk.flowmoney

data class Transaction(
    val id: String,
    val occurredAtEpochMillis: Long,
    /** Effective display merchant. For SimpleFIN rows, [providerMerchant] retains the raw owner. */
    val merchant: String,
    val category: String,
    val note: String,
    val cents: Int,
    val recurringInterval: RecurrenceInterval? = null,
    val source: String = "local",
    val accountKey: String? = null,
    val accountName: String? = null,
    val reviewedAtEpochMillis: Long? = null,
    val providerDescription: String? = null,
    val merchantOverride: String? = null,
    val providerMerchant: String? = null,
    val flowKind: FlowKind = FlowKind.NORMAL,
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

enum class RecurrenceInterval(
    val label: String,
) {
    Weekly("Weekly"),
    Monthly("Monthly"),
    Yearly("Yearly"),
}
