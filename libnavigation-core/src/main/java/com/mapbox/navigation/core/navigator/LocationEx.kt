@file:JvmName("LocationEx")

package com.mapbox.navigation.core.navigator

import android.location.Location
import android.os.Build
import android.os.Bundle
import com.mapbox.base.common.logger.model.Message
import com.mapbox.base.common.logger.model.Tag
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Point
import com.mapbox.navigation.utils.internal.LoggerProvider
import com.mapbox.navigator.FixLocation
import java.lang.reflect.Method
import java.util.Date

internal typealias FixLocationExtras = HashMap<String, Value>

private val TAG = Tag("MbxLocationEx")
private val setIsFromMockProviderMethod: Method? by lazy {
    val printError: (Exception) -> Unit = {
        LoggerProvider.logger.e(
            TAG,
            Message("Unable to find method for setting mock provider"),
            it
        )
    }
    try {
        // the "setIsFromMockProvider" method is a "SystemApi" and not available publicly,
        // so we're forced to make the best effort in setting
        // the mock status back (for example from history recordings)
        // but unfortunately cannot guarantee forward compatibility upfront
        Location::class.java.getDeclaredMethod(
            "setIsFromMockProvider",
            Boolean::class.java
        )
    } catch (ex: NoSuchMethodException) {
        printError(ex)
        null
    } catch (ex: SecurityException) {
        printError(ex)
        null
    }
}

internal fun FixLocation.toLocation(): Location = Location(this.provider).also {
    it.latitude = coordinate.latitude()
    it.longitude = coordinate.longitude()
    it.time = time.time
    it.elapsedRealtimeNanos = monotonicTimestampNanoseconds

    speed?.run { it.speed = this }
    bearing?.run { it.bearing = this }
    altitude?.run { it.altitude = this.toDouble() }
    accuracyHorizontal?.run { it.accuracy = this }

    if (isCurrentSdkVersionEqualOrGreaterThan(Build.VERSION_CODES.O)) {
        bearingAccuracy?.run { it.bearingAccuracyDegrees = this }
        speedAccuracy?.run { it.speedAccuracyMetersPerSecond = this }
        verticalAccuracy?.run { it.verticalAccuracyMeters = this }
    }
    it.extras = extras.toBundle()
    setIsFromMockProviderMethod?.invoke(it, isMock)
}

internal fun Location.toFixLocation(): FixLocation {
    var bearingAccuracy: Float? = null
    var speedAccuracy: Float? = null
    var verticalAccuracy: Float? = null

    if (isCurrentSdkVersionEqualOrGreaterThan(Build.VERSION_CODES.O)) {
        bearingAccuracy = if (hasBearingAccuracy()) this.bearingAccuracyDegrees else null
        speedAccuracy = if (hasSpeedAccuracy()) this.speedAccuracyMetersPerSecond else null
        verticalAccuracy = if (hasVerticalAccuracy()) this.verticalAccuracyMeters else null
    }

    return FixLocation(
        Point.fromLngLat(longitude, latitude),
        elapsedRealtimeNanos,
        Date(time),
        if (hasSpeed()) speed else null,
        if (hasBearing()) bearing else null,
        if (hasAltitude()) altitude.toFloat() else null,
        if (hasAccuracy()) accuracy else null,
        provider,
        bearingAccuracy,
        speedAccuracy,
        verticalAccuracy,
        extras?.toMap() ?: Bundle().toMap(),
        isFromMockProvider
    )
}

internal fun List<FixLocation>.toLocations(): List<Location> = this.map { it.toLocation() }

private fun isCurrentSdkVersionEqualOrGreaterThan(sdkCode: Int): Boolean =
    Build.VERSION.SDK_INT >= sdkCode

internal fun FixLocationExtras.toBundle(bundle: Bundle = Bundle()): Bundle = bundle.apply {
    forEach {
        when (val contents = it.value.contents) {
            is Double -> putDouble(it.key, contents)
            is Long -> putLong(it.key, contents)
            is Boolean -> putBoolean(it.key, contents)
            is String -> putString(it.key, contents)
            else -> {
                // do nothing
            }
        }
    }
}

internal fun Bundle.toMap(): FixLocationExtras {
    val map: FixLocationExtras = HashMap()
    val keySet = this.keySet()
    val iterator: Iterator<String> = keySet.iterator()
    while (iterator.hasNext()) {
        val key = iterator.next()
        when (val value = this.get(key)) {
            is Double -> map[key] = Value(value)
            is Long -> map[key] = Value(value)
            is Boolean -> map[key] = Value(value)
            is String -> map[key] = Value(value)
            else -> {
                // do nothing
            }
        }
    }
    return map
}
