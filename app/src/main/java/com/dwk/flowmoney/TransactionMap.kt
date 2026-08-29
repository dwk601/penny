package com.dwk.flowmoney

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

data class MappedTransactionPlace(
    val placeKey: String,
    val city: String,
    val state: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val transactions: List<Transaction>,
) {
    val count: Int
        get() = transactions.size

    val totalCents: Int
        get() = transactions.sumOf { it.cents }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TransactionMap(
    transactions: List<Transaction>,
    geocoder: PlaceGeocoder,
    onEdit: (Transaction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { arrayOfNulls<MapView>(1) }
    val fittedCameraKey = remember { arrayOfNulls<Set<String>>(1) }
    var mappedPlaces by remember { mutableStateOf<List<MappedTransactionPlace>>(emptyList()) }
    var unmappable by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var selectedPlace by remember { mutableStateOf<MappedTransactionPlace?>(null) }
    var showUnmappable by remember { mutableStateOf(false) }
    val latestOnEdit by rememberUpdatedState(onEdit)

    LaunchedEffect(transactions, geocoder) {
        val grouped =
            transactions.groupBy { transaction ->
                TransactionLocation.placeKey(
                    transaction.locationCity,
                    transaction.locationState,
                    transaction.locationCountry,
                )
            }
        val resolved = mutableListOf<MappedTransactionPlace>()
        val missing = mutableListOf<Transaction>()
        grouped.forEach { (placeKey, rows) ->
            if (placeKey == null) {
                missing += rows
                return@forEach
            }
            val sample = rows.first()
            val city = sample.locationCity ?: run {
                missing += rows
                return@forEach
            }
            when (
                val result =
                    geocoder.resolve(
                        city = city,
                        state = sample.locationState,
                        country = sample.locationCountry,
                    )
            ) {
                is PlaceGeocodeResult.Resolved ->
                    resolved +=
                        MappedTransactionPlace(
                            placeKey = placeKey,
                            city = city,
                            state = sample.locationState,
                            country = sample.locationCountry,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            transactions = rows,
                        )
                PlaceGeocodeResult.RetryableMiss,
                PlaceGeocodeResult.Unavailable,
                -> missing += rows
            }
        }
        mappedPlaces = resolved
        unmappable = missing
    }

    Box(modifier = modifier.fillMaxSize().testTag("transactions_map")) {
        AndroidView(
            factory = { viewContext ->
                configureOsmdroid(viewContext)
                MapView(viewContext).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                    controller.setCenter(GeoPoint(39.8283, -98.5795))
                    mapViewRef[0] = this
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        onResume()
                    }
                }
            },
            update = { mapView ->
                bindPlaceMarkers(
                    mapView = mapView,
                    places = mappedPlaces,
                    fittedCameraKey = fittedCameraKey,
                    onPlaceTap = { selectedPlace = it },
                )
            },
            onRelease = { mapView ->
                mapView.onPause()
                mapView.onDetach()
                if (mapViewRef[0] === mapView) {
                    mapViewRef[0] = null
                }
                fittedCameraKey[0] = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (unmappable.isNotEmpty()) {
            TextButton(
                onClick = { showUnmappable = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .testTag("transactions_unmappable"),
            ) {
                Text("${unmappable.size} without map location")
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapViewRef[0]?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapViewRef[0]?.onPause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef[0]?.onPause()
        }
    }

    selectedPlace?.let { place ->
        PlaceTransactionSheet(
            title = placeLabel(place.city, place.state, place.country),
            subtitle = "${place.count} · ${MoneyFormatter.formatUsd(place.totalCents)}",
            transactions = place.transactions,
            onEdit = { transaction ->
                selectedPlace = null
                latestOnEdit(transaction)
            },
            onDismiss = { selectedPlace = null },
            testTag = "transaction_place_sheet",
        )
    }
    if (showUnmappable) {
        PlaceTransactionSheet(
            title = "Without map location",
            subtitle = "${unmappable.size} transactions",
            transactions = unmappable,
            onEdit = { transaction ->
                showUnmappable = false
                latestOnEdit(transaction)
            },
            onDismiss = { showUnmappable = false },
            testTag = "transaction_unmappable_sheet",
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaceTransactionSheet(
    title: String,
    subtitle: String,
    transactions: List<Transaction>,
    onEdit: (Transaction) -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag(testTag),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                Column {
                    transactions.forEach { transaction ->
                        TransactionMapRow(
                            merchant = transaction.merchant,
                            subtitle = "${transaction.category.ifBlank { "Other" }} · ${MoneyFormatter.formatUsd(transaction.cents)}",
                            onClick = { onEdit(transaction) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionMapRow(
    merchant: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .testTag("transaction_map_row"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(merchant, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun configureOsmdroid(context: android.content.Context) {
    val configuration = Configuration.getInstance()
    val base = File(context.cacheDir, "osmdroid")
    configuration.osmdroidBasePath = base
    configuration.osmdroidTileCache = File(base, "tiles")
    configuration.userAgentValue = "${context.packageName}/map"
}

private fun bindPlaceMarkers(
    mapView: MapView,
    places: List<MappedTransactionPlace>,
    fittedCameraKey: Array<Set<String>?>,
    onPlaceTap: (MappedTransactionPlace) -> Unit,
) {
    if (mapView.context == null) return
    val existing = mapView.overlays.filterIsInstance<Marker>()
    existing.forEach { mapView.overlays.remove(it) }
    places.forEach { place ->
        val marker =
            Marker(mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = placeLabel(place.city, place.state, place.country)
                snippet = "${place.count} · ${MoneyFormatter.formatUsd(place.totalCents)}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    onPlaceTap(place)
                    true
                }
            }
        mapView.overlays.add(marker)
    }
    val cameraKey =
        places.map { place -> "${place.placeKey}|${place.latitude}|${place.longitude}" }.toSet()
    if (places.isNotEmpty() && cameraKey != fittedCameraKey[0]) {
        val points = places.map { GeoPoint(it.latitude, it.longitude) }
        if (points.size == 1) {
            mapView.controller.setZoom(10.0)
            mapView.controller.setCenter(points.first())
        } else {
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 80)
        }
        fittedCameraKey[0] = cameraKey
    } else if (places.isEmpty()) {
        fittedCameraKey[0] = emptySet()
    }
    mapView.invalidate()
}

private fun placeLabel(
    city: String,
    state: String?,
    country: String?,
): String = listOfNotNull(city, state?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() }).joinToString(", ")
