package org.geotrailmaps.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.core.animate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import org.geotrailmaps.track.TrackRecorder
import kotlin.math.abs
import kotlin.math.atan2
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.location.Location
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geotrailmaps.data.CoordinateFormat
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureImport
import org.geotrailmaps.data.FeatureImportException
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.LayerKind
import org.geotrailmaps.data.Trails
import org.geotrailmaps.data.TrackData
import org.geotrailmaps.data.displayName
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.CoordinateParser
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.MapEntry
import org.geotrailmaps.data.MapLibrary
import org.geotrailmaps.data.Settings
import org.geotrailmaps.data.Units
import org.geotrailmaps.ui.shareFeatures
import org.geotrailmaps.data.overlaps
import org.geotrailmaps.geo.GeoReference
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.geo.TrailNetwork
import org.geotrailmaps.geo.TrailRoute
import org.geotrailmaps.geo.normalizeDegrees
import org.geotrailmaps.geo.route
import org.geotrailmaps.location.LocationTracker
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.render.BasemapTiles
import org.geotrailmaps.ui.theme.MapStyle

@Composable
fun MapScreen(
    entry: MapEntry,
    library: MapLibrary,
    settings: Settings,
    tracker: LocationTracker,
    basemap: BasemapTiles,
    /** All imported maps, to suggest the one you're standing on. */
    allMaps: List<MapEntry>,
    onOpenMap: (String) -> Unit,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Opening a large PDF can take a while, so do it off the main thread.
    val opened by produceState<Result<MapSession>?>(null, entry.id) {
        var result: Result<MapSession>? = null
        try {
            // Assigned inside the IO block so `finally` can close it even if we're cancelled on return.
            withContext(Dispatchers.IO) { result = runCatching { MapSession(entry, library) } }
            value = result
            awaitCancellation()
        } finally {
            result?.getOrNull()?.close()
        }
    }
    val result = opened
    val session = result?.getOrNull()
    when {
        result == null -> {
            BackHandler(onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        session == null -> LaunchedEffect(Unit) {
            Toast.makeText(context, "Couldn't open this map file.", Toast.LENGTH_LONG).show()
            onBack()
        }
        // Its own state for each map: nothing picked, measured or drawn carries over to the next.
        else -> key(session) { MapContent(session, settings, tracker, basemap, allMaps, onOpenMap, hasLocationPermission, onRequestLocationPermission, onBack) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MapContent(
    session: MapSession,
    settings: Settings,
    tracker: LocationTracker,
    basemap: BasemapTiles,
    allMaps: List<MapEntry>,
    onOpenMap: (String) -> Unit,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val entry = session.entry
    val viewport = session.viewport
    val geo = entry.geo
    val proj = remember(geo, viewport) { geo?.let { MapProjection(it, viewport) } }

    val location by tracker.location.collectAsState()
    val heading by tracker.heading.collectAsState()
    DisposableEffect(tracker) {
        tracker.compassWanted = true
        onDispose { tracker.compassWanted = false }
    }
    val locationOn by tracker.enabled.collectAsState()
    val openLocationSettings = { context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    val format by settings.coordinateFormat.collectAsState()
    val units by settings.units.collectAsState()
    // Each map keeps its own background; one opened before it had a choice gets the last one chosen.
    val lastBasemap by settings.basemap.collectAsState()
    LaunchedEffect(session) { if (session.basemapChoice == null) session.setBasemap(lastBasemap) }
    val basemapSource = if (session.basemapChoice == null) lastBasemap else session.basemap
    // The tile credit shows on the map for a few seconds whenever the background map appears,
    // then fades; it stays in the layer list.
    var showCredit by remember { mutableStateOf(false) }
    val myLatLon = location?.let { LatLon(it.latitude, it.longitude) }
    val onMap = geo != null && myLatLon != null && geo.contains(myLatLon)

    // GPS freshness, re-checked every few seconds: the dot greys out when fixes stop coming.
    var clockNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            clockNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    val fixAgeSeconds = location?.let { ((clockNanos - it.elapsedRealtimeNanos) / 1e9).coerceAtLeast(0.0) }
    val stale = fixAgeSeconds != null && fixAgeSeconds > STALE_FIX_SECONDS

    var following by remember { mutableStateOf(false) }
    /** Heading-up: the map turns so the direction you face is at the top. */
    var compass by remember { mutableStateOf(false) }
    var navTarget by remember { mutableStateOf<NavTarget?>(null) }
    /** The bottom panel shows the search box (results float above it). */
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchPoint by remember { mutableStateOf<LatLon?>(null) }
    val recording by TrackRecorder.state.collectAsState()
    var showStopTrack by remember { mutableStateOf(false) }
    /** Track pressed while a track paused on another map is unfinished: save or discard it first. */
    var askingUnfinished by remember { mutableStateOf(false) }
    /** Resume pressed this far (m) from where the track was paused: asked first. */
    var resumingFar by remember { mutableStateOf<Double?>(null) }
    /** A save waiting for the last batched fixes: Stop/Save mustn't run twice. */
    var savingTrack by remember { mutableStateOf(false) }
    LaunchedEffect(recording.status) {
        when (recording.status) {
            // A paused track keeps its controls on maps it recorded on, even with no points there.
            TrackRecorder.Status.RECORDING -> TrackRecorder.recordedOn += entry.id
            TrackRecorder.Status.IDLE -> { showStopTrack = false; askingUnfinished = false; resumingFar = null } // stopped from the notification
            TrackRecorder.Status.PAUSED -> {}
        }
    }
    val recordingFeature = remember(recording.points) {
        Feature(
            id = RECORDING_ID, type = FeatureType.LINE, name = "", points = recording.points, color = MapStyle.recordingTrack.toArgb(),
            track = TrackData(recording.times, recording.elevations, segmentStarts = recording.segmentStarts),
        )
    }

    /**
     * Recording shows on every map while it runs, but a paused track only on maps it's on:
     * elsewhere it stays out of the way until Track is pressed (see [askingUnfinished]).
     */
    val recordingHere = recording.status == TrackRecorder.Status.RECORDING ||
        recording.status == TrackRecorder.Status.PAUSED && (entry.id in TrackRecorder.recordedOn || remember(recordingFeature) {
            // By segment, so a map between two (e.g. under a flight) doesn't count.
            overlaps(recordingFeature, session.coverage)
        })
    /** A global layer about to come back to this map only: confirmed first, as other maps lose it. */
    var localizing by remember { mutableStateOf<FeatureLayer?>(null) }
    /** Navigate: tapping points to measure the way between them, along trails or straight. */
    var navigating by remember { mutableStateOf(false) }
    /** Bars hidden so the map fills the screen; a tap on empty map toggles it. */
    var chromeHidden by remember { mutableStateOf(false) }
    // Last measured bar heights, restored explicitly when the bars come back.
    val barHeights = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(chromeHidden) {
        if (chromeHidden) viewport.setInsets(0f, 0f) else viewport.setInsets(barHeights[0], barHeights[1])
    }
    /** The + tool: a point aimed with the centre crosshair, or a line or area tapped out. */
    var adding by remember { mutableStateOf<AddMode?>(null) }
    val placing = adding == AddMode.POINT
    /** Taps on the map add points: navigating, or drawing a line or area. */
    val tapping = navigating || adding == AddMode.LINE || adding == AddMode.AREA
    var navMode by remember { mutableStateOf(NavMode.TRAILS) }
    val tapPoints = remember { mutableStateListOf<LatLon>() }
    val alongTrails = navigating && navMode == NavMode.TRAILS
    /** A spot long-pressed on the map, marked with a crosshair; its actions replace the toolbar. */
    var pressed by remember { mutableStateOf<LatLon?>(null) }
    /** Finding a route before there's a GPS fix: it starts from you once there is one. */
    var routeFromMe by remember { mutableStateOf(false) }
    /** Adding trails (over Navigate, if it was open: it's back after). */
    var addingTrails by remember { mutableStateOf(false) }
    var trailMode by remember { mutableStateOf(TrailMode.DRAW) }
    // Nothing drawn or picked is saved until it's confirmed.
    /** The trail being drawn, and those finished before it. */
    val trailPoints = remember { mutableStateListOf<TrailPoint>() }
    val pendingTrails = remember { mutableStateListOf<List<TrailPoint>>() }
    /** Lines picked from the map, by index in [MapLines.lines]; the last one tapped. */
    val pickSelection = remember { mutableStateListOf<Int>() }
    var lastPicked by remember { mutableStateOf<Int?>(null) }
    /** Each change to [pickSelection] (lines added, or removed), for Undo. */
    val pickHistory = remember { mutableStateListOf<Pair<Boolean, List<Int>>>() }
    val pendingCount = pendingTrails.size + (if (trailPoints.size >= 2) 1 else 0) + pickSelection.size
    var confirmingClose by remember { mutableStateOf(false) }
    val mapLines by produceState<MapLines?>(null, session, addingTrails) { if (addingTrails) value = session.mapLines() }
    /** Lines of [mapLines] already picked, which can't be picked again. */
    val usedLines = remember(mapLines, session.pickedLineEnds) { mapLines?.indicesOf(session.pickedLineEnds).orEmpty() }
    /** Built the first time it's needed, again when the trail layers change; null until then. */
    val trailNetwork by produceState<TrailNetwork?>(null, session, alongTrails, session.trailLines) {
        if (alongTrails) value = session.trailNetwork()
    }
    /** While drawing: the trails plus those drawn but not yet confirmed, to snap onto. */
    val drawNetwork by produceState<TrailNetwork?>(null, session, addingTrails, session.trailLines, pendingTrails.toList()) {
        if (addingTrails) value = session.trailNetwork(pendingTrails.map { t -> t.map { it.at } })
    }
    /** The route along trails, with the points it goes through. */
    val measureRoute by produceState<Pair<List<LatLon>, TrailRoute>?>(null, alongTrails, trailNetwork, tapPoints.toList()) {
        val net = trailNetwork
        val points = tapPoints.toList()
        value = if (alongTrails && net != null) points to withContext(Dispatchers.Default) { net.route(points) } else null
    }
    val currentRoute = measureRoute?.takeIf { it.first == tapPoints.toList() }?.second
    /** Highlighted on the map. */
    var selectedId by remember { mutableStateOf<String?>(null) }
    /** Open in the editor sheet. */
    var editingId by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    // Shown once each time the background map comes on screen (map opened or switched on);
    // if it's switched on from the Features sheet, wait until the sheet closes.
    LaunchedEffect(basemapSource) {
        showCredit = false
        if (basemapSource == null) return@LaunchedEffect
        snapshotFlow { showList }.first { !it }
        showCredit = true
        delay(6_000)
        showCredit = false
    }
    /** Which groups are open in the Features list, kept while the map is open. */
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showInfo by remember { mutableStateOf(false) }
    val tilePaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    // System bars and cutouts, but not the keyboard: the map's controls must not jump when a
    // sheet's text field opens the keyboard.
    val barsAndCutout = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val horizontalSafeArea = barsAndCutout.only(WindowInsetsSides.Horizontal)

    LaunchedEffect(session) { session.renderBase() }
    LaunchedEffect(session) { session.followGlobal() }
    LaunchedEffect(session) { session.renderTiles() }
    // Built ahead once the map shows, so measuring along trails is ready when it's first used.
    LaunchedEffect(session, session.trailLines) {
        snapshotFlow { session.base }.first { it != null }
        session.trailNetwork()
    }
    LaunchedEffect(session) {
        // While moving too, not just once it stops: tiles render nearest the centre first.
        snapshotFlow { listOf(viewport.scale, viewport.offset, viewport.size, viewport.rotation, session.base, session.seeThrough > 0f) }
            .collect { session.requestTiles() }
    }
    if (basemapSource != null && proj != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(proj, lifecycleOwner, basemapSource) {
            // Only while visible: stopping the app or closing the map drops queued downloads and retries.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Only when the tile set changes, and at most every 80 ms while moving, so a
                    // pinch doesn't queue every zoom level in between. Throttled, not debounced:
                    // compass turns or a long pan never pause long enough for a debounce.
                    snapshotFlow { visibleTiles(proj, viewport, density.density, basemapSource.maxZoom).toSet() }.conflate().collect {
                        basemap.request(basemapSource, it.toList())
                        delay(80)
                    }
                } finally {
                    basemap.cancelAll()
                }
            }
        }
    }
    LaunchedEffect(myLatLon, following) {
        if (following && onMap) viewport.centerOn(geo.toPage(myLatLon))
    }
    // The heading is read here rather than as a key: it changes many times a second, and as a
    // key every change would recompose the whole screen.
    LaunchedEffect(compass, myLatLon) {
        if (!compass || proj == null || myLatLon == null) return@LaunchedEffect
        snapshotFlow { heading }.collect { h ->
            if (h == null || adding != null || navigating || addingTrails) return@collect
            // Turn the map so the heading points up, whatever the map's own north.
            val a = proj.toScreen(myLatLon)
            val b = proj.toScreen(Geodesy.destination(myLatLon, h.toDouble(), 50.0))
            val theta = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            viewport.rotation = normalizeDegrees(-(theta + 90f))
        }
    }
    /** Shows [text], replacing the current message rather than queueing behind it; true if [action] was tapped. */
    suspend fun replaceMessage(text: String, action: String? = null, duration: SnackbarDuration = SnackbarDuration.Short): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        return snackbar.showSnackbar(text, action, duration = duration) == SnackbarResult.ActionPerformed
    }

    // Standing on another imported map? Offer it once per visit.
    val suggestedMaps = remember { mutableSetOf<String>() }
    val otherMapHere = if (myLatLon != null && !onMap && !stale) {
        allMaps.firstOrNull { it.id != entry.id && !it.archived && it.geo?.contains(myLatLon) == true }
    } else null
    LaunchedEffect(otherMapHere?.id, addingTrails) {
        // Not while adding trails: opening the other map would drop them. It's offered after.
        if (addingTrails) return@LaunchedEffect
        val other = otherMapHere ?: return@LaunchedEffect
        if (!suggestedMaps.add(other.id)) return@LaunchedEffect
        if (replaceMessage("You're on \"${other.name}\"", "Open", SnackbarDuration.Long)) onOpenMap(other.id)
    }

    fun showMessage(text: String, duration: SnackbarDuration = SnackbarDuration.Short) = scope.launch { replaceMessage(text, duration = duration) }

    // Once per map opened: without a connection, only background areas viewed before can show.
    val basemapOffline = basemapSource != null && basemap.offline
    var toldOffline by remember { mutableStateOf(false) }
    LaunchedEffect(basemapOffline) {
        if (basemapOffline && !toldOffline) {
            // After any message already showing (e.g. a recovered track), not instead of it.
            snapshotFlow { snackbar.currentSnackbarData }.first { it == null }
            toldOffline = true
            showMessage("Can't reach the map server. The background map shows only areas viewed before.")
        }
    }

    fun exitCompass() {
        compass = false
        val from = viewport.rotation
        if (from != 0f) scope.launch { animate(from, 0f) { v, _ -> viewport.rotation = v } }
    }

    /** The map stops moving by itself: no following, no compass. */
    fun stopAutoMove() {
        following = false
        if (compass) exitCompass()
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Approximate location is too coarse for the recorder's accuracy filter: no points would be kept.
    fun hasPreciseLocation() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun askForPreciseLocation() {
        showMessage("Recording a track needs precise location. Allow it, then try again.")
        onRequestLocationPermission()
    }

    /** Starts recording (onto [from], if given); false if it couldn't, and the user is told why. */
    fun beginRecording(from: Feature? = null): Boolean {
        if (!hasPreciseLocation()) {
            askForPreciseLocation()
            return false
        }
        // The recording notification needs this; recording works without it, just silently.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!TrackRecorder.start(context.applicationContext, from)) {
            showMessage("Still saving the last track. Try again in a moment.")
            return false
        }
        return true
    }

    fun startRecording() {
        if (!beginRecording()) return
        if (locationOn) showMessage("Recording a track.")
        else showMessage("Recording a track, but device location is off. Turn it on to record points.", SnackbarDuration.Long)
    }

    /** Highlights feature [id] and opens it in the editor sheet. */
    fun openEditor(id: String) {
        selectedId = id
        editingId = id
    }

    /** Saves the recording (see [TrackRecorder.stop]) and opens it, if it's on this map. */
    fun saveTrack() {
        val saved = TrackRecorder.stop(context.applicationContext)
        session.geometry?.forget(listOf(RECORDING_ID))
        if (saved == null) return run { showMessage("The track was too short to save.") }
        if (!overlaps(saved, session.coverage)) return run { showMessage("Saved \"${saved.name}\" in Tracks.") }
        saved.layerId?.let { session.setLayerVisible(it, true) }
        openEditor(saved.id)
    }

    /** Records more onto [f]. Its layer becomes global if it isn't, since recordings are saved there. */
    fun continueTrack(f: Feature) {
        val layer = session.layers.find { it.id == f.layerId } ?: return
        if (!beginRecording(from = f)) return
        if (layer.global) return run { showMessage("Continuing \"${f.name}\". Stop saves it into the same track.") }
        scope.launch {
            // If the layer can't move (the library says why), Stop saves a new track in Tracks instead.
            if (session.setLayerGlobal(layer.id, true)) {
                showMessage("Continuing \"${f.name}\". \"${layer.name}\" now shows on every map it covers, so the track can keep growing.", SnackbarDuration.Long)
            }
        }
    }

    /** Saves the paused track, then starts a new one here. */
    fun saveAndRecordNew() {
        if (savingTrack) return
        savingTrack = true
        scope.launch {
            try {
                val saved = TrackRecorder.stop(context.applicationContext)
                TrackRecorder.awaitSaved()
                // A failed save comes back paused (the recorder says so): don't replace it.
                if (TrackRecorder.state.value.status == TrackRecorder.Status.IDLE) {
                    startRecording()
                    if (saved != null) showMessage("Saved \"${saved.name}\" in Tracks. Recording a new track.")
                }
            } finally {
                savingTrack = false
            }
        }
    }

    fun addPlacemark(at: LatLon) {
        val n = session.features.count { it.type == FeatureType.POINT } + 1
        val f = Feature(type = FeatureType.POINT, name = "Placemark $n", points = listOf(at), color = MapStyle.placemarkColor.toArgb())
        session.upsert(f)
        openEditor(f.id)
    }

    fun closeTools() {
        adding = null
        navigating = false
        tapPoints.clear()
        routeFromMe = false
        pressed = null
    }

    /** Opening + or Navigate: the map stops moving by itself, and nothing else stays open. */
    fun startTool() {
        closeTools()
        selectedId = null
        stopAutoMove()
    }

    /** Saves the tapped points as a line or area and opens it in the editor. */
    fun saveShape(area: Boolean, points: List<LatLon>) {
        val type = if (area) FeatureType.AREA else FeatureType.LINE
        val n = session.features.count { it.type == type } + 1
        val f = Feature(type = type, name = if (area) "Area $n" else "Line $n", points = points, color = MapStyle.lineColor.toArgb())
        session.upsert(f)
        closeTools()
        openEditor(f.id)
    }

    /** Shows two places together, such as where you are and where you're going. */
    fun showBoth(a: LatLon, b: LatLon) {
        val g = geo ?: return
        val pa = g.toPage(a)
        val pb = g.toPage(b)
        stopAutoMove()
        viewport.showRect(minOf(pa.x, pb.x).toFloat(), minOf(pa.y, pb.y).toFloat(), maxOf(pa.x, pb.x).toFloat(), maxOf(pa.y, pb.y).toFloat())
    }

    /** Navigate along trails from where you are to [to]. */
    fun findRoute(to: LatLon) {
        startTool()
        navMode = NavMode.TRAILS
        navigating = true
        val me = myLatLon
        if (me == null || !onMap) {
            // Off the map, a route from you would mostly be a straight line to its edge.
            if (!hasLocationPermission) onRequestLocationPermission()
            else if (me != null) showMessage("You are ${Formats.distance(geo!!.distanceFromMap(me), units)} from this map. Tap where to start.")
            tapPoints += to
            routeFromMe = true
        } else {
            tapPoints += listOf(me, to)
            showBoth(me, to)
        }
    }
    // The first fix on this map after "Find route" becomes the route's start (after adding trails, if that's open).
    LaunchedEffect(routeFromMe, onMap, addingTrails) {
        val me = myLatLon ?: return@LaunchedEffect
        if (!routeFromMe || !navigating || !onMap || addingTrails) return@LaunchedEffect
        routeFromMe = false
        tapPoints.add(0, me)
        tapPoints.getOrNull(1)?.let { showBoth(me, it) }
    }

    /** Zooms to fit [list] (or centres on a single placemark). */
    fun showFeatures(list: List<Feature>) {
        val g = session.geometry ?: return
        if (list.isEmpty()) return
        stopAutoMove()
        val shapes = list.map { g.shape(it) }
        viewport.showRect(shapes.minOf { it.minX }, shapes.minOf { it.minY }, shapes.maxOf { it.maxX }, shapes.maxOf { it.maxY })
    }

    /** Unhides [f] (it can't be shown hidden), zooms to it and highlights it. */
    fun showFeature(f: Feature) {
        session.reveal(f)
        showFeatures(listOf(f))
        selectedId = f.id
    }

    fun discardTrails() {
        trailPoints.clear()
        pendingTrails.clear()
        pickSelection.clear()
        pickHistory.clear()
        lastPicked = null
    }

    fun startAddingTrails() {
        selectedId = null
        adding = null
        pressed = null
        searching = false
        stopAutoMove()
        trailMode = TrailMode.DRAW
        discardTrails()
        addingTrails = true
    }

    /** Sets the trail being drawn aside (if it has a line's worth of points) to start the next. */
    fun nextTrail() {
        if (trailPoints.size >= 2) pendingTrails += trailPoints.toList()
        trailPoints.clear()
    }

    /** Saves everything drawn or picked, and says where it went. */
    fun confirmTrails() {
        nextTrail()
        val drawn = pendingTrails.size
        val picked = pickSelection.size
        pendingTrails.forEach { t -> session.addTrail(t.map { it.at }) }
        mapLines?.let { m -> session.addMapLines(pickSelection.map { m.lines[it] }) }
        discardTrails()
        val n = drawn + picked
        if (n == 0) return
        val kinds = setOfNotNull(LayerKind.DRAWN_TRAILS.takeIf { drawn > 0 }, LayerKind.MAP_LINES.takeIf { picked > 0 })
        val hidden = session.layers.filter { it.kind in kinds && !it.visible }
        val text = "Added " + if (n == 1) "1 trail" else "$n trails"
        if (hidden.isEmpty()) return run { showMessage(text) }
        scope.launch {
            if (replaceMessage("$text (not shown on the map)", "Show", SnackbarDuration.Long)) hidden.forEach { session.setLayerVisible(it.id, true) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || geo == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val name = context.displayName(uri) ?: "Imported"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_IMPORT_BYTES + 1) }
                        ?: throw FeatureImportException("Couldn't open the file.")
                    if (bytes.size > MAX_IMPORT_BYTES) throw FeatureImportException("The file is too large to import.")
                    val palette = MapStyle.featureColors.filterKeys { it != "Black" }.values.toList()
                    val color = palette[(session.layers.size + 3) % palette.size].toArgb()
                    FeatureImport.parse(name, bytes, color).also { data ->
                        // Project now, off the main thread, so the first frame doesn't stall on long tracks.
                        data.features.forEach { session.geometry?.shape(it) }
                    }
                }
                session.addLayer(imported)
                showList = false
                expandedGroups[imported.layers.first().id] = true
                val outside = imported.features.count { f -> f.points.none { geo.contains(it) } }
                val n = imported.features.size
                val what = "$n ${if (n == 1) "feature" else "features"}"
                showMessage(
                    when (outside) {
                        0 -> "Imported $what into \"${imported.layers.first().name}\", hidden. Tick it in Layers to show it."
                        n -> "Imported $what, but none of them are on this map"
                        else -> "Imported $what ($outside outside this map), hidden. Tick it in Layers to show it."
                    },
                    SnackbarDuration.Long,
                )
            } catch (e: FeatureImportException) {
                showMessage(e.message ?: "Import failed")
            } catch (e: Exception) {
                showMessage("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    val savePdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            showMessage(if (session.library.exportPdf(entry.id, uri)) "Exported the map's PDF." else "Couldn't export the PDF.")
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    // After search closes, the panel keeps riding the keyboard until it has slid away,
    // so both go down together instead of the panel snapping first.
    var holdForKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, editingId, showList) {
        if (!imeVisible || editingId != null || showList) holdForKeyboard = false
    }
    // The keyboard takes the first Back itself. If nothing has been typed, that same Back
    // closes search too; with a query, it just drops the keyboard so the results can be browsed.
    var keyboardShownWhileSearching by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, searching) {
        if (!searching) keyboardShownWhileSearching = false
        else if (imeVisible) keyboardShownWhileSearching = true
        else if (keyboardShownWhileSearching && searchQuery.isBlank()) {
            keyboardShownWhileSearching = false
            searching = false
        }
    }

    fun closeSearch() {
        if (imeVisible) holdForKeyboard = true
        keyboard?.hide()
        searching = false
    }

    BackHandler {
        when {
            chromeHidden -> chromeHidden = false
            searching -> closeSearch()
            addingTrails -> if (pendingCount > 0) confirmingClose = true else addingTrails = false
            pressed != null -> pressed = null
            navigating || adding != null -> closeTools()
            else -> onBack()
        }
    }

    fun goTo(p: LatLon) {
        closeSearch()
        stopAutoMove()
        searchPoint = p
        viewport.showPoint(geo!!.toPage(p))
        if (!geo.contains(p)) showMessage("That point is outside this map.")
    }

    fun locate() {
        when {
            !hasLocationPermission -> onRequestLocationPermission()
            geo == null -> showMessage("This PDF has no location information.")
            !locationOn -> {
                showMessage("Device location is off.")
                openLocationSettings()
            }
            myLatLon == null -> showMessage("Waiting for GPS…")
            !onMap -> showMessage("You are ${Formats.distance(geo.distanceFromMap(myLatLon), units)} from this map.")
            // Following would pull the crosshair or the points being tapped away on every fix.
            adding != null || navigating || addingTrails -> viewport.centerOn(geo.toPage(myLatLon))
            // Taps cycle: follow (north up) -> compass (heading up) -> follow (north up).
            !following -> {
                following = true
                viewport.centerOn(geo.toPage(myLatLon))
            }
            !compass -> {
                if (heading == null) {
                    showMessage("This phone has no compass, so the map can't turn with you.")
                } else {
                    compass = true
                    showMessage("Compass mode: the map turns as you turn. Tap the compass for north up.")
                }
                viewport.centerOn(geo.toPage(myLatLon))
            }
            else -> exitCompass()
        }
    }

    // Only with no tool open: the tools use taps for themselves.
    val canPress = proj != null && adding == null && !navigating && !addingTrails && !searching
    fun press(p: LatLon) {
        pressed = p
        selectedId = null
        // Following or compass mode would pull the spot away on the next fix or turn.
        stopAutoMove()
        // The controls come back for its actions; keep the spot from ending up under them.
        if (chromeHidden) {
            chromeHidden = false
            viewport.centerOn(geo!!.toPage(p))
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapView(
            viewport = viewport,
            base = session.base,
            tiles = {
                session.imagesVersion // redraw when tiles arrive
                session.base?.let { session.tiles.drawable(viewport, it.scale) }.orEmpty()
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    onClick(label = if (chromeHidden) "Show controls" else "Hide controls") { chromeHidden = !chromeHidden; true }
                    if (canPress) onLongClick(label = "Mark the centre of the map") { press(geo!!.toLatLon(viewport.center)); true }
                },
            doubleTapZoom = !tapping && !addingTrails,
            onUserMove = {
                following = false
                if (compass) {
                    exitCompass()
                    showMessage("Compass mode off")
                }
            },
            onTap = { pos ->
                if (searching) return@MapView closeSearch()
                if (placing) return@MapView
                // A tap away from a long-pressed spot just lets it go.
                if (pressed != null) return@MapView run { pressed = null }
                if (proj == null) {
                    chromeHidden = !chromeHidden // no features without georeferencing
                } else if (addingTrails && trailMode == TrailMode.DRAW) {
                    // Onto a trail when close: its ends and junctions first, then anywhere along it.
                    val p = proj.toLatLon(pos)
                    val net = drawNetwork
                    fun within(q: LatLon, reach: Dp) = (proj.toScreen(q) - pos).getDistance() <= with(density) { reach.toPx() }
                    val onTrail = net?.nearestEnd(p)?.takeIf { within(it, 24.dp) } ?: net?.snap(p)?.point?.takeIf { within(it, 16.dp) }
                    trailPoints += TrailPoint(onTrail ?: p, joined = onTrail != null)
                } else if (addingTrails) {
                    val lines = mapLines ?: return@MapView
                    lines.hitTest(pos, viewport, with(density) { 16.dp.toPx() }) { it in usedLines }?.let { i ->
                        val add = i !in pickSelection
                        if (add) pickSelection += i else pickSelection -= i
                        pickHistory += add to listOf(i)
                        lastPicked = i
                    }
                } else if (tapping) {
                    val p = proj.toLatLon(pos)
                    // Along trails, a tap close to a trail goes on it.
                    val snap = if (alongTrails) trailNetwork?.snap(p) else null
                    val near = snap != null && (proj.toScreen(snap.point) - pos).getDistance() <= with(density) { 24.dp.toPx() }
                    val q = if (near) snap!!.point else p
                    // Still waiting for a fix after "Find route": a tap is where to start instead.
                    if (routeFromMe) {
                        routeFromMe = false
                        tapPoints.add(0, q)
                    } else {
                        tapPoints += q
                    }
                } else {
                    val pinHeight = with(density) { MapStyle.pinHeight.toPx() }
                    val hit = hitTest(session.shownFeatures, pos, session.geometry!!, viewport, density.density, pinHeight)
                    if (hit != null) {
                        openEditor(hit.id)
                    } else {
                        // The highlight outlives the feature's sheet, so you can look at it
                        // on the map; this tap only clears it, the next hides the controls.
                        // (A track being continued isn't drawn, the live line replaces it.)
                        val highlighted = selectedId != null && selectedId != recording.continuingId
                        selectedId = null
                        searchPoint = null
                        if (highlighted) return@MapView
                        chromeHidden = !chromeHidden
                        if (chromeHidden && !settings.fullscreenHintShown) {
                            settings.fullscreenHintShown = true
                            showMessage("Tap the map to show the controls again")
                        }
                    }
                }
            },
            onLongPress = if (canPress) { pos ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                press(proj!!.toLatLon(pos))
            } else null,
            underlay = {
                if (basemapSource != null && proj != null) drawBasemap(basemap, basemapSource, visibleTiles(proj, viewport, density.density, basemapSource.maxZoom), proj, tilePaint)
            },
            // Only over a background map: over the plain background it would just look faded.
            seeThrough = if (basemapSource != null && proj != null) session.seeThrough else 0f,
        ) {
            if (proj != null) {
                // While a saved track is being continued, the live line (which includes it) replaces it.
                val shown = recording.continuingId?.let { id -> session.shownFeatures.filter { it.id != id } } ?: session.shownFeatures
                drawFeatures(shown, selectedId, session.geometry!!, viewport)
                if (addingTrails) {
                    if (trailMode == TrailMode.PICK) mapLines?.let { drawPickableLines(it, usedLines, pickSelection.toSet(), viewport) }
                    drawTrails(session.trailFeatures, session.geometry, viewport)
                    pendingTrails.forEach { drawTrailInProgress(it, proj) }
                    drawTrailInProgress(trailPoints, proj)
                }
                if (recording.points.size >= 2) drawFeatures(listOf(recordingFeature), null, session.geometry, viewport)
                // The last route until the new one is found (a few ms), rather than flashing straight lines.
                if (tapping && !addingTrails) drawMeasurement(tapPoints, adding == AddMode.AREA, proj, if (alongTrails) measureRoute?.second else null)
                searchPoint?.let { drawSearchMarker(it, proj) }
                navTarget?.let { drawNavigation(myLatLon, it.position, proj) }
                if (myLatLon != null) drawLocation(myLatLon, location?.accuracy, heading, proj, stale)
                if (placing) drawCrosshair(viewport.toScreen(viewport.center))
                pressed?.let { drawCrosshair(proj.toScreen(it)) }
            }
        }

        // Overlaid just below the top bar so they never move anything else.
        if (geo != null) {
            val overlayTop = if (chromeHidden) {
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            } else {
                with(density) { barHeights[0].toDp() }
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = overlayTop + 4.dp, start = 8.dp, end = 8.dp)
                    .windowInsetsPadding(horizontalSafeArea),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                navTarget?.let { target ->
                    NavigationBanner(
                        target = target,
                        me = myLatLon,
                        arrowAngle = if (myLatLon != null && proj != null) screenAngle(proj, viewport, myLatLon, target.position) else null,
                        units = units,
                        onStop = { navTarget = null },
                        locationOn = locationOn,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    AnimatedVisibility(
                        visible = basemapSource != null && showCredit,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.weight(1f, fill = false),
                    ) { BasemapAttribution(basemapSource ?: BasemapSource.OSM) }
                    Spacer(Modifier.weight(1f))
                    if ((compass || abs(viewport.rotation) > 0.5f) && proj != null) {
                        val c = geo.toLatLon(viewport.center)
                        CompassButton(
                            northAngle = screenAngle(proj, viewport, c, Geodesy.destination(c, 0.0, 100.0)),
                            onClick = ::exitCompass,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !chromeHidden,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
        TopAppBar(
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                // Trails drawn or picked but not added yet: ask first, as when closing them.
                IconButton(onClick = { if (addingTrails && pendingCount > 0) confirmingClose = true else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                // Hidden while adding or navigating, so opening search can't discard that work.
                if (geo != null && adding == null && !navigating && !addingTrails) IconButton(onClick = {
                    closeTools()
                    searchQuery = ""
                    searching = true
                }) { Icon(Icons.Filled.Search, "Search or go to coordinates") }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "Map info") }
                MapMenu(
                    canExport = session.features.isNotEmpty(),
                    canImport = geo != null,
                    onImport = { importLauncher.launch(IMPORT_TYPES) },
                    onSavePdf = { savePdfLauncher.launch(entry.name.replace('/', '_') + ".pdf") },
                    onZoomToMap = { following = false; viewport.fit() },
                    onExport = { kml ->
                        val features = session.features.toList()
                        val layers = session.layers.toList()
                        scope.launch {
                            if (!shareFeatures(context, entry.name, features, layers, kml)) showMessage("Couldn't export the features. Storage may be full.")
                        }
                    },
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
            modifier = Modifier.onSizeChanged {
                barHeights[0] = it.height.toFloat()
                if (!chromeHidden) viewport.setInsets(top = barHeights[0])
            },
        )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // Only the search box should ride up on the keyboard; nothing else moves for it.
                // The full ime inset: padding consumes it, so the panel's own navigation-bar padding
                // then adds nothing and the panel sits right on top of the keyboard.
                .then(if (searching || holdForKeyboard) Modifier.windowInsetsPadding(WindowInsets.ime) else Modifier),
        ) {
            SnackbarHost(snackbar, Modifier.windowInsetsPadding(horizontalSafeArea))
            if (searching && geo != null) {
                SearchResults(
                    query = searchQuery,
                    features = session.features,
                    layers = session.layers,
                    myLocation = myLatLon,
                    format = format,
                    units = units,
                    onGoTo = ::goTo,
                    onAddPlacemark = { p ->
                        closeSearch()
                        following = false
                        viewport.showPoint(geo.toPage(p))
                        addPlacemark(p)
                    },
                    onShow = { f ->
                        closeSearch()
                        showFeature(f)
                    },
                    // Gets only the space left after the panel, so many results can't squeeze it.
                    modifier = Modifier.weight(1f, fill = false).windowInsetsPadding(horizontalSafeArea).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            } else {
            // Scale bar and locate button stay in both modes; with the bars hidden they sit
            // just above the navigation bar.
            val controlInsets = if (chromeHidden) barsAndCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else horizontalSafeArea
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(controlInsets).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // With the bars hidden, a small chip keeps recording visible.
                    if (chromeHidden && recordingHere) RecordingChip(recording, units)
                    if (geo != null) ScaleBar(geo, viewport, units)
                }
                Spacer(Modifier.weight(1f))
                FloatingActionButton(
                    onClick = ::locate,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.semantics {
                        stateDescription = when {
                            compass -> "Compass mode"
                            following -> "Following"
                            else -> "Not following"
                        }
                    },
                ) {
                    Icon(
                        when {
                            compass -> Icons.Filled.Explore
                            following -> Icons.Filled.GpsFixed
                            else -> Icons.Filled.GpsNotFixed
                        },
                        when {
                            compass -> "Compass mode. Tap for north up."
                            following -> "Following you. Tap for compass mode."
                            else -> "My location"
                        },
                        tint = if (following || compass) MapStyle.locationDot else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            }
            AnimatedVisibility(
                visible = !chromeHidden,
                // Expanding/shrinking too, so the locate button glides rather than jumps.
                enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
            Column {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp,
                // While placing, keep the crosshair on its target as the panel changes height.
                modifier = Modifier.onSizeChanged {
                    barHeights[1] = it.height.toFloat()
                    if (!chromeHidden) viewport.setInsets(bottom = barHeights[1], keepCenter = placing)
                },
            ) {
                Column(Modifier.windowInsetsPadding(barsAndCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                    // In every mode, so switching modes never changes the panel's height.
                    if (recordingHere) {
                        RecordingBar(
                            state = recording,
                            units = units,
                            onPauseResume = {
                                if (recording.status == TrackRecorder.Status.RECORDING) {
                                    // Let fixes batched while the screen was off arrive first.
                                    scope.launch {
                                        TrackRecorder.awaitPendingFixes()
                                        TrackRecorder.pause(context.applicationContext)
                                    }
                                }
                                else if (!hasPreciseLocation()) askForPreciseLocation()
                                else resumeGap(recording, location)?.let { resumingFar = it } ?: TrackRecorder.resume(context.applicationContext)
                            },
                            onStop = { if (!savingTrack) showStopTrack = true },
                        )
                    }
                    // Every mode fits the same fixed height, so the panel never resizes.
                    // Scales with the font size so larger text isn't clipped (still equal across modes),
                    // but never more than 40% of the screen height, so landscape keeps some map.
                    val panelHeight = maxOf(
                        PANEL_HEIGHT,
                        minOf(PANEL_HEIGHT * LocalDensity.current.fontScale.coerceIn(1f, 2f), with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.4f),
                    )
                    Box(Modifier.fillMaxWidth().height(panelHeight)) {
                    if (searching && geo != null) {
                        SearchPanel(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onGo = {
                                val matches = searchMatches(searchQuery, session.features)
                                val coordinate = CoordinateParser.parse(searchQuery)
                                when {
                                    coordinate != null -> goTo(coordinate)
                                    matches.size == 1 -> {
                                        val f = matches.single()
                                        closeSearch()
                                        showFeature(f)
                                    }
                                    // Several matches: drop the keyboard so the list is easy to pick from.
                                    else -> keyboard?.hide()
                                }
                            },
                            onClose = ::closeSearch,
                        )
                    } else if (adding != null && geo != null) {
                        val mode = adding!!
                        AddPanel(
                            mode = mode,
                            target = geo.toLatLon(viewport.center),
                            points = tapPoints,
                            format = format,
                            units = units,
                            onModeChange = { adding = it },
                            onUndo = { tapPoints.removeLastOrNull() },
                            onPlace = {
                                val at = geo.toLatLon(viewport.center)
                                closeTools()
                                addPlacemark(at)
                            },
                            onSave = { saveShape(mode == AddMode.AREA, tapPoints.toList()) },
                            onClose = ::closeTools,
                        )
                    } else if (addingTrails) {
                        TrailPanel(
                            mode = trailMode,
                            points = trailPoints.size,
                            trailNumber = pendingTrails.size + 1,
                            pending = pendingCount,
                            drawing = drawNetwork,
                            canUndo = if (trailMode == TrailMode.DRAW) trailPoints.isNotEmpty() || pendingTrails.isNotEmpty() else pickHistory.isNotEmpty(),
                            lines = mapLines,
                            selected = pickSelection.size,
                            likeLast = lastPicked?.let { i ->
                                mapLines?.let { m -> m.byStyle.getValue(m.lines[i].style).filter { it !in usedLines } }
                            },
                            likeAllSelected = lastPicked?.let { i ->
                                mapLines?.let { m -> m.byStyle.getValue(m.lines[i].style).all { it in pickSelection || it in usedLines } }
                            } ?: true,
                            onModeChange = { trailMode = it },
                            onUndo = {
                                if (trailMode == TrailMode.DRAW) {
                                    if (trailPoints.isNotEmpty()) trailPoints.removeAt(trailPoints.lastIndex)
                                    else pendingTrails.removeLastOrNull()?.let { trailPoints += it } // back into the last trail
                                } else {
                                    pickHistory.removeLastOrNull()?.let { (added, lines) ->
                                        if (added) pickSelection.removeAll(lines) else pickSelection += lines.filter { it !in pickSelection }
                                        lastPicked = pickHistory.lastOrNull()?.second?.lastOrNull()
                                    }
                                }
                            },
                            onNextTrail = ::nextTrail,
                            onSelectLike = { like ->
                                val added = like.filter { it !in pickSelection }
                                pickSelection += added
                                pickHistory += true to added
                            },
                            onConfirm = ::confirmTrails,
                            onClose = { if (pendingCount > 0) confirmingClose = true else addingTrails = false },
                        )
                    } else if (navigating) {
                        NavigatePanel(
                            points = tapPoints,
                            mode = navMode,
                            trails = trailNetwork.takeIf { alongTrails },
                            route = currentRoute,
                            hasLineLayers = alongTrails && session.features.any { it.type == FeatureType.LINE && it.layerId != null },
                            waitingForLocation = routeFromMe,
                            units = units,
                            onModeChange = { navMode = it },
                            onOpenLayers = { showList = true },
                            onAddTrails = ::startAddingTrails,
                            onImport = { importLauncher.launch(IMPORT_TYPES) },
                            onUndo = {
                                routeFromMe = false
                                tapPoints.removeLastOrNull()
                            },
                            onSave = { saveShape(false, if (alongTrails) currentRoute!!.points else tapPoints.toList()) },
                            onClose = ::closeTools,
                        )
                    } else if (pressed != null && geo != null) {
                        val at = pressed!!
                        PressedPanel(
                            at = at,
                            me = myLatLon,
                            format = format,
                            units = units,
                            onCopy = {
                                copyCoordinates(context, at)
                                pressed = null
                            },
                            onRoute = { findRoute(at) },
                            onAdd = {
                                pressed = null
                                addPlacemark(at)
                            },
                            onClose = { pressed = null },
                        )
                    } else {
                        Column {
                        StatusLine(geo, location, onMap, fixAgeSeconds, hasLocationPermission, locationOn, format, units, onRequestLocationPermission, openLocationSettings)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ToolbarButton(Icons.Filled.Add, "Add", enabled = geo != null) {
                                // Aim with the crosshair, starting at your position when you're on the map.
                                startTool()
                                if (onMap) viewport.centerOn(geo.toPage(myLatLon))
                                adding = AddMode.POINT
                            }
                            ToolbarButton(Icons.Filled.Route, "Navigate", enabled = geo != null) {
                                startTool()
                                navigating = true
                            }
                            // Works on maps without location information too: the track is kept in Tracks.
                            ToolbarButton(if (recordingHere) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, if (recordingHere) "Stop track" else "Track") {
                                when {
                                    recordingHere -> if (!savingTrack) showStopTrack = true
                                    recording.status == TrackRecorder.Status.IDLE -> startRecording()
                                    else -> askingUnfinished = true
                                }
                            }
                            ToolbarButton(Icons.Filled.Layers, "Layers", badge = session.shownFeatures.size) { showList = true }
                        }
                        }
                    }
                    }
                }
            }
            }
            }
        }
    }

    session.features.find { it.id == editingId }?.let { f ->
        FeatureEditSheet(
            feature = f,
            units = units,
            format = format,
            layerName = session.layers.find { it.id == f.layerId }?.name,
            // Tracks (recorded, or imported with times) can be extended by recording more.
            onContinueTrack = if (f.type == FeatureType.LINE && f.layerId != null && recording.status == TrackRecorder.Status.IDLE &&
                (f.track != null || session.layers.find { it.id == f.layerId }?.kind == LayerKind.TRACKS)
            ) ({ continueTrack(f) }) else null,
            trailByLayer = Trails.byLayer(f, session.layers.find { it.id == f.layerId }),
            onTrail = if (f.type == FeatureType.LINE) ({ on -> session.setFeatureTrail(f.id, on) }) else null,
            onNavigate = {
                navTarget = NavTarget(f.name, f.points.first())
                myLatLon?.let { showBoth(it, f.points.first()) }
            },
            onSave = session::upsert,
            onDelete = {
                if (f.id == selectedId) selectedId = null
                session.delete(f.id)
                scope.launch { if (replaceMessage("\"${f.name}\" deleted", "Undo")) session.upsert(f) }
            },
            onDismiss = { editingId = null }, // stays highlighted until the next tap on the map
        )
    }

    if (showList) {
        FeatureListSheet(
            features = session.features,
            layers = session.layers,
            basemap = basemapSource,
            seeThrough = session.seeThrough,
            // Needs the background drawn under the page, which needs georeferencing.
            canSeeThrough = geo != null,
            myLocation = myLatLon,
            units = units,
            format = format,
            expanded = expandedGroups,
            actions = FeatureListActions(
                onImport = {
                    if (geo == null) showMessage("This PDF has no location information, so files can't be placed on it.")
                    else importLauncher.launch(IMPORT_TYPES)
                },
                onAddTrails = {
                    if (geo == null) showMessage("This PDF has no location information, so trails can't be added to it.")
                    else {
                        showList = false
                        startAddingTrails()
                    }
                },
                onShow = { f ->
                    showList = false
                    showFeature(f)
                },
                onEdit = { f ->
                    showList = false
                    session.reveal(f) // so the edited feature is visible afterwards
                    openEditor(f.id)
                },
                onDelete = { f ->
                    if (f.id == editingId) editingId = null
                    if (f.id == selectedId) selectedId = null
                    session.delete(f.id)
                },
                onShowLayer = { layer ->
                    showList = false
                    if (!layer.visible) session.setLayerVisible(layer.id, true)
                    showFeatures(session.features.filter { it.layerId == layer.id })
                },
                onRenameLayer = { layer, name -> session.renameLayer(layer.id, name) },
                onDeleteLayer = { layer ->
                    if (session.features.any { it.layerId == layer.id && it.id == editingId }) editingId = null
                    if (session.features.any { it.layerId == layer.id && it.id == selectedId }) selectedId = null
                    session.deleteLayer(layer.id)
                },
                onLayerTrails = { layer, on, folders -> session.setLayerTrails(layer.id, on, folders) },
                onLayerGlobal = { layer, on ->
                    val global = session.library.global.value
                    val recordingInto = global.features.find { it.id == recording.continuingId }?.layerId == layer.id
                    when {
                        // It would vanish from this map's list, so it couldn't be changed back here.
                        on && session.features.none { it.layerId == layer.id && overlaps(it, session.coverage) } ->
                            showMessage("Nothing in \"${layer.name}\" is on this map, so it can't be shown on all maps from here.")
                        on -> scope.launch {
                            if (session.setLayerGlobal(layer.id, true)) showMessage("\"${layer.name}\" now shows on every map it covers.")
                        }
                        recordingInto -> showMessage("A track in \"${layer.name}\" is being recorded. Stop it first.")
                        // Other maps may show it too, so this always asks.
                        else -> localizing = layer
                    }
                },
                onLayerVisible = { layer, visible ->
                    if (!visible && session.features.any { it.layerId == layer.id && it.id == selectedId }) selectedId = null
                    session.setLayerVisible(layer.id, visible)
                },
                onBasemap = { source ->
                    // Also for maps opened later that haven't chosen yet.
                    if (session.setBasemap(source)) settings.setBasemap(source)
                },
                onSeeThrough = { session.seeThrough = it },
                onSeeThroughDone = session::saveSeeThrough,
                onFeatureVisible = { f, visible ->
                    if (!visible && f.id == selectedId) selectedId = null
                    session.setFeatureVisible(f.id, visible)
                },
            ),
            onDismiss = { showList = false },
        )
    }

    if (showStopTrack) {
        StopTrackDialog(
            state = recording,
            units = units,
            onSave = {
                showStopTrack = false
                if (!savingTrack) {
                    savingTrack = true
                    scope.launch {
                        try {
                            TrackRecorder.awaitPendingFixes()
                            saveTrack()
                        } finally {
                            savingTrack = false
                        }
                    }
                }
            },
            onDiscard = {
                showStopTrack = false
                TrackRecorder.discard(context.applicationContext)
                session.geometry?.forget(listOf(RECORDING_ID))
                showMessage("Track discarded.")
            },
            onDismiss = { showStopTrack = false },
        )
    }

    localizing?.let { layer ->
        AlertDialog(
            onDismissRequest = { localizing = null },
            title = { Text("Show only on this map?") },
            text = { Text("All of \"${layer.name}\" will move to this map, and stop showing on other maps and in Tracks.") },
            confirmButton = {
                TextButton(onClick = {
                    localizing = null
                    scope.launch {
                        if (session.setLayerGlobal(layer.id, false)) showMessage("\"${layer.name}\" now shows only on this map.")
                    }
                }) { Text("Move here") }
            },
            dismissButton = { TextButton(onClick = { localizing = null }) { Text("Cancel") } },
        )
    }

    resumingFar?.let { distance ->
        ResumeGapDialog(
            distance = distance,
            units = units,
            saveLabel = "Save, record new",
            onResume = { resumingFar = null; TrackRecorder.resume(context.applicationContext) },
            onSave = { resumingFar = null; saveAndRecordNew() },
            onDismiss = { resumingFar = null },
        )
    }

    if (askingUnfinished) {
        AlertDialog(
            onDismissRequest = { askingUnfinished = false },
            title = { Text("Unfinished track") },
            text = {
                Text(
                    "A track (${Formats.distance(recording.distance, units)}) was paused " +
                        DateUtils.getRelativeTimeSpanString(recording.pausedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        ". Save or discard it to start a new one here. To carry on with it instead, resume it in Tracks.",
                )
            },
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { askingUnfinished = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        askingUnfinished = false
                        TrackRecorder.discard(context.applicationContext)
                        startRecording()
                    }) { Text("Discard, record new") }
                    TextButton(onClick = {
                        askingUnfinished = false
                        saveAndRecordNew()
                    }) { Text("Save, record new") }
                }
            },
        )
    }

    if (confirmingClose) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            title = { Text("Add ${if (pendingCount == 1) "this trail" else "these $pendingCount trails"}?") },
            text = { Text("You've drawn or selected trails that haven't been added yet.") },
            // Three choices, wrapping onto more lines with large fonts.
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmingClose = false }) { Text("Keep editing") }
                    TextButton(onClick = { confirmingClose = false; discardTrails(); addingTrails = false }) { Text("Discard") }
                    TextButton(onClick = { confirmingClose = false; confirmTrails(); addingTrails = false }) { Text("Add") }
                }
            },
        )
    }

    if (showInfo) MapInfoDialog(entry, myLatLon, format, units, basemapSource) { showInfo = false }
}

@Composable
private fun MapMenu(
    canExport: Boolean,
    canImport: Boolean,
    onImport: () -> Unit,
    onSavePdf: () -> Unit,
    onZoomToMap: () -> Unit,
    onExport: (kml: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Zoom to whole map") }, onClick = { open = false; onZoomToMap() })
            DropdownMenuItem(text = { Text("Import GPX / KML…") }, enabled = canImport, onClick = { open = false; onImport() })
            DropdownMenuItem(text = { Text("Export features (KML)") }, enabled = canExport, onClick = { open = false; onExport(true) })
            DropdownMenuItem(text = { Text("Export features (GPX)") }, enabled = canExport, onClick = { open = false; onExport(false) })
            DropdownMenuItem(text = { Text("Export PDF…") }, onClick = { open = false; onSavePdf() })
        }
    }
}

@Composable
private fun StatusLine(
    geo: GeoReference?,
    location: Location?,
    onMap: Boolean,
    fixAgeSeconds: Double?,
    hasPermission: Boolean,
    locationOn: Boolean,
    format: CoordinateFormat,
    units: Units,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val myLatLon = location?.let { LatLon(it.latitude, it.longitude) }
    val stale = fixAgeSeconds != null && fixAgeSeconds > STALE_FIX_SECONDS
    val text: String
    var action: (() -> Unit)? = null
    var actionLabel = "Allow"
    when {
        geo == null -> text = "This PDF has no location information, so GPS is unavailable."
        !hasPermission -> { text = "Location permission is off."; action = onRequestPermission }
        !locationOn -> { text = "Device location is off."; action = onOpenLocationSettings; actionLabel = "Turn on" }
        myLatLon == null -> text = "Waiting for GPS…"
        stale -> {
            val minutes = ((fixAgeSeconds ?: 0.0) / 60).toInt()
            text = "GPS signal lost · last fix " + if (minutes < 1) "${(fixAgeSeconds ?: 0.0).toInt()} s ago" else "$minutes min ago"
        }
        !onMap -> {
            val nearest = geo.nearestPointOnMap(myLatLon)
            val dir = Geodesy.compassPoint(Geodesy.bearing(myLatLon, nearest))
            text = "Off map · ${Formats.distance(Geodesy.distance(myLatLon, nearest), units)} to map edge ($dir)"
        }
        else -> {
            val acc = location?.accuracy?.let { " ±" + Formats.distance(it.toDouble(), units) } ?: ""
            text = Formats.coordinate(myLatLon, format) + acc
        }
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, enabled: Boolean = true, badge: Int = 0, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, null, tint = tint) }
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/** The background map's credit, linking to its source's page. */
@Composable
private fun BasemapAttribution(source: BasemapSource, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    // Deliberately faint: it's on screen only briefly and shouldn't compete with the map.
    Text(
        source.shortCredit,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), MaterialTheme.shapes.extraSmall)
            .clickable(onClickLabel = "Open the ${source.label} credits page") { uriHandler.openUri(source.creditUrl) }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private enum class AddMode(val label: String) { POINT("Point"), LINE("Line"), AREA("Area") }

/** The + tool: a point aimed with the crosshair, or a line or area tapped out on the map. */
@Composable
private fun AddPanel(
    mode: AddMode,
    /** Under the crosshair, for a point. */
    target: LatLon,
    /** Tapped so far, for a line or area. */
    points: List<LatLon>,
    format: CoordinateFormat,
    units: Units,
    onModeChange: (AddMode) -> Unit,
    onUndo: () -> Unit,
    onPlace: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChips(AddMode.entries, mode, { it.label }, onModeChange, Modifier.weight(1f))
            if (mode != AddMode.POINT) IconButton(onClick = onUndo, enabled = points.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Remove last point") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel adding") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mode == AddMode.POINT) {
                Column(Modifier.weight(1f)) {
                    Text("Move the map to aim the crosshair", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Two lines if need be: DMS and UTM are wide beside the button.
                    Text(Formats.coordinate(target, format), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPlace) { Text("Place marker") }
            } else {
                val area = mode == AddMode.AREA
                val (result, isHint) = when {
                    points.size < 2 -> "Tap the map to add points" to true
                    area && points.size == 2 -> "Add one more point" to true
                    area -> "${Formats.area(Geodesy.area(points), units)} · perimeter ${Formats.distance(Geodesy.pathLength(points + points.first()), units)}" to false
                    else -> Formats.distance(Geodesy.pathLength(points), units) to false
                }
                ResultText(result, isHint, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = !isHint) { Text("Save") }
            }
        }
    }
}

/** What can be done at a long-pressed spot. */
@Composable
private fun PressedPanel(
    at: LatLon,
    me: LatLon?,
    format: CoordinateFormat,
    units: Units,
    onCopy: () -> Unit,
    onRoute: () -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    Formats.coordinate(at, format),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Announced, since it replaces the toolbar without anything being tapped there.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (me != null) {
                    val dir = Geodesy.compassPoint(Geodesy.bearing(me, at))
                    Text(
                        "${Formats.distance(Geodesy.distance(me, at), units)} $dir of you",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
        }
        // Scrolls rather than squeezing the buttons at large font sizes.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val compact = PaddingValues(horizontal = 12.dp)
            OutlinedButton(onClick = onCopy, contentPadding = compact) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = onRoute, contentPadding = compact) {
                Icon(Icons.Filled.Route, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find route")
            }
            Button(onClick = onAdd, contentPadding = compact) {
                Icon(Icons.Filled.AddLocationAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add point")
            }
        }
    }
}

/** One choice of several, as chips that scroll rather than squeeze at large font sizes. */
@Composable
private fun <T> ModeChips(modes: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in modes) FilterChip(selected = selected == m, onClick = { onSelect(m) }, label = { Text(label(m)) })
    }
}

/** A measurement, or a hint (smaller) while there isn't one yet. */
@Composable
private fun ResultText(text: String, isHint: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = if (isHint) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private enum class TrailMode(val label: String) { DRAW("Draw"), PICK("Pick from map") }

/**
 * Adding trails: drawing them point by point, or picking lines printed in the PDF. Nothing is
 * saved until it's confirmed; closing with anything pending asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrailPanel(
    mode: TrailMode,
    /** Points of the trail being drawn. */
    points: Int,
    trailNumber: Int,
    /** Trails drawn or picked, waiting to be confirmed. */
    pending: Int,
    /** The trails to snap to while drawing; null while they're found. */
    drawing: TrailNetwork?,
    canUndo: Boolean,
    /** The PDF's lines; null while they're read. */
    lines: MapLines?,
    selected: Int,
    /** The unused lines drawn like the last one tapped, if any. */
    likeLast: List<Int>?,
    likeAllSelected: Boolean,
    onModeChange: (TrailMode) -> Unit,
    onUndo: () -> Unit,
    onNextTrail: () -> Unit,
    onSelectLike: (List<Int>) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                TrailMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, TrailMode.entries.size),
                        icon = {},
                    ) { Text(m.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done adding trails") }
        }
        // The buttons go under the text when they don't fit beside it (large fonts).
        FlowRow(horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
            val compact = PaddingValues(horizontal = 12.dp)
            val like = likeLast.orEmpty()
            Text(
                if (mode == TrailMode.DRAW) {
                    when {
                        drawing == null -> "Finding the trails…"
                        points > 0 -> "Trail $trailNumber · $points ${if (points == 1) "point" else "points"}"
                        trailNumber > 1 -> "Tap to start the next trail"
                        else -> "Tap points along a trail"
                    }
                } else {
                    when {
                        lines == null -> "Reading the map's lines…"
                        lines.lines.isEmpty() -> "This map is an image, so there are no lines to pick. Draw the trails or import them."
                        // A style drawn this often is more likely contours or a grid than trails.
                        like.size > MANY_LIKE && likeAllSelected -> "That many lines drawn alike may not be trails."
                        selected == 0 -> "Tap lines on the map to select them"
                        else -> "$selected selected"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).heightIn(max = 48.dp).verticalScroll(rememberScrollState()),
            )
            if (mode == TrailMode.DRAW && points > 0) {
                TextButton(onClick = onNextTrail, enabled = points >= 2, contentPadding = compact) { Text("Next trail") }
            }
            if (mode == TrailMode.PICK && like.size > 1 && !likeAllSelected) {
                TextButton(onClick = { onSelectLike(like) }, contentPadding = compact) { Text("All ${like.size} like it") }
            }
            if (pending > 0) {
                Button(onClick = onConfirm, contentPadding = compact) { Text("Confirm $pending ${if (pending == 1) "trail" else "trails"}") }
            }
        }
    }
}

private const val MANY_LIKE = 300

private enum class NavMode(val label: String) { TRAILS("Trails"), DISTANCE("Distance") }

/** Navigate: the way between tapped points, along trails or straight, which can be saved as a line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NavigatePanel(
    points: List<LatLon>,
    mode: NavMode,
    /** Along trails: the network, null while it's built. */
    trails: TrailNetwork?,
    /** Along trails: the route through [points], null while it's found. */
    route: TrailRoute?,
    /** Whether any layer has lines that could be used as trails. */
    hasLineLayers: Boolean,
    /** "Find route" is waiting for a GPS fix to start from. */
    waitingForLocation: Boolean,
    units: Units,
    onModeChange: (NavMode) -> Unit,
    onOpenLayers: () -> Unit,
    onAddTrails: () -> Unit,
    onImport: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChips(NavMode.entries, mode, { it.label }, onModeChange, Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = points.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Remove last point") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close navigate") }
        }
        if (mode == NavMode.TRAILS && trails?.isEmpty == true) {
            // Nothing to save: the ways to get trails instead (under the text if they don't fit).
            FlowRow(horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                val link = LinkAnnotation.Clickable(
                    "layers",
                    TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)),
                ) { onOpenLayers() }
                Text(
                    buildAnnotatedString {
                        if (hasLineLayers) {
                            append("Turn on trail lines in ")
                            withLink(link) { append("Layers") }
                        } else {
                            append("No usable trail data on this map.")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    // Scrolls rather than being cut off on narrow screens or at large font sizes.
                    modifier = Modifier.weight(1f).heightIn(max = 48.dp).verticalScroll(rememberScrollState()),
                )
                TextButton(onClick = onImport) { Text("Import") }
                Button(onClick = onAddTrails) { Text("Add trails") }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (result, isHint) = when {
                mode == NavMode.TRAILS && trails == null -> "Finding the trails…" to true
                waitingForLocation && points.size < 2 -> "Waiting for GPS, or tap where to start" to true
                points.size < 2 -> "Tap the map to add points" to true
                mode == NavMode.TRAILS && route == null -> "Finding the route…" to true
                mode == NavMode.TRAILS -> buildString {
                    // The whole walk; how much of it isn't on a trail, if any.
                    append(Formats.distance(route!!.length, units))
                    if (route.offTrail >= 1) append(" · ").append(Formats.distance(route.offTrail, units)).append(" off trail")
                    else append(" along trails")
                } to false
                else -> Formats.distance(Geodesy.pathLength(points), units) to false
            }
            ResultText(result, isHint, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = !isHint) { Text("Save line") }
        }
    }
}

/** A scale bar for the centre of the screen, at a round length no wider than ~100dp. */
@Composable
private fun ScaleBar(geo: GeoReference, viewport: MapViewport, units: Units) {
    val density = LocalDensity.current
    val maxPx = with(density) { 100.dp.toPx() }
    val c = viewport.center
    val metersPerPx = Geodesy.distance(
        geo.toLatLon(c),
        geo.toLatLon(PagePoint(c.x + maxPx / viewport.scale, c.y)),
    ) / maxPx
    if (metersPerPx <= 0 || metersPerPx.isNaN()) return
    val (meters, label) = niceScaleLength(metersPerPx * maxPx, units)
    val widthDp = with(density) { (meters / metersPerPx).toFloat().toDp() }
    Column(
        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.small).padding(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        Box(Modifier.width(widthDp).height(4.dp).background(MaterialTheme.colorScheme.onSurface))
    }
}

private fun niceScaleLength(maxMeters: Double, units: Units): Pair<Double, String> {
    val unit = when {
        units == Units.METRIC -> 1.0
        maxMeters < 1609.344 / 4 -> 0.3048 // feet
        else -> 1609.344 // miles
    }
    val maxUnits = maxMeters / unit
    var nice = 1e-3
    for (exp in -3..6) for (m in listOf(1.0, 2.0, 5.0)) {
        val v = m * Math.pow(10.0, exp.toDouble())
        if (v <= maxUnits) nice = v
    }
    val label = when {
        units == Units.METRIC && nice >= 1000 -> "${fmt(nice / 1000)} km"
        units == Units.METRIC -> "${fmt(nice)} m"
        unit == 0.3048 -> "${fmt(nice)} ft"
        else -> "${fmt(nice)} mi"
    }
    return nice * unit to label
}

private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
private fun MapInfoDialog(entry: MapEntry, me: LatLon?, format: CoordinateFormat, units: Units, basemap: BasemapSource?, onDismiss: () -> Unit) {
    val geo = entry.geo
    val rows = buildList {
        add("File size" to android.text.format.Formatter.formatShortFileSize(LocalContext.current, entry.fileSize))
        if (basemap != null) add("Background map" to "${basemap.label} · ${basemap.credit}")
        add("Page size" to "${entry.pageWidth} × ${entry.pageHeight} pt")
        if (entry.pageCount > 1) add("Pages" to "${entry.pageCount}; only the first is shown")
        if (geo == null) {
            add("Georeferenced" to "No")
        } else {
            add("Coordinate system" to (geo.crsName ?: "Unknown"))
            if (geo.oldDatum) add("Accuracy" to "This datum's shift from GPS (WGS84) isn't applied, so positions may be off by tens to hundreds of metres.")
            add("Map centre" to Formats.coordinate(geo.center, format))
            if (me != null) {
                val d = geo.distanceFromMap(me)
                add("You are" to if (d == 0.0) "On this map" else "${Formats.distance(d, units)} away")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, v) in rows) {
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

private const val MAX_IMPORT_BYTES = 50 * 1024 * 1024

/** Listed first in the picker; any file is accepted because providers often mislabel GPX/KML. */
private val IMPORT_TYPES = arrayOf(
    "application/gpx+xml",
    "application/vnd.google-earth.kml+xml",
    "application/vnd.google-earth.kmz",
    "application/xml",
    "text/xml",
    "application/octet-stream",
    "*/*",
)

private const val RECORDING_ID = "__recording"
private const val STALE_FIX_SECONDS = 30

/** Height of the bottom panel's content in every mode (toolbar, adding, navigating, search). */
private val PANEL_HEIGHT = 112.dp
