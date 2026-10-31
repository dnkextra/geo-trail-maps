package org.geotrailmaps.render

import android.graphics.Bitmap
import androidx.core.graphics.get
import kotlin.math.roundToInt

/**
 * The most common colour among [pixels] (ARGB), taken to be the map's paper. Colours are
 * grouped coarsely first, so slight noise doesn't split the vote; the result is the most
 * common exact colour in the winning group. (Not the group's average: antialiased edges
 * would pull that just off the paper, and anything on the far side of it would count as ink.)
 */
fun dominantColor(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0xFFFFFFFF.toInt()
    fun group(c: Int) = (((c shr 16) and 0xF8) shl 7) or (((c shr 8) and 0xF8) shl 2) or ((c and 0xF8) shr 3)
    val groups = HashMap<Int, Int>()
    for (c in pixels) groups.merge(group(c), 1, Int::plus)
    val winner = groups.maxBy { it.value }.key
    val exact = HashMap<Int, Int>()
    for (c in pixels) if (group(c) == winner) exact.merge(c or (0xFF shl 24), 1, Int::plus)
    return exact.maxBy { it.value }.key
}

/** About 40,000 pixels spread over [bitmap], split into those [inside] the area and the rest. */
fun samplePixels(bitmap: Bitmap, inside: (x: Int, y: Int) -> Boolean): Pair<IntArray, IntArray> {
    val step = maxOf(1, kotlin.math.sqrt(bitmap.width.toDouble() * bitmap.height / 40_000).toInt())
    val inner = ArrayList<Int>()
    val outer = ArrayList<Int>()
    for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
        (if (inside(x, y)) inner else outer) += bitmap[x, y]
    }
    return inner.toIntArray() to outer.toIntArray()
}

/**
 * Colour to alpha: the most transparent colour that, laid over [paper], looks like [color]
 * (both opaque ARGB). Paper becomes fully transparent, ink keeps its own colour, and the
 * antialiased edges of lines and text lose the paper tint. Unlike a multiply blend, this
 * doesn't darken the ink over dark imagery. Returns unpremultiplied ARGB.
 */
fun removePaper(color: Int, paper: Int): Int {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val pr = (paper shr 16) and 0xFF
    val pg = (paper shr 8) and 0xFF
    val pb = paper and 0xFF
    val a = maxOf(channelAlpha(r, pr), channelAlpha(g, pg), channelAlpha(b, pb))
    if (a <= 0f) return 0
    fun unblend(c: Int, p: Int) = ((c - p) / a + p).roundToInt().coerceIn(0, 255)
    return ((a * 255).roundToInt() shl 24) or (unblend(r, pr) shl 16) or (unblend(g, pg) shl 8) or unblend(b, pb)
}

/**
 * How far channel [c] is from the paper's [p], as a share of how far it could go (0..1).
 * Differences up to [PAPER_TOLERANCE] count as paper, so slight noise in it isn't ink.
 */
private fun channelAlpha(c: Int, p: Int): Float {
    val d = kotlin.math.abs(c - p) - PAPER_TOLERANCE
    if (d <= 0) return 0f
    val room = (if (c > p) 255 - p else p) - PAPER_TOLERANCE
    return (d / room.coerceAtLeast(1).toFloat()).coerceAtMost(1f)
}

private const val PAPER_TOLERANCE = 3

/**
 * A copy of [bitmap] with the paper removed. [paperAt] gives the paper colour for each
 * pixel, so the map frame and its margins can have different ones.
 */
fun removePaper(bitmap: Bitmap, paperAt: (x: Int, y: Int) -> Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        pixels[i] = removePaper(pixels[i], paperAt(x, y))
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
