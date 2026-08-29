package com.dwk.flowmoney

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Caching policy around the injectable [PlaceGeocodeLookup] seam.
 *
 * The rule the cache has to hold is: only answers the platform actually gave us are persisted. A
 * missing or broken geocoder backend must stay invisible to the cache, otherwise a temporary
 * outage would permanently blank out a user's map.
 */
class PlaceGeocoderTest {
    @Test
    fun theLookupRunsOnTheInjectedIoDispatcherAndNotTheCallersThread() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "geocoder-io-test") }
        try {
            runBlocking {
                val dao = FakeDao()
                val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784) }
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = lookup,
                        now = { NOW },
                        ioDispatcher = executor.asCoroutineDispatcher(),
                    )

                val result = geocoder.resolve("Portland", "OR", "US")

                assertThat(result).isEqualTo(PlaceGeocodeResult.Resolved(45.5152, -122.6784))
                assertThat(lookup.threads.map { it.name }).containsExactly("geocoder-io-test")
                assertThat(lookup.threads.single()).isNotSameInstanceAs(Thread.currentThread())
                assertThat(lookup.queries).containsExactly("Portland, OR, US")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun withNoLookupConfiguredTheGeocoderIsUnavailableAndWritesNothing() =
        runBlocking {
            val dao = FakeDao()

            val geocoder = PlaceGeocoder(dao = dao, now = { NOW }, ioDispatcher = Dispatchers.Unconfined)

            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)
            assertThat(dao.rows).isEmpty()
            assertThat(dao.upserts).isEqualTo(0)
        }

    @Test
    fun anUnavailableBackendCachesNothingAndStaysRetryableForever() =
        runBlocking {
            val dao = FakeDao()
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Unavailable }
            val geocoder = geocoder(dao, lookup)

            repeat(3) {
                assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)
            }

            assertThat(dao.rows).isEmpty()
            assertThat(dao.upserts).isEqualTo(0)
            // Nothing is cached, so every call still reaches the backend.
            assertThat(lookup.queries).hasSize(3)
        }

    @Test
    fun ioFailuresAndUnexpectedBackendErrorsDegradeToUnavailableWithoutCaching() =
        runBlocking {
            listOf(
                IOException("geocoder backend offline"),
                IllegalStateException("service died"),
                RuntimeException("binder transaction failed"),
            ).forEach { thrown ->
                val dao = FakeDao()
                val geocoder = geocoder(dao, RecordingLookup { throw thrown })

                assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)
                assertThat(dao.rows).isEmpty()
                assertThat(dao.upserts).isEqualTo(0)
            }
        }

    @Test
    fun cancellationFromTheLookupPropagatesAndCachesNothing() =
        runBlocking {
            val dao = FakeDao()
            val geocoder = geocoder(dao, RecordingLookup { throw CancellationException("stopped") })

            val failure = runCatching { geocoder.resolve("Portland", "OR", "US") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(dao.rows).isEmpty()
            assertThat(dao.upserts).isEqualTo(0)
        }

    @Test
    fun cancellationAfterASuccessfulLookupStillCachesNothing() =
        runBlocking {
            val dao = FakeDao()
            var job: Job? = null
            val lookup =
                RecordingLookup {
                    job?.cancel()
                    PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784)
                }
            val geocoder = geocoder(dao, lookup)

            job = launch(start = CoroutineStart.LAZY) { geocoder.resolve("Portland", "OR", "US") }
            job.start()
            job.join()

            assertThat(job.isCancelled).isTrue()
            assertThat(lookup.queries).hasSize(1)
            assertThat(dao.rows).isEmpty()
            assertThat(dao.upserts).isEqualTo(0)
        }

    @Test
    fun zeroResultsCacheARetryableMissThatIsServedUntilTheTtlExpires() =
        runBlocking {
            val dao = FakeDao()
            var now = 1_000_000L
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.NoMatch }
            val geocoder =
                PlaceGeocoder(
                    dao = dao,
                    lookup = lookup,
                    now = { now },
                    ioDispatcher = Dispatchers.Unconfined,
                    missTtlMillis = 1_000L,
                )

            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            val cached = dao.rows.getValue("nowhere|zz|")
            assertThat(cached.latitude).isNull()
            assertThat(cached.longitude).isNull()
            assertThat(cached.city).isEqualTo("Nowhere")
            assertThat(cached.state).isEqualTo("ZZ")
            assertThat(cached.resolvedAtEpochMillis).isEqualTo(1_000_000L)

            now += 999L
            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            assertThat(lookup.queries).hasSize(1)

            now += 2L
            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            assertThat(lookup.queries).hasSize(2)
            assertThat(dao.rows.getValue("nowhere|zz|").resolvedAtEpochMillis).isEqualTo(1_001_001L)
        }

    @Test
    fun theDefaultMissTtlIsOneWeek() =
        runBlocking {
            val dao = FakeDao()
            var now = 1_000_000_000L
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.NoMatch }
            val geocoder = PlaceGeocoder(dao = dao, lookup = lookup, now = { now }, ioDispatcher = Dispatchers.Unconfined)
            val week = 7L * 24 * 60 * 60 * 1000

            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)

            now += week - 1
            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            assertThat(lookup.queries).hasSize(1)

            now += 2
            assertThat(geocoder.resolve("Nowhere", "ZZ", null)).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            assertThat(lookup.queries).hasSize(2)
        }

    @Test
    fun resolvedCoordinatesAreCachedIndefinitelyAndSurviveTheMissTtl() =
        runBlocking {
            val dao = FakeDao()
            var now = 1_000L
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784) }
            val geocoder =
                PlaceGeocoder(
                    dao = dao,
                    lookup = lookup,
                    now = { now },
                    ioDispatcher = Dispatchers.Unconfined,
                    missTtlMillis = 1L,
                )
            val resolved = PlaceGeocodeResult.Resolved(45.5152, -122.6784)

            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(resolved)
            assertThat(dao.rows.getValue("portland|or|us").latitude).isEqualTo(45.5152)

            now += 10L * 365 * 24 * 60 * 60 * 1000
            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(resolved)
            assertThat(geocoder.resolve("  portland ", "or", " US ")).isEqualTo(resolved)
            assertThat(lookup.queries).hasSize(1)

            // A fresh session still reads the same durable row without another lookup.
            val secondLookup = RecordingLookup { PlaceGeocodeLookupOutcome.Unavailable }
            assertThat(geocoder(dao, secondLookup).resolve("Portland", "OR", "US")).isEqualTo(resolved)
            assertThat(secondLookup.queries).isEmpty()
        }

    @Test
    fun theSessionBudgetCapsLookupsWhileTheCacheKeepsServing() =
        runBlocking {
            val dao = FakeDao()
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Coordinates(1.0, 2.0) }
            val geocoder =
                PlaceGeocoder(
                    dao = dao,
                    lookup = lookup,
                    now = { NOW },
                    ioDispatcher = Dispatchers.Unconfined,
                    maxLookupsPerSession = 2,
                )

            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Resolved(1.0, 2.0))
            assertThat(geocoder.resolve("Seattle", "WA", "US")).isEqualTo(PlaceGeocodeResult.Resolved(1.0, 2.0))
            assertThat(geocoder.resolve("Boston", "MA", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)
            assertThat(geocoder.resolve("Austin", "TX", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)

            assertThat(lookup.queries).containsExactly("Portland, OR, US", "Seattle, WA, US").inOrder()
            assertThat(dao.rows.keys).containsExactly("portland|or|us", "seattle|wa|us")
            // Cache hits are free and never consume budget.
            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Resolved(1.0, 2.0))
            assertThat(lookup.queries).hasSize(2)
            // The budget is per instance, so a new session may look up again.
            assertThat(geocoder(dao, lookup).resolve("Boston", "MA", "US")).isEqualTo(PlaceGeocodeResult.Resolved(1.0, 2.0))
        }

    @Test
    fun failedLookupsStillConsumeTheSessionBudgetSoAnOutageCannotSpin() =
        runBlocking {
            val dao = FakeDao()
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Unavailable }
            val geocoder =
                PlaceGeocoder(
                    dao = dao,
                    lookup = lookup,
                    now = { NOW },
                    ioDispatcher = Dispatchers.Unconfined,
                    maxLookupsPerSession = 2,
                )

            repeat(5) { index -> geocoder.resolve("City$index", "ZZ", null) }

            assertThat(lookup.queries).containsExactly("City0, ZZ", "City1, ZZ").inOrder()
            assertThat(dao.rows).isEmpty()
        }

    @Test
    fun anUnusablePlaceIsRejectedBeforeTheBackendIsAsked() =
        runBlocking {
            val dao = FakeDao()
            val lookup = RecordingLookup { PlaceGeocodeLookupOutcome.Coordinates(1.0, 2.0) }
            val geocoder = geocoder(dao, lookup)

            listOf("", "   ", "\u00A0").forEach { city ->
                assertThat(geocoder.resolve(city, "OR", "US")).isEqualTo(PlaceGeocodeResult.Unavailable)
            }

            assertThat(lookup.queries).isEmpty()
            assertThat(dao.rows).isEmpty()
        }

    @Test
    fun aNonFiniteCoordinateIsNeverServedFromANewLookupOrFromTheCache() =
        runBlocking {
            val dao = FakeDao()
            val geocoder = geocoder(dao, RecordingLookup { PlaceGeocodeLookupOutcome.Coordinates(Double.NaN, 1.0) })

            assertThat(geocoder.resolve("Portland", "OR", "US")).isEqualTo(PlaceGeocodeResult.Resolved(Double.NaN, 1.0))

            // Whatever was written must not later be replayed as a usable coordinate.
            val poisoned = FakeDao()
            poisoned.rows["portland|or|us"] =
                PlaceGeocodeEntity(
                    placeKey = "portland|or|us",
                    city = "Portland",
                    state = "OR",
                    country = "US",
                    latitude = Double.NaN,
                    longitude = Double.POSITIVE_INFINITY,
                    resolvedAtEpochMillis = NOW,
                )
            val replayLookup = RecordingLookup { PlaceGeocodeLookupOutcome.NoMatch }
            assertThat(
                PlaceGeocoder(
                    dao = poisoned,
                    lookup = replayLookup,
                    now = { NOW + 1 },
                    ioDispatcher = Dispatchers.Unconfined,
                    missTtlMillis = 1L,
                ).resolve("Portland", "OR", "US"),
            ).isEqualTo(PlaceGeocodeResult.RetryableMiss)
            assertThat(replayLookup.queries).hasSize(1)
        }

    private fun geocoder(
        dao: PlaceGeocodeDao,
        lookup: PlaceGeocodeLookup,
    ) = PlaceGeocoder(dao = dao, lookup = lookup, now = { NOW }, ioDispatcher = Dispatchers.Unconfined)

    private class FakeDao : PlaceGeocodeDao {
        val rows = linkedMapOf<String, PlaceGeocodeEntity>()
        var upserts = 0

        override suspend fun get(placeKey: String): PlaceGeocodeEntity? = rows[placeKey]

        override suspend fun upsert(entity: PlaceGeocodeEntity) {
            upserts++
            rows[entity.placeKey] = entity
        }
    }

    private class RecordingLookup(
        private val handler: (String) -> PlaceGeocodeLookupOutcome,
    ) : PlaceGeocodeLookup {
        val queries = mutableListOf<String>()
        val threads = mutableListOf<Thread>()

        override fun lookup(query: String): PlaceGeocodeLookupOutcome {
            queries += query
            threads += Thread.currentThread()
            return handler(query)
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000
    }
}
