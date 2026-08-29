package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.HttpURLConnection

/**
 * JVM-safe half of the geocoder. `classifyGeocodeOutcome` is the pure decision seam that decides
 * whether a Nominatim answer is cacheable, retryable, or must be discarded; nothing here touches
 * `org.json`, the network, or Android, so it stays a local unit test. Body parsing and caching are
 * covered instrumented in `PlaceGeocoderDataPathTest`.
 */
class PlaceGeocodeOutcomeTest {
    @Test
    fun onlyOkWithCoordinatesResolves() {
        assertThat(classifyGeocodeOutcome(HttpURLConnection.HTTP_OK, GeocodeBodyParse.Coordinates))
            .isEqualTo(PlaceGeocodeClassification.Resolved)
    }

    @Test
    fun emptyBodiesAndAbsentPlacesAreRetryableMisses() {
        assertThat(classifyGeocodeOutcome(HttpURLConnection.HTTP_OK, GeocodeBodyParse.Empty))
            .isEqualTo(PlaceGeocodeClassification.RetryableMiss)
        listOf(HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_NO_CONTENT).forEach { status ->
            GeocodeBodyParse.entries.forEach { body ->
                assertThat(classifyGeocodeOutcome(status, body))
                    .isEqualTo(PlaceGeocodeClassification.RetryableMiss)
            }
        }
    }

    @Test
    fun malformedBodiesAndEveryOtherStatusAreUnavailableAndNeverRetriedAsMisses() {
        assertThat(classifyGeocodeOutcome(HttpURLConnection.HTTP_OK, GeocodeBodyParse.Malformed))
            .isEqualTo(PlaceGeocodeClassification.Unavailable)

        val unavailableStatuses =
            listOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_BAD_REQUEST,
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                429,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                HttpURLConnection.HTTP_UNAVAILABLE,
                HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
                0,
                -1,
            )

        unavailableStatuses.forEach { status ->
            GeocodeBodyParse.entries.forEach { body ->
                assertThat(classifyGeocodeOutcome(status, body))
                    .isEqualTo(PlaceGeocodeClassification.Unavailable)
            }
        }
    }

    @Test
    fun classificationIsTotalOverEveryStatusAndBodyCombination() {
        val statuses = listOf(-1, 0, 200, 204, 301, 400, 404, 429, 500, 503, 599)

        statuses.forEach { status ->
            GeocodeBodyParse.entries.forEach { body ->
                assertThat(PlaceGeocodeClassification.entries)
                    .contains(classifyGeocodeOutcome(status, body))
            }
        }
    }
}
