package org.geotrailmaps.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.track.TrackRecorder
import org.geotrailmaps.ui.theme.MapStyle
import kotlin.math.atan2

/** Where "Navigate" is taking you. */
data class NavTarget(val name: String, val position: LatLon)

/** Angle (degrees, screen space, 0 = right, 90 = down) from [from] towards [to] as drawn now. */
fun screenAngle(proj: MapProjection, viewport: MapViewport, from: LatLon, to: LatLon): Float {
    val a = viewport.viewToScreen(proj.toScreen(from))
    val b = viewport.viewToScreen(proj.toScreen(to))
    return Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
}

/** Banner while navigating: name, distance, bearing and an arrow pointing at the target on screen. */
@Composable
fun NavigationBanner(target: NavTarget, me: LatLon?, arrowAngle: Float?, units: Units, onStop: () -> Unit, modifier: Modifier = Modifier, locationOn: Boolean = true) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                // Navigation icon points up (-90°), so turn it by angle + 90.
                if (arrowAngle != null) {
                    Icon(Icons.Filled.Navigation, null, Modifier.size(30.dp).rotate(arrowAngle + 90f))
                } else {
                    Icon(Icons.Filled.GpsOff, null, Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(target.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (!locationOn) {
                    "Device location is off"
                } else if (me == null) {
                    "Waiting for GPS…"
                } else {
                    val d = Geodesy.distance(me, target.position)
                    val b = Geodesy.bearing(me, target.position)
                    if (d < 15) "You're there" else "${Formats.distance(d, units)} · ${b.toInt()}° ${Geodesy.compassPoint(b)}"
                }
                Text(detail, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "Stop navigating") }
        }
    }
}

fun DrawScope.drawNavigation(me: LatLon?, target: LatLon, proj: MapProjection) {
    val t = proj.toScreen(target)
    if (me != null) {
        drawLine(
            MapStyle.navigationLine, proj.toScreen(me), t, strokeWidth = 3f * density, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12 * density, 8 * density)),
        )
    }
    drawCircle(Color.White, 11f * density, t, style = Stroke(5f * density))
    drawCircle(MapStyle.navigationLine, 11f * density, t, style = Stroke(3f * density))
}

/** Marker for a searched coordinate. */
fun DrawScope.drawSearchMarker(p: LatLon, proj: MapProjection) {
    val c = proj.toScreen(p)
    drawCircle(Color.White, 9f * density, c, style = Stroke(5f * density))
    drawCircle(Color(0xFF6A1B9A), 9f * density, c, style = Stroke(3f * density))
    drawCircle(Color(0xFF6A1B9A), 2.5f * density, c)
}

/** Compact row in the bottom panel while a track records or is paused. */
@Composable
fun RecordingBar(
    state: TrackRecorder.State,
    units: Units,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    /** Opens the track's sheet when the bar's text is tapped; null for none. */
    onOpen: (() -> Unit)? = null,
) {
    val recording = state.status == TrackRecorder.Status.RECORDING
    // Ticks only this row, not the whole map screen.
    val now by produceState(System.currentTimeMillis(), recording) {
        while (recording) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.FiberManualRecord, null, Modifier.size(14.dp),
            tint = if (recording) MapStyle.recordingTrack else MaterialTheme.colorScheme.outline,
        )
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open track", onClick = onOpen) else Modifier),
            verticalArrangement = Arrangement.Center,
        ) {
            // Announced when it changes between recording and paused.
            Text(
                if (recording) "Recording" else "Paused",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                Formats.distance(state.distance, units) + " · " + Formats.duration(state.elapsedMillis(now)) +
                    if (state.ascent >= 1) " · ↑" + Formats.distance(state.ascent, units) else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPauseResume) {
            Icon(if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (recording) "Pause recording" else "Resume recording")
        }
        IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Stop recording") }
    }
}

/** Resuming this far from where the track was paused asks first (see [resumeGap]). */
private const val RESUME_GAP_M = 1_000.0

/**
 * How far [fix] is from where the paused [state] ends, if that's further than [RESUME_GAP_M];
 * null to resume without asking (also without a fix from the last two minutes).
 */
fun resumeGap(state: TrackRecorder.State, fix: Location?): Double? {
    val end = state.points.lastOrNull() ?: return null
    if (fix == null || SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > 120_000_000_000L) return null
    return Geodesy.distance(end, LatLon(fix.latitude, fix.longitude)).takeIf { it > RESUME_GAP_M }
}

/** Resuming far from where the track was paused: carry on with a gap, or save it ([saveLabel]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResumeGapDialog(distance: Double, units: Units, saveLabel: String, onResume: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume this track?") },
        text = {
            Text(
                "You're ${Formats.distance(distance, units)} from where it was paused. Resuming carries on the same " +
                    "track, without a line or distance for the gap. Or save it as it is.",
            )
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onSave) { Text(saveLabel) }
                TextButton(onClick = onResume) { Text("Resume") }
            }
        },
    )
}

@Composable
fun StopTrackDialog(
    state: TrackRecorder.State,
    units: Units,
    /** The track's name now; offered to change for a new track, which may still have its default name. */
    name: String,
    onStop: (name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop recording?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        state.points.size < 2 && state.isNew -> "No points recorded yet, so stopping removes this track."
                        else -> "${Formats.distance(state.distance, units)} in ${Formats.duration(state.elapsedMillis())}. " +
                            "It stays in Tracks, and you can carry on with it later."
                    },
                )
                if (state.isNew && state.points.size >= 2) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { Button(onClick = { onStop(newName.takeIf { state.isNew && it.trim() != name }) }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep recording") } },
    )
}

/**
 * Starting a new track, or continuing [continuing], while [current] is recording or paused:
 * that one is stopped first (it's already saved).
 */
@Composable
fun SwitchTrackDialog(current: String, continuing: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (continuing == null) "Start a new track?" else "Continue \"$continuing\"?") },
        text = { Text("\"$current\" is still being recorded. It will be stopped and kept in Tracks.") },
        confirmButton = { Button(onClick = onConfirm) { Text(if (continuing == null) "Stop and start" else "Stop and continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small recording indicator for when the bars are hidden. */
@Composable
fun RecordingChip(state: TrackRecorder.State, units: Units) {
    val recording = state.status == TrackRecorder.Status.RECORDING
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FiberManualRecord, null, Modifier.size(10.dp),
                tint = if (recording) MapStyle.recordingTrack else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                (if (recording) "" else "Paused · ") + Formats.distance(state.distance, units),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Shown while the map is rotated: points north; tap to go back to north-up. */
@Composable
fun CompassButton(northAngle: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Icon(Icons.Filled.Navigation, "Compass mode on. Tap for north up.", Modifier.rotate(northAngle + 90f), tint = Color(0xFFD32F2F))
    }
}
