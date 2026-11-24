package org.geotrailmaps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNameTest {
    @Test
    fun keepsLettersAndReplacesWhatFileSystemsRefuse() {
        assertEquals("Café trail_ day 1", fileNameFor("Café trail: day 1"))
        assertEquals("a_b", fileNameFor("..a/b. "))
        assertEquals("features", fileNameFor(" .. "))
    }

    @Test
    fun capsUtf8BytesWithoutSplittingCharacters() {
        // 90 three-byte characters is 270 bytes: too long for a file name once ".gpx" is added.
        val name = fileNameFor("山".repeat(90))
        assertEquals("山".repeat(50), name)
        // Four-byte characters (surrogate pairs) aren't cut in half.
        val emoji = fileNameFor("🌲".repeat(60))
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 150)
        assertEquals("🌲".repeat(37), emoji)
    }
}
