package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.MapTileIndex

class FolloOfflineTest {
  // George Town TAS, the MV search
  private val lat = -41.1060
  private val lon = 146.8260

  @Test
  fun `tile numbers match the web mercator scheme`() {
    // worked out separately (Python, the standard slippy-map formula): z15 at George Town
    assertEquals(29748, FolloOffline.tileX(lon, 15))
    assertEquals(20495, FolloOffline.tileY(lat, 15))
    // a tile's own corner falls in that tile; its edges bracket the point
    val z = 19
    val x = FolloOffline.tileX(lon, z)
    val y = FolloOffline.tileY(lat, z)
    assertTrue(FolloOffline.tileLon(x, z) <= lon && lon < FolloOffline.tileLon(x + 1, z))
    assertTrue(FolloOffline.tileLat(y + 1, z) < lat && lat <= FolloOffline.tileLat(y, z))
  }

  @Test
  fun `a tile touches a circle when its nearest point is inside the reach`() {
    val z = 19 // a tile is ~57 m wide here
    val x = FolloOffline.tileX(lon, z)
    val y = FolloOffline.tileY(lat, z)
    assertTrue(FolloOffline.tileTouches(x, y, z, lat, lon, 1.0)) // the tile holding the centre
    assertTrue(FolloOffline.tileTouches(x + 1, y, z, lat, lon, 60.0)) // the next one east is at most a tile away
    assertFalse(FolloOffline.tileTouches(x + 3, y, z, lat, lon, 60.0)) // two whole tiles between: over 100 m
    assertFalse(FolloOffline.tileTouches(x + 2, y + 2, z, lat, lon, 60.0)) // the corner case: diagonal is further
  }

  @Test
  fun `the tiles of a circle cover it and little else`() {
    val c = FolloRules.Circle(lat, lon, 500.0, "MV")
    val tiles = ArrayList<Long>().also { FolloOffline.tilesOfCircle(c, 17, it) }
    assertEquals(tiles.size, tiles.toSet().size)
    // every point of the circle's edge lies in a listed tile
    val set = tiles.toSet()
    for (k in 0 until 360) {
      val t = Math.toRadians(k.toDouble())
      val pLat = lat + 500.0 * Math.sin(t) / 110540.0
      val pLon = lon + 500.0 * Math.cos(t) / (111320.0 * Math.cos(Math.toRadians(lat)))
      assertTrue(MapTileIndex.getTileIndex(17, FolloOffline.tileX(pLon, 17), FolloOffline.tileY(pLat, 17)) in set)
    }
    // a z17 tile is ~230 m here: the 530 m reach is ~21 tiles of area, the square round it 36
    assertTrue("${tiles.size} tiles", tiles.size in 21..36)
    // one level deeper is about four times as many
    val deeper = ArrayList<Long>().also { FolloOffline.tilesOfCircle(c, 18, it) }
    assertTrue("${deeper.size} vs ${tiles.size}", deeper.size > 2.5 * tiles.size && deeper.size < 4 * tiles.size)
  }

  private val settings = FolloOffline.Settings(mapOf("MV" to 19, "FG" to 19, "DW" to 19), 300.0, 20000, 2500)
  private val mv = listOf(FolloRules.Circle(lat, lon, 400.0, "MV"), FolloRules.Circle(lat + 0.004, lon, 400.0, "MV"))
  private val fg = listOf(FolloRules.Circle(-41.654969, 146.716518, 750.0, "FG"))
  private val dw = listOf(FolloRules.Circle(-27.63, 153.09, 2254.0, "DW")) // Brisbane: over 300 km from Tasmania
  private val basecamp = listOf(FolloRules.Circle(lat, lon + 0.01, 100.0, "Basecamp"))

  @Test
  fun `the plan is the named areas near the phone, nearest first, lower zooms first`() {
    // phone in Launceston: MV ~40 km, FG ~40 km, DW far
    val plan = FolloOffline.plan(mv + fg + dw + basecamp, settings, -41.4391, 147.1358)
    assertTrue(plan.isNotEmpty())
    assertEquals(plan.size, plan.toSet().size)
    val zooms = plan.map { MapTileIndex.getZoom(it) }
    assertEquals(10, zooms.min())
    assertEquals(19, zooms.max())
    // none in Queensland, none for the Basecamp circle (not an area the server named)
    val alone = FolloOffline.plan(mv + fg, settings, -41.4391, 147.1358)
    assertEquals(alone, plan)
    // one area after the other, each from zoom 10 up
    val firstTop = zooms.indexOf(19)
    val restart = zooms.drop(firstTop).indexOfFirst { it < 19 }
    assertTrue(restart > 0)
    assertEquals(zooms.take(firstTop + restart), zooms.take(firstTop + restart).sorted())
    assertEquals(zooms.drop(firstTop + restart), zooms.drop(firstTop + restart).sorted())
    // overlapping circles of one area share tiles: fewer than the two counted apart
    val one = FolloOffline.plan(mv.take(1), settings, lat, lon).size
    val two = FolloOffline.plan(mv, settings, lat, lon).size
    assertTrue(two in (one + 1) until 2 * one)
  }

  @Test
  fun `no position, a far phone, the cap and the imagery's depth all limit the plan`() {
    assertTrue(FolloOffline.plan(mv, settings, null, null).isEmpty())
    assertTrue(FolloOffline.plan(mv, settings, -27.47, 153.02).isEmpty()) // Brisbane phone, Tasmanian area
    assertEquals(50, FolloOffline.plan(mv + fg, settings.copy(cap = 50), lat, lon).size)
    // zoom 20 asked for an area whose imagery (LIST) stops at 19: no zoom 20 tile (it would be a blank picture)
    val deep = FolloOffline.plan(mv, settings.copy(z = mapOf("MV" to 20)), lat, lon)
    assertEquals(19, deep.maxOf { MapTileIndex.getZoom(it) })
    assertEquals(FolloOffline.plan(mv, settings, lat, lon), deep)
    // Queensland's imagery has zoom 20
    val qld = FolloOffline.plan(dw, FolloOffline.Settings(mapOf("DW" to 20), 300.0, 100000, 0), -27.47, 153.02)
    assertEquals(20, qld.maxOf { MapTileIndex.getZoom(it) })
    // an area the server gives zoom 0 (or leaves out) saves nothing
    assertTrue(FolloOffline.plan(mv, settings.copy(z = mapOf("MV" to 0)), lat, lon).isEmpty())
  }

  @Test
  fun `megabytes, percent and the metered rule`() {
    assertEquals(140, FolloOffline.megabytes(13000)) // 13,000 tiles × 11 KB
    assertEquals(1, FolloOffline.megabytes(3))
    assertEquals(100, FolloOffline.percent(1000, 0))
    assertEquals(99, FolloOffline.percent(1000, 1)) // never "100%" with a tile missing
    assertEquals(45, FolloOffline.percent(1000, 550))
    assertEquals(0, FolloOffline.percent(1000, 1000))
    assertTrue(FolloOffline.mayFetch(metered = false, missing = 20000, free = 2500, userAllowed = false))
    assertTrue(FolloOffline.mayFetch(metered = true, missing = 2500, free = 2500, userAllowed = false))
    assertFalse(FolloOffline.mayFetch(metered = true, missing = 2501, free = 2500, userAllowed = false))
    assertTrue(FolloOffline.mayFetch(metered = true, missing = 20000, free = 2500, userAllowed = true))
  }
}
