package org.geotrailmaps.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Names given to points, for suggesting them again, and the defaults the app gives. */
object PointNames {
    /** How many names are counted; the least used go first when there are more. */
    const val MAX_COUNTED = 300

    /** A name the app gave ("Placemark 3", "Line 2", "Area 1"), not one someone typed. */
    fun isDefault(name: String) = Regex("^(Placemark|Line|Area) \\d+$").matches(name)

    /** [counts] with [name] counted once more; null names, blanks and defaults aren't counted. */
    fun used(counts: Map<String, Int>, name: String, max: Int = MAX_COUNTED): Map<String, Int> {
        val n = name.trim()
        if (n.isEmpty() || isDefault(n)) return counts
        val next = counts + (n to (counts[n] ?: 0) + 1)
        if (next.size <= max) return next
        // Drop the least used, keeping the one just counted.
        val drop = next.entries.filter { it.key != n }.minBy { it.value }.key
        return next - drop
    }

    /**
     * Names used more than once, most used first (then A–Z), containing [typed] if anything's
     * typed; the name already typed in full isn't offered.
     */
    fun suggestions(counts: Map<String, Int>, typed: String, max: Int = 8): List<String> {
        val t = typed.trim()
        return counts.entries
            .filter { it.value >= 2 && !it.key.equals(t, ignoreCase = true) && it.key.contains(t, ignoreCase = true) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(max)
            .map { it.key }
    }

    /**
     * [date] (as the Material date picker gives it: midnight UTC of the chosen day) at
     * [hour]:[minute] local time in [zone], as epoch millis.
     */
    fun combine(date: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(date), ZoneOffset.UTC).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** Midnight UTC of [time]'s local day in [zone], for the date picker. */
    fun pickerDate(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(time), zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
