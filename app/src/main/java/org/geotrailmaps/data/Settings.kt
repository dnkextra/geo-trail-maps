package org.geotrailmaps.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.geotrailmaps.render.BasemapSource

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _coordinateFormat = MutableStateFlow(
        enumOrDefault(prefs.getString("coordinateFormat", null), CoordinateFormat.DECIMAL),
    )
    val coordinateFormat: StateFlow<CoordinateFormat> = _coordinateFormat

    private val _units = MutableStateFlow(enumOrDefault(prefs.getString("units", null), Units.METRIC))
    val units: StateFlow<Units> = _units

    // Before 0.5 this was an on/off switch for OpenStreetMap ("basemap"). Before 0.9.2 it was the
    // background chosen last on any map, so that choice carries over as the default.
    private val _defaultBasemap = MutableStateFlow(
        when (val name = prefs.getString("basemapSource", null)) {
            null -> if (prefs.getBoolean("basemap", true)) BasemapSource.OSM else null
            else -> BasemapSource.entries.firstOrNull { it.name == name }
        },
    )
    /**
     * The background map set in Settings (null = none): what a map gets the first time it's
     * opened. After that each map keeps its own ([MapEntry.basemap]).
     */
    val defaultBasemap: StateFlow<BasemapSource?> = _defaultBasemap

    fun setDefaultBasemap(value: BasemapSource?) {
        prefs.edit { putString("basemapSource", value?.name ?: "NONE") }
        _defaultBasemap.value = value
    }

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
