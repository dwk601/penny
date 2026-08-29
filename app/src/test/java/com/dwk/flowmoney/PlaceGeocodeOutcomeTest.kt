package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure seams around the injectable geocoder lookup. `classifyGeocodeLookup` decides what may be
 * written to the cache, and `locationQuery` decides what the platform geocoder is asked for; both
 * are plain JVM code with no Android or network dependency.
 */
class PlaceGeocodeOutcomeTest {
    @Test
    fun coordinatesResolveAndNoMatchIsARetryableMiss() {
        assertThat(classifyGeocodeLookup(PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784)))
            .isEqualTo(PlaceGeocodeClassification.Resolved)
        assertThat(classifyGeocodeLookup(PlaceGeocodeLookupOutcome.NoMatch))
            .isEqualTo(PlaceGeocodeClassification.RetryableMiss)
    }

    @Test
    fun anUnavailableBackendIsNeverTreatedAsAnAnswer() {
        assertThat(classifyGeocodeLookup(PlaceGeocodeLookupOutcome.Unavailable))
            .isEqualTo(PlaceGeocodeClassification.Unavailable)
    }

    @Test
    fun classificationIsTotalAndCoordinateValuesNeverChangeIt() {
        val outcomes =
            listOf(
                PlaceGeocodeLookupOutcome.Coordinates(0.0, 0.0),
                PlaceGeocodeLookupOutcome.Coordinates(-89.9, 179.9),
                PlaceGeocodeLookupOutcome.Coordinates(Double.NaN, Double.NaN),
                PlaceGeocodeLookupOutcome.NoMatch,
                PlaceGeocodeLookupOutcome.Unavailable,
            )

        outcomes.forEach { outcome ->
            assertThat(PlaceGeocodeClassification.entries).contains(classifyGeocodeLookup(outcome))
        }
        // A degenerate coordinate still classifies as Resolved; PlaceGeocoder is what rejects it.
        assertThat(classifyGeocodeLookup(PlaceGeocodeLookupOutcome.Coordinates(Double.NaN, 1.0)))
            .isEqualTo(PlaceGeocodeClassification.Resolved)
    }

    @Test
    fun theGeocoderQueryJoinsOnlyThePartsThatExist() {
        assertThat(locationQuery("Portland", "OR", "US")).isEqualTo("Portland, OR, US")
        assertThat(locationQuery("Portland", "OR", null)).isEqualTo("Portland, OR")
        assertThat(locationQuery("Portland", null, "US")).isEqualTo("Portland, US")
        assertThat(locationQuery("Portland", null, null)).isEqualTo("Portland")
    }

    @Test
    fun theGeocoderQueryTrimsAndDropsBlankParts() {
        assertThat(locationQuery("  SALT LAKE CITY  ", "  UT ", "   ")).isEqualTo("SALT LAKE CITY, UT")
        assertThat(locationQuery("Portland", "", "")).isEqualTo("Portland")
        assertThat(locationQuery("   ", "OR", "US")).isEqualTo("OR, US")
        assertThat(locationQuery("Sa\u0303o Paulo", "SP", "BR")).isEqualTo("Sa\u0303o Paulo, SP, BR")
    }
}
