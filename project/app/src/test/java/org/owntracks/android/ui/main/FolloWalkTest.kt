package org.owntracks.android.ui.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking-only lines (FP079, 22 Nov 2026). The cases are the shared ones of FOLLO_tracks/docs/next_build/
 * walk_vectors.json (made by walk_ref.py, the reference; the iPhone app runs the same file).
 */
class FolloWalkTest {
  private class Case(val name: String, val now: Double, val points: List<FolloWalk.Fix>, val pieces: List<Int>, val firstLast: List<List<DoubleArray>>)

  private fun cases(): List<Case> {
    val text = javaClass.classLoader!!.getResourceAsStream("follo/walk_vectors.json")!!.bufferedReader().readText()
    val root = Json.parseToJsonElement(text).jsonObject
    return root["cases"]!!.jsonArray.map { e ->
      val c = e.jsonObject
      Case(
          c["name"]!!.jsonPrimitive.content,
          c["now"]!!.jsonPrimitive.double,
          c["points"]!!.jsonArray.map { p -> p.jsonArray.let { FolloWalk.Fix(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) } },
          c["pieces"]!!.jsonArray.map { it.jsonPrimitive.int },
          c["first_last"]!!.jsonArray.map { fl -> fl.jsonArray.map { ll -> doubleArrayOf(ll.jsonArray[0].jsonPrimitive.double, ll.jsonArray[1].jsonPrimitive.double) } },
      )
    }
  }

  @Test
  fun `the constants are the shared ones`() {
    val text = javaClass.classLoader!!.getResourceAsStream("follo/walk_vectors.json")!!.bufferedReader().readText()
    val k = Json.parseToJsonElement(text).jsonObject["constants"]!!.jsonObject
    assertEquals(k["V"]!!.jsonPrimitive.double, FolloWalk.V, 0.0)
    assertEquals(k["W"]!!.jsonPrimitive.double, FolloWalk.W, 0.0)
    assertEquals(k["CAP"]!!.jsonPrimitive.double, FolloWalk.CAP, 0.0)
    assertEquals(k["MINN"]!!.jsonPrimitive.int, FolloWalk.MINN)
    assertEquals(k["MINT"]!!.jsonPrimitive.double, FolloWalk.MINT, 0.0)
    assertEquals(k["KEEP"]!!.jsonPrimitive.double, FolloWalk.KEEP, 0.0)
    assertEquals(k["GAP"]!!.jsonPrimitive.double, FolloWalk.GAP, 0.0)
    assertEquals(k["TAIL"]!!.jsonPrimitive.double, FolloWalk.TAIL, 0.0)
  }

  @Test
  fun `every shared case gives the reference's lines`() {
    val all = cases()
    assertTrue(all.size >= 11)
    for (c in all) {
      val got = FolloWalk.pieces(c.points, c.now)
      assertEquals(c.name, c.pieces, got.map { it.size })
      for ((i, piece) in got.withIndex()) {
        assertEquals("${c.name} piece $i first lat", c.firstLast[i][0][0], piece.first().lat, 1e-9)
        assertEquals("${c.name} piece $i first lon", c.firstLast[i][0][1], piece.first().lon, 1e-9)
        assertEquals("${c.name} piece $i last lat", c.firstLast[i][1][0], piece.last().lat, 1e-9)
        assertEquals("${c.name} piece $i last lon", c.firstLast[i][1][1], piece.last().lon, 1e-9)
      }
    }
  }

  /** The map keeps its lines in step one fix at a time: that must come to the same lines as the one pass. */
  @Test
  fun `one fix at a time comes to the same lines, and looking ahead changes nothing`() {
    for (c in cases()) {
      val w = FolloWalk.Walker()
      val drawn = mutableListOf<Int>()
      for ((i, p) in c.points.withIndex()) {
        c.points.getOrNull(i)?.let { w.wouldJoin(it) } // the look the map takes before the arrow reaches the fix
        w.add(p).ended?.let { if (it.drawnEnded) drawn += it.n }
      }
      w.open?.let { if (it.drawnOpen(c.now)) drawn += it.n }
      assertEquals(c.name, c.pieces, drawn)
    }
  }

  @Test
  fun `the caller's own break starts a new piece whatever the rule says`() {
    val walk = cases().first { it.name == "walk" }
    val w = FolloWalk.Walker()
    var ended: FolloWalk.Piece? = null
    for ((i, p) in walk.points.withIndex()) w.add(p, breakBefore = i == 13).ended?.let { ended = it }
    assertNotNull(ended)
    assertEquals(13, ended!!.n)
    assertEquals(12, w.open!!.n)
  }

  @Test
  fun `a piece shows about 15 s after walking starts, and a short one comes off a minute after it goes quiet`() {
    val walk = cases().first { it.name == "walk" }
    val w = FolloWalk.Walker()
    w.add(walk.points[0])
    w.add(walk.points[1])
    w.add(walk.points[2]) // 10 s, three fixes: not yet
    assertFalse(w.open!!.drawnOpen(walk.points[2].t))
    w.add(walk.points[3]) // 15 s
    assertTrue(w.open!!.drawnOpen(walk.points[3].t))
    assertTrue(w.open!!.drawnOpen(walk.points[3].t + 60))
    assertFalse(w.open!!.drawnOpen(walk.points[3].t + 61))
    assertFalse(w.open!!.drawnEnded)
  }

  @Test
  fun `lines are flat lat lon arrays, the dot joined to the newest when it is a step away`() {
    val walk = cases().first { it.name == "walk" }
    val last = walk.points.last()
    val plain = FolloWalk.lines(walk.points, walk.now)
    assertEquals(1, plain.size)
    assertEquals(50, plain[0].size)
    val dot = last.lat + 10 / 111195.0 to last.lon
    val joined = FolloWalk.lines(walk.points, walk.now, dot)
    assertEquals(52, joined[0].size)
    assertEquals(dot.first, joined[0][50], 0.0)
    // a dot 200 m on is not joined; nor is one to a line that does not reach the newest fix
    assertEquals(50, FolloWalk.lines(walk.points, walk.now, last.lat + 200 / 111195.0 to last.lon)[0].size)
    val jump = cases().first { it.name == "tail_ended" }
    assertTrue(FolloWalk.lines(jump.points, jump.now, jump.points.last().lat to jump.points.last().lon + 0.0001).isEmpty())
  }
}
