package org.geotrailmaps.data

import java.text.BreakIterator

/** Icons for points: any single emoji (or character), drawn in the pin's head. */
object PointIcons {
    /** How many recently used icons are kept (more than fit on one row of the sheet). */
    const val MAX_RECENT = 24

    /** Offered when choosing an icon: things people mark outdoors. */
    val suggestions = listOf(
        "🐍", "🐻", "🦌", "🐺", "🦊", "🐗", "🐿️", "🦫", "🦔", "🐇", "🦡", "🐾",
        "🐦", "🦅", "🦉", "🦆", "🐸", "🦎", "🐢", "🐟", "🦋", "🐝", "🕷️", "🦟",
        "🌸", "🌼", "🌿", "🍄", "🌲", "🌳", "🍂", "🫐", "🍓", "🌾", "🪨", "🪵",
        "💧", "🌊", "🏞️", "⛰️", "🏔️", "🌋", "🕳️", "❄️", "🔥", "🌉",
        "⛺", "🏕️", "🛖", "🏠", "🚻", "🅿️", "🚗", "🚲", "🛶", "🎣", "📷", "🔭",
        "⚠️", "⛔", "🚧", "🩹", "💀", "❗", "❓", "⭐", "❤️", "🚩", "📍", "🏁",
    )

    /** [icon] moved to the front of [recent], which keeps at most [max]. */
    fun used(recent: List<String>, icon: String, max: Int = MAX_RECENT): List<String> =
        (listOf(icon) + recent.filter { it != icon }).take(max)

    /**
     * The first character as you'd see it (an emoji with its skin tone, flag or joined parts
     * counts as one), or null if [text] is blank or starts with a control character (which
     * KML can't hold). Typing or pasting into the picker uses this.
     */
    fun first(text: String): String? {
        val s = text.trim()
        if (s.isEmpty() || Character.isISOControl(s.codePointAt(0))) return null
        val it = BreakIterator.getCharacterInstance().apply { setText(s) }
        return s.substring(0, it.next())
    }
}
