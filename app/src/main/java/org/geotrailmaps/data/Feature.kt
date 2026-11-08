package org.geotrailmaps.data

import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import java.util.UUID

enum class FeatureType { POINT, LINE, AREA }

/** A placemark, line or area drawn on a map. Stored as lat/lon so it is independent of the PDF. */
data class Feature(
    val id: String = UUID.randomUUID().toString(),
    val type: FeatureType,
    val name: String,
    val description: String = "",
    val points: List<LatLon>,
    /** ARGB colour. */
    val color: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** The layer this belongs to; null for features drawn in the app ("My features"). */
    val layerId: String? = null,
    /** Per-point times and elevations for recorded or imported tracks. */
    val track: TrackData? = null,
    /** Hidden features aren't drawn or tappable (their layer can also be hidden). */
    val visible: Boolean = true,
    /**
     * The KML folder it was imported from ("Trails / Recorded" when nested), if any; for
     * lines picked from the map, how they're drawn there.
     */
    val folder: String? = null,
    /** A line used for trail distance by itself (its layer can also make it one). */
    val trail: Boolean = false,
) {
    /** The points split where a track was paused or continued from elsewhere; one list for anything else. */
    val segments: List<List<LatLon>>
        get() {
            val starts = track?.segmentStarts.orEmpty().filter { it in 1 until points.size }.distinct().sorted()
            if (type != FeatureType.LINE || starts.isEmpty()) return listOf(points)
            return (listOf(0) + starts + points.size).zipWithNext { a, b -> points.subList(a, b) }
        }

    /** Gaps between track segments don't count. */
    val length: Double
        get() = when (type) {
            FeatureType.POINT -> 0.0
            FeatureType.LINE -> segments.sumOf { Geodesy.pathLength(it) }
            FeatureType.AREA -> Geodesy.pathLength(points + points.first())
        }

    val area: Double get() = if (type == FeatureType.AREA) Geodesy.area(points) else 0.0
}

/**
 * Extra data for a track, parallel to [Feature.points]: each point's time (epoch millis) and
 * elevation (metres), null where unknown, plus the moving time and ascent if recorded here.
 */
data class TrackData(
    val times: List<Long?>,
    val elevations: List<Double?>,
    val durationMillis: Long? = null,
    val ascent: Double? = null,
    /** Indices of points that begin a new segment (after a pause with movement); empty for one segment. */
    val segmentStarts: List<Int> = emptyList(),
) {
    /** Recording time to resume from: the stored moving time, else the span of the timestamps in each segment. */
    val resumeMillis: Long
        get() = durationMillis ?: (listOf(0) + segmentStarts.filter { it in 1 until times.size }.distinct().sorted() + times.size)
            .zipWithNext { a, b -> times.subList(a, b).filterNotNull().let { if (it.size >= 2) it.last() - it.first() else 0L } }
            .sum()
}

enum class LayerKind {
    /** From a GPX/KML/KMZ file. */
    IMPORTED,

    /** Tracks recorded in the app. */
    TRACKS,

    /** Trails drawn in the app. */
    DRAWN_TRAILS,

    /** Lines picked from the PDF to use as trails. */
    MAP_LINES,
}

/** A group of features: one imported file, the recorded tracks, or trails added in the app. */
data class FeatureLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val kind: LayerKind = LayerKind.IMPORTED,
    /** Hidden layers aren't drawn or tappable. */
    val visible: Boolean = true,
    /** Whether its lines are walkable trails for measuring along trails. */
    val trails: Boolean = false,
    /** With [trails]: only lines in these [Feature.folder]s count; null = all its lines. */
    val trailFolders: Set<String>? = null,
)

/** Everything drawn on one map. */
data class MapFeatures(val layers: List<FeatureLayer> = emptyList(), val features: List<Feature> = emptyList())
