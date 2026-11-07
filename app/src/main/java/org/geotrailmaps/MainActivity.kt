package org.geotrailmaps

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.geotrailmaps.data.ImportException
import org.geotrailmaps.data.MapLibrary
import org.geotrailmaps.data.Settings
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.location.LocationTracker
import org.geotrailmaps.render.BasemapTiles
import org.geotrailmaps.track.TrackRecorder
import org.geotrailmaps.ui.MapListScreen
import org.geotrailmaps.ui.SettingsScreen
import org.geotrailmaps.ui.map.MapScreen
import org.geotrailmaps.ui.theme.AppTheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TrackRecorder.init(this)
    }

    val library by lazy { MapLibrary(this) }
    val settings by lazy { Settings(this) }
    val tracker by lazy { LocationTracker(this) }
    val basemap by lazy { BasemapTiles(this) }
}

class MainActivity : ComponentActivity() {
    /** A file handed to us by another app ("Open with" / "Share"). */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        // The root Surface gives every screen the theme's background and text colours.
        setContent { AppTheme { Surface { AppRoot(application as App, incoming) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incoming.value = uri
    }
}

@Composable
private fun AppRoot(app: App, incoming: MutableStateFlow<Uri?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val activity = LocalActivity.current
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun permissionGranted() = locationPermissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var hasLocationPermission by remember { mutableStateOf(permissionGranted()) }
    // After repeated denials Android stops showing the dialog; then we send the user to app settings.
    var dialogBlocked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocationPermission = it.values.any { granted -> granted }
        dialogBlocked = !hasLocationPermission &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false
    }
    val requestLocationPermission = {
        if (dialogBlocked) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            permissionLauncher.launch(locationPermissions)
        }
    }
    LaunchedEffect(Unit) { if (!hasLocationPermission) requestLocationPermission() }
    LifecycleStartEffect(hasLocationPermission) {
        hasLocationPermission = permissionGranted() // may have been granted in system settings
        if (hasLocationPermission) app.tracker.start()
        onStopOrDispose { app.tracker.stop() }
    }

    var openMapId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastMapId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openMapId) { openMapId?.let { lastMapId = it } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    /** Imports [uris] one by one. A single map opens; several stay in the list, with a summary. */
    fun import(uris: List<Uri>) = scope.launch {
        importing = true
        val imported = mutableListOf<String>()
        val errors = mutableListOf<String>()
        try {
            for (uri in uris) {
                try {
                    imported += app.library.import(uri).id
                } catch (e: ImportException) {
                    errors += e.message ?: "Import failed"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "Import failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        } finally {
            importing = false
        }
        when {
            uris.size == 1 && imported.size == 1 -> openMapId = imported.single()
            errors.isEmpty() -> snackbar.showSnackbar("Imported ${imported.size} maps")
            imported.isEmpty() && errors.size == 1 -> snackbar.showSnackbar(errors.single())
            // The first problem, and how many more, so one bad file doesn't hide the rest.
            else -> snackbar.showSnackbar(
                "Imported ${imported.size} of ${uris.size} maps. ${errors.first()}" +
                    if (errors.size > 1) " (and ${errors.size - 1} more)" else "",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) import(uris) }

    /** The map whose PDF is being saved, while the system's save dialog is open. */
    var savingPdfOf by rememberSaveable { mutableStateOf<String?>(null) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = savingPdfOf
        savingPdfOf = null
        if (uri != null && id != null) scope.launch {
            snackbar.showSnackbar(if (app.library.exportPdf(id, uri)) "Exported the PDF." else "Couldn't export the PDF.")
        }
    }

    val pending by incoming.collectAsState()
    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        incoming.value = null
        openMapId = null
        showSettings = false
        import(listOf(uri))
    }

    val maps by app.library.maps.collectAsState()
    val location by app.tracker.location.collectAsState()
    val units by app.settings.units.collectAsState()
    val mapSort by app.settings.mapSort.collectAsState()
    val openEntry = maps.find { it.id == openMapId }

    when {
        showSettings -> SettingsScreen(app.settings, onBack = { showSettings = false })
        openEntry != null -> MapScreen(
            entry = openEntry,
            library = app.library,
            settings = app.settings,
            tracker = app.tracker,
            basemap = app.basemap,
            allMaps = maps,
            onOpenMap = { openMapId = it },
            hasLocationPermission = hasLocationPermission,
            onRequestLocationPermission = requestLocationPermission,
            onBack = { openMapId = null },
        )
        else -> MapListScreen(
            maps = maps,
            thumbnail = app.library::thumbnailFile,
            myLocation = location?.let { LatLon(it.latitude, it.longitude) },
            units = units,
            importing = importing,
            snackbar = snackbar,
            onImport = { picker.launch(arrayOf("application/pdf", "application/octet-stream")) },
            onOpen = { openMapId = it },
            onRecordingClick = lastMapId?.takeIf { id -> maps.any { it.id == id } }?.let { id -> { openMapId = id } },
            onRename = app.library::rename,
            onDelete = app.library::delete,
            onSavePdf = { map ->
                savingPdfOf = map.id
                savePdf.launch(map.name.replace('/', '_') + ".pdf")
            },
            onMoveToFolder = app.library::setFolder,
            onSetArchived = app.library::setArchived,
            sort = mapSort,
            onSortChange = app.settings::setMapSort,
            onSettings = { showSettings = true },
        )
    }
}
