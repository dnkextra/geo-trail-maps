package org.geotrailmaps.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.geotrailmaps.MainActivity
import org.geotrailmaps.R
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Settings

internal const val RECORDING_CHANNEL = "recording"

/** The recording notification's channel; quiet, since it's updated as the track grows. */
internal fun Context.recordingChannel() {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(RECORDING_CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW),
    )
}

/** A notification about the track: tapping it opens the app; [actions] are Pause and/or Stop. */
internal fun Context.trackNotification(title: String, ongoing: Boolean, vararg actions: String): Notification {
    val s = TrackRecorder.state.value
    val units = Settings(this).units.value
    val open = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(this, RECORDING_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_track)
        .setContentTitle(title)
        .setContentText(Formats.distance(s.distance, units) + " · " + Formats.duration(s.elapsedMillis()))
        .setContentIntent(open)
        .setOngoing(ongoing)
        // The recording notification (and its Stop) shows at once, not up to 10 s later.
        .apply { if (ongoing) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
        .apply {
            for (action in actions) {
                val intent = PendingIntent.getBroadcast(
                    this@trackNotification, action.hashCode(),
                    Intent(this@trackNotification, RecordingActionReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val label = if (action == RecordingActionReceiver.PAUSE) "Pause" else "Stop and save"
                addAction(Notification.Action.Builder(null, label, intent).build())
            }
        }
        .build()
}

/**
 * Shown while a track is paused, so one left paused isn't forgotten (the recording service,
 * and its notification, stop while paused). Resuming is done in the app.
 */
internal object PausedNotification {
    private const val ID = 2

    fun show(context: Context) {
        context.recordingChannel()
        // Without notification permission this does nothing, as for the recording notification.
        context.getSystemService(NotificationManager::class.java)
            .notify(ID, context.trackNotification("Track paused", ongoing = false, RecordingActionReceiver.STOP))
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}

/** Pause and Stop from the notification. Stop saves the track, as in the app. */
class RecordingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                // Let fixes batched while the screen was off arrive first.
                if (TrackRecorder.state.value.status == TrackRecorder.Status.RECORDING) TrackRecorder.awaitPendingFixes()
                when (intent.action) {
                    PAUSE -> TrackRecorder.pause(app)
                    STOP -> if (TrackRecorder.state.value.status != TrackRecorder.Status.IDLE) {
                        val saved = TrackRecorder.stop(app)
                        // The app may be closed: keep the process alive until it's written. A failed
                        // save comes back paused, and the recorder says so.
                        TrackRecorder.awaitSaved()
                        if (saved == null) Toast.makeText(app, "The track was too short to save.", Toast.LENGTH_LONG).show()
                        else if (TrackRecorder.state.value.status == TrackRecorder.Status.IDLE) {
                            Toast.makeText(app, "Saved \"${saved.name}\" in Tracks.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE = "org.geotrailmaps.PAUSE_TRACK"
        const val STOP = "org.geotrailmaps.STOP_TRACK"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
