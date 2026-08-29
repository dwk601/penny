package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Conservative city/state extraction from SimpleFIN descriptors.
 *
 * A false positive writes a wrong place onto a real financial record and later pins a map
 * marker on it, so every accepted shape here is one the parser must keep accepting and every
 * rejected shape is one it must keep refusing.
 */
class TransactionLocationParserTest {
    @Test
    fun acceptsCommaDelimitedDescriptorsIncludingMultiWordCities() {
        assertThat(TransactionLocation.parse("AMAZON MKTPLACE, SEATTLE WA"))
            .isEqualTo(ParsedTransactionLocation(city = "SEATTLE", state = "WA"))
        assertThat(TransactionLocation.parse("AIRBNB, SALT LAKE CITY UT"))
            .isEqualTo(ParsedTransactionLocation(city = "SALT LAKE CITY", state = "UT"))
        assertThat(TransactionLocation.parse("  AMAZON   MKTPLACE ,   SEATTLE   WA  "))
            .isEqualTo(ParsedTransactionLocation(city = "SEATTLE", state = "WA"))
    }

    @Test
    fun acceptsHashDigitStoreNumberAsTheCityBoundary() {
        assertThat(TransactionLocation.parse("TARGET #1234 MINNEAPOLIS MN"))
            .isEqualTo(ParsedTransactionLocation(city = "MINNEAPOLIS", state = "MN"))
        assertThat(TransactionLocation.parse("TRADER JOES #123, PORTLAND OR"))
            .isEqualTo(ParsedTransactionLocation(city = "PORTLAND", state = "OR"))
    }

    @Test
    fun acceptsThreeOrMoreDigitReferenceRunsAsTheCityBoundary() {
        assertThat(TransactionLocation.parse("SHELL OIL 574410 HOUSTON TX"))
            .isEqualTo(ParsedTransactionLocation(city = "HOUSTON", state = "TX"))
        assertThat(TransactionLocation.parse("EXXONMOBIL 123 SAN ANTONIO TX"))
            .isEqualTo(ParsedTransactionLocation(city = "SAN ANTONIO", state = "TX"))
    }

    @Test
    fun acceptsStarDelimitedProviderReferencesAsTheCityBoundary() {
        assertThat(TransactionLocation.parse("PAYPAL *UBER SAN FRANCISCO CA"))
            .isEqualTo(ParsedTransactionLocation(city = "SAN FRANCISCO", state = "CA"))
        assertThat(TransactionLocation.parse("SQ *COFFEE OAKLAND CA"))
            .isEqualTo(ParsedTransactionLocation(city = "OAKLAND", state = "CA"))
    }

    @Test
    fun rejectsDescriptorsWithNoBoundaryBeforeTheCity() {
        // No store number, reference token or comma separates the merchant name from "SEATTLE".
        assertThat(TransactionLocation.parse("STARBUCKS STORE SEATTLE WA")).isNull()
        // "IN" is a USPS region but there is no boundary, so an English preposition is not a state.
        assertThat(TransactionLocation.parse("THE SHOP IN")).isNull()
        // Fewer than three tokens can never carry merchant + boundary + city + state.
        assertThat(TransactionLocation.parse("PERTH WA")).isNull()
        assertThat(TransactionLocation.parse("WA")).isNull()
    }

    @Test
    fun rejectsTrailingTokensThatAreNotUspsRegions() {
        assertThat(TransactionLocation.parse("TARGET #1234 MINNEAPOLIS MINNESOTA")).isNull()
        assertThat(TransactionLocation.parse("TARGET #1234 MINNEAPOLIS ZZ")).isNull()
        assertThat(TransactionLocation.parse("TARGET #1234 MINNEAPOLIS 55401")).isNull()
    }

    @Test
    fun rejectsMixedCaseAndSentenceCaseDescriptors() {
        assertThat(TransactionLocation.parse("Trader Joes #123, Portland OR")).isNull()
        assertThat(TransactionLocation.parse("Payment to Target #1234 Minneapolis MN")).isNull()
        assertThat(TransactionLocation.parse("target #1234 minneapolis mn")).isNull()
        // A handful of lowercase characters inside an otherwise shouting descriptor still parses.
        assertThat(TransactionLocation.parse("TRADER JOEs #123, PORTLAND OR"))
            .isEqualTo(ParsedTransactionLocation(city = "PORTLAND", state = "OR"))
    }

    @Test
    fun rejectsCityTokensCarryingDigitsHashesOrStars() {
        assertThat(TransactionLocation.parse("SHELL 574410 5TH AVE TX")).isNull()
        assertThat(TransactionLocation.parse("SHELL 574410 CITY#2 TX")).isNull()
        assertThat(TransactionLocation.parse("PAYPAL *UBER SAN *FRANCISCO CA")).isNull()
    }

    @Test
    fun rejectsCitiesThatAreTooShortTooLongOrTooManyWords() {
        assertThat(TransactionLocation.parse("SHELL 574410 X TX")).isNull()
        assertThat(TransactionLocation.parse("STORE #1 ${"A".repeat(21)} ${"B".repeat(21)} TX")).isNull()
        assertThat(TransactionLocation.parse("STORE #1 ALPHA BETA GAMMA DELTA EPSILON TX")).isNull()
        assertThat(TransactionLocation.parse("STORE #1 ALPHA BETA GAMMA DELTA TX"))
            .isEqualTo(ParsedTransactionLocation(city = "ALPHA BETA GAMMA DELTA", state = "TX"))
    }

    @Test
    fun rejectsBlankNullAndOversizeDescriptors() {
        assertThat(TransactionLocation.parse(null)).isNull()
        assertThat(TransactionLocation.parse("")).isNull()
        assertThat(TransactionLocation.parse("     ")).isNull()
        assertThat(TransactionLocation.parse("\t\n  \u00A0 ")).isNull()

        val suffix = " #123, PORTLAND OR"
        val atLimit = "X".repeat(MAX_DESCRIPTION_CHARS - suffix.length) + suffix
        val overLimit = "X".repeat(MAX_DESCRIPTION_CHARS - suffix.length + 1) + suffix

        assertThat(atLimit).hasLength(MAX_DESCRIPTION_CHARS)
        assertThat(TransactionLocation.parse(atLimit))
            .isEqualTo(ParsedTransactionLocation(city = "PORTLAND", state = "OR"))
        assertThat(TransactionLocation.parse(overLimit)).isNull()
    }

    @Test
    fun neverInfersACountryEvenWhenTheRegionCodeIsAmbiguousAbroad() {
        val descriptors =
            listOf(
                "AMAZON MKTPLACE, SEATTLE WA",
                "TARGET #1234 MINNEAPOLIS MN",
                "SHELL OIL 574410 HOUSTON TX",
                "PAYPAL *UBER SAN FRANCISCO CA",
                // "WA" is also Western Australia; the parser must not guess a country from it.
                "THE SHOP IN, PERTH WA",
            )

        descriptors.forEach { descriptor ->
            val parsed = TransactionLocation.parse(descriptor)
            assertThat(parsed).isNotNull()
            assertThat(parsed!!.country).isNull()
        }
        assertThat(TransactionLocation.parse("THE SHOP IN, PERTH WA"))
            .isEqualTo(ParsedTransactionLocation(city = "PERTH", state = "WA", country = null))
    }

    @Test
    fun leavesTheDescriptionInputUnchanged() {
        val descriptor = StringBuilder("  TRADER   JOES #123,   PORTLAND OR  ").toString()

        val parsed = TransactionLocation.parse(descriptor)

        assertThat(parsed).isEqualTo(ParsedTransactionLocation(city = "PORTLAND", state = "OR"))
        assertThat(descriptor).isEqualTo("  TRADER   JOES #123,   PORTLAND OR  ")
        assertThat(descriptor).hasLength(37)
    }

    @Test
    fun placeKeyNormalizesCaseAndWhitespaceAndRequiresACity() {
        assertThat(TransactionLocation.placeKey("Portland", "OR", null)).isEqualTo("portland|or|")
        assertThat(TransactionLocation.placeKey("  SALT   LAKE  CITY ", " ut ", " US "))
            .isEqualTo("salt lake city|ut|us")
        assertThat(TransactionLocation.placeKey("Portland", "or", null))
            .isEqualTo(TransactionLocation.placeKey(" PORTLAND ", "OR", ""))
        assertThat(TransactionLocation.placeKey(null, "OR", "US")).isNull()
        assertThat(TransactionLocation.placeKey("", "OR", "US")).isNull()
        assertThat(TransactionLocation.placeKey("   ", "OR", "US")).isNull()
        assertThat(TransactionLocation.placeKey("Portland", null, null))
            .isNotEqualTo(TransactionLocation.placeKey("Portland", "OR", null))
    }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 512
    }
}
