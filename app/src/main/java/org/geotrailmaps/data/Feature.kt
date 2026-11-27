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
    /** When it was added. Never changed, not shown, but exported (KML) so it travels with it. */
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
    /** For points: an emoji drawn in the pin's head instead of its dot; null for none. */
    val icon: String? = null,
    /** For points: the time it marks (e.g. when something was seen), changeable like its name; starts as when it was added. Null for lines and areas. */
    val time: Long? = if (type == FeatureType.POINT) createdAt else null,
    /** For lines and areas: how solid the line is drawn, 0..1; null for the default ([DEFAULT_OPACITY]). */
    val opacity: Float? = null,
) {
    /** [opacity], or the default. */
    val lineOpacity: Float get() = opacity?.coerceIn(MIN_OPACITY, 1f) ?: DEFAULT_OPACITY

    /** The points split where a track was paused or continued from elsewhere; one list for anything else. */
    val segments: List<List<LatLon>>
        get() {
            val starts = track?.segmentStarts(points.size).orEmpty()
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

    companion object {
        /** Lines are half see-through unless set otherwise, so the map under them stays readable. */
        const val DEFAULT_OPACITY = 0.5f
        /** Fainter than this, a line is hard to find again to make it more solid: hide it instead. */
        const val MIN_OPACITY = 0.1f
    }
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
    /** [segmentStarts] that are valid for [size] points: in range, ascending, no repeats. */
    fun segmentStarts(size: Int): List<Int> = segmentStarts.filter { it in 1 until size }.distinct().sorted()

    /** Recording time to resume from: the stored moving time, else the span of the timestamps in each segment. */
    val resumeMillis: Long
        get() = durationMillis ?: (listOf(0) + segmentStarts(times.size) + times.size)
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
    /**
     * Shown on every map it overlaps, not kept with one map (see [MapLibrary.global]). Not
     * stored: it's set from where the layer is kept when a map's layers are put together.
     */
    val global: Boolean = false,
)

/**
 * Everything drawn on one map, or the global layers (see [MapLibrary.global]).
 * [hiddenGlobal]: on a map, the global layers hidden there.
 */
data class MapFeatures(
    val layers: List<FeatureLayer> = emptyList(),
    val features: List<Feature> = emptyList(),
    val hiddenGlobal: Set<String> = emptySet(),
) {
    fun withFeature(id: String, edit: (Feature) -> Feature) = copy(features = features.map { if (it.id == id) edit(it) else it })
    fun withLayer(id: String, edit: (FeatureLayer) -> FeatureLayer) = copy(layers = layers.map { if (it.id == id) edit(it) else it })

    /** Replaces the feature with [feature]'s id, or adds it. */
    fun upsert(feature: Feature) =
        if (features.any { it.id == feature.id }) withFeature(feature.id) { feature } else copy(features = features + feature)

    fun removeFeature(id: String) = copy(features = features.filter { it.id != id })

    /** Removes a layer and its features. */
    fun removeLayer(id: String) = copy(layers = layers.filter { it.id != id }, features = features.filter { it.layerId != id })
}
