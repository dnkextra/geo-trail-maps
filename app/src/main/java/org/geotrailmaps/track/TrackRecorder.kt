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
import java.util.UUID

/**
 * Records a GPS track. Lives for the whole process so recording carries on while you switch
 * maps or the screen is off; [RecordingService] feeds it locations. Every accepted point
 * and every pause/resume is appended to a file, so an unfinished track survives the app
 * being killed and comes back paused.
 *
 * Tracks belong to no map. A new track is added to the global "Recorded tracks" layer as soon as
 * it starts (or records into the track continued), so it's listed, edited, hidden and deleted
 * like any other track while it records. Its points are written into it every so often, on
 * pause, and on [stop]. So recording works the same from any map, the list, or the notification.
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
        /** The saved track this records into: added when it started, or the one continued. */
        val trackId: String? = null,
        /** Added by this recording (not continued), so Stop offers to name it, and drops it if it's empty. */
        val isNew: Boolean = false,
        val distance: Double = 0.0,
        val ascent: Double = 0.0,
        val startedAt: Long = 0,
        /** Recording time before the current segment (pauses excluded). */
        val pastMillis: Long = 0,
        /** When the current recording segment began, while recording. */
        val resumedAt: Long = 0,
        /** When it was paused, while paused. */
        val pausedAt: Long = 0,
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
     * Whether the track has been in the global layers since this recording started: once it
     * has, its going means it was deleted, and the recording stops.
     */
    private var stored = false
    /** Points in the stored track, and when it was last written. */
    private var flushedPoints = 0
    private var flushedAt = 0L
    private var watching = false

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
        if (recovered) {
            PausedNotification.show(context.applicationContext)
            // After startup, so the library isn't loaded before the first screen needs it.
            cleanupScope.launch { watch(context.applicationContext) }
        }
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
     * Starts a new track (added to Recorded tracks now), or continues [from] (a saved track in a
     * global layer) where it ended. False (and nothing starts) while the previous track is still
     * being saved.
     */
    fun start(context: Context, from: Feature? = null): Boolean {
        if (saving.value) return false
        val now = System.currentTimeMillis()
        altitudeRef = null
        generation++
        recordedOn.clear()
        gap = from != null
        val id = from?.id ?: UUID.randomUUID().toString()
        var s = State(status = Status.RECORDING, startedAt = now, resumedAt = now, trackId = id, isNew = from == null)
        val log = StringBuilder("START,$now,$id,${if (from == null) "new" else ""}\n")
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
        val store = library.value
        val g = store.global.value
        if (from == null) {
            val layer = tracksLayer(g)
            store.updateGlobal(notify = false) { d ->
                d.copy(
                    layers = if (d.layers.any { it.id == layer.id }) d.layers else listOf(layer) + d.layers,
                    features = d.features + Feature(
                        id = id, type = FeatureType.LINE, name = defaultName(now), points = emptyList(), color = layer.color,
                        createdAt = now, layerId = layer.id, track = TrackData(emptyList(), emptyList()),
                    ),
                )
            }
        }
        // A track in a layer kept with one map isn't global until that layer moves (just after this).
        stored = g.features.any { it.id == id } || from == null
        flushedPoints = s.points.size
        flushedAt = now
        _state.value = s
        file?.writeText(log.toString())
        PausedNotification.cancel(context)
        startService(context)
        watch(context.applicationContext)
        return true
    }

    private fun tracksLayer(g: org.geotrailmaps.data.MapFeatures) = g.layers.find { it.kind == LayerKind.TRACKS }
        ?: FeatureLayer(name = "Recorded tracks", color = MapStyle.recordingTrack.toArgb(), kind = LayerKind.TRACKS)

    private fun defaultName(startedAt: Long) = "Track " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(startedAt))

    private fun trackData(s: State) = TrackData(s.times, s.elevations, s.elapsedMillis(), s.ascent, s.segmentStarts)

    /**
     * Writes the points so far into the stored track. A track in a layer that hasn't moved to the
     * global layers yet waits; one from a log older than this has its track added now. Main thread only.
     */
    private fun flush() {
        val s = _state.value
        if (s.status == Status.IDLE) return
        val store = library.value
        val g = store.global.value
        val id = s.trackId
        if (id == null || !stored && s.isNew && g.features.none { it.id == id }) {
            // Recovered from an older log, which saved the track only at Stop: add it now.
            val layer = tracksLayer(g)
            val newId = id ?: UUID.randomUUID().toString()
            store.updateGlobal(notify = false) { d ->
                d.copy(
                    layers = if (d.layers.any { it.id == layer.id }) d.layers else listOf(layer) + d.layers,
                    features = d.features + Feature(
                        id = newId, type = FeatureType.LINE, name = defaultName(s.startedAt), points = s.points, color = layer.color,
                        createdAt = s.startedAt, layerId = layer.id, track = trackData(s),
                    ),
                )
            }
            if (id == null) append("LINK,$newId")
            _state.value = s.copy(trackId = newId)
            stored = true
        } else if (g.features.any { it.id == id }) {
            store.updateGlobal(notify = false) { d -> d.withFeature(id) { it.copy(points = s.points, track = trackData(s)) } }
        } else {
            return // not global yet, or deleted (then [watch] stops the recording)
        }
        flushedPoints = s.points.size
        flushedAt = System.currentTimeMillis()
    }

    /**
     * Follows the global layers for the rest of the process: the track being recorded is
     * marked stored once it's there, and the recording ends (without saving) if it's deleted.
     */
    private fun watch(app: Context) {
        if (watching) return
        watching = true
        cleanupScope.launch {
            library.value.global.collect { g ->
                val s = _state.value
                val id = s.trackId ?: return@collect
                if (s.status == Status.IDLE || saving.value) return@collect
                if (g.features.any { it.id == id }) stored = true
                else if (stored) discard(app)
            }
        }
    }

    fun pause(context: Context) {
        val s = _state.value
        if (s.status != Status.RECORDING) return
        val now = System.currentTimeMillis()
        _state.value = s.copy(status = Status.PAUSED, pastMillis = s.elapsedMillis(now), pausedAt = now)
        append("PAUSE,$now")
        stopService(context)
        PausedNotification.show(context)
        flush()
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
     * Ends the recording and writes the last of it into its track, named [name] if given.
     * Returns the track, or null if it had too few points to keep: then a new track is removed
     * (a continued one stays as it was). If the track has gone from the global layers (a layer
     * that never became global), it's saved as a new track in Recorded tracks. Main thread only.
     */
    fun stop(context: Context, name: String? = null): Feature? {
        // Already stopped (e.g. a second tap): don't touch the log of the save in progress.
        if (_state.value.status == Status.IDLE) return null
        val track = finish(context)
        val store = library.value
        val g = store.global.value
        val existing = track.trackId?.let { id -> g.features.find { it.id == id } }
        if (track.points.size < 2) {
            if (track.isNew && existing != null) store.updateGlobal { it.removeFeature(existing.id) }
            clearLog()
            return null
        }
        val layer = tracksLayer(g)
        val saved = (existing?.copy(points = track.points, track = trackData(track)) ?: Feature(
            type = FeatureType.LINE,
            name = defaultName(track.startedAt),
            points = track.points,
            color = layer.color,
            createdAt = track.startedAt,
            layerId = layer.id,
            track = trackData(track),
        )).let { f -> name?.trim()?.takeIf { it.isNotEmpty() }?.let { f.copy(name = it) } ?: f }
        val job = store.updateGlobal(notify = false) { d ->
            if (existing != null) d.withFeature(saved.id) { saved }
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
            _state.value = track.copy(status = Status.PAUSED, trackId = featureId, pausedAt = System.currentTimeMillis())
            stored = true
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
        if (_state.value.points.size - flushedPoints >= FLUSH_POINTS || System.currentTimeMillis() - flushedAt >= FLUSH_MS) flush()
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
                        trackId = v.getOrNull(2)?.ifEmpty { null }, isNew = v.getOrNull(3) == "new" || v.getOrNull(2).isNullOrEmpty(),
                    )
                    // Logs since tracks were added at Start: it was written then.
                    stored = s.trackId != null
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
                "LINK" -> s = s.copy(trackId = v[1].ifEmpty { null }).also { stored = true }
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
    /** The stored track is brought up to date after this many new points, or this long. */
    private const val FLUSH_POINTS = 50
    private const val FLUSH_MS = 60_000L
}
