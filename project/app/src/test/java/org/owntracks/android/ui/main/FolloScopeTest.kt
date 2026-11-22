package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageFollo
import org.owntracks.android.ui.main.FolloRules.Circle
import org.owntracks.android.ui.main.FolloScope.Fix
import org.owntracks.android.ui.main.FolloScope.Mode
import org.owntracks.android.ui.main.FolloScope.State
import org.owntracks.android.ui.main.FolloScope.View

/** FP055 (19 Nov 2026): the shared test vectors of docs/FP055_SPEC_20261119.md §10, the same on iOS. */
class FolloScopeTest {
  private val a = Circle(-41.10, 146.80, 1000.0, "A")
  private val b = Circle(-41.50, 146.80, 1000.0, "B")
  private val held = FolloScope.areas(listOf(a, b))
  private val inA = Fix(-41.10, 146.80)
  private val out = Fix(-41.30, 146.80)

  // §10.1
  @Test
  fun `a Basecamp circle joins the nearest area, and none when no area is held`() {
    val base = Circle(-41.12, 146.80, 100.0, "Basecamp")
    val areas = FolloScope.areas(listOf(a, b, base))
    assertEquals(listOf("A", "B"), areas.keys.toList())
    assertEquals(listOf(a, base), areas["A"])
    assertEquals(listOf(b), areas["B"])
    assertTrue(FolloScope.areas(listOf(base)).isEmpty())
    // a tie goes to the first area in name order, whatever order the circles come in
    val z = Circle(-41.14, 146.80, 1000.0, "Z")
    val c = Circle(-41.10, 146.80, 1000.0, "C")
    assertEquals(listOf(c, base), FolloScope.areas(listOf(z, c, base))["C"])
    // a second Basecamp circle is measured to the area's own circles, not to the first Basecamp circle
    val far = Circle(-41.48, 146.80, 100.0, "Basecamp")
    assertEquals(listOf(b, far), FolloScope.areas(listOf(a, b, base, far))["B"])
  }

  // §10.2
  @Test
  fun `bbox and frame of one circle`() {
    val box = FolloScope.bbox(listOf(a))!!
    assertEquals(-41.10 - 0.0089932, box.south, 1e-7)
    assertEquals(-41.10 + 0.0089932, box.north, 1e-7)
    assertEquals(146.80 - 0.0119343, box.west, 1e-7)
    assertEquals(146.80 + 0.0119343, box.east, 1e-7)
    assertEquals(0.0179864, box.north - box.south, 1e-7)
    val f = FolloScope.frame(box)
    assertEquals(-41.10 + 0.0098925, f.north, 1e-7)
    assertEquals(-41.10 - 0.0098925, f.south, 1e-7)
    assertEquals(146.80 + 0.0119343 * 1.1, f.east, 1e-7)
    assertEquals(146.80 - 0.0119343 * 1.1, f.west, 1e-7)
    assertNull(FolloScope.bbox(emptyList()))
    // the box of an area takes in every circle, a Basecamp circle too
    val both = FolloScope.bbox(listOf(a, Circle(-41.12, 146.83, 100.0, "Basecamp")))!!
    assertEquals(-41.12 - 100.0 / FolloScope.M_PER_DEG, both.south, 1e-9)
    assertTrue(both.east >= 146.80 + 0.0119343)
  }

  // §10.3
  @Test
  fun `which view`() {
    assertEquals(FolloScope.WAITING, FolloScope.choose(held, emptyMap(), out))
    assertEquals(View(Mode.LAST, "B"), FolloScope.choose(held, mapOf("B" to 5L), out))
    assertEquals(View(Mode.LAST, "A"), FolloScope.choose(held, mapOf("A" to 9L, "B" to 5L), out))
    assertEquals(View(Mode.LAST, "B"), FolloScope.choose(held, mapOf("C" to 99L, "B" to 5L), out)) // C is not held
    assertEquals(View(Mode.LIVE, "A"), FolloScope.choose(held, emptyMap(), inA))
    assertEquals(View(Mode.LIVE, "A"), FolloScope.choose(held, mapOf("B" to 5L), inA))
    // no fix, or a stale one (the caller passes none): never LIVE
    assertEquals(View(Mode.LAST, "B"), FolloScope.choose(held, mapOf("B" to 5L), null))
    assertEquals(FolloScope.WAITING, FolloScope.choose(held, emptyMap(), null))
    // holds nothing: waiting, wherever it has been
    assertEquals(FolloScope.WAITING, FolloScope.choose(emptyMap(), mapOf("A" to 9L), inA))
    // been to two at the same second: the first in name order
    assertEquals(View(Mode.LAST, "A"), FolloScope.choose(held, mapOf("A" to 5L, "B" to 5L), out))
  }

  // §10.4
  @Test
  fun `LIVE ends only after 10 s outside without a break`() {
    val been = mapOf("A" to 100L)
    var s = FolloScope.step(null, held, been, inA, 0L)
    assertEquals(State(View(Mode.LIVE, "A")), s)
    for (t in listOf(1_000L, 5_000L, 10_000L)) {
      s = FolloScope.step(s, held, been, out, t) // outside at 0, 4, 9 s after the first outside fix
      assertEquals(Mode.LIVE, s.view.mode)
      assertEquals(1_000L, s.outsideSinceMs)
      assertFalse(FolloScope.showsOwn(s, held, out)) // …but nothing of this phone shows, at once
    }
    s = FolloScope.step(s, held, been, out, 11_000L) // 10 s
    assertEquals(State(View(Mode.LAST, "A")), s)

    // an inside fix at 6 s resets the count
    s = FolloScope.step(State(View(Mode.LIVE, "A")), held, been, out, 0L)
    s = FolloScope.step(s, held, been, inA, 6_000L)
    assertNull(s.outsideSinceMs)
    assertTrue(FolloScope.showsOwn(s, held, inA))
    s = FolloScope.step(s, held, been, out, 9_000L)
    s = FolloScope.step(s, held, been, out, 12_000L)
    assertEquals(Mode.LIVE, s.view.mode)
    s = FolloScope.step(s, held, been, out, 19_000L)
    assertEquals(View(Mode.LAST, "A"), s.view)

    // the fix goes stale, or the area is no longer held: at once
    val live = State(View(Mode.LIVE, "A"))
    assertEquals(View(Mode.LAST, "A"), FolloScope.step(live, held, been, null, 0L).view)
    assertEquals(FolloScope.WAITING, FolloScope.step(live, FolloScope.areas(listOf(b)), been, inA, 0L).view)
    // not LIVE before: no guard, and nothing of this phone shows
    assertEquals(State(View(Mode.LAST, "A")), FolloScope.step(State(View(Mode.LAST, "A")), held, been, out, 0L))
    assertFalse(FolloScope.showsOwn(State(View(Mode.LAST, "A")), held, inA))
    assertFalse(FolloScope.showsOwn(null, held, inA))
  }

  // §10.5
  @Test
  fun `the server's been rows are merged, bad rows skipped`() {
    assertEquals(mapOf("A" to 100L, "B" to 70L), FolloScope.mergeBeen(mapOf("A" to 100L), listOf("A" to 50L, "B" to 70L)))
    val follo =
        Parser().fromJson("""[{"_type":"follo","state":"outside","been":[["A",50],["B",70.0],["C","x"],[7,8],["D"],"junk",{"a":1},["",9],null]}]""".byteInputStream())
            .filterIsInstance<MessageFollo>().single()
    assertEquals(listOf("A" to 50L, "B" to 70L), follo.beenRows())
    val none = Parser().fromJson("""[{"_type":"follo","state":"outside"}]""".byteInputStream()).filterIsInstance<MessageFollo>().single()
    assertTrue(none.beenRows().isEmpty())
    val odd = Parser().fromJson("""[{"_type":"follo","state":"outside","been":"no"}]""".byteInputStream()).filterIsInstance<MessageFollo>().single()
    assertTrue(odd.beenRows().isEmpty())
    // and the record survives the phone's storage
    val kept = mapOf("MV" to 1794000000L, "FG" to 1793000000L)
    assertEquals(kept, FolloScope.decodeBeen(FolloScope.encodeBeen(kept)))
    assertTrue(FolloScope.decodeBeen(null).isEmpty())
    assertTrue(FolloScope.decodeBeen("rubbish;=5;X=y").isEmpty())
  }

  // §10.6
  @Test
  fun `inside the union of two overlapping circles`() {
    val two = listOf(Circle(-41.10, 146.80, 1000.0, "A"), Circle(-41.10, 146.815, 1000.0, "A"))
    assertTrue(FolloScope.inside(-41.10, 146.8075, two)) // in the overlap
    assertTrue(FolloScope.inside(-41.10, 146.79, two)) // in the first only
    assertFalse(FolloScope.inside(-41.12, 146.8075, two)) // in neither
    assertEquals(listOf("A"), FolloScope.areasAt(-41.10, 146.8075, mapOf("A" to two, "B" to listOf(b))))
    assertTrue(FolloScope.areasAt(-41.30, 146.80, held).isEmpty())
  }

  // §10.7
  @Test
  fun `exempt phones`() {
    assertFalse(FolloScope.applies(1, volunteerView = false))
    assertTrue(FolloScope.applies(1, volunteerView = true))
    assertTrue(FolloScope.applies(null, volunteerView = false)) // no status yet
    assertTrue(FolloScope.applies(0, volunteerView = false))
  }

  @Test
  fun `a circle's ring lies on the circle, so the mask ends where sharing starts`() {
    for (c in listOf(a, Circle(-27.64, 153.08, 3000.0, "DW"), Circle(-41.6, 145.93, 30_000.0, "CI"))) {
      val ring = FolloScope.ring(c, FolloScope.ringPoints(c.r))
      assertEquals(FolloScope.ringPoints(c.r), ring.size)
      for ((la, lo) in ring) assertEquals(c.r, FolloRules.distanceM(c.lat, c.lon, la, lo), 0.01)
    }
    assertEquals(72, FolloScope.ringPoints(100.0))
    assertEquals(375, FolloScope.ringPoints(3000.0))
    assertEquals(720, FolloScope.ringPoints(30_000.0))
  }

  @Test
  fun `the box holds and clamps`() {
    val f = FolloScope.Box(-41.2, 146.7, -41.0, 146.9)
    assertTrue(f.contains(-41.1, 146.8))
    assertFalse(f.contains(-41.3, 146.8))
    assertEquals(-41.2 to 146.9, f.clamp(-41.3, 147.0))
    assertEquals(-41.1 to 146.8, f.clamp(-41.1, 146.8))
    assertEquals(-41.1, f.midLat, 1e-9)
    assertEquals(146.8, f.midLon, 1e-9)
  }
}
