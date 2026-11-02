package org.owntracks.android.model

import kotlin.math.roundToLong

/**
 * The lines to draw for an area boundary (Alex, 3 Nov 2026: "an extra line perpendicular to 2 circles on TEST").
 *
 * When an area's circles fall into separate groups, the server stores the boundary as ONE ring: each extra piece hangs
 * off the main ring by a zero-width link (out along the link, round the piece, back the same way). The inside test
 * counts the link's two edges twice, so it changes nothing there, but stroking the ring draws the link as a line
 * across the gap. This returns the ring's edges with every retraced segment left out, as open runs of points; a ring
 * with no links comes back as one closed run. Drawing only: the inside test still uses the whole ring.
 */
object FolloRingEdges {
  private fun key(p: Pair<Double, Double>) = (p.first * 1e7).roundToLong() to (p.second * 1e7).roundToLong()

  fun pieces(ring: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
    val pts = if (ring.size >= 2 && key(ring.first()) == key(ring.last())) ring.dropLast(1) else ring
    val n = pts.size
    if (n < 2) return if (n == 0) emptyList() else listOf(pts)
    val keys = pts.map(::key)
    // a segment walked both ways (a -> b and later b -> a) is a link
    val seen = HashMap<Pair<Pair<Long, Long>, Pair<Long, Long>>, Int>()
    fun undirected(i: Int): Pair<Pair<Long, Long>, Pair<Long, Long>> {
      val a = keys[i]
      val b = keys[(i + 1) % n]
      return if (compareValuesBy(a, b, { it.first }, { it.second }) <= 0) a to b else b to a
    }
    for (i in 0 until n) if (keys[i] != keys[(i + 1) % n]) seen.merge(undirected(i), 1, Int::plus)
    val link = BooleanArray(n) { i -> keys[i] != keys[(i + 1) % n] && (seen[undirected(i)] ?: 0) >= 2 }
    if (link.none { it }) return listOf(pts + pts.first())
    val out = mutableListOf<List<Pair<Double, Double>>>()
    var run = mutableListOf<Pair<Double, Double>>()
    fun flush() {
      if (run.size >= 2) out += run
      run = mutableListOf()
    }
    val start = link.indexOfFirst { it } // start right after a link, so no run wraps round the end
    for (k in 1..n) {
      val i = (start + k) % n
      if (link[i]) {
        flush()
      } else {
        if (run.isEmpty()) run.add(pts[i])
        run.add(pts[(i + 1) % n])
      }
    }
    flush()
    return out
  }
}
