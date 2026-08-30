package com.dwk.flowmoney

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE_URL = "https://tiles.versatiles.org/assets/styles/eclipse/style.json"
private const val PLACE_SOURCE_ID = "penny-places"
private const val PLACE_LAYER_ID = "penny-place-circles"

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
    onPlacesBound: (List<MappedTransactionPlace>) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { arrayOfNulls<MapView>(1) }
    val fittedCameraKey = remember { arrayOfNulls<Set<String>>(1) }
    var mappedPlaces by remember { mutableStateOf<List<MappedTransactionPlace>>(emptyList()) }
    var unmappable by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var selectedPlace by remember { mutableStateOf<MappedTransactionPlace?>(null) }
    var showUnmappable by remember { mutableStateOf(false) }
    var mapStyle by remember { mutableStateOf<Style?>(null) }
    val latestOnEdit by rememberUpdatedState(onEdit)
    val latestPlaces by rememberUpdatedState(mappedPlaces)
    val colorScheme = MaterialTheme.colorScheme
    val styleUrl = if (isSystemInDarkTheme()) DARK_STYLE_URL else LIGHT_STYLE_URL
    val circleFillColor = colorScheme.primary.toArgb()
    val circleStrokeColorValue = colorScheme.surface.toArgb()

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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .testTag("transactions_map"),
        ) {
            AndroidView(
                factory = { viewContext ->
                    MapLibre.getInstance(viewContext)
                    MapView(
                        viewContext,
                        MapLibreMapOptions.createFromAttributes(viewContext).textureMode(true),
                    ).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        onCreate(null)
                        val currentState = lifecycleOwner.lifecycle.currentState
                        if (currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            onStart()
                            onResume()
                        } else if (currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            onStart()
                        }
                        getMapAsync { map ->
                            configureMapChrome(this, map)
                            map.addOnMapClickListener { latLng ->
                                val screen = map.projection.toScreenLocation(latLng)
                                val hit = map.queryRenderedFeatures(screen, PLACE_LAYER_ID).firstOrNull()
                                val key = hit?.getStringProperty("placeKey")
                                val place = key?.let { k -> latestPlaces.firstOrNull { it.placeKey == k } }
                                if (place != null) {
                                    selectedPlace = place
                                    true
                                } else {
                                    false
                                }
                            }
                            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                                mapStyle = style
                            }
                        }
                        setOnTouchListener { v, _ ->
                            v.parent.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        mapViewRef[0] = this
                    }
                },
                update = { mapView ->
                    val places = mappedPlaces
                    onPlacesBound(places)
                    fitCameraToPlaces(mapView, places, fittedCameraKey)
                    val style = mapStyle
                    if (style != null) {
                        bindPlaceSource(
                            style = style,
                            places = places,
                            fillColor = circleFillColor,
                            strokeColor = circleStrokeColorValue,
                        )
                    }
                },
                onRelease = { mapView ->
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        mapView.onPause()
                    }
                    mapView.onStop()
                    mapView.onDestroy()
                    if (mapViewRef[0] === mapView) {
                        mapViewRef[0] = null
                    }
                    fittedCameraKey[0] = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tap a place to see its transactions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (unmappable.isNotEmpty()) {
                TextButton(
                    onClick = { showUnmappable = true },
                    modifier = Modifier.testTag("transactions_unmappable"),
                ) {
                    Text("${unmappable.size} without map location")
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapViewRef[0]?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapViewRef[0]?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapViewRef[0]?.onPause()
                    Lifecycle.Event.ON_STOP -> mapViewRef[0]?.onStop()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

private fun configureMapChrome(mapView: MapView, map: MapLibreMap) {
    val uiSettings = map.uiSettings
    uiSettings.isCompassEnabled = false
    uiSettings.isRotateGesturesEnabled = false
    uiSettings.isAttributionEnabled = true
    uiSettings.isLogoEnabled = true
    val density = mapView.resources.displayMetrics.density
    val inset = 4
    uiSettings.setLogoMargins(inset, inset, inset, inset)
    uiSettings.setAttributionMargins((92 * density).toInt(), inset, inset, inset)
}

private fun fitCameraToPlaces(
    mapView: MapView,
    places: List<MappedTransactionPlace>,
    fittedCameraKey: Array<Set<String>?>,
) {
    if (mapView.isDestroyed) return
    val cameraKey =
        places.map { place -> "${place.placeKey}|${place.latitude}|${place.longitude}" }.toSet()
    mapView.getMapAsync { map ->
        if (mapView.isDestroyed) return@getMapAsync
        if (places.isNotEmpty() && cameraKey != fittedCameraKey[0]) {
            if (places.size == 1) {
                val place = places.first()
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(place.latitude, place.longitude), 10.0),
                )
                fittedCameraKey[0] = cameraKey
            } else if (mapView.width > 0 && mapView.height > 0) {
                val bounds =
                    LatLngBounds.Builder().apply {
                        places.forEach { include(LatLng(it.latitude, it.longitude)) }
                    }.build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                fittedCameraKey[0] = cameraKey
            }
        } else if (places.isEmpty()) {
            fittedCameraKey[0] = emptySet()
        }
    }
}

private fun bindPlaceSource(
    style: Style,
    places: List<MappedTransactionPlace>,
    fillColor: Int,
    strokeColor: Int,
) {
    if (!style.isFullyLoaded) return
    val features =
        places.map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
                addStringProperty("placeKey", place.placeKey)
                addNumberProperty("count", place.count)
            }
        }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSourceAs<GeoJsonSource>(PLACE_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(PLACE_SOURCE_ID, collection))
        style.addLayer(
            CircleLayer(PLACE_LAYER_ID, PLACE_SOURCE_ID).withProperties(
                circleRadius(
                    interpolate(
                        linear(),
                        get("count"),
                        stop(1, 7f),
                        stop(25, 18f),
                    ),
                ),
                circleColor(fillColor),
                circleOpacity(0.85f),
                circleStrokeWidth(2f),
                circleStrokeColor(strokeColor),
            ),
        )
    }
}

private fun placeLabel(
    city: String,
    state: String?,
    country: String?,
): String = listOfNotNull(city, state?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() }).joinToString(", ")
