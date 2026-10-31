package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.Utm

/**
 * Reads a typed coordinate in any of the formats the app displays, plus common variants:
 *  - decimal: "49.5, -123.25", "49.5N 123.25W"
 *  - degrees/minutes(/seconds): "49°30'00.0"N 123°15'00.0"W", "N49 30.5 W123 15.2", "49 30 0 N 123 15 0 W"
 *  - UTM: "10U 500000 5480000", "10U 500000 E 5480000 N"
 * Without hemisphere letters the order is latitude, longitude.
 */
object CoordinateParser {

    fun parse(input: String): LatLon? {
        val text = input.trim().uppercase()
        if (text.isEmpty()) return null
        return parseUtm(text) ?: parseDegrees(text)
    }

    private val utmPattern = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])\s+(\d{1,7}(?:\.\d+)?)\s*(?:M?E)?[\s,]+(\d{1,8}(?:\.\d+)?)\s*(?:M?N)?$""")

    private fun parseUtm(text: String): LatLon? {
        val m = utmPattern.matchEntire(text) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val north = m.groupValues[2][0] >= 'N'
        val easting = m.groupValues[3].toDouble()
        val northing = m.groupValues[4].toDouble()
        if (easting !in 100_000.0..900_000.0 || northing !in 0.0..10_000_000.0) return null
        return Utm.toLatLon(Utm.Coord(zone, north, easting, northing)).takeIf { valid(it) }
    }

    /** [negative] keeps an explicit minus on the degrees, which "-0" would otherwise lose. */
    private class Part(val numbers: MutableList<Double> = mutableListOf(), var hemisphere: Char? = null, var negative: Boolean = false)

    private val tokenPattern = Regex("""[NSEW]|[-+]?\d+(?:[.,]\d+)?""")

    private fun parseDegrees(text: String): LatLon? {
        // A comma between two numbers separates them; a comma inside a number is a decimal comma.
        val cleaned = text.replace(Regex("""(\d),(\s)"""), "$1 $2").replace(Regex(""",\s*"""), " ")
        val tokens = tokenPattern.findAll(cleaned).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        // Anything other than numbers, hemisphere letters, separators and degree symbols means it isn't a coordinate.
        val leftover = cleaned.replace(tokenPattern, "").replace(Regex("""[\s°º'′’"″”:;]"""), "")
        if (leftover.isNotEmpty()) return null

        val parts = ArrayList<Part>()
        var current = Part()
        var leading: Char? = null
        for (t in tokens) {
            if (t.length == 1 && t[0] in "NSEW") {
                if (current.numbers.isEmpty()) {
                    leading = t[0]
                } else if (current.hemisphere != null) {
                    // "N49 30 W123 15": the part already has its letter, so this one leads the next.
                    parts += current
                    current = Part()
                    leading = t[0]
                } else {
                    current.hemisphere = t[0]
                    parts += current
                    current = Part()
                }
            } else {
                if (current.numbers.isEmpty() && leading != null) {
                    current.hemisphere = leading
                    leading = null
                } else if (leading != null) {
                    // A leading letter after numbers starts the next coordinate.
                    parts += current
                    current = Part(hemisphere = leading)
                    leading = null
                }
                if (current.numbers.isEmpty() && t.startsWith("-")) current.negative = true
                current.numbers += t.replace(',', '.').toDouble()
            }
        }
        if (current.numbers.isNotEmpty()) parts += current

        // No hemisphere letters: split the numbers evenly ("49 30 123 15" -> two parts).
        val split = if (parts.size == 1 && parts[0].hemisphere == null) {
            val n = parts[0].numbers
            if (n.size % 2 != 0 || n.size > 6) return null
            val half = n.size / 2
            val signs = Regex("""[-+]?\d+(?:[.,]\d+)?""").findAll(cleaned).map { it.value.startsWith("-") }.toList()
            listOf(
                Part(n.subList(0, half).toMutableList(), negative = signs.getOrElse(0) { false }),
                Part(n.subList(half, n.size).toMutableList(), negative = signs.getOrElse(half) { false }),
            )
        } else {
            parts
        }
        if (split.size != 2) return null

        val values = split.map { toDegrees(it) ?: return null }
        val (a, b) = split
        val aIsLon = a.hemisphere == 'E' || a.hemisphere == 'W' || b.hemisphere == 'N' || b.hemisphere == 'S'
        val result = if (aIsLon) LatLon(values[1], values[0]) else LatLon(values[0], values[1])
        return result.takeIf { valid(it) }
    }

    private fun toDegrees(p: Part): Double? {
        val n = p.numbers
        if (n.isEmpty() || n.size > 3) return null
        if (n.drop(1).any { it < 0 || it >= 60 }) return null
        val negative = p.negative || p.hemisphere == 'S' || p.hemisphere == 'W'
        val magnitude = kotlin.math.abs(n[0]) + n.getOrElse(1) { 0.0 } / 60 + n.getOrElse(2) { 0.0 } / 3600
        return if (negative) -magnitude else magnitude
    }

    private fun valid(p: LatLon) = p.lat in -90.0..90.0 && p.lon in -180.0..180.0
}
