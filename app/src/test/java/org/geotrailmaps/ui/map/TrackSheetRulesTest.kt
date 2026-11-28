package org.geotrailmaps.ui.map

import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.LayerKind
import org.geotrailmaps.data.TrackData
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.track.TrackRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSheetRulesTest {
    private val pts = listOf(LatLon(44.0, -64.0), LatLon(44.001, -64.0))
    private val tracks = FeatureLayer(name = "Recorded tracks", color = 0, kind = LayerKind.TRACKS)
    private val imported = FeatureLayer(name = "File", color = 0)
    private fun f(type: FeatureType, layer: FeatureLayer?, timed: Boolean = false) = Feature(
        type = type, name = "f", points = if (type == FeatureType.POINT) pts.take(1) else pts, color = 0, layerId = layer?.id,
        track = if (timed) TrackData(listOf(1L, 2L), listOf(null, null)) else null,
    )

    @Test
    fun onlyTracksAreTracks() {
        assertTrue(isTrack(f(FeatureType.LINE, tracks), tracks))
        assertTrue(isTrack(f(FeatureType.LINE, imported, timed = true), imported))
        // From a layer shown on all maps, so listed in Tracks: not tracks.
        assertFalse(isTrack(f(FeatureType.LINE, imported), imported))
        assertFalse(isTrack(f(FeatureType.POINT, tracks), tracks))
        assertFalse(isTrack(f(FeatureType.AREA, imported), imported))
    }

    @Test
    fun theTrackBeingRecordedCantBeContinued() {
        val t = f(FeatureType.LINE, tracks)
        assertTrue(canContinue(t, tracks, TrackRecorder.State()))
        assertFalse(canContinue(t, tracks, TrackRecorder.State(status = TrackRecorder.Status.PAUSED, trackId = t.id)))
        // Another track is active: offered, and confirmed before that one stops.
        assertTrue(canContinue(t, tracks, TrackRecorder.State(status = TrackRecorder.Status.RECORDING, trackId = "other")))
        assertFalse(canContinue(f(FeatureType.POINT, tracks), tracks, TrackRecorder.State()))
    }
}
