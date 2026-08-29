package com.dwk.flowmoney

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Geocoding is a third-party lookup on the user's spending history, so it must be cached, capped
 * per session, and must never turn a bad answer into a permanent coordinate. Every request here is
 * served by an injected fake connection: this test never touches the network.
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

    @Test fun parsesOnlyWellFormedCoordinateArrays() {
        assertEquals(
            GeocodeBodyParse.Coordinates to (45.5152 to -122.6784),
            parseGeocodeBody("""[{"lat":"45.5152","lon":"-122.6784","display_name":"Portland"}]"""),
        )
        listOf("", "   ", "[]", "[ ]").forEach { body ->
            assertEquals("body=$body", GeocodeBodyParse.Empty, parseGeocodeBody(body).first)
            assertNull(parseGeocodeBody(body).second)
        }
        listOf(
            "{not json",
            """{"results":[]}""",
            "[1,2,3]",
            """[{"lat":"nope","lon":"-122.6"}]""",
            """[{"lon":"-122.6"}]""",
            """[{"lat":"NaN","lon":"Infinity"}]""",
        ).forEach { body ->
            assertEquals("body=$body", GeocodeBodyParse.Malformed, parseGeocodeBody(body).first)
            assertNull(parseGeocodeBody(body).second)
        }
    }

    @Test fun resolvedCoordinatesAreCachedAndServedWithoutASecondRequest() =
        runBlocking {
            withDao { dao ->
                val transport = FakeTransport(status = 200, body = """[{"lat":"45.5152","lon":"-122.6784"}]""")
                val geocoder = geocoder(dao, transport)

                assertEquals(PlaceGeocodeResult.Resolved(45.5152, -122.6784), geocoder.resolve("PORTLAND", "OR", null))
                assertEquals(1, transport.requests)
                assertTrue(transport.disconnected)
                assertEquals("nominatim.openstreetmap.org", transport.lastUrl?.host)
                assertTrue(transport.lastUrl.toString().contains("q=PORTLAND%2C+OR"))

                val cached = requireNotNull(dao.get("portland|or|"))
                assertEquals(45.5152, cached.latitude ?: 0.0, 0.0)
                assertEquals(-122.6784, cached.longitude ?: 0.0, 0.0)
                assertEquals("PORTLAND", cached.city)
                assertEquals("OR", cached.state)

                assertEquals(PlaceGeocodeResult.Resolved(45.5152, -122.6784), geocoder.resolve("  portland ", "or", null))
                assertEquals("normalized keys must hit the same cache row", 1, transport.requests)

                // A brand new geocoder still reads the cache instead of the network.
                val transportTwo = FakeTransport(status = 200, body = "[]")
                assertEquals(
                    PlaceGeocodeResult.Resolved(45.5152, -122.6784),
                    geocoder(dao, transportTwo).resolve("PORTLAND", "OR", null),
                )
                assertEquals(0, transportTwo.requests)
            }
        }

    @Test fun missesAreCachedForTheTtlAndRetriedAfterIt() =
        runBlocking {
            withDao { dao ->
                var now = 1_000_000L
                val transport = FakeTransport(status = 200, body = "[]")
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        now = { now },
                        openConnection = transport::open,
                        maxNetworkLookupsPerSession = 10,
                        missTtlMillis = 1_000L,
                    )

                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("NOWHERE", "ZZ", null))
                assertEquals(1, transport.requests)
                val cached = requireNotNull(dao.get("nowhere|zz|"))
                assertNull(cached.latitude)
                assertNull(cached.longitude)

                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("NOWHERE", "ZZ", null))
                assertEquals("a cached miss inside the TTL must not hit the network", 1, transport.requests)

                now += 1_001L
                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder.resolve("NOWHERE", "ZZ", null))
                assertEquals("an expired miss is retried", 2, transport.requests)
            }
        }

    @Test fun absentPlacesAreRetryableAndEverythingElseIsUnavailableAndUncached() =
        runBlocking {
            withDao { dao ->
                val notFound = FakeTransport(status = 404, body = "")
                assertEquals(PlaceGeocodeResult.RetryableMiss, geocoder(dao, notFound).resolve("GONE", "ZZ", null))
                assertEquals(1, notFound.requests)
                assertTrue(dao.get("gone|zz|") != null)

                listOf(
                    FakeTransport(status = 200, body = "{not json"),
                    FakeTransport(status = 500, body = "server exploded"),
                    FakeTransport(status = 429, body = "[]"),
                    FakeTransport(status = 301, body = """[{"lat":"1","lon":"2"}]"""),
                ).forEachIndexed { index, transport ->
                    val city = "BROKEN$index"
                    assertEquals(PlaceGeocodeResult.Unavailable, geocoder(dao, transport).resolve(city, "ZZ", null))
                    assertEquals(1, transport.requests)
                    assertTrue(transport.disconnected)
                    assertNull(
                        "a failed lookup must not be cached",
                        dao.get(TransactionLocation.placeKey(city, "ZZ", null)!!),
                    )
                }
            }
        }

    @Test fun transportFailuresAndOversizeBodiesDegradeToUnavailableWithoutCaching() =
        runBlocking {
            withDao { dao ->
                val throwing =
                    PlaceGeocoder(
                        dao = dao,
                        now = { 1L },
                        openConnection = { error("no network in tests") },
                    )
                assertEquals(PlaceGeocodeResult.Unavailable, throwing.resolve("PORTLAND", "OR", null))
                assertNull(dao.get("portland|or|"))

                val oversize = FakeTransport(status = 200, body = "[" + "\"x\",".repeat(40_000) + "\"x\"]")
                assertEquals(PlaceGeocodeResult.Unavailable, geocoder(dao, oversize).resolve("HUGE", "ZZ", null))
                assertNull(dao.get("huge|zz|"))
            }
        }

    @Test fun theSessionLookupCapStopsFurtherNetworkWorkButKeepsServingTheCache() =
        runBlocking {
            withDao { dao ->
                val transport = FakeTransport(status = 200, body = """[{"lat":"45.5152","lon":"-122.6784"}]""")
                val geocoder =
                    PlaceGeocoder(
                        dao = dao,
                        now = { 1L },
                        openConnection = transport::open,
                        maxNetworkLookupsPerSession = 1,
                    )

                assertEquals(PlaceGeocodeResult.Resolved(45.5152, -122.6784), geocoder.resolve("PORTLAND", "OR", null))
                assertEquals(PlaceGeocodeResult.Unavailable, geocoder.resolve("SEATTLE", "WA", null))
                assertEquals(1, transport.requests)
                assertNull(dao.get("seattle|wa|"))
                // The already-cached place keeps resolving even after the cap is reached.
                assertEquals(PlaceGeocodeResult.Resolved(45.5152, -122.6784), geocoder.resolve("PORTLAND", "OR", null))
                assertEquals(1, transport.requests)
            }
        }

    @Test fun anUnusableCityIsRejectedBeforeAnyRequestIsMade() =
        runBlocking {
            withDao { dao ->
                val transport = FakeTransport(status = 200, body = """[{"lat":"1","lon":"2"}]""")
                val geocoder = geocoder(dao, transport)

                listOf("", "   ", "\u00A0").forEach { city ->
                    assertEquals(PlaceGeocodeResult.Unavailable, geocoder.resolve(city, "OR", null))
                }
                assertEquals(0, transport.requests)
            }
        }

    private fun geocoder(
        dao: PlaceGeocodeDao,
        transport: FakeTransport,
    ) = PlaceGeocoder(dao = dao, now = { 1L }, openConnection = transport::open)

    private inline fun withDao(block: (PlaceGeocodeDao) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, FlowMoneyDatabase::class.java).build()
        try {
            block(database.placeGeocodeDao())
        } finally {
            database.close()
        }
    }

    private class FakeTransport(
        private val status: Int,
        private val body: String,
    ) {
        var requests = 0
        var disconnected = false
        var lastUrl: URL? = null

        fun open(url: URL): HttpURLConnection {
            requests++
            lastUrl = url
            return FakeConnection(url, status, body) { disconnected = true }
        }
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        body: String,
        private val onDisconnect: () -> Unit,
    ) : HttpURLConnection(url) {
        private val bytes = body.toByteArray(Charsets.UTF_8)

        override fun connect() = Unit

        override fun disconnect() {
            onDisconnect()
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

        override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)

        override fun getContentLengthLong(): Long = bytes.size.toLong()
    }
}
