package com.dwk.flowmoney

import java.util.Locale

data class ParsedTransactionLocation(
    val city: String,
    val state: String,
    val country: String? = null,
)

data class StoredProviderOwnedFields(
    val locationCity: String?,
    val locationState: String?,
    val locationCountry: String?,
    val transactedAtEpochMillis: Long? = null,
)

object TransactionLocation {
    fun parse(description: String?): ParsedTransactionLocation? {
        if (description.isNullOrEmpty()) return null
        val normalized = normalizeWhitespace(description)
        if (normalized.isEmpty()) return null
        if (normalized.length > MAX_DESCRIPTION_CHARS) return null
        if (!isUppercaseDominant(normalized)) return null
        if (isMixedCaseSentence(normalized)) return null

        val tokens = tokenize(normalized)
        if (tokens.size < 3) return null
        val state = tokens.last()
        if (state !in USPS_REGIONS) return null

        val beforeState = tokens.subList(0, tokens.lastIndex)
        for (index in beforeState.indices.reversed()) {
            if (!isLocationBoundary(beforeState[index])) continue
            val cityTokens = beforeState.subList(index + 1, beforeState.size)
            if (cityTokens.isEmpty()) continue
            if (cityTokens.size > MAX_CITY_WORDS) return null
            if (!cityTokens.all(::isAlphabeticCityToken)) continue
            val city = cityTokens.joinToString(" ")
            if (city.length > MAX_CITY_CHARS) return null
            if (city.count { it.isLetter() } < MIN_CITY_LETTERS) continue
            return ParsedTransactionLocation(city = city, state = state, country = null)
        }
        return null
    }

    fun placeKey(
        city: String?,
        state: String?,
        country: String?,
    ): String? {
        val normalizedCity = city.normalizedPlacePart() ?: return null
        val normalizedState = state.normalizedPlacePart().orEmpty()
        val normalizedCountry = country.normalizedPlacePart().orEmpty()
        return "$normalizedCity|$normalizedState|$normalizedCountry"
    }
}

internal fun String?.normalizedPlacePart(): String? {
    if (this == null) return null
    val normalized = normalizeWhitespace(this).lowercase(Locale.US)
    return normalized.takeIf { it.isNotEmpty() }
}

internal fun normalizeWhitespace(value: String): String =
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

private fun tokenize(value: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
        }
    }

    value.forEach { character ->
        when {
            character.isWhitespace() || Character.isSpaceChar(character) -> flush()
            character == ',' -> {
                flush()
                tokens += ","
            }
            else -> current.append(character)
        }
    }
    flush()
    return tokens
}

private fun isUppercaseDominant(value: String): Boolean {
    val letters = value.filter { it.isLetter() }
    if (letters.isEmpty()) return false
    val uppercase = letters.count { it.isUpperCase() }
    return uppercase * 5 >= letters.length * 4
}

private fun isMixedCaseSentence(value: String): Boolean {
    val letters = value.filter { it.isLetter() }
    if (letters.isEmpty()) return false
    val lowercase = letters.count { it.isLowerCase() }
    val uppercase = letters.count { it.isUpperCase() }
    return lowercase > 0 && uppercase > 0 && lowercase * 5 >= letters.length
}

private fun isAlphabeticCityToken(token: String): Boolean {
    if (token.any { it.isDigit() || it == '#' || it == '*' }) return false
    if (!token.any { it.isLetter() }) return false
    return token.all { it.isLetter() || it == '-' || it == '\'' || it == '.' }
}

private fun isLocationBoundary(token: String): Boolean {
    if (token == ",") return true
    if (HASH_DIGITS.containsMatchIn(token)) return true
    if (DIGIT_RUN.containsMatchIn(token)) return true
    return token.contains('*') && STAR_DELIMITED_REFERENCE.containsMatchIn(token)
}

private const val MAX_DESCRIPTION_CHARS = 512
private const val MAX_CITY_CHARS = 40
private const val MAX_CITY_WORDS = 4
private const val MIN_CITY_LETTERS = 2

private val HASH_DIGITS = Regex("""#\d+""")
private val DIGIT_RUN = Regex("""\d{3,}""")
private val STAR_DELIMITED_REFERENCE =
    Regex("""\*[A-Za-z0-9._/-]+|[A-Za-z0-9._/-]+\*""")

private val USPS_REGIONS =
    setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
        "DC", "PR", "VI", "GU", "AS", "MP",
    )
