package org.geotrailmaps.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.MarkerIcon
import org.geotrailmaps.data.MarkerType
import java.util.WeakHashMap

/** Icons turned into paths once (by identity; dropped with their set). */
private val iconPaths = WeakHashMap<MarkerIcon, List<Pair<Path, MarkerIcon.Part>>>()

private fun paths(icon: MarkerIcon): List<Pair<Path, MarkerIcon.Part>> = iconPaths.getOrPut(icon) {
    icon.parts.mapNotNull { part ->
        // Path data that doesn't parse is left out rather than losing the whole icon.
        runCatching { PathParser().parsePathString(part.pathData).toPath() }.getOrNull()?.let { path ->
            if (part.evenOdd) path.fillType = PathFillType.EvenOdd
            path to part
        }
    }
}

/** Draws [icon] in one colour, fitted into a [size] px square centred on [center]. */
fun DrawScope.drawMarkerIcon(icon: MarkerIcon, center: Offset, size: Float, color: Color) {
    val s = size / maxOf(icon.width, icon.height)
    translate(center.x - (icon.minX + icon.width / 2) * s, center.y - (icon.minY + icon.height / 2) * s) {
        scale(s, s, Offset.Zero) {
            for ((path, part) in paths(icon)) {
                if (part.fill) drawPath(path, color)
                part.strokeWidth?.let {
                    drawPath(path, color, style = Stroke(it, cap = if (part.roundCaps) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * A marker type as in its pins: its icon in white on a disc of its colour. A plain placemark
 * (null) is a pin icon in [plainColor].
 */
@Composable
fun MarkerBadge(type: MarkerType?, modifier: Modifier = Modifier, size: Dp = 24.dp, plainColor: Color = Color.Unspecified) {
    if (type == null) {
        Icon(Icons.Filled.Place, null, modifier.size(size), tint = plainColor)
        return
    }
    val color = Color(type.color)
    Canvas(modifier.size(size)) {
        drawCircle(color)
        type.icon?.let { drawMarkerIcon(it, center, this.size.minDimension * 0.62f, Color.White) }
            ?: drawCircle(Color.White, this.size.minDimension * 0.16f)
    }
}
