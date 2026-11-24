package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FP066 approach rings (23 Nov 2026; FOLLO_tracks/docs/FP066_SPEC_20261123.md). Made-up places, not search areas. */
class FolloRingsTest {
  // ~ 0.009° latitude = 1 km
  private val a1 = FolloRules.Circle(-30.0, 140.0, 200.0, "AA")
  private val a2 = FolloRules.Circle(-30.01, 140.0, 300.0, "AA")
  private val bc = FolloRules.Circle(-30.05, 140.0, 100.0, "Basecamp")
  private val b1 = FolloRules.Circle(-32.0, 141.0, 500.0, "BB")

  @Test
  fun `one circle gives a ring of its radius plus 1 km`() {
    val r = FolloRings.rings(listOf(a1), 1000.0).single()
    assertEquals("AA", r.area)
    assertEquals(-30.0, r.lat, 1e-9)
    assertEquals(1200.0, r.r, 0.5)
  }

  @Test
  fun `several circles share one ring that covers them all, Basecamp gets its own`() {
    val rings = FolloRings.rings(listOf(a1, a2, bc, b1), 1000.0)
    assertEquals(listOf("AA", "BB", "Basecamp"), rings.map { it.area })
    val aa = rings.first { it.area == "AA" }
    for (c in listOf(a1, a2)) assertTrue(FolloRules.distanceM(aa.lat, aa.lon, c.lat, c.lon) + c.r + 1000.0 <= aa.r + 0.5)
    assertEquals(556.0 + 300.0 + 1000.0, aa.r, 5.0) // half of 1.11 km between centres + the bigger radius + 1 km
  }

  @Test
  fun `the server's margin is used when sane, else 1 km`() {
    assertEquals(1000.0, FolloRings.apprM(null), 0.0)
    assertEquals(2000.0, FolloRings.apprM(2000), 0.0)
    assertEquals(1000.0, FolloRings.apprM(0), 0.0)
    assertEquals(1000.0, FolloRings.apprM(1_000_000), 0.0)
  }

  @Test
  fun `a good fix decides the rings, a fix worse than 500 m changes nothing`() {
    val rings = FolloRings.rings(listOf(a1, b1), 1000.0)
    // 1.1 km north of a1's centre: inside AA's 1.2 km ring
    assertEquals(setOf("AA"), FolloRings.afterFix(emptySet(), rings, -29.990, 140.0, 20f))
    // 5 km away: outside every ring
    assertEquals(emptySet<String>(), FolloRings.afterFix(setOf("AA"), rings, -29.955, 140.0, 20f))
    // a 600 m cell fix 5 km away: stays inside (cannot tell 1 km)
    assertEquals(setOf("AA"), FolloRings.afterFix(setOf("AA"), rings, -29.955, 140.0, 600f))
    // and a 600 m fix inside does not put it in either
    assertEquals(emptySet<String>(), FolloRings.afterFix(emptySet(), rings, -30.0, 140.0, 600f))
    // exactly 500 m still counts
    assertEquals(setOf("AA"), FolloRings.afterFix(emptySet(), rings, -30.0, 140.0, 500f))
  }

  @Test
  fun `areas no longer held are forgotten`() {
    val rings = FolloRings.rings(listOf(a1), 1000.0)
    assertEquals(setOf("AA"), FolloRings.keepHeld(setOf("AA", "ZZ"), rings))
    assertEquals(setOf("AA"), FolloRings.afterFix(setOf("AA", "ZZ"), rings, 0.0, 0.0, 900f))
  }

  @Test
  fun `OS ring events add and remove an area`() {
    assertEquals(setOf("AA", "BB"), FolloRings.afterEvent(setOf("AA"), "BB", entered = true))
    assertEquals(setOf("BB"), FolloRings.afterEvent(setOf("AA", "BB"), "AA", entered = false))
  }

  @Test
  fun `the watch runs only for an area whose window is open AND whose ring the phone is in`() {
    // decision table: ring in/out x window open/closed
    assertEquals(setOf("AA"), FolloRings.watchAreas(setOf("AA"), setOf("AA")))
    assertEquals(emptySet<String>(), FolloRings.watchAreas(setOf("AA"), emptySet()))      // in ring, window closed
    assertEquals(emptySet<String>(), FolloRings.watchAreas(emptySet(), setOf("AA")))      // window open, 50 km away
    assertEquals(emptySet<String>(), FolloRings.watchAreas(setOf("BB"), setOf("AA")))     // in another area's ring
    assertEquals(setOf("Basecamp"), FolloRings.watchAreas(setOf("Basecamp"), setOf("AA", "Basecamp")))
  }

  @Test
  fun `window opens while inside the ring starts the watch on the next evaluation`() {
    val inside = setOf("AA")
    assertTrue(FolloRings.watchAreas(inside, emptySet()).isEmpty()) // 05:59
    assertEquals(setOf("AA"), FolloRings.watchAreas(inside, setOf("AA"))) // 06:00: ring state kept, window now open
  }

  @Test
  fun `geofences, every ring first, then only the circles of areas the phone is in`() {
    val rings = FolloRings.rings(listOf(a1, a2, b1), 1000.0)
    val (kept, idx) = FolloRings.plan(rings, setOf("AA"), listOf("AA", "AA", "BB"))
    assertEquals(2, kept.size)
    assertEquals(listOf(0, 1), idx)
    val (_, none) = FolloRings.plan(rings, emptySet(), listOf("AA", "AA", "BB"))
    assertEquals(emptyList<Int>(), none)
  }

  @Test
  fun `more rings than fit, the nearest win`() {
    val far = FolloRules.Circle(-40.0, 145.0, 100.0, "FF")
    val rings = FolloRings.rings(listOf(a1, b1, far), 1000.0)
    val (kept, _) = FolloRings.plan(rings, emptySet(), emptyList(), nearLat = -39.9, nearLon = 145.0, max = 1)
    assertEquals(listOf("FF"), kept.map { it.area })
  }

  @Test
  fun `ring ids round-trip and waypoint ids are not rings`() {
    assertEquals("AA", FolloRings.areaOfId(FolloRings.ringId("AA")))
    assertEquals(null, FolloRings.areaOfId("12"))
  }
}
