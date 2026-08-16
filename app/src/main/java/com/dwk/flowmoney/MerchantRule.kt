package com.dwk.flowmoney

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val normalizedProviderMerchant: String,
    val category: String,
    val merchantOverride: String?,
) {
    init {
        require(normalizedProviderMerchant.isNotBlank()) { "Merchant rule key must not be blank" }
        require(normalizedProviderMerchant == normalizedProviderMerchantKey(normalizedProviderMerchant)) {
            "Merchant rule key must already be normalized"
        }
        require(category.isNotBlank()) { "Merchant rule category must not be blank" }
    }
}

internal data class TransactionReviewState(
    val id: String,
    val category: String,
    val reviewedAtEpochMillis: Long?,
    val merchantOverride: String?,
)

/** Opaque state needed to undo a bulk review without replacing provider-owned columns. */
class ReviewUndoToken internal constructor(
    internal val transactionStates: List<TransactionReviewState>,
)

/** Opaque state needed to restore both the origin and the exact prior rule. */
class MerchantRuleUndoToken internal constructor(
    internal val transactionState: TransactionReviewState,
    internal val previousRule: MerchantRuleEntity?,
    internal val appliedRule: MerchantRuleEntity,
)

sealed interface MerchantRuleSaveResult {
    data class Applied(
        val rule: MerchantRuleEntity,
        val undoToken: MerchantRuleUndoToken,
    ) : MerchantRuleSaveResult

    data class Conflict(
        val existingRule: MerchantRuleEntity,
        val proposedRule: MerchantRuleEntity,
    ) : MerchantRuleSaveResult
}
