package com.dwk.flowmoney

import java.util.Locale

/**
 * Exact rule key for provider-owned merchant text. Only case and whitespace vary for matching;
 * digits, store numbers, and location text are deliberately retained.
 */
fun normalizedProviderMerchantKey(providerMerchant: String): String = collapseMerchantWhitespace(providerMerchant).lowercase(Locale.ROOT)

/** Cosmetic display cleanup is intentionally separate from rule-key generation. */
fun cosmeticMerchantDisplay(providerMerchant: String): String = collapseMerchantWhitespace(providerMerchant)

private fun collapseMerchantWhitespace(value: String): String =
    buildString(value.length) {
        var pendingSpace = false
        value.forEach { character ->
            if (character.isWhitespace() || Character.isSpaceChar(character)) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
