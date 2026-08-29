package com.dwk.flowmoney

import android.content.Context
import android.location.Geocoder
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

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

sealed class PlaceGeocodeLookupOutcome {
    data class Coordinates(
        val latitude: Double,
        val longitude: Double,
    ) : PlaceGeocodeLookupOutcome()

    data object NoMatch : PlaceGeocodeLookupOutcome()

    data object Unavailable : PlaceGeocodeLookupOutcome()
}

fun interface PlaceGeocodeLookup {
    fun lookup(query: String): PlaceGeocodeLookupOutcome
}

class PlaceGeocoder(
    private val dao: PlaceGeocodeDao,
    private val lookup: PlaceGeocodeLookup = PlaceGeocodeLookup { PlaceGeocodeLookupOutcome.Unavailable },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxLookupsPerSession: Int = MAX_LOOKUPS_PER_SESSION,
    private val missTtlMillis: Long = MISS_TTL_MILLIS,
) {
    constructor(
        dao: PlaceGeocodeDao,
        context: Context,
        now: () -> Long = System::currentTimeMillis,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxLookupsPerSession: Int = MAX_LOOKUPS_PER_SESSION,
        missTtlMillis: Long = MISS_TTL_MILLIS,
    ) : this(
        dao = dao,
        lookup = AndroidGeocoderLookup(context.applicationContext),
        now = now,
        ioDispatcher = ioDispatcher,
        maxLookupsPerSession = maxLookupsPerSession,
        missTtlMillis = missTtlMillis,
    )

    private var lookupsThisSession = 0

    suspend fun resolve(
        city: String,
        state: String?,
        country: String?,
    ): PlaceGeocodeResult {
        val placeKey = TransactionLocation.placeKey(city, state, country) ?: return PlaceGeocodeResult.Unavailable
        val cached = dao.get(placeKey)
        val cachedResult = cached?.toResult(nowMillis = now(), missTtlMillis = missTtlMillis)
        if (cachedResult != null) return cachedResult
        if (lookupsThisSession >= maxLookupsPerSession) return PlaceGeocodeResult.Unavailable

        lookupsThisSession++
        val query = locationQuery(city, state, country)
        return withContext(ioDispatcher) {
            val outcome =
                try {
                    lookup.lookup(query)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    PlaceGeocodeLookupOutcome.Unavailable
                } catch (_: Exception) {
                    PlaceGeocodeLookupOutcome.Unavailable
                }
            currentCoroutineContext().ensureActive()
            when (classifyGeocodeLookup(outcome)) {
                PlaceGeocodeClassification.Unavailable -> PlaceGeocodeResult.Unavailable
                PlaceGeocodeClassification.RetryableMiss -> {
                    dao.upsert(
                        PlaceGeocodeEntity(
                            placeKey = placeKey,
                            city = city,
                            state = state,
                            country = country,
                            latitude = null,
                            longitude = null,
                            resolvedAtEpochMillis = now(),
                        ),
                    )
                    PlaceGeocodeResult.RetryableMiss
                }
                PlaceGeocodeClassification.Resolved -> {
                    val coordinates = outcome as? PlaceGeocodeLookupOutcome.Coordinates
                    if (coordinates == null) {
                        PlaceGeocodeResult.Unavailable
                    } else {
                        dao.upsert(
                            PlaceGeocodeEntity(
                                placeKey = placeKey,
                                city = city,
                                state = state,
                                country = country,
                                latitude = coordinates.latitude,
                                longitude = coordinates.longitude,
                                resolvedAtEpochMillis = now(),
                            ),
                        )
                        PlaceGeocodeResult.Resolved(
                            latitude = coordinates.latitude,
                            longitude = coordinates.longitude,
                        )
                    }
                }
            }
        }
    }
}

internal fun classifyGeocodeLookup(outcome: PlaceGeocodeLookupOutcome): PlaceGeocodeClassification =
    when (outcome) {
        is PlaceGeocodeLookupOutcome.Coordinates -> PlaceGeocodeClassification.Resolved
        PlaceGeocodeLookupOutcome.NoMatch -> PlaceGeocodeClassification.RetryableMiss
        PlaceGeocodeLookupOutcome.Unavailable -> PlaceGeocodeClassification.Unavailable
    }

internal class AndroidGeocoderLookup(
    private val context: Context,
) : PlaceGeocodeLookup {
    @Suppress("DEPRECATION")
    override fun lookup(query: String): PlaceGeocodeLookupOutcome {
        if (!Geocoder.isPresent()) return PlaceGeocodeLookupOutcome.Unavailable
        val results =
            try {
                Geocoder(context, Locale.US).getFromLocationName(query, 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return PlaceGeocodeLookupOutcome.Unavailable
            }
        val address = results?.firstOrNull() ?: return PlaceGeocodeLookupOutcome.NoMatch
        val latitude = address.latitude
        val longitude = address.longitude
        return if (latitude.isFinite() && longitude.isFinite()) {
            PlaceGeocodeLookupOutcome.Coordinates(latitude = latitude, longitude = longitude)
        } else {
            PlaceGeocodeLookupOutcome.Unavailable
        }
    }
}

internal fun locationQuery(
    city: String,
    state: String?,
    country: String?,
): String =
    listOfNotNull(
        city.trim().takeIf { it.isNotBlank() },
        state?.trim()?.takeIf { it.isNotBlank() },
        country?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(", ")

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

private const val MAX_LOOKUPS_PER_SESSION = 20
private const val MISS_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
