package org.geotrailmaps.geo

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Walkable lines joined into a network, for distances along trails. Lines are joined where
 * they cross, and where one ends within [JOIN_METERS] of another (a junction drawn or
 * recorded slightly apart).
 */
class TrailNetwork(lines: List<List<LatLon>>) {
    private val proj = LocalProjection(lines.firstOrNull { it.isNotEmpty() }?.first() ?: LatLon(0.0, 0.0))

    // Nodes in metres (local projection); edges between them, with each node's edges.
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val edgeA: IntArray
    private val edgeB: IntArray
    private val edgeLength: DoubleArray
    private val nodeEdges: Array<IntArray>
    /** Edges by where they are, to find those near a point quickly. */
    private val edgeGrid: Grid

    val isEmpty: Boolean get() = edgeA.isEmpty()

    init {
        // Segments of the lines, as node pairs.
        val segA = ArrayList<Int>()
        val segB = ArrayList<Int>()
        val segLine = ArrayList<Int>()
        val ends = ArrayList<Int>() // each line's first and last node
        val endLine = ArrayList<Int>()
        val along = ArrayList<Double>() // distance from its line's start, per vertex node
        for ((li, line) in lines.withIndex()) {
            var first = -1
            var prev = -1
            var walked = 0.0
            for (p in line) {
                val q = proj.forward(p)
                if (prev >= 0) {
                    val d = hypot(q.x - xs[prev], q.y - ys[prev])
                    if (d < 1e-6) continue
                    walked += d
                }
                val n = addNode(q.x, q.y)
                along += walked
                if (prev >= 0) { segA += prev; segB += n; segLine += li } else first = n
                prev = n
            }
            if (prev != first) { ends += first; ends += prev; endLine += li; endLine += li }
        }

        val grid = Grid(segA.size, forPairs = true) { s -> doubleArrayOf(xs[segA[s]], ys[segA[s]], xs[segB[s]], ys[segB[s]]) }
        val splits = Array(segA.size) { ArrayList<Pair<Double, Int>>() }
        val extraA = ArrayList<Int>()
        val extraB = ArrayList<Int>()

        // The end of segment s at parameter t, if t is at one; else -1.
        fun endAt(s: Int, t: Double) = if (t <= EPS) segA[s] else if (t >= 1 - EPS) segB[s] else -1

        // Crossings.
        grid.pairs { i, j ->
            val a = segA[i]; val b = segB[i]; val c = segA[j]; val d = segB[j]
            if (a == c || a == d || b == c || b == d) return@pairs
            val rx = xs[b] - xs[a]; val ry = ys[b] - ys[a]
            val sx = xs[d] - xs[c]; val sy = ys[d] - ys[c]
            val den = rx * sy - ry * sx
            if (den == 0.0) return@pairs // parallel
            val qx = xs[c] - xs[a]; val qy = ys[c] - ys[a]
            val t = (qx * sy - qy * sx) / den
            val u = (qx * ry - qy * rx) / den
            if (t < -EPS || t > 1 + EPS || u < -EPS || u > 1 + EPS) return@pairs
            val endI = endAt(i, t)
            val endJ = endAt(j, u)
            when {
                endI >= 0 && endJ >= 0 -> { extraA += endI; extraB += endJ } // vertices drawn at the same spot
                endI >= 0 -> splits[j] += u to endI
                endJ >= 0 -> splits[i] += t to endJ
                else -> addNode(xs[a] + t * rx, ys[a] + t * ry).let { n -> splits[i] += t to n; splits[j] += u to n }
            }
        }

        // Line ends close to other lines (or to a far part of their own, e.g. a loop), joined
        // to the nearest place on each: ends drawn around a junction all meet.
        for ((k, e) in ends.withIndex()) {
            val ex = xs[e]; val ey = ys[e]
            val nearest = HashMap<Int, Triple<Int, Double, Double>>() // line -> segment, t, distance
            grid.near(ex, ey, JOIN_METERS) { s ->
                val a = segA[s]; val b = segB[s]
                if (a == e || b == e) return@near
                if (segLine[s] == endLine[k] && minOf(abs(along[a] - along[e]), abs(along[b] - along[e])) < 3 * JOIN_METERS) return@near
                val (t, d) = closest(ex, ey, a, b)
                if (d < JOIN_METERS && d < (nearest[segLine[s]]?.third ?: Double.MAX_VALUE)) nearest[segLine[s]] = Triple(s, t, d)
            }
            for ((s, t) in nearest.values) {
                val a = segA[s]; val b = segB[s]
                val n = endAt(s, t).takeIf { it >= 0 }
                    ?: addNode(xs[a] + t * (xs[b] - xs[a]), ys[a] + t * (ys[b] - ys[a])).also { splits[s] += t to it }
                extraA += e; extraB += n
            }
        }

        // Edges: each segment cut at its splits, plus the joins.
        val ea = ArrayList<Int>()
        val eb = ArrayList<Int>()
        for (s in segA.indices) {
            var prev = segA[s]
            for ((_, n) in splits[s].sortedBy { it.first }) {
                if (n != prev) { ea += prev; eb += n; prev = n }
            }
            if (segB[s] != prev) { ea += prev; eb += segB[s] }
        }
        ea += extraA; eb += extraB
        edgeA = ea.toIntArray()
        edgeB = eb.toIntArray()
        edgeLength = DoubleArray(edgeA.size) { hypot(xs[edgeB[it]] - xs[edgeA[it]], ys[edgeB[it]] - ys[edgeA[it]]) }
        val lists = Array(xs.size) { ArrayList<Int>(2) }
        for (k in edgeA.indices) { lists[edgeA[k]] += k; lists[edgeB[k]] += k }
        nodeEdges = Array(xs.size) { lists[it].toIntArray() }
        edgeGrid = Grid(edgeA.size) { k -> doubleArrayOf(xs[edgeA[k]], ys[edgeA[k]], xs[edgeB[k]], ys[edgeB[k]]) }
    }

    private fun addNode(x: Double, y: Double): Int {
        xs += x; ys += y
        return xs.size - 1
    }

    /** Parameter along a–b of the point closest to (x, y), and its distance. */
    private fun closest(x: Double, y: Double, a: Int, b: Int): Pair<Double, Double> {
        val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - xs[a]) * dx + (y - ys[a]) * dy) / len2).coerceIn(0.0, 1.0)
        return t to hypot(xs[a] + t * dx - x, ys[a] + t * dy - y)
    }

    /** A position on the network: [point], [distance] metres from where it was asked for. */
    class Snap internal constructor(internal val edge: Int, internal val t: Double, val point: LatLon, val distance: Double)

    /** The closest position on the network to [p]; null if the network is empty. */
    fun snap(p: LatLon): Snap? {
        val q = proj.forward(p)
        var best = -1; var bestT = 0.0; var bestD = Double.MAX_VALUE
        fun consider(k: Int) {
            val (t, d) = closest(q.x, q.y, edgeA[k], edgeB[k])
            if (d < bestD) { best = k; bestT = t; bestD = d }
        }
        // The cells around it, wider until an edge is found within reach (a closer one would
        // be in them); every edge once that's more cells than edges.
        var r = CELL
        while (true) {
            if (edgeGrid.cellsNear(r) > edgeA.size) { edgeA.indices.forEach(::consider); break }
            edgeGrid.near(q.x, q.y, r, ::consider)
            // Found within the radius searched: nothing closer can be outside it.
            if (best >= 0 && bestD <= r) break
            r *= 2
        }
        if (best < 0) return null
        return Snap(best, bestT, at(best, bestT), bestD)
    }

    /** The closest line end or junction to [p], where new trails most likely join; null if none. */
    fun nearestEnd(p: LatLon): LatLon? {
        val q = proj.forward(p)
        var best = -1; var bestD = Double.MAX_VALUE
        for (n in nodeEdges.indices) {
            if (nodeEdges[n].size == 2 || nodeEdges[n].isEmpty()) continue // the middle of a line
            val d = hypot(xs[n] - q.x, ys[n] - q.y)
            if (d < bestD) { best = n; bestD = d }
        }
        return if (best < 0) null else proj.inverse(PagePoint(xs[best], ys[best]))
    }

    private fun at(edge: Int, t: Double): LatLon {
        val a = edgeA[edge]; val b = edgeB[edge]
        return proj.inverse(PagePoint(xs[a] + t * (xs[b] - xs[a]), ys[a] + t * (ys[b] - ys[a])))
    }

    /** The shortest way along the network from [from] to [to]; null if they aren't connected. */
    fun path(from: Snap, to: Snap): List<LatLon>? {
        val dist = DoubleArray(xs.size) { Double.MAX_VALUE }
        val prev = IntArray(xs.size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun reach(n: Int, d: Double, via: Int) {
            if (d < dist[n]) { dist[n] = d; prev[n] = via; queue += d to n }
        }
        val fl = edgeLength[from.edge]
        reach(edgeA[from.edge], from.t * fl, -1)
        reach(edgeB[from.edge], (1 - from.t) * fl, -1)

        val tl = edgeLength[to.edge]
        var best = if (from.edge == to.edge) abs(from.t - to.t) * fl else Double.MAX_VALUE
        var bestEnd = -1 // -1: straight along the shared edge
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()!!
            if (d > dist[n]) continue
            if (d >= best) break
            if (n == edgeA[to.edge] && d + to.t * tl < best) { best = d + to.t * tl; bestEnd = n }
            if (n == edgeB[to.edge] && d + (1 - to.t) * tl < best) { best = d + (1 - to.t) * tl; bestEnd = n }
            for (k in nodeEdges[n]) {
                val m = if (edgeA[k] == n) edgeB[k] else edgeA[k]
                reach(m, d + edgeLength[k], n)
            }
        }
        if (best == Double.MAX_VALUE) return null
        val nodes = ArrayList<Int>()
        var n = bestEnd
        while (n >= 0) { nodes += n; n = prev[n] }
        nodes.reverse()
        return listOf(from.point) + nodes.map { proj.inverse(PagePoint(xs[it], ys[it])) } + to.point
    }

    /** Segments bucketed into square cells, to find the ones near each other quickly. */
    private class Grid(count: Int, forPairs: Boolean = false, bounds: (Int) -> DoubleArray) {
        private val cells = HashMap<Long, ArrayList<Int>>()
        /** Each segment's cells, for [pairs] only. */
        private var segCells: Array<LongArray?>? = if (forPairs) arrayOfNulls(count) else null

        init {
            val keys = HashSet<Long>()
            for (s in 0 until count) {
                // In pieces no longer than a cell, so a long diagonal takes the cells along it,
                // not every cell of its bounding box.
                val (ax, ay, bx, by) = bounds(s).toList()
                val pieces = maxOf(1, ceil(hypot(bx - ax, by - ay) / CELL).toInt())
                keys.clear()
                for (i in 0 until pieces) {
                    val t0 = i.toDouble() / pieces
                    val t1 = (i + 1).toDouble() / pieces
                    val x0 = ax + t0 * (bx - ax); val x1 = ax + t1 * (bx - ax)
                    val y0 = ay + t0 * (by - ay); val y1 = ay + t1 * (by - ay)
                    forCells(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)) { keys += it }
                }
                for (key in keys) cells.getOrPut(key) { ArrayList() } += s
                segCells?.set(s, keys.toLongArray())
            }
        }

        private inline fun forCells(x0: Double, y0: Double, x1: Double, y1: Double, f: (Long) -> Unit) {
            for (cx in cell(x0)..cell(x1)) for (cy in cell(y0)..cell(y1)) f((cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL))
        }

        private fun cell(v: Double) = floor(v / CELL).toInt()

        /** Calls [f] once for each pair of segments sharing a cell. Only once: it frees what it needs. */
        fun pairs(f: (Int, Int) -> Unit) {
            val segCells = segCells ?: error("Not made for pairs, or pairs() already called")
            this.segCells = null
            // Each segment's later neighbours, marked as found so one sharing several cells
            // comes once (a set of all pairs runs out of memory on a large map).
            val pairedWith = IntArray(segCells.size) { -1 }
            for (i in segCells.indices) for (key in segCells[i]!!) for (j in cells.getValue(key)) {
                if (j > i && pairedWith[j] != i) { pairedWith[j] = i; f(i, j) }
            }
        }

        /** How many cells [near] looks in for [r]. */
        fun cellsNear(r: Double): Long = (2 * r / CELL).toLong().plus(2).let { it * it }

        /** Calls [f] for each segment in the cells within [r] of (x, y) (some more than once). */
        fun near(x: Double, y: Double, r: Double, f: (Int) -> Unit) {
            forCells(x - r, y - r, x + r, y + r) { key -> cells[key]?.forEach(f) }
        }
    }

    companion object {
        /** Line ends this close to another line are joined to it. */
        const val JOIN_METERS = 10.0
        private const val CELL = 50.0
        private const val EPS = 1e-9
    }
}

/** A measured route: along trails where it can be, straight where there's no trail. */
class TrailRoute(val pieces: List<Piece>) {
    class Piece(val points: List<LatLon>, val onTrail: Boolean)

    val length: Double = pieces.sumOf { Geodesy.pathLength(it.points) }
    val offTrail: Double = pieces.filter { !it.onTrail }.sumOf { Geodesy.pathLength(it.points) }

    /** The whole route as one line. */
    val points: List<LatLon>
        get() = pieces.flatMap { it.points }.fold(ArrayList()) { out, p -> out.apply { if (lastOrNull() != p) add(p) } }
}

/**
 * The route through [points] in order. Each leg goes from its point straight to the nearest
 * trail, along the trails, and straight on to the next point; it's straight all the way
 * when the trails don't connect, or when reaching them is longer than the leg itself.
 */
fun TrailNetwork.route(points: List<LatLon>): TrailRoute {
    val snaps = points.map { snap(it) }
    val pieces = ArrayList<TrailRoute.Piece>()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val sa = snaps[i]
        val sb = snaps[i + 1]
        val path = if (sa != null && sb != null && Geodesy.distance(a, b) > sa.distance + sb.distance) path(sa, sb) else null
        if (path == null) {
            pieces += TrailRoute.Piece(listOf(a, b), onTrail = false)
            continue
        }
        if (sa!!.distance >= 1) pieces += TrailRoute.Piece(listOf(a, sa.point), onTrail = false)
        pieces += TrailRoute.Piece(path, onTrail = true)
        if (sb!!.distance >= 1) pieces += TrailRoute.Piece(listOf(sb.point, b), onTrail = false)
    }
    return TrailRoute(pieces)
}
