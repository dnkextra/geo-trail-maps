package org.geotrailmaps.track

import android.content.Context
import android.content.Intent
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.geotrailmaps.data.Feature
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import java.io.File

/**
 * Records a GPS track. Lives for the whole process so recording carries on while you switch
 * maps or the screen is off; [RecordingService] feeds it locations. Every accepted point
 * and every pause/resume is appended to a file, so an unfinished track survives the app
 * being killed and comes back paused.
 */
object TrackRecorder {
    enum class Status { IDLE, RECORDING, PAUSED }

    data class State(
        val status: Status = Status.IDLE,
        val points: List<LatLon> = emptyList(),
        /** Per point, parallel to [points]; null where unknown. */
        val times: List<Long?> = emptyList(),
        val elevations: List<Double?> = emptyList(),
        /** Set when continuing a saved track: Stop updates that feature, on [continuingMapId]. */
        val continuingId: String? = null,
        val continuingMapId: String? = null,
        val distance: Double = 0.0,
        val ascent: Double = 0.0,
        val startedAt: Long = 0,
        /** Recording time before the current segment (pauses excluded). */
        val pastMillis: Long = 0,
        /** When the current recording segment began, while recording. */
        val resumedAt: Long = 0,
    ) {
        fun elapsedMillis(now: Long = System.currentTimeMillis()) =
            pastMillis + if (status == Status.RECORDING) (now - resumedAt).coerceAtLeast(0) else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var file: File? = null
    /** Bumped on each start, so a late cleanup can't delete a newer recording's log. */
    private var generation = 0
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var altitudeRef: Double? = null
    private var recovered = false

    /** Call once at startup; restores an unfinished track as paused. */
    fun init(context: Context) {
        val f = File(context.filesDir, "recording.csv")
        file = f
        if (!f.isFile || _state.value.status != Status.IDLE) return
        // A write cut short by the app being killed can leave a partial last line: end it, so
        // later appends start on a fresh line (replay skips the broken one).
        runCatching { if (f.length() > 0 && !f.readText().endsWith("\n")) f.appendText("\n") }
        runCatching { replay(f.readLines()) }
        recovered = _state.value.status != Status.IDLE
    }

    /** True once if [init] restored a track left over from a previous run. */
    fun takeRecoveredFlag(): Boolean = recovered.also { recovered = false }

    /** Starts a new track, or continues [from] (a saved track on map [mapId]) where it ended. */
    fun start(context: Context, from: Feature? = null, mapId: String? = null) {
        val now = System.currentTimeMillis()
        altitudeRef = null
        generation++
        var s = State(status = Status.RECORDING, startedAt = now, resumedAt = now, continuingId = from?.id, continuingMapId = from?.let { mapId })
        val log = StringBuilder("START,$now,${from?.id ?: ""},${s.continuingMapId ?: ""}\n")
        if (from != null) {
            // Carry the saved track over, so distance and time continue from it.
            val past = from.track?.resumeMillis ?: 0L
            s = s.copy(
                startedAt = from.track?.times?.firstOrNull { it != null } ?: from.createdAt,
                points = from.points,
                times = from.points.indices.map { from.track?.times?.getOrNull(it) },
                elevations = from.points.indices.map { from.track?.elevations?.getOrNull(it) },
                distance = from.length,
                ascent = from.track?.ascent ?: 0.0,
                pastMillis = past,
            )
            log.append("BASE,$past,${s.ascent},${s.startedAt}\n")
            from.points.forEachIndexed { i, p -> log.append("P,${p.lat},${p.lon},${s.elevations[i] ?: ""},${s.times[i] ?: ""}\n") }
            log.append("RESUME,$now\n")
        }
        _state.value = s
        file?.writeText(log.toString())
        startService(context)
    }

    fun pause(context: Context) {
        val s = _state.value
        if (s.status != Status.RECORDING) return
        val now = System.currentTimeMillis()
        _state.value = s.copy(status = Status.PAUSED, pastMillis = s.elapsedMillis(now))
        append("PAUSE,$now")
        stopService(context)
    }

    fun resume(context: Context) {
        val s = _state.value
        if (s.status != Status.PAUSED) return
        val now = System.currentTimeMillis()
        altitudeRef = null // don't count the climb made while paused
        _state.value = s.copy(status = Status.RECORDING, resumedAt = now)
        append("RESUME,$now")
        startService(context)
    }

    /**
     * Ends the recording and returns what was recorded. The recovery log is kept until
     * [clearLog] is called, i.e. once the track has been saved (or immediately on discard).
     */
    fun finish(context: Context): State {
        val s = _state.value
        val result = s.copy(status = Status.PAUSED, pastMillis = s.elapsedMillis())
        stopService(context)
        _state.value = State()
        return result
    }

    /** Deletes the recovery log now (discarded, or too short to save). */
    fun clearLog() {
        if (_state.value.status == Status.IDLE) file?.delete()
    }

    /**
     * Deletes the recovery log once [saved] (the write of the saved track) completes. Runs in an
     * app-wide scope, so leaving the screen doesn't cancel it, and does nothing if another
     * recording has started since.
     */
    fun clearLogAfter(saved: Deferred<Boolean>) {
        val gen = generation
        cleanupScope.launch {
            // Only once the track is really on disk; a failed write keeps the log for recovery.
            if (saved.await() && gen == generation) clearLog()
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

    internal fun onLocation(location: Location) {
        val s = _state.value
        if (s.status != Status.RECORDING) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val p = LatLon(location.latitude, location.longitude)
        val last = s.points.lastOrNull()
        val step = last?.let { Geodesy.distance(it, p) } ?: 0.0
        if (last != null && step < MIN_SPACING_M) return // standing still: ignore GPS jitter
        val alt = if (location.hasAltitude()) location.altitude else null
        _state.value = s.copy(
            points = s.points + p,
            times = s.times + location.time,
            elevations = s.elevations + alt,
            distance = s.distance + step,
            ascent = s.ascent + climb(alt),
        )
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
        // Points carried over from a saved track: their distance counts, their ascent is already in BASE.
        var basePoints = false
        for (line in lines) {
            val v = line.split(',')
            // A malformed line (e.g. cut short when the app was killed) is skipped, not fatal.
            try {
            when (v[0]) {
                "START" -> v[1].toLong().let {
                    points.clear(); times.clear(); elevations.clear()
                    s = State(
                        status = Status.RECORDING, startedAt = it, resumedAt = it,
                        continuingId = v.getOrNull(2)?.ifEmpty { null },
                        continuingMapId = v.getOrNull(3)?.ifEmpty { null },
                    )
                }
                "BASE" -> {
                    s = s.copy(pastMillis = v[1].toLong(), ascent = v[2].toDouble(), startedAt = v[3].toLong())
                    basePoints = true
                }
                "PAUSE" -> s = s.copy(status = Status.PAUSED, pastMillis = s.pastMillis + (v[1].toLong() - s.resumedAt).coerceAtLeast(0))
                "RESUME" -> {
                    s = s.copy(status = Status.RECORDING, resumedAt = v[1].toLong())
                    basePoints = false
                    altitudeRef = null
                }
                "P" -> {
                    val p = LatLon(v[1].toDouble(), v[2].toDouble())
                    val alt = v[3].toDoubleOrNull()
                    val time = v.getOrNull(4)?.toLongOrNull()
                    val step = points.lastOrNull()?.let { Geodesy.distance(it, p) } ?: 0.0
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
        s = s.copy(points = points, times = times, elevations = elevations)
        // The app died while recording: count time up to the last point, then come back paused.
        if (s.status == Status.RECORDING) {
            s = s.copy(status = Status.PAUSED, pastMillis = s.pastMillis + (maxOf(lastPointTime, s.resumedAt) - s.resumedAt))
            append("PAUSE,${maxOf(lastPointTime, s.resumedAt)}")
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

    private const val MAX_ACCURACY_M = 35f
    private const val MIN_SPACING_M = 5.0
}
