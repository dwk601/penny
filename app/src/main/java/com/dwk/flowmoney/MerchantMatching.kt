package com.dwk.flowmoney

import java.util.Locale

/**
 * Exact rule key for provider-owned merchant text. Only case and whitespace vary for matching;
 * digits, store numbers, and location text are deliberately retained.
 */
fun normalizedProviderMerchantKey(providerMerchant: String): String = collapseMerchantWhitespace(providerMerchant).lowercase(Locale.ROOT)

/**
 * Conservative fallback rule key for provider text that differs only by an opaque reference.
 * Exact normalized matching must always be attempted before this key is used.
 */
fun canonicalProviderMerchantKey(providerMerchant: String): String? {
    var canonical = normalizedProviderMerchantKey(providerMerchant)
    canonical =
        BRACKETED_PROVIDER_REFERENCE.replace(canonical) { match ->
            if (match.groupValues[1].isProviderReferenceToken()) " " else match.value
        }
    canonical =
        TERMINAL_PROVIDER_REFERENCE.replace(canonical) { match ->
            if (match.groupValues[1].isProviderReferenceToken()) " " else match.value
        }
    canonical =
        STAR_DELIMITED_REFERENCE_TOKEN.replace(canonical) { match ->
            if (match.groupValues[1].isStarDelimitedReferenceToken()) " " else match.value
        }
    val normalizedCanonical = normalizedProviderMerchantKey(canonical)
    return normalizedCanonical.takeIf {
        it.count(Char::isLetterOrDigit) >= MIN_CANONICAL_MERCHANT_CHARACTERS
    }
}

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

private fun String.isProviderReferenceToken(): Boolean = count(Char::isLetterOrDigit) >= MIN_PROVIDER_REFERENCE_CHARACTERS

private fun String.isStarDelimitedReferenceToken(): Boolean {
    val alphanumeric = filter(Char::isLetterOrDigit)
    if (alphanumeric.length < MIN_STAR_REFERENCE_CHARACTERS) return false
    val digitCount = alphanumeric.count(Char::isDigit)
    if (digitCount == alphanumeric.length) return true
    val letterCount = alphanumeric.count(Char::isLetter)
    if (digitCount < 2 || letterCount < 2) return false
    val kindTransitions =
        alphanumeric.zipWithNext().count { (first, second) ->
            first.isDigit() != second.isDigit()
        }
    return kindTransitions >= MIN_STAR_REFERENCE_KIND_TRANSITIONS
}

private const val MIN_CANONICAL_MERCHANT_CHARACTERS = 4
private const val MIN_PROVIDER_REFERENCE_CHARACTERS = 4
private const val MIN_STAR_REFERENCE_CHARACTERS = 6
private const val MIN_STAR_REFERENCE_KIND_TRANSITIONS = 2

private val BRACKETED_PROVIDER_REFERENCE =
    Regex(
        pattern =
            """[\[(]\s*(?:provider\s+)?(?:ref(?:erence)?|order)(?:\s+(?:id|no|number))?""" +
                """\s*[:#-]?\s*([a-z0-9][a-z0-9._/-]*)\s*[)\]]""",
        option = RegexOption.IGNORE_CASE,
    )

private val TERMINAL_PROVIDER_REFERENCE =
    Regex(
        pattern =
            """(?:\s+|\s*[|;]\s*)(?:provider\s+)?(?:ref(?:erence)?|order)""" +
                """(?:\s+(?:id|no|number)\s*[:#-]?|\s*[:#])\s*""" +
                """([a-z0-9][a-z0-9._/-]*)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )

private val STAR_DELIMITED_REFERENCE_TOKEN =
    Regex(
        pattern = """\s*\*\s*([a-z0-9][a-z0-9._/-]*)""",
        option = RegexOption.IGNORE_CASE,
    )
