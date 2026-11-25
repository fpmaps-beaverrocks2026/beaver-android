package com.youngmin.pobook

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt

internal class IndoorPositionSource(
    private val context: Context,
    private val onFix: (PositionFix?) -> Unit,
) : LocationListener, SensorEventListener {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var latestLocation: Location? = null
    private var heading: Float? = null
    private var pressureAltitude: Float? = null
    private var anchorAltitude: Float? = null
    private var confirmedFloor: Int? = null
    private var pendingMove: Int? = null
    private var moveCount = 0
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull(Location::getElapsedRealtimeNanos)?.let(::onLocationChanged)
        for (provider in providers) {
            runCatching { locationManager.requestLocationUpdates(provider, 1200L, 1.0f, this, Looper.getMainLooper()) }
        }
        for (type in listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_ROTATION_VECTOR)) {
            sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
        sensors.unregisterListener(this)
        latestLocation = null
        heading = null
        pressureAltitude = null
        anchorAltitude = null
        confirmedFloor = null
        pendingMove = null
        moveCount = 0
        emit()
    }

    fun confirmFloor(floor: Int) {
        require(floor in 1..5)
        confirmedFloor = floor
        anchorAltitude = pressureAltitude
        pendingMove = null
        moveCount = 0
        emit()
    }

    override fun onLocationChanged(location: Location) {
        val previous = latestLocation
        if (previous != null && location.elapsedRealtimeNanos < previous.elapsedRealtimeNanos) return
        if (previous != null && location.provider == LocationManager.NETWORK_PROVIDER &&
            location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos < 8_000_000_000L &&
            location.accuracy > previous.accuracy * 2f) return
        latestLocation = location
        emit()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val next = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
                if (!next.isFinite()) return
                pressureAltitude = pressureAltitude?.let { it * 0.75f + next * 0.25f } ?: next
                val altitude = requireNotNull(pressureAltitude)
                val floor = confirmedFloor ?: return
                val anchor = anchorAltitude ?: run { anchorAltitude = altitude; return }
                val difference = altitude - anchor
                if (abs(difference) < 4.26f * 0.6f) {
                    pendingMove = null
                    moveCount = 0
                    return
                }
                val delta = (difference / 4.26f).roundToInt()
                val estimate = (floor + delta).coerceIn(1, 5)
                if (estimate == pendingMove) moveCount++ else { pendingMove = estimate; moveCount = 1 }
                if (moveCount >= 2) {
                    confirmedFloor = estimate
                    anchorAltitude = anchor + (estimate - floor) * 4.26f
                    pendingMove = null
                    moveCount = 0
                    emit()
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val next = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (heading?.let { abs(next - it) < 10f } == true) return
                heading = next
                emit()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emit() {
        val location = latestLocation
        onFix(location?.let {
            PositionFix(MapPoint.geographic(it.longitude, it.latitude), confirmedFloor, it.accuracy,
                it.elapsedRealtimeNanos / 1_000_000L, heading ?: it.bearing.takeIf { _ -> it.hasBearing() })
        })
    }
}
