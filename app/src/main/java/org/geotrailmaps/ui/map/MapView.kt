package org.geotrailmaps.ui.map

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.ui.theme.MapStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pan/zoom state: view = page * scale + offset, in PDF points. The map can also be rotated
 * ([rotation], clockwise degrees about [pivot]) for compass mode: drawing happens in the
 * unrotated "view" space inside a rotated canvas, and touches are converted back with
 * [screenToView].
 */
@Stable
class MapViewport(val pageWidth: Float, val pageHeight: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set

    // Screen covered by the translucent top and bottom bars. Fitting and centring use the
    // visible area between them.
    // State, so drawing that depends on the pivot follows inset changes.
    private var insetTop by mutableFloatStateOf(0f)
    private var insetBottom by mutableFloatStateOf(0f)
    private val visibleHeight get() = (size.height - insetTop - insetBottom).coerceAtLeast(1f)

    /** True until the user moves the map, so the first layout of the bars can re-fit it. */
    private var fitted = true
    private var insetsSettled = false

    private val fitScale get() = min(size.width / pageWidth, visibleHeight / pageHeight)
    // Zooming well out is useful with the background map: it shows where the map sits.
    private val minScale get() = fitScale * 0.125f
    private val maxScale get() = max(fitScale * 64f, 16f)

    /** Page to view coordinates (what the drawing code uses). */
    fun toScreen(p: PagePoint) = Offset(p.x.toFloat() * scale + offset.x, p.y.toFloat() * scale + offset.y)
    fun toPage(o: Offset) = PagePoint(((o.x - offset.x) / scale).toDouble(), ((o.y - offset.y) / scale).toDouble())

    var rotation by mutableFloatStateOf(0f)

    /** Centre of the visible area; the map rotates about it. */
    val pivot: Offset get() = Offset(size.width / 2f, insetTop + visibleHeight / 2f)

    fun screenToView(o: Offset): Offset = rotateBy(o - pivot, -rotation) + pivot
    fun viewToScreen(o: Offset): Offset = rotateBy(o - pivot, rotation) + pivot

    /** The screen's bounding box in view coordinates (larger than the screen while rotated). */
    fun visibleViewBounds(): Rect {
        if (rotation == 0f) return Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val corners = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f), Offset(0f, size.height.toFloat()), Offset(size.width.toFloat(), size.height.toFloat()))
            .map(::screenToView)
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    val center: PagePoint get() = toPage(pivot)

    fun onSizeChanged(newSize: IntSize) {
        if (newSize.width == 0 || newSize.height == 0) return
        val keep = if (fitted || size == IntSize.Zero) null else center
        size = newSize
        if (keep == null) fit() else centerOn(keep)
    }

    /**
     * Called as the bars are measured. Re-fits only during the first layout; later changes
     * (e.g. switching to the Navigate panel) leave the map where it is.
     */
    fun setInsets(top: Float = insetTop, bottom: Float = insetBottom, keepCenter: Boolean = false) {
        val keep = if (keepCenter && size != IntSize.Zero) center else null
        val oldPivot = pivot
        insetTop = top
        insetBottom = bottom
        // The map rotates about the pivot, which just moved: shift the offset so nothing on
        // screen moves (screen = R(view - pivot) + pivot).
        if (rotation != 0f) {
            val d = oldPivot - pivot
            offset += rotateBy(d, -rotation) - d
        }
        if (fitted && !insetsSettled && size != IntSize.Zero) fit() else keep?.let { centerOn(it, scale, userMove = false) }
        if (insetTop > 0 && insetBottom > 0) insetsSettled = true
    }

    fun fit() {
        scale = fitScale
        offset = Offset((size.width - pageWidth * scale) / 2, insetTop + (visibleHeight - pageHeight * scale) / 2)
        fitted = true
    }

    fun zoomBy(factor: Float, focus: Offset) {
        fitted = false
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val f = newScale / scale
        offset = focus - (focus - offset) * f
        scale = newScale
    }

    fun panBy(delta: Offset) {
        fitted = false
        // Keep part of the page in the area between the bars, so the map can't get lost.
        val w = pageWidth * scale
        val h = pageHeight * scale
        val o = offset + delta
        offset = Offset(
            clampPan(o.x, w, 0f, size.width.toFloat()),
            clampPan(o.y, h, insetTop, size.height - insetBottom),
        )
    }

    /** Keeps at least [margin] of a page edge of length [extent] inside [lo]..[hi]. Never inverts. */
    private fun clampPan(pos: Float, extent: Float, lo: Float, hi: Float): Float {
        val margin = minOf(extent, (hi - lo).coerceAtLeast(0f)) * 0.15f + 1f
        val min = lo + margin - extent
        val max = hi - margin
        return if (min <= max) pos.coerceIn(min, max) else (min + max) / 2
    }

    fun centerOn(p: PagePoint, newScale: Float = scale, userMove: Boolean = true) {
        scale = newScale.coerceIn(minScale, maxScale)
        offset = Offset(size.width / 2f - p.x.toFloat() * scale, insetTop + visibleHeight / 2f - p.y.toFloat() * scale)
        if (userMove) fitted = false
    }

    /** Centres on [p], zooming in to at least twice the whole-map scale. */
    fun showPoint(p: PagePoint) = centerOn(p, max(scale, fitScale * 2))

    /** Fits a page-space rectangle into the visible area, with some room around it. */
    fun showRect(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val c = PagePoint((minX + maxX) / 2.0, (minY + maxY) / 2.0)
        val w = maxX - minX
        val h = maxY - minY
        if (w < 0.01f && h < 0.01f) return showPoint(c)
        centerOn(c, min(size.width / w.coerceAtLeast(0.01f), visibleHeight / h.coerceAtLeast(0.01f)) * 0.8f)
    }

    /**
     * Brings a page-space rectangle into the visible area, clear of its edges by a tenth of
     * the width and height, changing the view no more than that takes: zooms out if it's too
     * big (never in), then moves just far enough. Nothing happens if it's already there, or
     * if it's too big to show at all.
     */
    fun reveal(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (size == IntSize.Zero) return
        val fits = min(size.width / (maxX - minX).coerceAtLeast(0.01f), visibleHeight / (maxY - minY).coerceAtLeast(0.01f)) * 0.8f
        // Too far apart to show even zoomed right out (somewhere far off the map): better left alone than centred on neither end.
        if (fits < minScale) return
        if (fits < scale) centerOn(center, fits)
        // The 0.8 above leaves at least this much room on each side.
        val padX = size.width * 0.1f
        val padY = visibleHeight * 0.1f
        val dx = shiftInto(minX * scale + offset.x, maxX * scale + offset.x, padX, size.width - padX)
        val dy = shiftInto(minY * scale + offset.y, maxY * scale + offset.y, insetTop + padY, size.height - insetBottom - padY)
        if (dx != 0f || dy != 0f) {
            offset += Offset(dx, dy)
            fitted = false
        }
    }

    /** How far to move [a]..[b] so it lies within [lo]..[hi] (centred, if it's too long). */
    private fun shiftInto(a: Float, b: Float, lo: Float, hi: Float): Float = when {
        b - a > hi - lo -> (lo + hi) / 2 - (a + b) / 2
        a < lo -> lo - a
        b > hi -> hi - b
        else -> 0f
    }
}

fun rotateBy(v: Offset, degrees: Float): Offset {
    if (degrees == 0f) return v
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/**
 * A rendered part of the page, at [scale] pixels per point; [keyed] is the same image with
 * the paper removed, made once see-through is used.
 */
class RenderedRegion(val bitmap: Bitmap, val left: Float, val top: Float, val scale: Float, val keyed: Bitmap? = null) {
    fun withKeyed(keyed: Bitmap) = RenderedRegion(bitmap, left, top, scale, keyed)
}

/**
 * Draws the PDF page (a low-res base image plus a sharp image of the visible area)
 * and handles pan, pinch zoom, fling, tap and double tap.
 */
@Composable
fun MapView(
    viewport: MapViewport,
    base: RenderedRegion?,
    /** Sharper parts of the page over [base], in drawing order; read while drawing. */
    tiles: () -> List<RenderedRegion>,
    modifier: Modifier = Modifier,
    /** Off while tapping out points, so quick taps add them instead of zooming (and aren't delayed). */
    doubleTapZoom: Boolean = true,
    onTap: (Offset) -> Unit = {},
    /** Null turns long press off (while a tool takes taps). */
    onLongPress: ((Offset) -> Unit)? = null,
    onUserMove: () -> Unit = {},
    /** Drawn under the PDF page (the background map). */
    underlay: DrawScope.() -> Unit = {},
    /** How much the underlay shows through the page's paper, 0..1 (see [RenderedRegion.keyed]). */
    seeThrough: Float = 0f,
    overlay: DrawScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    // Paper-free tiles replace what's under them, so the blurrier base doesn't show through.
    val replace = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC } }
    val fade = remember { Paint() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val longPress = onLongPress != null
    val currentOnUserMove by rememberUpdatedState(onUserMove)

    Canvas(
        modifier
            .onSizeChanged { viewport.onSizeChanged(it) }
            .pointerInput(viewport, doubleTapZoom, longPress) {
                // Callers get positions in view coordinates, whatever the rotation.
                detectTapGestures(
                    onTap = { currentOnTap(viewport.screenToView(it)) },
                    onDoubleTap = if (doubleTapZoom) { pos -> viewport.zoomBy(2f, viewport.screenToView(pos)) } else null,
                    onLongPress = if (longPress) { pos -> currentOnLongPress?.invoke(viewport.screenToView(pos)) } else null,
                )
            }
            .pointerInput(viewport) {
                val velocity = VelocityTracker()
                var fling: Job? = null
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    velocity.resetTracking()
                    var moving = false
                    var reportedMove = false
                    var multiTouch = false
                    var totalPan = Offset.Zero
                    var totalZoom = 1f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size > 1) multiTouch = true
                        totalPan += pan
                        if (!moving) {
                            totalZoom *= zoom
                            val zoomMotion = abs(1 - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                            if (zoomMotion > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop) {
                                moving = true
                            }
                        }
                        // Only a real pan counts as moving away (zooming keeps follow/compass on).
                        if (moving && !reportedMove && totalPan.getDistance() > viewConfiguration.touchSlop * 3) {
                            reportedMove = true
                            currentOnUserMove()
                        }
                        if (moving) {
                            if (zoom != 1f) viewport.zoomBy(zoom, viewport.screenToView(event.calculateCentroid(useCurrent = false)))
                            viewport.panBy(rotateBy(pan, -viewport.rotation))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        event.changes.singleOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    } while (event.changes.any { it.pressed })

                    if (moving && !multiTouch) {
                        val v = velocity.calculateVelocity()
                        fling = scope.launch {
                            var last = Offset.Zero
                            AnimationState(Offset.VectorConverter, Offset.Zero, Offset(v.x, v.y))
                                .animateDecay(exponentialDecay(frictionMultiplier = 1.5f)) {
                                    viewport.panBy(rotateBy(value - last, -viewport.rotation))
                                    last = value
                                }
                        }
                    }
                }
            },
    ) {
        drawRect(MapStyle.background)
        rotate(viewport.rotation, viewport.pivot) {
        underlay()
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            val regions = listOfNotNull(base) + tiles()
            fun draw(region: RenderedRegion, bitmap: Bitmap, p: Paint) {
                val topLeft = viewport.toScreen(PagePoint(region.left.toDouble(), region.top.toDouble()))
                val k = viewport.scale / region.scale
                c.drawBitmap(bitmap, null, RectF(topLeft.x, topLeft.y, topLeft.x + bitmap.width * k, topLeft.y + bitmap.height * k), p)
            }
            if (seeThrough <= 0f) {
                for (r in regions) draw(r, r.bitmap, paint)
            } else {
                // The paper-free page, then the plain page faded on top: together that's the
                // mix [seeThrough] asks for. Each is composited as one layer, because the base
                // and tiles overlap and would otherwise show through each other.
                val topLeft = viewport.toScreen(PagePoint(0.0, 0.0))
                val page = RectF(topLeft.x, topLeft.y, topLeft.x + viewport.pageWidth * viewport.scale, topLeft.y + viewport.pageHeight * viewport.scale)
                c.saveLayer(page, null)
                // Until its paper-free copy is ready, the base shows as it is: better than no map.
                for (r in regions) (r.keyed ?: r.bitmap.takeIf { r === base })?.let { draw(r, it, if (r === base) paint else replace) }
                c.restore()
                if (seeThrough < 1f) {
                    fade.alpha = ((1f - seeThrough) * 255).roundToInt()
                    c.saveLayer(page, fade)
                    for (r in regions) draw(r, r.bitmap, paint)
                    c.restore()
                }
            }
        }
        if (base != null) {
            // Thin edge so the page stands out from the background map.
            drawRect(
                MapStyle.pageOutline,
                topLeft = viewport.toScreen(PagePoint(0.0, 0.0)),
                size = Size(viewport.pageWidth * viewport.scale, viewport.pageHeight * viewport.scale),
                style = Stroke(1f * density),
            )
        }
        overlay()
        }
    }
}
