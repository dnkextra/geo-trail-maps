package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon

/** Which lines are walkable, for measuring along trails. */
object Trails {
    private val words = listOf("trail", "path", "road", "sendero", "camino")

    /** Whether a folder or PDF layer name looks like trails, paths or roads. */
    fun looksWalkable(name: String) = words.any { name.contains(it, ignoreCase = true) }

    /** The folders of [layer]'s lines with how many lines each has; "" holds lines in no folder. */
    fun folders(layer: FeatureLayer, features: List<Feature>): Map<String, Int> =
        features.filter { it.layerId == layer.id && it.type == FeatureType.LINE }
            .groupingBy { it.folder.orEmpty() }.eachCount().toSortedMap()

    /** The folders to tick at first: the walkable-looking ones, or all if none look it. */
    fun suggested(folders: Collection<String>): Set<String> =
        folders.filter(::looksWalkable).toSet().ifEmpty { folders.toSet() }

    /** Whether [layer] (if any) makes [f] a trail. */
    fun byLayer(f: Feature, layer: FeatureLayer?) =
        layer != null && layer.trails && (layer.trailFolders == null || f.folder.orEmpty() in layer.trailFolders)

    /** The lines used for trail distance (hidden or not): marked themselves, or by their layer. */
    fun features(layers: List<FeatureLayer>, features: List<Feature>): List<Feature> {
        val byId = layers.associateBy { it.id }
        return features.filter { f -> f.type == FeatureType.LINE && (f.trail || byLayer(f, byId[f.layerId])) }
    }

    fun lines(layers: List<FeatureLayer>, features: List<Feature>): List<List<LatLon>> = features(layers, features).map { it.points }
}
