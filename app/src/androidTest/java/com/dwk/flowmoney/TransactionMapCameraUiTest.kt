package com.dwk.flowmoney

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The map camera is user state. Once a place is framed, a recomposition that does not change the
 * resolved places — opening a marker sheet, opening the unmappable sheet, dismissing either — must
 * leave the camera exactly where the user left it. The MapLibre `MapView` in the view hierarchy is
 * the seam used to observe it, and `onPlacesBound` is the seam used to observe bind passes; no tile
 * or geocoder network is required because the geocode cache is seeded up front and camera fitting
 * is style-independent.
 */
class TransactionMapCameraUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var transactions by mutableStateOf(emptyList<Transaction>())

    /** Every bind pass, in order. Written on the main thread, read through `runOnIdle`. */
    private val boundPlaces = mutableListOf<List<MappedTransactionPlace>>()
    private var mapReady = CountDownLatch(1)

    @Volatile private var mapLibreMap: MapLibreMap? = null

    @Before fun seedGeocodeCache() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        transactions = emptyList()
        boundPlaces.clear()
        mapLibreMap = null
        mapReady = CountDownLatch(1)
        runBlocking {
            val dao = FlowMoneyDatabase.get(context).placeGeocodeDao()
            listOf(
                Triple("portland|or|us", 45.5152, -122.6784),
                Triple("portland|me|us", 43.6591, -70.2568),
            ).forEach { (key, latitude, longitude) ->
                dao.upsert(
                    PlaceGeocodeEntity(
                        placeKey = key,
                        city = "Portland",
                        state = null,
                        country = "US",
                        latitude = latitude,
                        longitude = longitude,
                        resolvedAtEpochMillis = 1_700_000_000_000,
                    ),
                )
            }
        }
    }

    @After fun clearDatabase() {
        FlowMoneyDatabase.resetForTest()
        context.deleteDatabase("flow_money.db")
        mapLibreMap = null
    }

    @Test fun openingASheetLeavesTheCameraAloneEvenWhenMarkersAreReboundUnderIt() {
        transactions = listOf(portlandOr, unlocated)
        setMap()
        awaitMarkers(1)
        moveCameraLikeAUser()
        assertCameraIsWhereTheUserLeftIt()

        // Opening the sheet recomposes TransactionMap; the camera must not move.
        composeRule.onNodeWithTag("transactions_unmappable").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("transaction_unmappable_sheet").assertExists()
        assertCameraIsWhereTheUserLeftIt()

        // With the sheet open, force a real marker rebind for the same place set. This is the
        // path that could reframe, so the assertion below is not vacuous.
        val passesBeforeRebind = composeRule.runOnIdle { boundPlaces.size }
        composeRule.runOnIdle { transactions = listOf(portlandOr.copy(note = "edited"), unlocated.copy(note = "edited")) }
        composeRule.waitForIdle()
        val passes = awaitBindPassAfter(passesBeforeRebind, expected = 1)

        assertNotSame(
            "the places were not rebound, so this test proves nothing",
            passes[passes.lastIndex - 1],
            passes.last(),
        )
        composeRule.onNodeWithTag("transaction_unmappable_sheet").assertExists()
        assertCameraIsWhereTheUserLeftIt()
    }

    @Test fun anEqualPlaceSetNeverReframesEvenWhenTheTransactionListIsRebuilt() {
        transactions = listOf(portlandOr, portlandMe)
        setMap()
        awaitMarkers(2)
        moveCameraLikeAUser()

        // Same two places, different Transaction instances and a different order.
        repeat(2) {
            composeRule.runOnIdle {
                transactions = listOf(portlandMe.copy(note = "edited $it"), portlandOr.copy(note = "edited $it"))
            }
            awaitMarkers(2)
            assertCameraIsWhereTheUserLeftIt()
        }
    }

    @Test fun theCameraStillFitsWhenTheResolvedPlacesActuallyChange() {
        transactions = listOf(portlandOr, portlandMe)
        setMap()
        awaitMarkers(2)
        moveCameraLikeAUser()
        assertCameraIsWhereTheUserLeftIt()

        // Filtering down to one place is a real change, so the map must frame it.
        composeRule.runOnIdle { transactions = listOf(portlandOr) }
        awaitMarkers(1)

        composeRule.runOnIdle {
            val camera = map().cameraPosition
            assertEquals(10.0, camera.zoom, 0.001)
            assertEquals(45.5152, camera.target!!.latitude, 0.01)
            assertEquals(-122.6784, camera.target!!.longitude, 0.01)
        }
    }

    private fun moveCameraLikeAUser() {
        composeRule.runOnIdle {
            map().moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(USER_LATITUDE, USER_LONGITUDE), USER_ZOOM),
            )
        }
        composeRule.waitForIdle()
    }

    /** Tolerances are well under a degree but comfortably above pixel quantization at zoom 4. */
    private fun assertCameraIsWhereTheUserLeftIt() {
        composeRule.runOnIdle {
            val camera = map().cameraPosition
            assertEquals("zoom was reframed", USER_ZOOM, camera.zoom, 0.001)
            assertEquals("latitude was reframed", USER_LATITUDE, camera.target!!.latitude, 0.5)
            assertEquals("longitude was reframed", USER_LONGITUDE, camera.target!!.longitude, 0.5)
        }
    }

    private fun setMap() {
        val geocoder = PlaceGeocoder(dao = FlowMoneyDatabase.get(context).placeGeocodeDao())
        composeRule.setContent {
            MaterialTheme {
                TransactionMap(
                    transactions = transactions,
                    geocoder = geocoder,
                    onEdit = {},
                    modifier = Modifier.fillMaxSize(),
                    onPlacesBound = { boundPlaces.add(it) },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            mapView().getMapAsync { ready ->
                mapLibreMap = ready
                mapReady.countDown()
            }
        }
        if (!mapReady.await(20, TimeUnit.SECONDS)) fail("the MapLibre map never became ready")
    }

    /** Waits until the most recent bind pass carries [expected] places. */
    private fun awaitMarkers(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        var seen = -1
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            seen = composeRule.runOnIdle { boundPlaces.lastOrNull()?.size ?: -1 }
            if (seen == expected) return
            Thread.sleep(50)
        }
        fail("expected $expected bound places but saw $seen")
    }

    /**
     * Waits for a bind pass beyond [afterPasses] that carries [expected] places and returns the
     * snapshot of passes seen at that moment, so the caller can compare the last two list instances.
     */
    private fun awaitBindPassAfter(
        afterPasses: Int,
        expected: Int,
    ): List<List<MappedTransactionPlace>> {
        val deadline = System.currentTimeMillis() + 10_000
        var seen: List<List<MappedTransactionPlace>> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            seen = composeRule.runOnIdle { boundPlaces.toList() }
            if (seen.size > afterPasses && seen.last().size == expected) return seen
            Thread.sleep(50)
        }
        fail(
            "expected a bind pass after $afterPasses passes carrying $expected places " +
                "but saw ${seen.size} passes, the last of ${seen.lastOrNull()?.size} places",
        )
        error("unreachable")
    }

    private fun map(): MapLibreMap = requireNotNull(mapLibreMap) { "the MapLibre map was never bound" }

    private fun mapView(): MapView =
        requireNotNull(findMapView(composeRule.activity.window.decorView)) { "no MapLibre MapView in the hierarchy" }

    private fun findMapView(view: View): MapView? =
        when {
            view is MapView -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMapView(view.getChildAt(it)) }
            else -> null
        }

    private companion object {
        const val USER_ZOOM = 4.0
        const val USER_LATITUDE = 10.0
        const val USER_LONGITUDE = 20.0

        val portlandOr = transaction("tx-portland-or", "Blue Bottle", "Portland", "OR", "US")
        val portlandMe = transaction("tx-portland-me", "Lobster Shack", "Portland", "ME", "US")
        val unlocated = transaction("tx-nowhere", "Rent", null, null, null)

        fun transaction(
            id: String,
            merchant: String,
            city: String?,
            state: String?,
            country: String?,
        ) = Transaction(
            id = id,
            occurredAtEpochMillis =
                LocalDate
                    .of(2026, 7, 10)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            merchant = merchant,
            category = "Food",
            note = "",
            cents = -500,
            locationCity = city,
            locationState = state,
            locationCountry = country,
        )
    }
}
