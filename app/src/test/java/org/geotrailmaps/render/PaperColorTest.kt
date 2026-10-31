package org.geotrailmaps.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperColorTest {

    @Test
    fun mostCommonColourWinsAndNoiseVotesWithIt() {
        val cream = 0xFFF4F1EA.toInt()
        val creamNoise = 0xFFF5F1EB.toInt()
        val white = 0xFFFFFFFF.toInt()
        val trail = 0xFF1E40C8.toInt()
        val pixels = IntArray(60) { cream } + IntArray(20) { creamNoise } + IntArray(70) { white } + IntArray(10) { trail }
        // Cream (80 pixels, split by noise) beats white (70): grouping keeps the vote together,
        // and the result is cream's exact colour, not an average with the noise.
        assertEquals(cream, dominantColor(pixels))
    }

    @Test
    fun exactColourNotTheGroupAverage() {
        // Averaging these gives (254,254,254), which would make pure white count as ink.
        val pixels = IntArray(60) { 0xFFFFFFFF.toInt() } + IntArray(20) { 0xFFFEFEFE.toInt() }
        assertEquals(0xFFFFFFFF.toInt(), dominantColor(pixels))
    }

    @Test
    fun emptyIsWhite() {
        assertEquals(0xFFFFFFFF.toInt(), dominantColor(IntArray(0)))
    }

    private fun over(argb: Int, paper: Int): Int {
        // Composite unpremultiplied [argb] over opaque [paper].
        val a = (argb ushr 24) / 255f
        fun ch(shift: Int) = Math.round(((argb shr shift) and 0xFF) * a + ((paper shr shift) and 0xFF) * (1 - a))
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test
    fun paperGoesInkStays() {
        val cream = 0xFFF5F3EC.toInt()
        assertEquals(0, removePaper(cream, cream))
        // Ink with a channel at 0 or 255 stays fully opaque, in its own colour.
        assertEquals(0xFF000000.toInt(), removePaper(0xFF000000.toInt(), cream))
        assertEquals(0xFFE01E00.toInt(), removePaper(0xFFE01E00.toInt(), cream))
        // An antialiased edge (red half-blended into white) becomes half-transparent red.
        val edge = removePaper(0xFFFF8080.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0xFF0000, edge and 0xFFFFFF)
        assert((edge ushr 24) in 124..128) // about half; the paper tolerance shaves a little
    }

    @Test
    fun paperJustOffWhiteStillLetsWhiteThrough() {
        // A slightly darker detected paper must not turn pure white into solid ink.
        assertEquals(0, removePaper(0xFFFFFFFF.toInt(), 0xFFFEFEFE.toInt()))
        assertEquals(0, removePaper(0xFFF5F3EC.toInt(), 0xFFF4F3EC.toInt()))
    }

    @Test
    fun layingTheResultBackOnThePaperGivesTheOriginal() {
        val papers = listOf(0xFFFFFFFF.toInt(), 0xFFF5F3EC.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt())
        val colors = listOf(0xFF123456, 0xFFFF8080, 0xFFE3E2DA, 0xFF87968C, 0xFFFFFFFF, 0xFF000000, 0xFFF4F1EA).map { it.toInt() }
        for (p in papers) for (c in colors) {
            val back = over(removePaper(c, p), p)
            for (shift in listOf(16, 8, 0)) {
                val d = Math.abs(((back shr shift) and 0xFF) - ((c shr shift) and 0xFF))
                assert(d <= 5) { "%08x over %08x came back as %08x".format(c, p, back) } // within the paper tolerance
            }
        }
    }
}
