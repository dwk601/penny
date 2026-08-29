package com.dwk.flowmoney

import android.content.Context
import android.location.Geocoder
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The geocoder against the real `place_geocodes` table and the real platform seam.
 *
 * Nothing here depends on a geocoding backend actually being installed on the device: the
 * framework-backed cases assert invariants that must hold whether the platform answers, returns
 * nothing, or is absent entirely.
 */
@RunWith(AndroidJUnit4::class)
class PlaceGeocoderDataPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @After fun closeDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
    }

    @Test fun theAndroidLookupIsUnavailableWheneverNoGeocoderBackendIsPresent() {
        val outcome = AndroidGeocoderLookup(context).lookup("Portland, OR, US")

        if (!Geocoder.isPresent()) {
            assertEquals(PlaceGeocodeLookupOutcome.Unavailable, outcome)
        } else {
            assertTrue(
                "unexpected outcome $outcome",
                outcome is PlaceGeocodeLookupOutcome.Coordinates ||
                    outcome == PlaceGeocodeLookupOutcome.NoMatch ||
                    outcome == PlaceGeocodeLookupOutcome.Unavailable,
            )
            (outcome as? PlaceGeocodeLookupOutcome.Coordinates)?.let { coordinates ->
                assertTrue(coordinates.latitude.isFinite())
                assertTrue(coordinates.longitude.isFinite())
            }
        }
    }

    @Test fun theAndroidLookupNeverThrowsForHostileOrEmptyQueries() {
        val lookup = AndroidGeocoderLookup(context)

        listOf("", "   ", "?????", "\u0000", "x".repeat(2_000), "Portland, OR, US").forEach { query ->
            val outcome = runCatching { lookup.lookup(query) }
            assertTrue("threw for '$query': ${outcome.exceptionOrNull()}", outcome.isSuccess)
            assertNotNull(outcome.getOrNull())
        }
    }

    @Test fun theFrameworkBackedGeocoderOnlyEverCachesAnAnswerThePlatformGave() =
        runBlocking {
            withDao { dao ->
                val geocoder = PlaceGeocoder(dao = dao, context = context, now = { NOW })

                val result = geocoder.resolve("Portland", "OR", "US")
                val cached = dao.get("portland|or|us")

                when (result) {
                    is PlaceGeocodeResult.Resolved -> {
                        assertNotNull(cached)
                        assertTrue(result.latitude.isFinite())
                        assertEquals(result.latitude, cached!!.latitude!!, 0.0)
                        assertEquals(result.longitude, cached.longitude!!, 0.0)
                        assertEquals(NOW, cached.resolvedAtEpochMillis)
                    }
                    PlaceGeocodeResult.RetryableMiss -> {
                        assertNotNull(cached)
                        assertNull(cached!!.latitude)
                        assertNull(cached.longitude)
                    }
                    PlaceGeocodeResult.Unavailable -> assertNull("an outage must not be cached", cached)
                }
            }
        }

    @Test fun theDefaultDispatcherKeepsTheFrameworkLookupOffTheMainThread() =
        runBlocking {
            withDao { dao ->
                val mainThread = Looper.getMainLooper().thread
                var lookupThread: Thread? = null
                var callerThread: Thread? = null
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = {
                            lookupThread = Thread.currentThread()
                            PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784)
                        },
                        now = { NOW },
                    )

                withContext(Dispatchers.Main) {
                    callerThread = Thread.currentThread()
                    geocoder.resolve("Portland", "OR", "US")
                }

                assertEquals(mainThread, callerThread)
                assertNotNull(lookupThread)
                assertFalse("the geocoder must not block the main thread", lookupThread === mainThread)
                assertEquals(45.5152, dao.get("portland|or|us")!!.latitude!!, 0.0)
            }
        }

    @Test fun anUnavailableOrFailingBackendLeavesTheRealTableEmpty() =
        runBlocking {
            withDao { dao ->
                val unavailable = PlaceGeocoder(dao = dao, lookup = { PlaceGeocodeLookupOutcome.Unavailable }, now = { NOW })
                assertEquals(PlaceGeocodeResult.Unavailable, unavailable.resolve("Portland", "OR", "US"))
                assertNull(dao.get("portland|or|us"))

                val throwing = PlaceGeocoder(dao = dao, lookup = { throw IOException("backend offline") }, now = { NOW })
                assertEquals(PlaceGeocodeResult.Unavailable, throwing.resolve("Seattle", "WA", "US"))
                assertNull(dao.get("seattle|wa|us"))
            }
        }

    @Test fun zeroResultsPersistARetryableMissThatExpiresWithItsTtl() =
        runBlocking {
            withDao { dao ->
                var now = 1_000_000L
                var lookups = 0
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = {
                            lookups++
                            PlaceGeocodeLookupOutcome.NoMatch
                        },
                        now = { now },
                        missTtlMillis = 1_000L,
                    )

                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("Nowhere", "ZZ", null))
                val cached = dao.get("nowhere|zz|")!!
                assertNull(cached.latitude)
                assertNull(cached.longitude)
                assertEquals("Nowhere", cached.city)
                assertEquals(1, lookups)

                now += 999L
                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("Nowhere", "ZZ", null))
                assertEquals("a miss inside its TTL is served from the table", 1, lookups)

                now += 2L
                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("Nowhere", "ZZ", null))
                assertEquals(2, lookups)
                assertEquals(1_001_001L, dao.get("nowhere|zz|")!!.resolvedAtEpochMillis)
            }
        }

    @Test fun resolvedCoordinatesSurviveInTheTableFarBeyondTheMissTtlAndAcrossSessions() =
        runBlocking {
            withDao { dao ->
                var now = 1_000L
                var lookups = 0
                val resolved = PlaceGeocodeResult.Resolved(45.5152, -122.6784)
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = {
                            lookups++
                            PlaceGeocodeLookupOutcome.Coordinates(45.5152, -122.6784)
                        },
                        now = { now },
                        missTtlMillis = 1L,
                    )

                assertEquals(resolved, geocoder.resolve("Portland", "OR", "US"))
                now += 10L * 365 * 24 * 60 * 60 * 1000
                assertEquals(resolved, geocoder.resolve("Portland", "OR", "US"))
                assertEquals(resolved, geocoder.resolve("  portland  ", "or", " US "))
                assertEquals(1, lookups)

                val freshSession = PlaceGeocoder(dao = dao, lookup = { PlaceGeocodeLookupOutcome.Unavailable }, now = { now })
                assertEquals(resolved, freshSession.resolve("Portland", "OR", "US"))
            }
        }

    @Test fun theSessionBudgetIsEnforcedAgainstTheRealTable() =
        runBlocking {
            withDao { dao ->
                val queries = mutableListOf<String>()
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = { query ->
                            queries += query
                            PlaceGeocodeLookupOutcome.Coordinates(1.0, 2.0)
                        },
                        now = { NOW },
                        maxLookupsPerSession = 2,
                    )

                assertEquals(PlaceGeocodeResult.Resolved(1.0, 2.0), geocoder.resolve("Portland", "OR", "US"))
                assertEquals(PlaceGeocodeResult.Resolved(1.0, 2.0), geocoder.resolve("Seattle", "WA", "US"))
                assertEquals(PlaceGeocodeResult.Unavailable, geocoder.resolve("Boston", "MA", "US"))

                assertEquals(listOf("Portland, OR, US", "Seattle, WA, US"), queries)
                assertNull(dao.get("boston|ma|us"))
                // A place already in the table is still served after the budget is spent.
                assertEquals(PlaceGeocodeResult.Resolved(1.0, 2.0), geocoder.resolve("Portland", "OR", "US"))
                assertEquals(2, queries.size)
            }
        }

    @Test fun placeKeyNormalizationMeansOneRowPerPlaceInTheTable() =
        runBlocking {
            withDao { dao ->
                var lookups = 0
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        lookup = {
                            lookups++
                            PlaceGeocodeLookupOutcome.Coordinates(1.0, 2.0)
                        },
                        now = { NOW },
                    )

                geocoder.resolve("Salt Lake City", "UT", "US")
                geocoder.resolve("  SALT   LAKE  CITY ", " ut ", " us ")

                assertEquals(1, lookups)
                assertNotNull(dao.get("salt lake city|ut|us"))
                // Distinct places do not collide on the key.
                geocoder.resolve("Salt Lake City", "UT", null)
                assertEquals(2, lookups)
                assertNotNull(dao.get("salt lake city|ut|"))
            }
        }

    private inline fun withDao(block: (PlaceGeocodeDao) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(database.placeGeocodeDao())
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000
    }
}
