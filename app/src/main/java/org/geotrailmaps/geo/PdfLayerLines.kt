package org.geotrailmaps.geo

import org.geotrailmaps.pdf.PdfFile
import org.geotrailmaps.pdf.PdfName
import org.geotrailmaps.pdf.PdfRef
import org.geotrailmaps.pdf.PdfStream
import org.geotrailmaps.pdf.PdfString
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads the lines drawn on a PDF's first page: every stroked path with how it's drawn
 * ([readAll]), or those in some layers (optional content groups) such as a "Trails" layer
 * ([read]): inside the layer's marked content or in a form XObject belonging to it. Curves
 * are flattened; rectangles and fills are left out (swatches and areas, not lines).
 */
object PdfLayerLines {
    private const val MAX_DEPTH = 8
    private const val MAX_OPERATORS = 5_000_000
    private const val CURVE_STEPS = 8
    /** Points kept at most, so a huge map can't run out of memory (a few tens of MB). */
    private const val MAX_POINTS = 1_000_000

    /**
     * How a line is drawn: RGB colour, width in page points (to 0.1) and dash pattern; [ink]
     * names a spot colour, whose RGB is only an approximation shared with other inks.
     */
    data class Style(val rgb: Int, val width: Float, val dash: List<Float>, val ink: String? = null)

    class Line(val points: List<PagePoint>, val style: Style)

    /** Lines in page points, from the layers whose name [wanted] accepts; empty if none. */
    fun read(pdf: PdfFile, wanted: (String) -> Boolean): List<List<PagePoint>> {
        val catalog = pdf.dict(pdf.trailer["Root"]) ?: return emptyList()
        val groups = pdf.array(pdf.dict(catalog["OCProperties"])?.get("OCGs")) ?: return emptyList()
        val layers = groups.filterIsInstance<PdfRef>().filter { ref ->
            (pdf.resolve(pdf.dict(ref)?.get("Name")) as? PdfString)?.text?.let(wanted) == true
        }.map { it.num }.toSet()
        if (layers.isEmpty()) return emptyList()
        return readLines(pdf, layers).map { it.points }
    }

    /** Every stroked line on the page, in any layer or none, with its style. */
    fun readAll(pdf: PdfFile): List<Line> = readLines(pdf, null)

    private fun readLines(pdf: PdfFile, layers: Set<Int>?): List<Line> {
        val (page, space) = GeoPdfParser.firstPage(pdf)
        return Reader(pdf, layers, space).apply {
            run(contents(page["Contents"]), pdf.dict(page["Resources"]), Graphics(IDENTITY), layers == null, 0)
        }.lines
    }

    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** [m] then [ctm], as PDF matrices [a b c d e f]. */
    private fun times(m: DoubleArray, ctm: DoubleArray) = doubleArrayOf(
        m[0] * ctm[0] + m[1] * ctm[2], m[0] * ctm[1] + m[1] * ctm[3],
        m[2] * ctm[0] + m[3] * ctm[2], m[2] * ctm[1] + m[3] * ctm[3],
        m[4] * ctm[0] + m[5] * ctm[2] + ctm[4], m[4] * ctm[1] + m[5] * ctm[3] + ctm[5],
    )

    /**
     * A stroke colour space, which says how SC/SCN numbers are read: its [family], the spot
     * [ink] it names (Separation, DeviceN), or the [palette] of an Indexed space with
     * [paletteComponents] per entry.
     */
    private class ColorSpace(val family: String, val ink: String? = null, val palette: ByteArray? = null, val paletteComponents: Int = 0)

    private val DEVICE_GRAY = ColorSpace("DeviceGray")

    /** The parts of the graphics state that say where and how lines are drawn. */
    private class Graphics(
        val ctm: DoubleArray,
        val rgb: Int = 0,
        val width: Double = 1.0,
        val dash: List<Double> = emptyList(),
        val space: ColorSpace = DEVICE_GRAY,
        /** The spot ink(s) and tints, for a Separation, DeviceN or unreadable Indexed colour. */
        val spot: String? = null,
    ) {
        fun copy(
            ctm: DoubleArray = this.ctm, rgb: Int = this.rgb, width: Double = this.width, dash: List<Double> = this.dash,
            space: ColorSpace = this.space, spot: String? = this.spot,
        ) = Graphics(ctm, rgb, width, dash, space, spot)
    }

    /** How a colour in a spot space is told apart: its inks, plus every tint when one isn't enough. */
    private fun spot(n: DoubleArray, space: ColorSpace): String? {
        val ink = space.ink?.takeIf { space.palette == null } ?: return null
        return if (n.size > 1 || space.family == "Indexed") "$ink " + n.joinToString("/") { String.format(Locale.US, "%.2f", it) } else ink
    }

    private fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    private fun rgb(r: Double, g: Double, b: Double) = (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    private fun gray(v: Double) = rgb(v, v, v)
    private fun cmyk(c: Double, m: Double, y: Double, k: Double) = rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))

    private fun fromComponents(n: DoubleArray) = when (n.size) {
        1 -> gray(n[0])
        3 -> rgb(n[0], n[1], n[2])
        4 -> cmyk(n[0], n[1], n[2], n[3])
        else -> 0
    }

    /** A colour from its components in [space]; a spot ink's tint is shown as grey (more is darker). */
    private fun color(n: DoubleArray, space: ColorSpace): Int {
        val palette = space.palette
        val k = space.paletteComponents
        return when {
            palette != null && n.size == 1 && k > 0 -> {
                val at = n[0].toInt() * k
                if (at < 0 || at + k > palette.size) 0
                else fromComponents(DoubleArray(k) { (palette[at + it].toInt() and 0xFF) / 255.0 })
            }
            space.ink != null || space.family == "Indexed" -> gray(1 - (n.firstOrNull() ?: 1.0))
            else -> fromComponents(n)
        }
    }

    private class Reader(val pdf: PdfFile, val layers: Set<Int>?, val space: GeoPdfParser.PageSpace) {
        val lines = ArrayList<Line>()
        private var operators = 0
        private var points = 0

        fun contents(obj: Any?): ByteArray = when (val o = pdf.resolve(obj)) {
            is PdfStream -> runCatching { pdf.streamData(o) }.getOrDefault(ByteArray(0))
            // In one buffer: a page can have thousands of parts.
            is List<*> -> ByteArrayOutputStream().apply { o.forEach { write(contents(it)); write('\n'.code) } }.toByteArray()
            else -> ByteArray(0)
        }

        /** Whether an /OC entry (a layer, or a membership dictionary of layers) is a wanted layer. */
        private fun inLayer(oc: Any?): Boolean {
            val layers = layers ?: return false
            if (oc is PdfRef && oc.num in layers) return true
            val d = pdf.dict(oc) ?: return false
            if ((pdf.resolve(d["Type"]) as? PdfName)?.value != "OCMD") return false
            return when (val g = d["OCGs"]) {
                is PdfRef -> g.num in layers
                else -> pdf.array(g)?.any { it is PdfRef && it.num in layers } == true
            }
        }

        /** A stroke colour space by its name in a CS operator, defined in [resources] unless it's a device one. */
        private fun colorSpace(name: String, resources: Map<String, Any?>?): ColorSpace {
            if (name.startsWith("Device") || name == "Pattern") return ColorSpace(name)
            return colorSpace(pdf.resolve(pdf.dict(resources?.get("ColorSpace"))?.get(name)), name)
        }

        private fun colorSpace(cs: Any?, name: String): ColorSpace {
            val def = pdf.resolve(cs)
            if (def is PdfName) return ColorSpace(def.value)
            val parts = def as? List<*> ?: return ColorSpace(name)
            fun part(i: Int) = pdf.resolve(parts.getOrNull(i))
            return when (val family = (part(0) as? PdfName)?.value) {
                "Separation" -> ColorSpace(family, ink = (part(1) as? PdfName)?.value ?: name)
                "DeviceN" -> ColorSpace(family, ink = (part(1) as? List<*>)?.mapNotNull { (pdf.resolve(it) as? PdfName)?.value }?.joinToString(" + ") ?: name)
                // An ICC profile stands in for the device space with as many components.
                "ICCBased" -> when ((part(1) as? PdfStream)?.dict?.get("N")?.let { pdf.resolve(it) as? Number }?.toInt()) {
                    1 -> ColorSpace("DeviceGray")
                    4 -> ColorSpace("DeviceCMYK")
                    else -> ColorSpace("DeviceRGB")
                }
                "Indexed" -> {
                    val components = when (val base = colorSpace(parts.getOrNull(1), "").family) {
                        "DeviceGray", "CalGray" -> 1
                        "DeviceCMYK" -> 4
                        else -> if (base.isEmpty()) 0 else 3
                    }
                    val lookup = when (val l = part(3)) {
                        is PdfString -> l.bytes
                        is PdfStream -> runCatching { pdf.streamData(l) }.getOrNull()
                        else -> null
                    }
                    ColorSpace(family, ink = name, palette = lookup, paletteComponents = components)
                }
                else -> ColorSpace(family ?: name)
            }
        }

        fun run(data: ByteArray, resources: Map<String, Any?>?, start: Graphics, inherited: Boolean, depth: Int) {
            var gs = start
            val saved = ArrayList<Graphics>()
            val marked = ArrayList<Boolean>() // per marked-content level: is it a wanted layer
            val path = ArrayList<ArrayList<PagePoint>>()
            var cx = 0.0
            var cy = 0.0
            var startX = 0.0 // the current subpath's start
            var startY = 0.0

            fun page(x: Double, y: Double): PagePoint {
                val ctm = gs.ctm
                return space.toPage(ctm[0] * x + ctm[2] * y + ctm[4], ctm[1] * x + ctm[3] * y + ctm[5])
            }
            var buffered = 0 // points in [path]
            fun lineTo(x: Double, y: Double) {
                cx = x; cy = y
                // Past the budget a path is still followed, but its points aren't kept.
                if (points + buffered >= MAX_POINTS) return
                path.lastOrNull()?.add(page(x, y)) ?: path.add(arrayListOf(page(x, y)))
                buffered++
            }
            // An empty subpath is reused, so a run of moves (or one past the budget) holds nothing.
            fun newSubpath() {
                if (path.lastOrNull()?.isEmpty() != true) path += ArrayList<PagePoint>()
            }
            fun clearPath() {
                path.clear()
                buffered = 0
            }
            fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
                val x0 = cx; val y0 = cy
                for (i in 1..CURVE_STEPS) {
                    val t = i.toDouble() / CURVE_STEPS
                    val u = 1 - t
                    lineTo(
                        u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                        u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
                    )
                }
            }
            // Like h: closes the current subpath only.
            fun closePath() {
                // Once per subpath, and within the budget like any other point.
                path.lastOrNull()?.let {
                    if (it.size > 2 && it.last() != it.first() && points + buffered < MAX_POINTS) {
                        it += it.first()
                        buffered++
                    }
                }
                cx = startX; cy = startY
            }
            fun stroke(close: Boolean) {
                if (close) closePath()
                if (inherited || marked.any { it }) {
                    // Width and dashes scale with the transform (by its average stretch).
                    val ctm = gs.ctm
                    val scale = sqrt(abs(ctm[0] * ctm[3] - ctm[1] * ctm[2]))
                    fun tenths(v: Double) = (v * scale * 10).roundToInt() / 10f
                    // An indexed colour is told apart by its RGB; a spot ink by name.
                    val style = Style(gs.rgb, tenths(gs.width), gs.dash.map(::tenths), gs.spot)
                    for (sub in path) {
                        if (sub.size < 2) continue
                        lines += Line(sub, style)
                        points += sub.size
                    }
                }
                clearPath()
            }
            fun dash(arg: Any?) = (pdf.resolve(arg) as? List<*>)?.mapNotNull { (pdf.resolve(it) as? Number)?.toDouble() }.orEmpty()

            pdf.readContent(data) { op, args ->
                if (++operators > MAX_OPERATORS) return@readContent
                val n = DoubleArray(args.size) { (args[it] as? Number)?.toDouble() ?: 0.0 }
                when (op) {
                    "q" -> saved += gs
                    "Q" -> if (saved.isNotEmpty()) gs = saved.removeAt(saved.lastIndex)
                    "cm" -> if (n.size == 6) gs = gs.copy(ctm = times(n, gs.ctm))
                    "w" -> if (n.size == 1) gs = gs.copy(width = n[0])
                    "d" -> gs = gs.copy(dash = dash(args.getOrNull(0)))
                    "G" -> if (n.size == 1) gs = gs.copy(rgb = gray(n[0]), space = DEVICE_GRAY, spot = null)
                    "RG" -> if (n.size == 3) gs = gs.copy(rgb = rgb(n[0], n[1], n[2]), space = ColorSpace("DeviceRGB"), spot = null)
                    "K" -> if (n.size == 4) gs = gs.copy(rgb = cmyk(n[0], n[1], n[2], n[3]), space = ColorSpace("DeviceCMYK"), spot = null)
                    "CS" -> {
                        val cs = colorSpace((args.getOrNull(0) as? PdfName)?.value.orEmpty(), resources)
                        gs = gs.copy(space = cs, rgb = 0, spot = spot(doubleArrayOf(1.0), cs))
                    }
                    // A pattern's name comes after its numbers (if any).
                    "SC", "SCN" -> {
                        val tints = args.filterIsInstance<Number>().map { it.toDouble() }.toDoubleArray()
                        gs = gs.copy(rgb = color(tints, gs.space), spot = spot(tints, gs.space))
                    }
                    "gs" -> {
                        val name = (args.getOrNull(0) as? PdfName)?.value ?: return@readContent
                        val ext = pdf.dict(pdf.dict(resources?.get("ExtGState"))?.get(name)) ?: return@readContent
                        (pdf.resolve(ext["LW"]) as? Number)?.let { gs = gs.copy(width = it.toDouble()) }
                        pdf.array(ext["D"])?.let { gs = gs.copy(dash = dash(it.firstOrNull())) }
                    }
                    "m" -> if (n.size == 2) { newSubpath(); lineTo(n[0], n[1]); startX = n[0]; startY = n[1] }
                    "l" -> if (n.size == 2) lineTo(n[0], n[1])
                    "c" -> if (n.size == 6) curve(n[0], n[1], n[2], n[3], n[4], n[5])
                    "v" -> if (n.size == 4) curve(cx, cy, n[0], n[1], n[2], n[3])
                    "y" -> if (n.size == 4) curve(n[0], n[1], n[2], n[3], n[2], n[3])
                    "h" -> closePath()
                    "re" -> newSubpath() // not a line; also ends the current subpath
                    "S", "B", "B*" -> stroke(close = false)
                    "s", "b", "b*" -> stroke(close = true)
                    "f", "F", "f*", "n" -> clearPath()
                    "BDC" -> {
                        val tag = (args.getOrNull(0) as? PdfName)?.value
                        val props = args.getOrNull(1).let { p ->
                            if (p is PdfName) pdf.dict(resources?.get("Properties"))?.get(p.value) else p
                        }
                        marked += tag == "OC" && inLayer(props)
                    }
                    "BMC" -> marked += false
                    "EMC" -> if (marked.isNotEmpty()) marked.removeAt(marked.lastIndex)
                    "Do" -> {
                        val name = (args.getOrNull(0) as? PdfName)?.value ?: return@readContent
                        val form = pdf.resolve(pdf.dict(resources?.get("XObject"))?.get(name)) as? PdfStream ?: return@readContent
                        if ((pdf.resolve(form.dict["Subtype"]) as? PdfName)?.value != "Form" || depth >= MAX_DEPTH) return@readContent
                        val matrix = pdf.numbers(form.dict["Matrix"])?.takeIf { it.size == 6 } ?: IDENTITY
                        run(
                            contents(form),
                            pdf.dict(form.dict["Resources"]) ?: resources,
                            gs.copy(ctm = times(matrix, gs.ctm)),
                            inherited || marked.any { it } || inLayer(form.dict["OC"]),
                            depth + 1,
                        )
                    }
                }
            }
        }
    }
}
