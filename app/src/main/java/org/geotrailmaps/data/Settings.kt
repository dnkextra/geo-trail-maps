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

    /** Whether the "tap to show controls" hint has been shown once. */
    var fullscreenHintShown: Boolean
        get() = prefs.getBoolean("fullscreenHintShown", false)
        set(value) = prefs.edit { putBoolean("fullscreenHintShown", value) }

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

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
