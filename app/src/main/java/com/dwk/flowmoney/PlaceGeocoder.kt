package com.dwk.flowmoney

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Entity(tableName = "place_geocodes")
data class PlaceGeocodeEntity(
    @PrimaryKey val placeKey: String,
    val city: String?,
    val state: String?,
    val country: String?,
    val latitude: Double?,
    val longitude: Double?,
    val resolvedAtEpochMillis: Long,
)

@Dao
interface PlaceGeocodeDao {
    @Query("SELECT * FROM place_geocodes WHERE placeKey = :placeKey")
    suspend fun get(placeKey: String): PlaceGeocodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaceGeocodeEntity)
}

enum class PlaceGeocodeClassification {
    Resolved,
    RetryableMiss,
    Unavailable,
}

sealed class PlaceGeocodeResult {
    data class Resolved(
        val latitude: Double,
        val longitude: Double,
    ) : PlaceGeocodeResult()

    data object RetryableMiss : PlaceGeocodeResult()

    data object Unavailable : PlaceGeocodeResult()
}

internal enum class GeocodeBodyParse {
    Coordinates,
    Empty,
    Malformed,
}

class PlaceGeocoder(
    private val dao: PlaceGeocodeDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection
            ?: error("Geocoder did not open an HTTP connection")
    },
    private val maxNetworkLookupsPerSession: Int = MAX_NETWORK_LOOKUPS_PER_SESSION,
    private val missTtlMillis: Long = MISS_TTL_MILLIS,
) {
    private var networkLookupsThisSession = 0

    suspend fun resolve(
        city: String,
        state: String?,
        country: String?,
    ): PlaceGeocodeResult {
        val placeKey = TransactionLocation.placeKey(city, state, country) ?: return PlaceGeocodeResult.Unavailable
        val cached = dao.get(placeKey)
        val cachedResult = cached?.toResult(nowMillis = now(), missTtlMillis = missTtlMillis)
        if (cachedResult != null) return cachedResult
        if (networkLookupsThisSession >= maxNetworkLookupsPerSession) return PlaceGeocodeResult.Unavailable

        networkLookupsThisSession++
        val fetched =
            try {
                fetchNominatim(city = city, state = state, country = country)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PlaceGeocodeResult.Unavailable
            }

        currentCoroutineContext().ensureActive()
        when (fetched.classification) {
            PlaceGeocodeClassification.Unavailable -> return PlaceGeocodeResult.Unavailable
            PlaceGeocodeClassification.Resolved,
            PlaceGeocodeClassification.RetryableMiss,
            -> {
                dao.upsert(
                    PlaceGeocodeEntity(
                        placeKey = placeKey,
                        city = city,
                        state = state,
                        country = country,
                        latitude = fetched.latitude,
                        longitude = fetched.longitude,
                        resolvedAtEpochMillis = now(),
                    ),
                )
            }
        }
        val latitude = fetched.latitude
        val longitude = fetched.longitude
        return when (fetched.classification) {
            PlaceGeocodeClassification.Resolved ->
                if (latitude == null || longitude == null) {
                    PlaceGeocodeResult.Unavailable
                } else {
                    PlaceGeocodeResult.Resolved(latitude = latitude, longitude = longitude)
                }
            PlaceGeocodeClassification.RetryableMiss -> PlaceGeocodeResult.RetryableMiss
            PlaceGeocodeClassification.Unavailable -> PlaceGeocodeResult.Unavailable
        }
    }

    private suspend fun fetchNominatim(
        city: String,
        state: String?,
        country: String?,
    ): FetchedGeocode {
        val query =
            buildString {
                append(city)
                state?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                country?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
            }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = URL("$NOMINATIM_ENDPOINT?format=jsonv2&limit=1&q=$encoded")
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "en")
            val status = connection.responseCode
            val stream =
                if (status >= 400) {
                    connection.errorStream ?: connection.inputStream
                } else {
                    connection.inputStream
                }
            val body =
                if (stream == null) {
                    ""
                } else {
                    StrictUtf8Reader.read(
                        stream,
                        maxBytes = MAX_GEOCODE_BODY_BYTES,
                        advertisedLength = connection.contentLengthLong,
                    )
                }
            currentCoroutineContext().ensureActive()
            val (parse, coordinates) = parseGeocodeBody(body)
            val classification = classifyGeocodeOutcome(status, parse)
            FetchedGeocode(
                classification = classification,
                latitude = coordinates?.first,
                longitude = coordinates?.second,
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal fun classifyGeocodeOutcome(
    statusCode: Int,
    body: GeocodeBodyParse,
): PlaceGeocodeClassification =
    when {
        statusCode == HttpURLConnection.HTTP_OK && body == GeocodeBodyParse.Coordinates ->
            PlaceGeocodeClassification.Resolved
        statusCode == HttpURLConnection.HTTP_OK && body == GeocodeBodyParse.Empty ->
            PlaceGeocodeClassification.RetryableMiss
        statusCode == HttpURLConnection.HTTP_OK && body == GeocodeBodyParse.Malformed ->
            PlaceGeocodeClassification.Unavailable
        statusCode == HttpURLConnection.HTTP_NOT_FOUND || statusCode == HttpURLConnection.HTTP_NO_CONTENT ->
            PlaceGeocodeClassification.RetryableMiss
        else -> PlaceGeocodeClassification.Unavailable
    }

internal fun parseGeocodeBody(body: String): Pair<GeocodeBodyParse, Pair<Double, Double>?> {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed == "[]") return GeocodeBodyParse.Empty to null
    return try {
        val array = JSONArray(trimmed)
        if (array.length() == 0) return GeocodeBodyParse.Empty to null
        val first = array.optJSONObject(0) ?: return GeocodeBodyParse.Malformed to null
        val latitude = first.optString("lat").toDoubleOrNull()
        val longitude = first.optString("lon").toDoubleOrNull()
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) {
            GeocodeBodyParse.Malformed to null
        } else {
            GeocodeBodyParse.Coordinates to (latitude to longitude)
        }
    } catch (_: JSONException) {
        GeocodeBodyParse.Malformed to null
    }
}

private data class FetchedGeocode(
    val classification: PlaceGeocodeClassification,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

private fun PlaceGeocodeEntity.toResult(
    nowMillis: Long,
    missTtlMillis: Long,
): PlaceGeocodeResult? {
    val latitude = latitude
    val longitude = longitude
    if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()) {
        return PlaceGeocodeResult.Resolved(latitude = latitude, longitude = longitude)
    }
    val age = nowMillis - resolvedAtEpochMillis
    if (age in 0 until missTtlMillis) return PlaceGeocodeResult.RetryableMiss
    return null
}

private const val NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search"
private const val USER_AGENT = "Penny/1.1.2 (com.dwk.flowmoney; personal finance map)"
private const val CONNECT_TIMEOUT_MILLIS = 8_000
private const val READ_TIMEOUT_MILLIS = 12_000
private const val MAX_GEOCODE_BODY_BYTES = 64 * 1024
private const val MAX_NETWORK_LOOKUPS_PER_SESSION = 20
private const val MISS_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
