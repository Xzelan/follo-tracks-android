package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3 Nov 2026 (Alex): the area edge must not draw the zero-width links between separate groups of circles. */
class FolloRingEdgesTest {
  private fun sq(la: Double, lo: Double, d: Double) = listOf(la to lo, la to lo + d, la + d to lo + d, la + d to lo)

  @Test
  fun `a ring without links is one closed line`() {
    val r = sq(0.0, 0.0, 1.0)
    val p = FolloRingEdges.pieces(r)
    assertEquals(1, p.size)
    assertEquals(r + r.first(), p[0])
  }

  @Test
  fun `a closing point repeated by the server makes no difference`() {
    val r = sq(0.0, 0.0, 1.0)
    assertEquals(FolloRingEdges.pieces(r), FolloRingEdges.pieces(r + r.first()))
  }

  @Test
  fun `a piece hung off the ring by a link comes back as two lines and no link`() {
    // main square, out from its corner (1,1) to the piece's corner (2,2), round the piece, back the same way
    val ring = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 2.0 to 2.0, 2.0 to 3.0, 3.0 to 3.0, 3.0 to 2.0, 2.0 to 2.0, 1.0 to 1.0, 1.0 to 0.0)
    val p = FolloRingEdges.pieces(ring)
    assertEquals(2, p.size)
    val segs = p.flatMap { run -> run.zipWithNext() }
    assertTrue("no segment along the link", segs.none { (a, b) -> setOf(a, b) == setOf(1.0 to 1.0, 2.0 to 2.0) })
    assertEquals("every other edge is kept", 4 + 4, segs.size)
    // the main square closes round on itself, the piece too
    assertTrue(p.all { it.first() == it.last() })
  }

  @Test
  fun `two pieces (TEST on 3 Nov 2026 has three groups) - both pieces closed, the main ring in two runs, no links`() {
    val ring = listOf(
        0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0,
        2.0 to 2.0, 2.0 to 3.0, 3.0 to 3.0, 2.0 to 2.0, // piece A off (1,1)
        1.0 to 1.0, 1.0 to 0.0,
        -2.0 to -2.0, -2.0 to -3.0, -3.0 to -3.0, -2.0 to -2.0, // piece B off (1,0)
        1.0 to 0.0, 0.5 to 0.0)
    val p = FolloRingEdges.pieces(ring)
    // the main ring is cut where each link left it: two open runs meeting end to end, plus the two closed pieces
    assertEquals(4, p.size)
    assertEquals(2, p.count { it.first() == it.last() })
    val segs = p.flatMap { it.zipWithNext() }.map { (a, b) -> setOf(a, b) }
    assertEquals("15 ring edges minus the 2 links walked both ways", 11, segs.size)
    assertTrue(setOf(1.0 to 1.0, 2.0 to 2.0) !in segs)
    assertTrue(setOf(1.0 to 0.0, -2.0 to -2.0) !in segs)
  }
}
