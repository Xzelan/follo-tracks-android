package org.owntracks.android.ui.main

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simple screen's pure rules: the shared vectors of SIMPLE_MODE_SPEC_20261119.md §9 (the same on iOS). */
class FolloMenuTest {
  // ── §9.1 the two-way symbol
  @Test
  fun `link colour`() {
    assertEquals(FolloMenu.Link.GREEN, FolloMenu.link(null, answered = true))
    assertEquals(FolloMenu.Link.GREEN, FolloMenu.link(59_000, answered = true))
    assertEquals(FolloMenu.Link.ORANGE, FolloMenu.link(60_000, answered = true))
    assertEquals(FolloMenu.Link.ORANGE, FolloMenu.link(299_000, answered = true))
    assertEquals(FolloMenu.Link.RED, FolloMenu.link(300_000, answered = true))
    assertEquals(FolloMenu.Link.ORANGE, FolloMenu.link(null, answered = false))
    assertEquals(FolloMenu.Link.RED, FolloMenu.link(400_000, answered = false))
  }

  @Test
  fun `waiting is counted from the later of queued and answered`() {
    assertNull(FolloMenu.waitingMs(null, 1_000, 500_000))
    // queued at 100 s, last answer at 20 s, now 170 s: 70 s waiting
    assertEquals(70_000L, FolloMenu.waitingMs(100_000, 20_000, 170_000))
    // a queue that keeps being answered (Move): queued long ago, answered 3 s ago
    assertEquals(3_000L, FolloMenu.waitingMs(100_000, 497_000, 500_000))
    assertEquals(0L, FolloMenu.waitingMs(100_000, 600_000, 500_000))
  }

  // ── §9.2 the column
  @Test
  fun `eleven buttons in one column when it fits`() {
    val slots = FolloMenu.layout(7, 4, 600f)
    assertEquals(listOf(11), FolloMenu.columns(slots))
    assertEquals(0f, slots[0].y, 0.001f)
    assertEquals(300f, slots[6].y, 0.001f) // the seventh layer
    assertEquals(360f, slots[7].y, 0.001f) // a half-button gap (20, not 10) before the functions
    assertEquals(510f, slots[10].y, 0.001f) // its bottom = 550 (the spec's sum says 570; the parts add to 550)
    assertEquals(listOf(11), FolloMenu.columns(FolloMenu.layout(7, 4, 550f)))
  }

  @Test
  fun `eleven buttons break into eight and three`() {
    val slots = FolloMenu.layout(7, 4, 400f)
    assertEquals(listOf(8, 3), FolloMenu.columns(slots))
    assertEquals(350f, slots[7].y, 0.001f) // 8 x 40 + 7 x 10 = 390: no wider gap across the break
    assertEquals(FolloMenu.Slot(1, 0f), slots[8])
    assertEquals(FolloMenu.Slot(1, 100f), slots[10])
  }

  @Test
  fun `three layers then the gap then four`() {
    val slots = FolloMenu.layout(3, 4, 600f)
    assertEquals(listOf(7), FolloMenu.columns(slots))
    assertEquals(100f, slots[2].y, 0.001f)
    assertEquals(160f, slots[3].y, 0.001f)
  }

  @Test
  fun `column odd cases`() {
    assertTrue(FolloMenu.layout(0, 0, 500f).isEmpty())
    // functions alone: no group gap
    assertEquals(listOf(0f, 50f), FolloMenu.layout(0, 2, 500f).map { it.y })
    // hardly any room: one a column
    assertEquals(listOf(1, 1, 1), FolloMenu.columns(FolloMenu.layout(1, 2, 10f)))
    // sideways on a small phone: fours
    assertEquals(listOf(4, 4, 3), FolloMenu.columns(FolloMenu.layout(7, 4, 226f)))
  }

  // ── §9.4 the buttons listed
  @Test
  fun `layer buttons for the area in view`() {
    val lay = mapOf("FG" to setOf("hs", "ct"))
    assertEquals(
        listOf(FolloMenu.Layer.GRIDS, FolloMenu.Layer.HILLSHADE, FolloMenu.Layer.CONTOURS),
        FolloMenu.layerButtons(lay, gridLines = true, gridNames = false, area = "FG"),
    )
    assertTrue(FolloMenu.layerButtons(lay, gridLines = false, gridNames = false, area = "MV").isEmpty())
    assertTrue(FolloMenu.layerButtons(lay, gridLines = false, gridNames = false, area = null).isEmpty())
    assertEquals(
        FolloMenu.Layer.entries.toList(),
        FolloMenu.layerButtons(mapOf("FG" to setOf("tl", "hs", "sl", "ct", "poi")), true, true, "FG"),
    )
  }

  // ── §9.5 mobile data
  @Test
  fun `metered rule`() {
    assertTrue(FolloMenu.mayFetch(raster = true, metered = true, kb = 3000))
    assertFalse(FolloMenu.mayFetch(raster = true, metered = true, kb = 3001))
    assertTrue(FolloMenu.mayFetch(raster = true, metered = false, kb = 3001))
    assertTrue(FolloMenu.mayFetch(raster = true, metered = true, kb = 30_000, userAllowed = true))
    assertTrue(FolloMenu.mayFetch(raster = false, metered = true, kb = 30_000)) // contours, POI, tracklogs: any network
    assertEquals(12, FolloMenu.megabytes(12_000))
    assertEquals(1, FolloMenu.megabytes(10))
  }

  // ── §5 "When" (added 19 Nov 2026): what is fetched, and in which order
  @Test
  fun `layers switched on for the area in view are fetched whatever the distance`() {
    val listed = mapOf("FG" to mapOf("hs" to "h2", "sl" to "s1", "ct" to "c1"), "DW" to mapOf("hs" to "d1", "poi" to "p1"))
    // far from FG (not among the near areas): only what is switched on there, in the order given
    assertEquals(listOf("FG/sl", "FG/hs"), FolloMenu.fetchOrder(listed, emptyMap(), emptyList(), listOf("FG/sl", "FG/hs")))
    // nothing switched on, nothing near: nothing
    assertTrue(FolloMenu.fetchOrder(listed, emptyMap(), emptyList(), emptyList()).isEmpty())
    // near DW: its layers in the buttons' order, after what is switched on for the area in view
    assertEquals(listOf("FG/ct", "DW/hs", "DW/poi"), FolloMenu.fetchOrder(listed, emptyMap(), listOf("DW"), listOf("FG/ct")))
    // held at the listed version: not fetched; held at an older version: fetched again
    assertEquals(listOf("FG/hs"), FolloMenu.fetchOrder(listed, mapOf("FG/hs" to "h1", "FG/sl" to "s1"), emptyList(), listOf("FG/hs", "FG/sl")))
    // not listed by the server (another area, a layer this person may not see, grids, rubbish): never
    assertTrue(FolloMenu.fetchOrder(listed, emptyMap(), listOf("MV"), listOf("MV/hs", "FG/tl", "FG/grids", "FG", "/hs")).isEmpty())
    // wanted and near at once: once
    assertEquals(listOf("DW/poi", "DW/hs"), FolloMenu.fetchOrder(listed, emptyMap(), listOf("DW"), listOf("DW/poi")))
  }

  // ── §9.3 the pack reader
  private fun packBytes(magic: String, tiles: List<Pair<IntArray, ByteArray>>): ByteArray {
    val head = 8 + 20 * tiles.size
    val b = ByteBuffer.allocate(head + tiles.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
    b.put(magic.toByteArray(Charsets.US_ASCII))
    b.putInt(tiles.size)
    var offset = head
    for ((zxy, body) in tiles) {
      b.putInt(zxy[0]).putInt(zxy[1]).putInt(zxy[2]).putInt(offset).putInt(body.size)
      offset += body.size
    }
    for ((_, body) in tiles) b.put(body)
    return b.array()
  }

  @Test
  fun `pack reader`() {
    val a = byteArrayOf(1, 2, 3, 4, 5)
    val c = byteArrayOf(9, 8, 7)
    val f = File.createTempFile("follo", ".pack")
    try {
      f.writeBytes(packBytes("FTP1", listOf(intArrayOf(13, 7400, 5100) to a, intArrayOf(14, 14801, 10201) to c)))
      val pack = FolloPack.open(f)
      assertNotNull(pack)
      pack!!
      assertEquals(2, pack.count)
      assertEquals(13, pack.minZ)
      assertEquals(14, pack.maxZ)
      assertArrayEquals(a, pack.tile(13, 7400, 5100))
      assertArrayEquals(c, pack.tile(14, 14801, 10201))
      assertNull(pack.tile(14, 14801, 10202))
      assertTrue(pack.has(13, 7400, 5100))
      assertFalse(pack.has(13, 7401, 5100))
      pack.close()
      // not a pack
      f.writeBytes(packBytes("FTP2", listOf(intArrayOf(13, 7400, 5100) to a)))
      assertNull(FolloPack.open(f))
      f.writeBytes(byteArrayOf(70, 84))
      assertNull(FolloPack.open(f))
      // an index that says more rows than the file has
      f.writeBytes(packBytes("FTP1", listOf(intArrayOf(13, 7400, 5100) to a)).copyOf(20))
      assertNull(FolloPack.open(f))
    } finally {
      f.delete()
    }
  }

  // ── the list and the bodies
  @Test
  fun `layer list`() {
    val l =
        FolloLay.parseList(
            """{"v":"ab12","lay":{"FG":{"hs":{"v":"9c1e22aa","kb":6100},"ct":{"v":"77","kb":210},"bad":{"kb":1}},"../x":{"hs":{"v":"1","kb":1}}},"an":{"FG":"Search Three","MV":"Search One"}}""")
    assertNotNull(l)
    l!!
    assertEquals("ab12", l.v)
    assertEquals(setOf("FG"), l.lay.keys)
    assertEquals(FolloLay.Entry("9c1e22aa", 6100), l.lay["FG"]?.get("hs"))
    assertEquals(setOf("hs", "ct"), l.listed["FG"])
    assertEquals("Search Three", l.names["FG"])
    assertNull(FolloLay.parseList("""{"lay":{}}"""))
    assertNull(FolloLay.parseList("not json"))
    assertTrue(FolloLay.parseList("""{"v":"0"}""")!!.lay.isEmpty())
  }

  @Test
  fun `layer bodies`() {
    val ct = FolloLay.parseContours("""{"v":"1","int":10,"l":[[150,-41.1,146.8,-41.2,146.9],[160,-41.1,146.8],[170,-41.1,146.8,-99,146.9]]}""")
    assertEquals(1, ct!!.size)
    assertEquals(150.0, ct[0].elev, 0.0)
    assertArrayEquals(doubleArrayOf(-41.1, 146.8, -41.2, 146.9), ct[0].line, 0.0)
    val tl = FolloLay.parseLines("""{"v":"1","l":[[-41.1,146.8,-41.2,146.9,-41.3,147.0],[1,2,3]]}""")
    assertEquals(1, tl!!.size)
    assertEquals(6, tl[0].size)
    val poi = FolloLay.parsePois("""{"v":"1","p":[[-41.1,146.8,"Car park","access"],[-41.2,146.9],["x"]]}""")
    assertEquals(2, poi!!.size)
    assertEquals("Car park", poi[0].name)
    assertEquals("", poi[1].name)
    assertNull(FolloLay.parsePois("""{"v":"1"}"""))
    assertTrue(FolloLay.isIndexContour(150.0))
    assertTrue(FolloLay.isIndexContour(0.0))
    assertFalse(FolloLay.isIndexContour(160.0))
    assertFalse(FolloLay.isIndexContour(150.5))
  }
}
