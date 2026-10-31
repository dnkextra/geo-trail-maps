package org.geotrailmaps.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailNetworkTest {
    private val origin = LatLon(10.32, -84.02)

    /** A position [east] and [north] metres from the origin. */
    private fun at(east: Double, north: Double) = Geodesy.destination(Geodesy.destination(origin, 90.0, east), 0.0, north)

    private fun line(vararg xy: Double) = xy.toList().chunked(2).map { (e, n) -> at(e, n) }

    @Test
    fun crossingLinesAreJoined() {
        val net = TrailNetwork(listOf(line(-100.0, 0.0, 100.0, 0.0), line(0.0, -100.0, 0.0, 100.0)))
        val r = net.route(listOf(at(-100.0, 0.0), at(0.0, 100.0)))
        assertEquals(200.0, r.length, 0.5)
        assertEquals(0.0, r.offTrail, 0.01)
        assertTrue(r.pieces.single().onTrail)
    }

    @Test
    fun nearlyTouchingEndIsJoinedButAGapIsNot() {
        val main = line(-100.0, 0.0, 100.0, 0.0)
        val close = TrailNetwork(listOf(main, line(0.0, 6.0, 0.0, 100.0))) // stops 6 m short
        assertEquals(200.0, close.route(listOf(at(-100.0, 0.0), at(0.0, 100.0))).length, 0.5)

        val far = TrailNetwork(listOf(main, line(0.0, 30.0, 0.0, 100.0)))
        val r = far.route(listOf(at(-100.0, 0.0), at(0.0, 100.0)))
        assertEquals(Geodesy.distance(at(-100.0, 0.0), at(0.0, 100.0)), r.length, 0.5)
        assertEquals(r.length, r.offTrail, 0.01)
        assertFalse(r.pieces.single().onTrail)
    }

    @Test
    fun takesTheShorterWayRound() {
        // A loop: short side straight along the bottom, long side up and over.
        val net = TrailNetwork(listOf(line(0.0, 0.0, 100.0, 0.0), line(0.0, 0.0, 0.0, 200.0, 100.0, 200.0, 100.0, 0.0)))
        assertEquals(100.0, net.route(listOf(at(0.0, 0.0), at(100.0, 0.0))).length, 0.5)
        // Via a point on the long side, it has to go that way.
        assertEquals(500.0, net.route(listOf(at(0.0, 0.0), at(50.0, 200.0), at(100.0, 0.0))).length, 1.0)
    }

    @Test
    fun walksToTheTrailFromAPointOffIt() {
        val net = TrailNetwork(listOf(line(0.0, 0.0, 200.0, 0.0)))
        val r = net.route(listOf(at(0.0, 20.0), at(200.0, 0.0)))
        assertEquals(220.0, r.length, 0.5)
        assertEquals(20.0, r.offTrail, 0.5)
        assertEquals(listOf(false, true), r.pieces.map { it.onTrail })
    }

    @Test
    fun goesStraightWhenTheTrailIsFurtherThanTheLeg() {
        val net = TrailNetwork(listOf(line(0.0, 0.0, 200.0, 0.0)))
        val r = net.route(listOf(at(50.0, 100.0), at(60.0, 100.0)))
        assertEquals(10.0, r.length, 0.1)
        assertFalse(r.pieces.single().onTrail)
    }

    @Test
    fun anOpenLoopIsClosedWhereItsEndsAlmostMeet() {
        // Recorded round a square, ending 4 m from where it started.
        val net = TrailNetwork(listOf(line(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0, 0.0, 4.0)))
        // 46 m down to the end, the 4 m join, then 50 m along the start; the other way is 300 m.
        assertEquals(100.0, net.route(listOf(at(0.0, 50.0), at(50.0, 0.0))).length, 1.0)
    }

    @Test
    fun aLineEndingOnAnotherIsJoinedThere() {
        val net = TrailNetwork(listOf(line(-100.0, 0.0, 100.0, 0.0), line(0.0, 0.0, 0.0, 100.0)))
        assertEquals(200.0, net.route(listOf(at(100.0, 0.0), at(0.0, 100.0))).length, 0.5)
    }

    @Test
    fun endsAroundAJunctionAllMeet() {
        // Four trails stopping short of a junction, at 0, 1, 9 and 10 m along it.
        val net = TrailNetwork(
            listOf(line(0.0, 100.0, 0.0, 0.0), line(1.0, -100.0, 1.0, 0.0), line(9.0, 100.0, 9.0, 0.0), line(10.0, -100.0, 10.0, 0.0)),
        )
        val r = net.route(listOf(at(0.0, 100.0), at(10.0, -100.0)))
        assertEquals(0.0, r.offTrail, 0.01)
        assertEquals(210.0, r.length, 1.0)
    }

    @Test
    fun nearestEndIsALineEndOrJunctionNotAMiddleVertex() {
        val net = TrailNetwork(listOf(line(0.0, 0.0, 50.0, 0.0, 100.0, 0.0), line(50.0, -50.0, 50.0, -10.0)))
        // The middle vertex at 50 m is closest, but it's the middle of a line; the T's end is next.
        val end = net.nearestEnd(at(50.0, -3.0))!!
        assertEquals(0.0, Geodesy.distance(end, at(50.0, -10.0)), 0.01)
        assertEquals(0.0, Geodesy.distance(net.nearestEnd(at(95.0, 5.0))!!, at(100.0, 0.0)), 0.01)
    }

    @Test
    fun emptyNetworkMeasuresStraight() {
        val net = TrailNetwork(emptyList())
        assertTrue(net.isEmpty)
        val r = net.route(listOf(at(0.0, 0.0), at(30.0, 40.0)))
        assertEquals(50.0, r.length, 0.1)
        assertEquals(50.0, r.offTrail, 0.1)
    }

    @Test
    fun snapsToTheNearestLineNearOrFar() {
        val net = TrailNetwork(listOf(line(0.0, 0.0, 2000.0, 0.0), line(0.0, 300.0, 2000.0, 300.0), line(3000.0, -500.0, 3000.0, 500.0)))
        assertEquals(20.0, net.snap(at(500.0, 20.0))!!.distance, 0.1)
        assertEquals(130.0, net.snap(at(500.0, 170.0))!!.distance, 0.1) // closer to the upper line
        assertEquals(400.0, net.snap(at(2600.0, 0.0))!!.distance, 0.1) // the vertical line, not the ends
        assertEquals(20_000.0, net.snap(at(-20_000.0, 0.0))!!.distance, 1.0) // far from all of them
        assertEquals(null, TrailNetwork(emptyList()).snap(at(0.0, 0.0)))
    }

    @Test
    fun snapsToTheNearestLineWhenTheFirstFoundIsOutsideTheSearch() {
        // Enough lines that the grid is searched rather than every line. Searching 50 m round
        // (70, 70) reaches the cell of the line at 98 m but not that of the one at 80 m.
        val far = (0 until 18).map { line(5000.0 + it * 100, 5000.0, 5000.0 + it * 100, 5001.0) }
        val net = TrailNetwork(listOf(line(0.0, 0.0, 0.0, 1.0), line(-10.0, 70.0, -10.0, 71.0)) + far)
        assertEquals(80.0, net.snap(at(70.0, 70.0))!!.distance, 0.5)
    }
}
