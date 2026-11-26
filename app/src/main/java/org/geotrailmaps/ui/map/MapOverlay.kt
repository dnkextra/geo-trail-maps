package org.geotrailmaps.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.geo.GeoReference
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.TrailRoute
import org.geotrailmaps.geo.pointInPolygon
import org.geotrailmaps.ui.theme.MapStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/** Converts lat/lon to screen pixels for the current view. */
class MapProjection(private val geo: GeoReference, private val viewport: MapViewport) {
    fun toScreen(p: LatLon): Offset = viewport.toScreen(geo.toPage(p))
    fun toLatLon(screen: Offset): LatLon = geo.toLatLon(viewport.toPage(screen))
}

/**
 * Screen points of a shape, culled to the canvas and thinned to about one per pixel, added to
 * [into] (a new path by default); null if off screen.
 */
private fun screenPath(shape: FeatureGeometry.Shape, viewport: MapViewport, bounds: Rect, close: Boolean, into: Path = Path()): Path? {
    val s = viewport.scale
    val o = viewport.offset
    val margin = 50f
    if (shape.maxX * s + o.x < bounds.left - margin || shape.minX * s + o.x > bounds.right + margin ||
        shape.maxY * s + o.y < bounds.top - margin || shape.minY * s + o.y > bounds.bottom + margin
    ) return null
    val xy = shape.xy
    val path = into
    var lastX = 0f
    var lastY = 0f
    val n = xy.size / 2
    for (i in 0 until n) {
        val x = xy[2 * i] * s + o.x
        val y = xy[2 * i + 1] * s + o.y
        if (i == 0 || shape.startsSegment(i)) {
            path.moveTo(x, y)
        } else if (i == n - 1 || shape.startsSegment(i + 1) || kotlin.math.abs(x - lastX) + kotlin.math.abs(y - lastY) >= 1.5f) {
            path.lineTo(x, y)
        } else {
            continue
        }
        lastX = x
        lastY = y
    }
    if (close) path.close()
    return path
}

/**
 * Line width for [metersPerDp] of ground: fine when zoomed in to read the map's detail, fuller
 * when zoomed out where the line itself is what you're looking at.
 */
internal fun quietLineWidthDp(metersPerDp: Double): Float {
    val (inMpd, outMpd) = MapStyle.quietLineZoomRange
    if (!metersPerDp.isFinite() || metersPerDp <= 0) return MapStyle.quietLineMid
    val t = ((ln(metersPerDp) - ln(inMpd)) / (ln(outMpd) - ln(inMpd))).coerceIn(0.0, 1.0).toFloat()
    return MapStyle.quietLineIn + (MapStyle.quietLineOut - MapStyle.quietLineIn) * t
}

/**
 * Lines and areas of [features]; points are drawn separately by [drawPins], so they can go on top of everything.
 *
 * Lines are kept quiet so the map under them stays readable: thin ([lineWidthDp]), with a faint
 * edge in the opposite tone to the line, and all faded together as one layer, so tracks that
 * overlap or double back stay one light tint instead of building up. Only [selectedId] is bold.
 */
fun DrawScope.drawShapes(features: List<Feature>, selectedId: String?, geometry: FeatureGeometry, viewport: MapViewport, lineWidthDp: Float) {
    val bounds = viewport.visibleViewBounds()
    val quiet = ArrayList<Pair<Path, Color>>()
    var selected: Pair<Path, Color>? = null
    for (f in features) {
        if (f.type == FeatureType.POINT) continue
        val color = Color(f.color)
        val path = screenPath(geometry.shape(f), viewport, bounds, close = f.type == FeatureType.AREA) ?: continue
        if (f.type == FeatureType.AREA) drawPath(path, color.copy(alpha = MapStyle.areaFillAlpha))
        if (f.id == selectedId) selected = path to color else quiet += path to color
    }
    if (quiet.isNotEmpty()) {
        val width = lineWidthDp * density
        val edge = Stroke(width + MapStyle.quietEdge.toPx() * 2, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = MapStyle.quietLineAlpha })
        // Every edge before any line, so one line's edge never cuts across another line.
        for ((path, color) in quiet) drawPath(path, edgeFor(color), style = edge)
        for ((path, color) in quiet) drawPath(path, color, style = line)
        drawContext.canvas.restore()
    }
    selected?.let { (path, color) ->
        drawPath(path, MapStyle.selectionHalo, style = Stroke(MapStyle.lineWidth.toPx() * 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // A contrasting casing keeps the line readable on any map colour.
        drawPath(path, casingFor(color), style = Stroke(MapStyle.lineWidth.toPx() * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(MapStyle.lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The points of [features] as pins. */
fun DrawScope.drawPins(features: List<Feature>, selectedId: String?, geometry: FeatureGeometry, viewport: MapViewport) {
    val bounds = viewport.visibleViewBounds()
    for (f in features) {
        if (f.type != FeatureType.POINT) continue
        val xy = geometry.shape(f).xy
        val tip = Offset(xy[0] * viewport.scale + viewport.offset.x, xy[1] * viewport.scale + viewport.offset.y)
        if (tip.x < bounds.left - 100f || tip.x > bounds.right + 100f || tip.y < bounds.top - 100f || tip.y > bounds.bottom + 100f) continue
        // Pins stay upright when the map is rotated.
        rotate(-viewport.rotation, tip) { drawPin(tip, Color(f.color), f.id == selectedId, f.icon) }
    }
}

/**
 * A teardrop pin: a round head narrowing to a point at [tip]. The point sets it apart from
 * the round location dot even at a glance. An [icon] (emoji) takes the place of the dot, on a
 * white disc so it reads on any pin colour; the pin stays the same size.
 */
private fun DrawScope.drawPin(tip: Offset, color: Color, selected: Boolean, icon: String?) {
    val r = MapStyle.pinRadius.toPx()
    val head = tip - Offset(0f, MapStyle.pinHeight.toPx() - r)
    val path = teardrop(tip, head, r)
    if (selected) drawPath(path, MapStyle.selectionHalo, style = Stroke(r * 1.4f, join = StrokeJoin.Round))
    drawCircle(Color.Black.copy(alpha = 0.35f), 2f * density, tip)
    // White casing, with a thin dark rim so it still shows on white paper.
    drawPath(path, Color.Black.copy(alpha = 0.3f), style = Stroke(5f * density, join = StrokeJoin.Round))
    drawPath(path, Color.White, style = Stroke(4f * density, join = StrokeJoin.Round))
    drawPath(path, color)
    if (icon == null) {
        drawCircle(Color.White, r * 0.38f, head)
    } else {
        drawCircle(Color.White, r * 0.8f, head)
        drawEmoji(icon, head, r * 1.15f)
    }
}

// Lazy: this file's helpers are also used in JVM tests, where Paint can't be made.
private val emojiPaint by lazy {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
}

/** [text] centred on [center], [size] px high. */
private fun DrawScope.drawEmoji(text: String, center: Offset, size: Float) {
    emojiPaint.textSize = size
    val fm = emojiPaint.fontMetrics
    drawContext.canvas.nativeCanvas.drawText(text, center.x, center.y - (fm.ascent + fm.descent) / 2, emojiPaint)
}

/** The outline of a circle of radius [r] around [head], with tangents meeting at [tip] below it. */
private fun teardrop(tip: Offset, head: Offset, r: Float): Path {
    val d = (tip - head).getDistance()
    // Angle between "straight down" and each tangent point, seen from the centre.
    val a = Math.toDegrees(kotlin.math.acos((r / d).coerceAtMost(1f)).toDouble()).toFloat()
    return Path().apply {
        moveTo(tip.x, tip.y)
        // From the right tangent point, anticlockwise over the top to the left one.
        arcTo(Rect(head, r), 90f - a, -(360f - 2 * a), forceMoveTo = false)
        close()
    }
}

fun DrawScope.drawMeasurement(points: List<LatLon>, area: Boolean, proj: MapProjection, route: TrailRoute? = null) {
    if (points.isEmpty()) return
    val pts = points.map(proj::toScreen)
    fun line(path: Path, dashed: Boolean) {
        drawPath(path, casingFor(MapStyle.measureLine), style = Stroke(MapStyle.lineWidth.toPx() * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(
            path, MapStyle.measureLine,
            style = Stroke(
                width = MapStyle.lineWidth.toPx() * 0.75f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10 * density, 6 * density)) else null,
            ),
        )
    }
    if (route != null) {
        for (piece in route.pieces) line(pathOf(piece.points.map(proj::toScreen), close = false), dashed = !piece.onTrail)
    } else {
        val path = pathOf(pts, close = area && pts.size > 2)
        if (area && pts.size > 2) drawPath(path, MapStyle.measureFill)
        line(path, dashed = true)
    }
    for (p in pts) {
        drawCircle(Color.White, MapStyle.measureVertexRadius.toPx() + 1.5f * density, p)
        drawCircle(MapStyle.measureLine, MapStyle.measureVertexRadius.toPx(), p)
    }
}

/** Trails while adding them, hidden or not, in one style so you can see what a new one joins. */
fun DrawScope.drawTrails(trails: List<Feature>, geometry: FeatureGeometry, viewport: MapViewport) {
    val bounds = viewport.visibleViewBounds()
    val path = Path()
    for (f in trails) screenPath(geometry.shape(f), viewport, bounds, close = false, into = path)
    drawPath(path, casingFor(MapStyle.trailLine), style = Stroke(MapStyle.lineWidth.toPx() * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, MapStyle.trailLine, style = Stroke(MapStyle.lineWidth.toPx() * 0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Lines in the PDF that can be picked: a faint tint along each, a strong halo round [selected]. */
fun DrawScope.drawPickableLines(lines: MapLines, used: Set<Int>, selected: Set<Int>, viewport: MapViewport) {
    val bounds = viewport.visibleViewBounds()
    val pickable = Path()
    val chosen = Path()
    lines.lines.forEachIndexed { i, line ->
        when {
            i in selected -> screenPath(line.shape, viewport, bounds, close = false, into = chosen)
            i !in used -> screenPath(line.shape, viewport, bounds, close = false, into = pickable)
        }
    }
    // Just a hint: the map should still look like itself, so its own lines can be told apart.
    drawPath(pickable, MapStyle.pickableHint, style = Stroke(1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(chosen, MapStyle.selectionHalo, style = Stroke(MapStyle.lineWidth.toPx() * 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A point of a trail being drawn: [joined] when it's on an existing trail. */
data class TrailPoint(val at: LatLon, val joined: Boolean)

/** The trail being drawn: filled dots where it joins a trail, hollow ones elsewhere. */
fun DrawScope.drawTrailInProgress(points: List<TrailPoint>, proj: MapProjection) {
    if (points.isEmpty()) return
    val pts = points.map { proj.toScreen(it.at) }
    val path = pathOf(pts, close = false)
    drawPath(path, casingFor(MapStyle.trailLine), style = Stroke(MapStyle.lineWidth.toPx() * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, MapStyle.trailLine, style = Stroke(MapStyle.lineWidth.toPx() * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val r = MapStyle.measureVertexRadius.toPx()
    points.forEachIndexed { i, p ->
        drawCircle(Color.White, r + 1.5f * density, pts[i])
        if (p.joined) drawCircle(MapStyle.trailLine, r, pts[i])
        else drawCircle(MapStyle.trailLine, r - 1f * density, pts[i], style = Stroke(2f * density))
    }
}

/** The position dot; [stale] when the last fix is old (grey, no accuracy circle or heading). */
fun DrawScope.drawLocation(location: LatLon, accuracyMeters: Float?, headingDeg: Float?, proj: MapProjection, stale: Boolean = false) {
    val c = proj.toScreen(location)
    if (!stale && accuracyMeters != null && accuracyMeters > 0) {
        val edge = proj.toScreen(Geodesy.destination(location, 90.0, accuracyMeters.toDouble()))
        val radius = hypot(edge.x - c.x, edge.y - c.y)
        drawCircle(MapStyle.locationAccuracy, radius, c)
        drawCircle(MapStyle.locationDot.copy(alpha = 0.4f), radius, c, style = Stroke(1f * density))
    }
    if (!stale && headingDeg != null) {
        // Direction on screen, which accounts for the map not being north-up.
        val ahead = proj.toScreen(Geodesy.destination(location, headingDeg.toDouble(), 50.0))
        val angle = atan2(ahead.y - c.y, ahead.x - c.x)
        val len = MapStyle.headingLength.toPx()
        val spread = Math.toRadians(28.0).toFloat()
        val cone = Path().apply {
            moveTo(c.x, c.y)
            lineTo(c.x + len * cos(angle - spread), c.y + len * sin(angle - spread))
            lineTo(c.x + len * cos(angle + spread), c.y + len * sin(angle + spread))
            close()
        }
        drawPath(cone, MapStyle.locationHeading)
    }
    val r = MapStyle.locationDotRadius.toPx()
    drawCircle(Color.Black.copy(alpha = 0.2f), r + 4f * density, c)
    drawCircle(MapStyle.locationDotBorder, r + 2.5f * density, c)
    drawCircle(if (stale) MapStyle.locationStale else MapStyle.locationDot, r, c)
}

/** Aiming crosshair for placing a placemark, with a casing so it shows on any background. */
fun DrawScope.drawCrosshair(c: Offset) {
    val arm = 18f * density
    val gap = 5f * density
    for ((color, width) in listOf(Color.White to 5f * density, Color(0xFF212121) to 2f * density)) {
        drawLine(color, c - Offset(arm, 0f), c - Offset(gap, 0f), width, StrokeCap.Round)
        drawLine(color, c + Offset(gap, 0f), c + Offset(arm, 0f), width, StrokeCap.Round)
        drawLine(color, c - Offset(0f, arm), c - Offset(0f, gap), width, StrokeCap.Round)
        drawLine(color, c + Offset(0f, gap), c + Offset(0f, arm), width, StrokeCap.Round)
    }
    drawCircle(Color.White, 3f * density, c)
    drawCircle(Color(0xFF212121), 1.8f * density, c)
}

/** A faint edge in the opposite tone to [color], so a quiet line reads on any background. */
private fun edgeFor(color: Color) =
    (if (color.luminance() > 0.6f) Color.Black else Color.White).copy(alpha = MapStyle.quietEdgeAlpha)

private fun casingFor(color: Color) =
    if (color.luminance() > 0.6f) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.8f)

private fun pathOf(pts: List<Offset>, close: Boolean) = Path().apply {
    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}

/** Finds the feature under a tap, preferring pins. */
fun hitTest(features: List<Feature>, tap: Offset, geometry: FeatureGeometry, viewport: MapViewport, density: Float, pinHeightPx: Float): Feature? {
    val touch = 24f * density
    val s = viewport.scale
    val o = viewport.offset
    features.filter { it.type == FeatureType.POINT }
        .map { f ->
            val xy = geometry.shape(f).xy
            val tip = Offset(xy[0] * s + o.x, xy[1] * s + o.y)
            // Pins are drawn upright on screen, so in view space their head turns with the map.
            val head = tip + rotateBy(Offset(0f, -pinHeightPx), -viewport.rotation)
            f to minOf(distanceToSegment(tap, tip, head), (tap - head).getDistance())
        }
        .filter { it.second < touch }
        .minByOrNull { it.second }
        ?.let { return it.first }

    // Lines and areas: skip shapes whose bounds are away from the tap, then test segments
    // straight from the cached coordinates (no per-point allocation for long tracks).
    val reach = touch * 0.75f
    return features.filter { it.type != FeatureType.POINT }.lastOrNull { f ->
        val shape = geometry.shape(f)
        if (tap.x < shape.minX * s + o.x - reach || tap.x > shape.maxX * s + o.x + reach ||
            tap.y < shape.minY * s + o.y - reach || tap.y > shape.maxY * s + o.y + reach
        ) return@lastOrNull false
        val xy = shape.xy
        val n = xy.size / 2
        val segments = if (f.type == FeatureType.AREA) n else n - 1
        (0 until segments).any { i ->
            val j = (i + 1) % n
            !shape.startsSegment(j) && distanceToSegment(
                tap,
                Offset(xy[2 * i] * s + o.x, xy[2 * i + 1] * s + o.y),
                Offset(xy[2 * j] * s + o.x, xy[2 * j + 1] * s + o.y),
            ) < reach
        } || (f.type == FeatureType.AREA && pointInPolygon(tap.x.toDouble(), tap.y.toDouble(), n, { (xy[2 * it] * s + o.x).toDouble() }, { (xy[2 * it + 1] * s + o.y).toDouble() }))
    }
}

internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}
