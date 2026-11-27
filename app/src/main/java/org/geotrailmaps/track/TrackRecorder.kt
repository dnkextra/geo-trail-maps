package org.geotrailmaps.track

import android.content.Context
import android.content.Intent
import android.location.Location
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.LayerKind
import org.geotrailmaps.data.MapLibrary
import org.geotrailmaps.data.TrackData
import org.geotrailmaps.ui.theme.MapStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import java.io.File
import java.util.Base64

/**
 * Records a GPS track. Lives for the whole process so recording carries on while you switch
 * maps or the screen is off; [RecordingService] feeds it locations. Every accepted point
 * and every pause/resume is appended to a file, so an unfinished track survives the app
 * being killed and comes back paused.
 *
 * Tracks belong to no map: [stop] saves them in the global "Recorded tracks" layer (or back
 * into the track continued), which every map they overlap shows. So recording works the
 * same from any map, the list, or the notification.
 */
object TrackRecorder {
    enum class Status { IDLE, RECORDING, PAUSED }

    data class State(
        val status: Status = Status.IDLE,
        val points: List<LatLon> = emptyList(),
        /** Per point, parallel to [points]; null where unknown. */
        val times: List<Long?> = emptyList(),
        val elevations: List<Double?> = emptyList(),
        /** Indices of points that begin a new segment, as [org.geotrailmaps.data.TrackData.segmentStarts]. */
        val segmentStarts: List<Int> = emptyList(),
        /** Set when continuing a saved track (in a global layer): Stop updates that feature. */
        val continuingId: String? = null,
        val distance: Double = 0.0,
        val ascent: Double = 0.0,
        val startedAt: Long = 0,
        /** Recording time before the current segment (pauses excluded). */
        val pastMillis: Long = 0,
        /** When the current recording segment began, while recording. */
        val resumedAt: Long = 0,
        /** When it was paused, while paused. */
        val pausedAt: Long = 0,
        /** What it's saved with: set when it starts (from the track continued), and changeable while it records. */
        val name: String = "",
        val notes: String = "",
        val color: Int = MapStyle.recordingTrack.toArgb(),
        /** As [Feature.opacity]: null for the default. */
        val opacity: Float? = null,
        /** Whether it's drawn on the map; it records either way. */
        val visible: Boolean = true,
        /**
         * False only for a track recovered from a log written before tracks had these: then Stop
         * keeps the continued track's own name and look.
         */
        val styled: Boolean = false,
    ) {
        fun elapsedMillis(now: Long = System.currentTimeMillis()) =
            pastMillis + if (status == Status.RECORDING) (now - resumedAt).coerceAtLeast(0) else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var file: File? = null
    private lateinit var library: Lazy<MapLibrary>
    /** Bumped on each start, so a late cleanup can't delete a newer recording's log. */
    private var generation = 0
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var altitudeRef: Double? = null
    private var recovered = false
    /**
     * After a resume, or continuing a saved track: the next fix starts a new segment unless
     * it's where the track left off, so travel while not recording isn't counted or drawn.
     */
    private var gap = false

    /**
     * Call once at startup; restores an unfinished track as paused. [library] is only needed
     * to save, so starting the app doesn't wait for it to load.
     */
    fun init(context: Context, library: Lazy<MapLibrary>) {
        this.library = library
        val f = File(context.filesDir, "recording.csv")
        file = f
        if (!f.isFile || _state.value.status != Status.IDLE) return
        // A write cut short by the app being killed can leave a partial last line: end it, so
        // later appends start on a fresh line (replay skips the broken one).
        val text = runCatching { f.readText() }.getOrNull() ?: return
        if (text.isNotEmpty() && !text.endsWith("\n")) runCatching { f.appendText("\n") }
        runCatching { replay(text.lines()) }
        recovered = _state.value.status != Status.IDLE
        if (recovered) PausedNotification.show(context.applicationContext)
    }

    /**
     * Maps that were open while this track recorded, by id: once paused, its controls stay on
     * them even where it has no points. For display only; not kept across restarts.
     */
    val recordedOn = mutableSetOf<String>()

    /** True once if [init] restored a track left over from a previous run. */
    fun takeRecoveredFlag(): Boolean = recovered.also { recovered = false }

    /** True while the last track's save is being written; its log mustn't be replaced yet. */
    private val saving = MutableStateFlow(false)

    /** Waits until the last track's save has been written (or failed: then it's back, paused). */
    suspend fun awaitSaved() {
        saving.first { !it }
    }

    /**
     * Starts a new track, or continues [from] (a saved track in a global layer) where it ended.
     * False (and nothing starts) while the previous track is still being saved.
     */
    fun start(context: Context, from: Feature? = null): Boolean {
        if (saving.value) return false
        val now = System.currentTimeMillis()
        altitudeRef = null
        generation++
        recordedOn.clear()
        gap = from != null
        var s = State(
            status = Status.RECORDING, startedAt = now, resumedAt = now, continuingId = from?.id,
            name = from?.name ?: defaultName(now), notes = from?.description ?: "",
            color = from?.color ?: MapStyle.recordingTrack.toArgb(), opacity = from?.opacity, styled = true,
        )
        val log = StringBuilder("START,$now,${from?.id ?: ""}\n${styleLine(s)}\n")
        if (from != null) {
            // Carry the saved track over, so distance and time continue from it.
            val past = from.track?.resumeMillis ?: 0L
            s = s.copy(
                startedAt = from.track?.times?.firstOrNull { it != null } ?: from.createdAt,
                points = from.points,
                times = from.points.indices.map { from.track?.times?.getOrNull(it) },
                elevations = from.points.indices.map { from.track?.elevations?.getOrNull(it) },
                segmentStarts = from.track?.segmentStarts(from.points.size).orEmpty(),
                distance = from.length,
                ascent = from.track?.ascent ?: 0.0,
                pastMillis = past,
            )
            log.append("BASE,$past,${s.ascent},${s.startedAt}\n")
            from.points.forEachIndexed { i, p ->
                if (i in s.segmentStarts) log.append("SEG\n")
                log.append("P,${p.lat},${p.lon},${s.elevations[i] ?: ""},${s.times[i] ?: ""}\n") }
            log.append("RESUME,$now\n")
        }
        _state.value = s
        file?.writeText(log.toString())
        PausedNotification.cancel(context)
        startService(context)
        return true
    }

    /** Changes what the track being recorded is saved with, and whether it's drawn. */
    fun edit(name: String, notes: String, color: Int, opacity: Float?, visible: Boolean) {
        val s = _state.value
        if (s.status == Status.IDLE) return
        val edited = s.copy(name = name, notes = notes, color = color, opacity = opacity, visible = visible, styled = true)
        if (edited == s) return
        _state.value = edited
        append(styleLine(edited))
    }

    fun setVisible(visible: Boolean) = _state.value.let { edit(it.name, it.notes, it.color, it.opacity, visible) }

    // Name and notes in Base64, so commas and line breaks in them can't break the log's lines.
    private fun styleLine(s: State) = listOf(
        "STYLE", b64(s.name), b64(s.notes), s.color.toString(), s.opacity?.toString() ?: "", if (s.visible) "1" else "0",
    ).joinToString(",")

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    private fun unb64(text: String) = String(Base64.getDecoder().decode(text))

    private fun defaultName(startedAt: Long) = "Track " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(startedAt))

    fun pause(context: Context) {
        val s = _state.value
        if (s.status != Status.RECORDING) return
        val now = System.currentTimeMillis()
        _state.value = s.copy(status = Status.PAUSED, pastMillis = s.elapsedMillis(now), pausedAt = now)
        append("PAUSE,$now")
        stopService(context)
        PausedNotification.show(context)
    }

    fun resume(context: Context) {
        val s = _state.value
        if (s.status != Status.PAUSED) return
        val now = System.currentTimeMillis()
        altitudeRef = null // don't count the climb made while paused
        gap = true
        _state.value = s.copy(status = Status.RECORDING, resumedAt = now)
        append("RESUME,$now")
        PausedNotification.cancel(context)
        startService(context)
    }

    /**
     * Ends the recording and returns what was recorded. The recovery log is kept until
     * [clearLog] is called, i.e. once the track has been saved (or immediately on discard).
     */
    fun finish(context: Context): State {
        val s = _state.value
        val now = System.currentTimeMillis()
        val result = s.copy(status = Status.PAUSED, pastMillis = s.elapsedMillis(now))
        // Kept in the log, so if the save fails the recovered track's time stops here too.
        if (s.status == Status.RECORDING) append("PAUSE,$now")
        stopService(context)
        PausedNotification.cancel(context)
        _state.value = State()
        return result
    }

    /**
     * Ends the recording and saves it: into the track it continued, if that still exists,
     * else as a new track in the global "Recorded tracks" layer. Returns the saved track, or
     * null if it was too short to save (and is discarded). Main thread only.
     */
    fun stop(context: Context): Feature? {
        // Already stopped (e.g. a second tap): don't touch the log of the save in progress.
        if (_state.value.status == Status.IDLE) return null
        val track = finish(context)
        if (track.points.size < 2) {
            clearLog()
            return null
        }
        val data = TrackData(track.times, track.elevations, track.elapsedMillis(), track.ascent, track.segmentStarts)
        val store = library.value
        val g = store.global.value
        val continued = g.features.find { it.id == track.continuingId }
        val layer = g.layers.find { it.kind == LayerKind.TRACKS }
            ?: FeatureLayer(name = "Recorded tracks", color = MapStyle.recordingTrack.toArgb(), kind = LayerKind.TRACKS)
        val saved = when {
            continued == null -> Feature(
                type = FeatureType.LINE,
                name = track.name.ifBlank { defaultName(track.startedAt) },
                description = track.notes,
                points = track.points,
                color = if (track.styled) track.color else layer.color,
                layerId = layer.id,
                track = data,
                opacity = track.opacity,
                visible = track.visible,
            )
            track.styled -> continued.copy(
                points = track.points, track = data, name = track.name.ifBlank { continued.name }, description = track.notes,
                color = track.color, opacity = track.opacity, visible = track.visible,
            )
            else -> continued.copy(points = track.points, track = data, visible = true)
        }
        val job = store.updateGlobal(notify = false) { d ->
            if (continued != null) d.withFeature(saved.id) { saved }
            else d.copy(layers = if (d.layers.any { it.id == layer.id }) d.layers else listOf(layer) + d.layers, features = d.features + saved)
        }
        clearLogAfter(job, context, track, saved.id)
        return saved
    }

    /** Ends the recording without saving it, and deletes its recovery log. */
    fun discard(context: Context) {
        finish(context)
        clearLog()
    }

    /** Deletes the recovery log now (discarded, or too short to save). */
    private fun clearLog() {
        if (_state.value.status == Status.IDLE) file?.delete()
    }

    /**
     * Deletes the recovery log once [saved] (the write of [track] as feature [featureId])
     * completes. Runs in an app-wide scope, so leaving the screen doesn't cancel it, and
     * does nothing if another recording has started since.
     *
     * If the write fails (e.g. storage full), the track comes back paused, set to update that
     * feature, so stopping it again retries the save without making a duplicate.
     */
    private fun clearLogAfter(saved: Deferred<Boolean>, context: Context, track: State, featureId: String) {
        val gen = generation
        val app = context.applicationContext
        saving.value = true
        cleanupScope.launch {
            val ok = try { saved.await() } finally { saving.value = false }
            if (gen != generation || _state.value.status != Status.IDLE) return@launch
            if (ok) return@launch clearLog()
            _state.value = track.copy(status = Status.PAUSED, continuingId = featureId, pausedAt = System.currentTimeMillis())
            gap = true
            // The log is kept for recovery; point it at the feature too.
            append("LINK,$featureId")
            PausedNotification.show(app)
            Toast.makeText(app, "The track wasn't saved, so it's paused. Storage may be full: free some space, then stop it again.", Toast.LENGTH_LONG).show()
        }
    }

    /** True while fixes batched with the screen off are still being delivered. */
    internal val flushPending = MutableStateFlow(false)

    /**
     * Waits for batched fixes still on their way, so pausing or saving keeps them. The service
     * clears the flag within 5 s even if the flush never reports, so this normally can't time
     * out; the limit is only a safety net.
     */
    suspend fun awaitPendingFixes() {
        withTimeoutOrNull(6_000) { flushPending.first { !it } }
    }

    /** [maxAccuracyM] is the Settings choice ([org.geotrailmaps.data.TrackAccuracy]): less accurate fixes are skipped, null keeps all. */
    internal fun onLocation(location: Location, maxAccuracyM: Float?) {
        val s = _state.value
        if (s.status != Status.RECORDING) return
        if (maxAccuracyM != null && location.hasAccuracy() && location.accuracy > maxAccuracyM) return
        val p = LatLon(location.latitude, location.longitude)
        val last = s.points.lastOrNull()
        val step = last?.let { Geodesy.distance(it, p) } ?: 0.0
        if (last != null && step < MIN_SPACING_M) {
            gap = false // resumed where it left off: carry on the same segment
            return // standing still: ignore GPS jitter
        }
        val newSegment = gap && last != null
        gap = false
        val alt = if (location.hasAltitude()) location.altitude else null
        _state.value = s.copy(
            points = s.points + p,
            times = s.times + location.time,
            elevations = s.elevations + alt,
            segmentStarts = if (newSegment) s.segmentStarts + s.points.size else s.segmentStarts,
            distance = s.distance + if (newSegment) 0.0 else step,
            ascent = s.ascent + climb(alt),
        )
        if (newSegment) append("SEG")
        append("P,${p.lat},${p.lon},${alt ?: ""},${location.time}")
    }

    /** Ascent with a 3 m dead band, so altitude noise doesn't add up. */
    private fun climb(alt: Double?): Double {
        if (alt == null) return 0.0
        val ref = altitudeRef ?: run { altitudeRef = alt; return 0.0 }
        return when {
            alt > ref + 3 -> { altitudeRef = alt; alt - ref }
            alt < ref - 3 -> { altitudeRef = alt; 0.0 }
            else -> 0.0
        }
    }

    private fun replay(lines: List<String>) {
        var s = State()
        var lastPointTime = 0L
        // Built in mutable lists and published once: copying per point would be quadratic.
        val points = ArrayList<LatLon>()
        val times = ArrayList<Long?>()
        val elevations = ArrayList<Double?>()
        val segmentStarts = ArrayList<Int>()
        var newSegment = false
        // Points carried over from a saved track: their distance counts, their ascent is already in BASE.
        var basePoints = false
        for (line in lines) {
            val v = line.split(',')
            // A malformed line (e.g. cut short when the app was killed) is skipped, not fatal.
            try {
            when (v[0]) {
                "START" -> v[1].toLong().let {
                    points.clear(); times.clear(); elevations.clear(); segmentStarts.clear()
                    s = State(
                        status = Status.RECORDING, startedAt = it, resumedAt = it,
                        continuingId = v.getOrNull(2)?.ifEmpty { null }, name = defaultName(it),
                    )
                }
                "BASE" -> {
                    s = s.copy(pastMillis = v[1].toLong(), ascent = v[2].toDouble(), startedAt = v[3].toLong())
                    basePoints = true
                }
                "PAUSE" -> s = s.copy(status = Status.PAUSED, pastMillis = s.pastMillis + (v[1].toLong() - s.resumedAt).coerceAtLeast(0), pausedAt = v[1].toLong())
                "RESUME" -> {
                    s = s.copy(status = Status.RECORDING, resumedAt = v[1].toLong())
                    basePoints = false
                    altitudeRef = null
                }
                "SEG" -> newSegment = true
                "LINK" -> s = s.copy(continuingId = v[1].ifEmpty { null })
                "STYLE" -> s = s.copy(
                    name = unb64(v[1]), notes = unb64(v[2]), color = v[3].toInt(),
                    opacity = v[4].toFloatOrNull(), visible = v[5] != "0", styled = true,
                )
                "P" -> {
                    val p = LatLon(v[1].toDouble(), v[2].toDouble())
                    val alt = v[3].toDoubleOrNull()
                    val time = v.getOrNull(4)?.toLongOrNull()
                    if (newSegment && points.isNotEmpty()) segmentStarts += points.size
                    val step = if (newSegment) 0.0 else points.lastOrNull()?.let { Geodesy.distance(it, p) } ?: 0.0
                    newSegment = false
                    val gained = if (basePoints) 0.0 else climb(alt)
                    points += p
                    times += time
                    elevations += alt
                    s = s.copy(distance = s.distance + step, ascent = s.ascent + gained)
                    if (!basePoints) lastPointTime = time ?: lastPointTime
                }
            }
            } catch (e: RuntimeException) {
                continue
            }
        }
        if (s.startedAt == 0L) return
        s = s.copy(points = points, times = times, elevations = elevations, segmentStarts = segmentStarts)
        // The app died while recording: count time up to the last point, then come back paused.
        if (s.status == Status.RECORDING) {
            val end = maxOf(lastPointTime, s.resumedAt)
            s = s.copy(status = Status.PAUSED, pastMillis = s.pastMillis + (end - s.resumedAt), pausedAt = end)
            append("PAUSE,$end")
        }
        altitudeRef = null
        _state.value = s
    }

    private fun append(line: String) {
        runCatching { file?.appendText(line + "\n") }
    }

    private fun startService(context: Context) {
        context.startForegroundService(Intent(context, RecordingService::class.java))
    }

    private fun stopService(context: Context) {
        context.stopService(Intent(context, RecordingService::class.java))
    }

    private const val MIN_SPACING_M = 5.0
}
