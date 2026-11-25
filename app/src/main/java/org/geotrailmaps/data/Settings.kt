package org.geotrailmaps.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.render.CustomBasemap
import org.geotrailmaps.render.Tile
import org.json.JSONArray
import org.json.JSONObject

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _coordinateFormat = MutableStateFlow(
        enumOrDefault(prefs.getString("coordinateFormat", null), CoordinateFormat.DECIMAL),
    )
    val coordinateFormat: StateFlow<CoordinateFormat> = _coordinateFormat

    private val _units = MutableStateFlow(enumOrDefault(prefs.getString("units", null), Units.METRIC))
    val units: StateFlow<Units> = _units

    private val _customBasemaps = MutableStateFlow(loadCustomBasemaps())
    /** Background map sources the user added. */
    val customBasemaps: StateFlow<List<CustomBasemap>> = _customBasemaps

    private val _basemaps = MutableStateFlow(BasemapSource.builtIn + _customBasemaps.value.map { it.toSource() })
    /** Every background map that can be chosen: the built-in ones, then the user's own. */
    val basemaps: StateFlow<List<BasemapSource>> = _basemaps

    // Before 0.5 this was an on/off switch for OpenStreetMap ("basemap"). Before 0.9.2 it was the
    // background chosen last on any map, so that choice carries over as the default.
    private var defaultBasemapId = prefs.getString("basemapSource", null)
        ?: if (prefs.getBoolean("basemap", true)) BasemapSource.OSM.id else NO_BASEMAP
    private val _defaultBasemap = MutableStateFlow(_basemaps.value.firstOrNull { it.id == defaultBasemapId })
    /**
     * The background map set in Settings (null = none): what a map gets the first time it's
     * opened. After that each map keeps its own ([MapEntry.basemap]).
     */
    val defaultBasemap: StateFlow<BasemapSource?> = _defaultBasemap

    fun setDefaultBasemap(value: BasemapSource?) {
        defaultBasemapId = value?.id ?: NO_BASEMAP
        prefs.edit { putString("basemapSource", defaultBasemapId) }
        _defaultBasemap.value = value
    }

    /** Adds a custom source, or replaces the one with the same id. */
    fun saveCustomBasemap(source: CustomBasemap) {
        val list = _customBasemaps.value
        setCustomBasemaps(if (list.any { it.id == source.id }) list.map { if (it.id == source.id) source else it } else list + source)
    }

    /** Maps using it, and the default if it was this one, fall back to no background. */
    fun deleteCustomBasemap(id: String) = setCustomBasemaps(_customBasemaps.value.filter { it.id != id })

    private fun setCustomBasemaps(list: List<CustomBasemap>) {
        val json = JSONArray()
        for (c in list) {
            json.put(
                JSONObject().put("id", c.id).put("name", c.name).put("url", c.urlTemplate)
                    .put("attribution", c.attribution).put("maxZoom", c.maxZoom),
            )
        }
        prefs.edit { putString("customBasemaps", json.toString()) }
        _customBasemaps.value = list
        _basemaps.value = BasemapSource.builtIn + list.map { it.toSource() }
        _defaultBasemap.value = _basemaps.value.firstOrNull { it.id == defaultBasemapId }
    }

    private fun loadCustomBasemaps(): List<CustomBasemap> = runCatching {
        val json = JSONArray(prefs.getString("customBasemaps", null) ?: return emptyList())
        List(json.length()) {
            val o = json.getJSONObject(it)
            CustomBasemap(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("attribution"), o.optInt("maxZoom", Tile.MAX_ZOOM))
        }
    }.getOrDefault(emptyList())

    private val _markerSets = MutableStateFlow(loadMarkerSets())
    /** Marker type files imported in Settings, in the order they were added. */
    val markerSets: StateFlow<List<MarkerSet>> = _markerSets

    private val _markerTypes = MutableStateFlow(typesOf(_markerSets.value))
    /** Every marker type by [MarkerType.id], in the order they're offered. */
    val markerTypes: StateFlow<Map<String, MarkerType>> = _markerTypes

    /**
     * Reads [text] as a marker types file and adds it, replacing a set with the same id (so an
     * edited file can be imported again). Returns the set and whether one was replaced.
     * @throws MarkerTypesException if it can't be read.
     */
    fun importMarkerSet(text: String, fallbackName: String): Pair<MarkerSet, Boolean> {
        val set = MarkerTypes.parse(text, fallbackName)
        val list = _markerSets.value
        val replaced = list.any { it.id == set.id }
        setMarkerSets(if (replaced) list.map { if (it.id == set.id) set else it } else list + set)
        return set to replaced
    }

    /** Markers of its types keep their type, attributes and colour, and show as plain pins. */
    fun deleteMarkerSet(id: String) = setMarkerSets(_markerSets.value.filter { it.id != id })

    private fun setMarkerSets(list: List<MarkerSet>) {
        // With its name, which may have come from the file name rather than the file.
        val json = JSONArray(list.map { JSONObject().put("name", it.name).put("source", it.source) })
        prefs.edit { putString("markerSets", json.toString()) }
        _markerSets.value = list
        _markerTypes.value = typesOf(list)
    }

    private fun typesOf(sets: List<MarkerSet>) = sets.flatMap { it.types }.associateByTo(LinkedHashMap()) { it.id }

    /** Kept as the files' text and read again on start, so a newer app can read more of an old file. */
    private fun loadMarkerSets(): List<MarkerSet> {
        val json = runCatching { JSONArray(prefs.getString("markerSets", null) ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return List(json.length()) { json.optJSONObject(it) }.mapNotNull { o ->
            runCatching { MarkerTypes.parse(o.getString("source"), o.optString("name", "Marker types")) }.getOrNull()
        }
    }

    /** The type + places next ([MarkerType.id], or [MarkerTypes.PLAIN]), so a run of sightings is quick to log. */
    var lastMarkerType: String
        get() = prefs.getString("lastMarkerType", null) ?: MarkerTypes.PLAIN
        set(value) = prefs.edit { putString("lastMarkerType", value) }

    /** Whether the "tap to show controls" hint has been shown once. */
    var fullscreenHintShown: Boolean
        get() = prefs.getBoolean("fullscreenHintShown", false)
        set(value) = prefs.edit { putBoolean("fullscreenHintShown", value) }

    /** Whether the notice about what routes can get wrong has been acknowledged. */
    var routeNoticeShown: Boolean
        get() = prefs.getBoolean("routeNoticeShown", false)
        set(value) = prefs.edit { putBoolean("routeNoticeShown", value) }

    private val _reopenLastMap = MutableStateFlow(prefs.getBoolean("reopenLastMap", true))
    /** Whether the app starts on the map left open last time, when you're on or near it. */
    val reopenLastMap: StateFlow<Boolean> = _reopenLastMap

    fun setReopenLastMap(value: Boolean) {
        prefs.edit { putBoolean("reopenLastMap", value) }
        _reopenLastMap.value = value
    }

    /** The map open when the app was last used; null if it was left on another screen. */
    var lastOpenMap: String?
        get() = prefs.getString("lastOpenMap", null)
        set(value) = prefs.edit { putString("lastOpenMap", value) }

    private val _mapSort = MutableStateFlow(enumOrDefault(prefs.getString("mapSort", null), MapSort.NAME))
    val mapSort: StateFlow<MapSort> = _mapSort

    fun setMapSort(value: MapSort) {
        prefs.edit { putString("mapSort", value.name) }
        _mapSort.value = value
    }

    fun setCoordinateFormat(value: CoordinateFormat) {
        prefs.edit { putString("coordinateFormat", value.name) }
        _coordinateFormat.value = value
    }

    fun setUnits(value: Units) {
        prefs.edit { putString("units", value.name) }
        _units.value = value
    }

    private val _trackAccuracy = MutableStateFlow(enumOrDefault(prefs.getString("trackAccuracy", null), TrackAccuracy.NORMAL))
    /** Read by the recorder for each fix, so a change applies to a recording already running. */
    val trackAccuracy: StateFlow<TrackAccuracy> = _trackAccuracy

    fun setTrackAccuracy(value: TrackAccuracy) {
        prefs.edit { putString("trackAccuracy", value.name) }
        _trackAccuracy.value = value
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
