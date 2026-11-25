package org.geotrailmaps.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * Marker types: kinds of point (a snake sighting, a bear track, a flower...) imported from a
 * JSON file in Settings, so they're offered by + on every map. Each type has an icon drawn in
 * its pins, a colour, and fields filled in for each marker (species, count...). The file format
 * is described in docs/marker-types.md.
 */

enum class FieldKind { TEXT, NUMBER, CHOICE }

/** Something recorded for each marker of a type; stored in [Feature.attributes] under [key]. */
data class MarkerField(
    val key: String,
    val label: String,
    val kind: FieldKind = FieldKind.TEXT,
    /** For [FieldKind.CHOICE]. */
    val options: List<String> = emptyList(),
    /** Filled in when a marker is placed; empty for none. */
    val default: String = "",
)

data class MarkerType(
    /** "set/type", unique across sets; what [Feature.markerType] holds. */
    val id: String,
    val name: String,
    /** ARGB. */
    val color: Int,
    /** Null: a plain pin. */
    val icon: MarkerIcon?,
    /** The type's own fields, then the set's (one per key). */
    val fields: List<MarkerField>,
    val setName: String,
)

/** One imported file: a named group of types, replaced as a whole when a file with its id is imported again. */
data class MarkerSet(
    val id: String,
    val name: String,
    val types: List<MarkerType>,
    /** The file as imported, kept so it can be read again (and by later versions, which may understand more of it). */
    val source: String,
)

/** Why a file couldn't be read as marker types, in words for the person importing it. */
class MarkerTypesException(message: String) : Exception(message)

object MarkerTypes {
    /** Shown on the map when a feature's type was deleted or isn't known. */
    const val PLAIN = ""

    private const val MAX_TYPES = 200
    private const val MAX_ICON_CHARS = 20_000

    /**
     * Reads a marker types file. Accepts a set (`{"name": ..., "types": [...]}`) or a bare list
     * of types (named after [fallbackName]).
     */
    fun parse(text: String, fallbackName: String = "Marker types"): MarkerSet {
        val trimmed = text.trim().removePrefix("\uFEFF")
        val root = try {
            if (trimmed.startsWith("[")) JSONObject().put("types", JSONArray(trimmed)) else JSONObject(trimmed)
        } catch (e: Exception) {
            throw MarkerTypesException("This isn't a JSON file (${e.message?.substringBefore(" at character")}).")
        }
        val name = root.optString("name").trim().ifEmpty { fallbackName }
        val setId = slug(root.optString("id").ifBlank { name })
        val sharedFields = fields(root.optJSONArray("fields"), "the set")
        val list = root.optJSONArray("types") ?: throw MarkerTypesException("No \"types\" list found.")
        if (list.length() == 0) throw MarkerTypesException("The \"types\" list is empty.")
        if (list.length() > MAX_TYPES) throw MarkerTypesException("Too many types (${list.length()}; at most $MAX_TYPES).")
        val seen = HashSet<String>()
        val types = List(list.length()) { i ->
            val o = list.optJSONObject(i) ?: throw MarkerTypesException("Type ${i + 1} isn't an object.")
            val typeName = o.optString("name").trim().ifEmpty { throw MarkerTypesException("Type ${i + 1} has no name.") }
            val typeId = slug(o.optString("id").ifBlank { typeName })
            if (!seen.add(typeId)) throw MarkerTypesException("Two types are called \"$typeName\". Give them different ids.")
            val iconText = o.optString("icon").trim()
            if (iconText.length > MAX_ICON_CHARS) throw MarkerTypesException("The icon of \"$typeName\" is too large.")
            val icon = if (iconText.isEmpty()) null else try {
                SvgIcon.parse(iconText)
            } catch (e: IllegalArgumentException) {
                throw MarkerTypesException("The icon of \"$typeName\" can't be read: ${e.message}")
            }
            MarkerType(
                id = "$setId/$typeId",
                name = typeName,
                color = parseColor(o.optString("color"), typeName),
                icon = icon,
                fields = (fields(o.optJSONArray("fields"), "\"$typeName\"") + sharedFields).distinctBy { it.key },
                setName = name,
            )
        }
        return MarkerSet(setId, name, types, text)
    }

    private fun fields(arr: JSONArray?, owner: String): List<MarkerField> {
        if (arr == null) return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: throw MarkerTypesException("Field ${i + 1} of $owner isn't an object.")
            val label = o.optString("label").trim().ifEmpty { o.optString("key").trim() }
                .ifEmpty { throw MarkerTypesException("Field ${i + 1} of $owner has no label.") }
            val kind = when (o.optString("type", "text").lowercase(Locale.ROOT)) {
                "text", "" -> FieldKind.TEXT
                "number" -> FieldKind.NUMBER
                "choice", "select" -> FieldKind.CHOICE
                else -> throw MarkerTypesException("Field \"$label\" of $owner: type must be text, number or choice.")
            }
            val options = o.optJSONArray("options")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            if (kind == FieldKind.CHOICE && options.isEmpty()) throw MarkerTypesException("Field \"$label\" of $owner needs \"options\".")
            MarkerField(
                key = o.optString("key").trim().ifEmpty { slug(label) },
                label = label,
                kind = kind,
                options = options,
                default = if (o.isNull("default")) "" else o.optString("default"),
            )
        }
    }

    /** "#RRGGBB" or "#AARRGGBB"; a grey when none is given. */
    private fun parseColor(s: String, owner: String): Int {
        val hex = s.trim().removePrefix("#")
        if (hex.isEmpty()) return 0xFF616161.toInt()
        val v = hex.toLongOrNull(16)?.takeIf { hex.length == 6 || hex.length == 8 }
            ?: throw MarkerTypesException("The colour of \"$owner\" should look like #4CAF50.")
        return if (hex.length == 6) (0xFF000000 or v).toInt() else v.toInt()
    }

    /** Lower case letters, digits and dashes, for ids that survive renaming in the file. */
    fun slug(s: String): String =
        s.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "set" }

    /** The type name for a default marker name ("Snake 3"). */
    fun defaultName(typeName: String, existing: List<Feature>): String {
        val used = existing.mapNotNull { Regex("^${Regex.escape(typeName)} (\\d+)$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
        return "$typeName ${(used.maxOrNull() ?: 0) + 1}"
    }

    /** "Rattlesnake · 2 · basking": the values filled in, in field order, for lists and search. */
    fun summary(f: Feature, type: MarkerType?): String {
        val keys = type?.fields?.map { it.key }.orEmpty()
        val ordered = keys.mapNotNull { f.attributes[it] } + f.attributes.filterKeys { it !in keys }.values
        return ordered.filter { it.isNotBlank() }.joinToString(" · ")
    }
}

/**
 * A single-colour icon from a small subset of SVG: `path`, `circle`, `ellipse`, `rect`,
 * `line`, `polyline` and `polygon`, filled and/or stroked, inside a `viewBox`. Colours are
 * ignored (it's drawn in one colour, white in a pin); `fill="none"` and `stroke` aren't. A bare
 * path ("M12 2L…") is taken as a 24×24 icon.
 */
// Not a data class: compared by identity, so drawing can cache its paths cheaply.
class MarkerIcon(val minX: Float, val minY: Float, val width: Float, val height: Float, val parts: List<Part>) {
    /** One shape, as SVG path data. [strokeWidth] null: not stroked. */
    data class Part(val pathData: String, val fill: Boolean, val strokeWidth: Float?, val evenOdd: Boolean = false, val roundCaps: Boolean = false)
}

object SvgIcon {
    private val tagRegex = Regex("<(/?)([a-zA-Z][\\w:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>")
    private val attrRegex = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val skipped = setOf("defs", "clippath", "mask", "title", "desc", "metadata", "style", "symbol", "pattern", "lineargradient", "radialgradient")
    private val inherited = listOf("fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap")

    fun parse(text: String): MarkerIcon {
        val src = text.trim().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        if (!src.startsWith("<")) {
            require(src.first().uppercaseChar() == 'M') { "a path must start with M" }
            return MarkerIcon(0f, 0f, 24f, 24f, listOf(MarkerIcon.Part(src, fill = true, strokeWidth = null)))
        }
        var viewBox: FloatArray? = null
        val parts = mutableListOf<MarkerIcon.Part>()
        // Style inherited from <svg> and <g>; each <g> pushes a copy.
        val stack = ArrayDeque<Map<String, String>>().apply { addLast(emptyMap()) }
        var skipDepth = 0
        for (m in tagRegex.findAll(src)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase(Locale.ROOT).substringAfter(':')
            val selfClosing = m.groupValues[4] == "/"
            if (skipDepth > 0) {
                if (tag in skipped) { if (closing) skipDepth-- else if (!selfClosing) skipDepth++ }
                continue
            }
            if (closing) {
                if ((tag == "g" || tag == "svg") && stack.size > 1) stack.removeLast()
                continue
            }
            if (tag in skipped) { if (!selfClosing) skipDepth++; continue }
            val attrs = attributes(m.groupValues[3])
            val style = stack.last() + attrs.filterKeys { it in inherited }
            when (tag) {
                "svg" -> {
                    viewBox = attrs["viewbox"]?.let(::numbers)?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                        ?: run {
                            val w = attrs["width"]?.let(::length)
                            val h = attrs["height"]?.let(::length)
                            if (w != null && h != null && w > 0 && h > 0) floatArrayOf(0f, 0f, w, h) else null
                        }
                    if (!selfClosing) stack.addLast(style)
                }
                "g" -> if (!selfClosing) stack.addLast(style)
                else -> shapeData(tag, attrs)?.let { d -> part(d, style)?.let(parts::add) }
            }
        }
        require(parts.isNotEmpty()) { "no shapes found (path, circle, rect, ...)" }
        val vb = viewBox ?: floatArrayOf(0f, 0f, 24f, 24f)
        return MarkerIcon(vb[0], vb[1], vb[2], vb[3], parts)
    }

    private fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (a in attrRegex.findAll(s)) out[a.groupValues[1].lowercase(Locale.ROOT)] = a.groupValues[2].ifEmpty { a.groupValues[3] }
        // style="fill:none;stroke-width:2" overrides attributes, as in SVG.
        out["style"]?.split(';')?.forEach { decl ->
            val k = decl.substringBefore(':', "").trim().lowercase(Locale.ROOT)
            if (k.isNotEmpty()) out[k] = decl.substringAfter(':').trim()
        }
        return out
    }

    private fun part(d: String, style: Map<String, String>): MarkerIcon.Part? {
        val fill = style["fill"]?.trim()?.lowercase(Locale.ROOT).let { it != "none" && it != "transparent" }
        val stroke = style["stroke"]?.trim()?.lowercase(Locale.ROOT).let { it != null && it != "none" && it != "transparent" }
        val width = if (stroke) style["stroke-width"]?.let(::length) ?: 1f else null
        if (!fill && width == null) return null
        return MarkerIcon.Part(
            d, fill, width,
            evenOdd = style["fill-rule"]?.trim() == "evenodd",
            roundCaps = style["stroke-linecap"]?.trim() == "round",
        )
    }

    /** The shape as path data, or null if it isn't one (or is empty). */
    private fun shapeData(tag: String, a: Map<String, String>): String? {
        fun n(k: String) = a[k]?.let(::length) ?: 0f
        return when (tag) {
            "path" -> a["d"]?.trim()?.takeIf { it.isNotEmpty() }
            "circle" -> n("r").takeIf { it > 0 }?.let { ellipse(n("cx"), n("cy"), it, it) }
            "ellipse" -> if (n("rx") > 0 && n("ry") > 0) ellipse(n("cx"), n("cy"), n("rx"), n("ry")) else null
            "rect" -> if (n("width") > 0 && n("height") > 0) "M${n("x")} ${n("y")}h${n("width")}v${n("height")}h${-n("width")}z" else null
            "line" -> "M${n("x1")} ${n("y1")}L${n("x2")} ${n("y2")}"
            "polyline", "polygon" -> a["points"]?.let(::numbers)?.takeIf { it.size >= 4 }?.let { p ->
                (p.indices step 2).filter { it + 1 < p.size }.joinToString(" ", postfix = if (tag == "polygon") "z" else "") { i ->
                    (if (i == 0) "M" else "L") + "${p[i]} ${p[i + 1]}"
                }
            }
            else -> null
        }
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0z"

    private fun numbers(s: String): FloatArray =
        Regex("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(s).map { it.value.toFloat() }.toList().toFloatArray()

    /** "24", "24px" or "2.5"; null for percentages and other units. */
    private fun length(s: String): Float? = s.trim().removeSuffix("px").toFloatOrNull()
}
