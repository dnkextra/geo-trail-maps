package org.geotrailmaps.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * GPS position and compass heading. Uses the platform LocationManager directly, so it
 * works without Google Play Services and fully offline.
 */
class LocationTracker(private val context: Context) : LocationListener, SensorEventListener {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    /** Degrees clockwise from true north, null when no compass is available. */
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading

    private var running = false
    private var declination = 0f

    /** Caller must hold a location permission (precise or approximate). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        try {
            startLocationUpdates()
        } catch (e: SecurityException) {
            // Approximate-only permission and no fused provider; compass still works.
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val provider = if (locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        // Show the previous fix only if it's recent; an old one would put you in the wrong place.
        _location.value = (
            listOfNotNull(_location.value) +
                listOf(provider, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            )
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_FIX_AGE_NANOS }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val request = LocationRequest.Builder(1000)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        locationManager.requestLocationUpdates(provider, request, context.mainExecutor, this)
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
    }

    override fun onLocationChanged(location: Location) {
        _location.value = location
        declination = GeomagneticField(
            location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat(), location.time,
        ).declination
    }

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Keep the heading relative to the top of the screen in any orientation.
        val (x, y) = when (display?.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val heading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + declination) + 360f) % 360f
        val previous = _heading.value
        if (previous == null) {
            _heading.value = heading
            return
        }
        // Low-pass filter that handles the 359 -> 0 wrap.
        val delta = ((heading - previous + 540f) % 360f) - 180f
        if (abs(delta) > 0.5f) _heading.value = (previous + delta * 0.2f + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val MAX_FIX_AGE_NANOS = 2 * 60 * 1_000_000_000L
    }
}
