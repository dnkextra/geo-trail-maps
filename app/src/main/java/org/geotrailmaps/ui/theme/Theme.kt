package org.geotrailmaps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * App-wide look. Change colours here (app chrome) and in MapStyle (things drawn on the map).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF455A64),
    surface = Color(0xFFFDFDFD),
    surfaceContainer = Color(0xFFF1F4F3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0BEC5),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Colours and sizes of everything drawn on top of the map. */
object MapStyle {
    /** Behind the PDF page (and behind the background map while its tiles load). */
    val background = Color(0xFFD6D6D6)
    val pageOutline = Color(0x99000000)

    val locationDot = Color(0xFF1E88E5)
    /** The dot when GPS has gone quiet. */
    val locationStale = Color(0xFF9E9E9E)
    val recordingTrack = Color(0xFFD81B60)
    val navigationLine = Color(0xFF1E88E5)
    val locationDotBorder = Color.White
    val locationAccuracy = Color(0x261E88E5)
    val locationHeading = Color(0x661E88E5)
    val locationDotRadius = 8.dp
    val headingLength = 46.dp

    val measureLine = Color(0xFFE53935)
    val measureFill = Color(0x33E53935)
    val measureVertexRadius = 5.dp

    /** Trails while adding them, and the layers they're added to. */
    val trailLine = Color(0xFFFB8C00)
    /** A faint tint along lines in the PDF that can be picked as trails. */
    val pickableHint = Color(0x4000B8D4)

    val lineWidth = 4.dp
    val areaFillAlpha = 0.2f
    val pinRadius = 10.dp
    val pinHeight = 28.dp
    val selectionHalo = Color(0xFFFFEB3B)

    /** Colours offered for placemarks, lines and areas (names are read by screen readers). */
    val featureColors = linkedMapOf(
        "Red" to Color(0xFFE53935),
        "Orange" to Color(0xFFFB8C00),
        "Yellow" to Color(0xFFFDD835),
        "Green" to Color(0xFF43A047),
        "Blue" to Color(0xFF1E88E5),
        "Purple" to Color(0xFF8E24AA),
        "Black" to Color(0xFF212121),
    )
    val placemarkColor = featureColors.getValue("Red")
    val lineColor = featureColors.getValue("Blue")
}
