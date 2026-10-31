package org.geotrailmaps.track

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import android.os.SystemClock
import org.geotrailmaps.MainActivity
import org.geotrailmaps.R
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Settings

/**
 * Foreground service that keeps GPS running while a track records, including with the
 * screen off. All state lives in [TrackRecorder]; this only forwards locations.
 */
class RecordingService : Service(), LocationListener {
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var lastNotified = 0L
    private val units by lazy { Settings(this).units.value }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            screenReceiverRegistered = true
        }
        requestUpdates(batched = !getSystemService(PowerManager::class.java).isInteractive)
        return START_NOT_STICKY
    }

    private var screenReceiverRegistered = false
    private var batched = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val off = intent.action == Intent.ACTION_SCREEN_OFF
            if (!off && batched) {
                // removeUpdates would drop fixes still held in the batch: deliver them first,
                // then switch (onFlushComplete), with a fallback in case the flush never reports.
                TrackRecorder.flushPending.value = true
                locationManager.requestFlush(provider, this@RecordingService, FLUSH_SCREEN_ON)
                handler.postDelayed(flushFallback, 5_000)
            } else {
                requestUpdates(batched = off)
            }
        }
    }
    private val handler by lazy { android.os.Handler(mainLooper) }
    private val flushFallback = Runnable { if (batched) requestUpdates(batched = false) }

    override fun onFlushComplete(requestCode: Int) {
        if (requestCode == FLUSH_SCREEN_ON && batched) {
            handler.removeCallbacks(flushFallback)
            requestUpdates(batched = false)
        }
    }

    private val provider: String
        get() = if (locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

    /**
     * GPS stays on either way (that's most of the battery cost). With the screen off, fixes are
     * delivered in batches (each keeps its own time), so the CPU isn't woken every 2 s; with it
     * on they arrive immediately for the live track.
     */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(batched: Boolean) {
        this.batched = batched
        if (!batched) TrackRecorder.flushPending.value = false
        val request = LocationRequest.Builder(2_000)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMaxUpdateDelayMillis(if (batched) 30_000 else 0)
            .build()
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(provider, request, mainExecutor, this)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        TrackRecorder.onLocation(location)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified > 15_000) {
            lastNotified = now
            notifications.notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(flushFallback)
        TrackRecorder.flushPending.value = false
        locationManager.removeUpdates(this)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val s = TrackRecorder.state.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_track)
            .setContentTitle("Recording track")
            .setContentText(Formats.distance(s.distance, units) + " · " + Formats.duration(s.elapsedMillis()))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
        const val FLUSH_SCREEN_ON = 1
    }
}
