package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageFollo

class FolloDrawTest {
  private val body =
      """{"v":"3fa91c0e","items":[
        {"id":12,"k":"circle","p":[-41.05,146.8],"r":150,"t":"Area of interest"},
        {"id":13,"k":"line","p":[-41.05,146.8,-41.051,146.802,-41.052,146.801],"c":"#FF3B30","w":4,"d":1,"t":"Follow the fence"},
        {"id":14,"k":"poly","p":[-41.0,146.0,-41.0,146.2,-41.2,146.2,-41.2,146.0],"f":0.15},
        {"id":15,"k":"pin","p":[-41.06,146.81],"t":"Gate"},
        {"id":16,"k":"text","p":[-41.07,146.82],"t":"  Creek crossing  "},
        {"id":17,"k":"spline","p":[-41.07,146.82,-41.08,146.83]},
        {"id":18,"k":"line","p":[-41.07,146.82]},
        {"id":19,"k":"poly","p":[-41.07,146.82,-41.08,146.83]},
        {"id":20,"k":"circle","p":[-41.07,146.82]},
        {"id":21,"k":"line","p":[-41.07,146.82,-91.0,146.83]},
        {"id":22,"k":"line","p":[-41.07,146.82,-41.08]},
        {"id":23,"k":"text","p":[-41.07,146.82]},
        {"id":24,"k":"pin","p":["a",146.82]},
        "rubbish",
        {"k":"pin","p":[-41.09,146.84]}
      ]}"""

  @Test
  fun `a draw reply is read, bad and unknown items skipped`() {
    val d = FolloDraw.parse(body)
    assertNotNull(d)
    assertEquals("3fa91c0e", d!!.v)
    assertEquals(listOf(12L, 13L, 14L, 15L, 16L, -15L), d.items.map { it.id }) // the last has no id: one from its place
    val (circle, line, poly, pin, text) = d.items

    assertEquals(FolloDraw.Kind.CIRCLE, circle.kind)
    assertEquals(150.0, circle.radius, 0.0)
    assertEquals(FolloDraw.DEFAULT_COLOR, circle.color)
    assertEquals(FolloDraw.DEFAULT_WIDTH, circle.width, 0f)
    assertEquals(0f, circle.fill, 0f)
    assertFalse(circle.dashed)
    assertEquals("Area of interest", circle.text)
    assertEquals(72 * 2, circle.outline.size)
    assertEquals(-41.05, circle.labelLat, 0.0) // a circle's label sits on its centre
    assertEquals(146.8, circle.labelLon, 0.0)
    assertTrue(circle.north > -41.05 && circle.south < -41.05 && circle.east > 146.8 && circle.west < 146.8)

    assertEquals(FolloDraw.Kind.LINE, line.kind)
    assertEquals(0xFFFF3B30.toInt(), line.color)
    assertEquals(4f, line.width, 0f)
    assertTrue(line.dashed)
    assertEquals(6, line.outline.size)
    assertEquals(-41.052, line.south, 0.0)
    assertEquals(146.802, line.east, 0.0)

    assertEquals(FolloDraw.Kind.POLY, poly.kind)
    assertEquals(0.15f, poly.fill, 1e-6f)
    assertNull(poly.text)
    assertEquals(-41.1, poly.labelLat, 1e-9) // the average of its corners
    assertEquals(146.1, poly.labelLon, 1e-9)

    assertEquals(FolloDraw.Kind.PIN, pin.kind)
    assertEquals(0, pin.outline.size)
    assertEquals("Gate", pin.text)
    assertEquals(-41.06, pin.pts[0], 0.0)

    assertEquals(FolloDraw.Kind.TEXT, text.kind)
    assertEquals("Creek crossing", text.text)
  }

  @Test
  fun `no drawings and rubbish`() {
    val none = FolloDraw.parse("""{"v":"0","items":[]}""")
    assertEquals(FolloDraw.NONE, none!!.v)
    assertTrue(none.items.isEmpty())
    assertTrue(FolloDraw.parse("""{"v":"abc"}""")!!.items.isEmpty())
    assertNull(FolloDraw.parse("<html>"))
    assertNull(FolloDraw.parse("""{"items":[]}""")) // no version: not a draw reply
    assertNull(FolloDraw.parse("[]"))
  }

  @Test
  fun `style values are kept within reason`() {
    val d =
        FolloDraw.parse(
            """{"v":"x","items":[
              {"id":1,"k":"line","p":[-41,146,-41.1,146.1],"w":500,"f":0.5,"c":"red","d":true},
              {"id":2,"k":"circle","p":[-41,146,-42,147],"r":20,"f":7,"w":0},
              {"id":3,"k":"pin","p":[-41,146],"t":"${"x".repeat(80)}"}]}""",
        )!!
    assertEquals(12f, d.items[0].width, 0f)
    assertEquals(0f, d.items[0].fill, 0f) // a line has no fill
    assertEquals(FolloDraw.DEFAULT_COLOR, d.items[0].color)
    assertTrue(d.items[0].dashed)
    assertEquals(1f, d.items[1].fill, 0f)
    assertEquals(1f, d.items[1].width, 0f)
    assertEquals(2, d.items[1].pts.size) // a circle is one point, whatever else was sent
    assertEquals(FolloDraw.LABEL_MAX, d.items[2].text!!.length)
  }

  // 13 Nov 2026: widths in metres on the ground, and background lines without the white casing
  @Test
  fun `a width in metres and no casing are read, and left alone when absent or rubbish`() {
    val d =
        FolloDraw.parse(
                """{"v":"x","items":[
                  {"id":1,"k":"line","p":[-41,146,-41.1,146.1],"w":2,"wm":1,"nc":1},
                  {"id":2,"k":"line","p":[-41,146,-41.1,146.1]},
                  {"id":3,"k":"line","p":[-41,146,-41.1,146.1],"wm":5000,"nc":0},
                  {"id":4,"k":"line","p":[-41,146,-41.1,146.1],"wm":-2,"nc":true},
                  {"id":5,"k":"poly","p":[-41,146,-41.1,146.1,-41.2,146],"wm":0.01},
                  {"id":6,"k":"line","p":[-41,146,-41.1,146.1],"wm":"wide"}
                ]}""")!!
            .items
    assertEquals(6, d.size)
    assertEquals(1f, d[0].metres, 0f)
    assertFalse(d[0].casing)
    assertEquals("the dp width stays for builds that do not know metres", 2f, d[0].width, 0f)
    assertEquals(0f, d[1].metres, 0f)
    assertTrue(d[1].casing)
    assertEquals("no wider than 100 m", 100f, d[2].metres, 0f)
    assertTrue(d[2].casing)
    assertEquals("a width that is not one means the dp width", 0f, d[3].metres, 0f)
    assertFalse(d[3].casing)
    assertEquals("no thinner than 0.1 m", 0.1f, d[4].metres, 0f)
    assertEquals(0f, d[5].metres, 0f)
  }

  @Test
  fun `a ground width follows the zoom and never goes under the hairline`() {
    assertEquals(12f, FolloDrawOverlay.groundPx(2f, 6f, 2f), 0.0001f) // 2 m at 6 px a metre
    assertEquals(1f, FolloDrawOverlay.groundPx(1f, 0.2f, 2f), 0.0001f) // 0.2 px would vanish: half a dp instead
    assertEquals(1f, FolloDrawOverlay.groundPx(1f, Float.NaN, 2f), 0.0001f)
  }

  @Test
  fun `a circle's ring is the ground circle`() {
    for ((lat, lon, r) in listOf(Triple(-41.05, 146.8, 150.0), Triple(-27.6, 153.1, 2500.0), Triple(-43.1, 147.9, 25.0))) {
      val ring = FolloDraw.circleRing(lat, lon, r)
      assertEquals(FolloDraw.circlePoints(r) * 2, ring.size)
      for (i in 0 until ring.size / 2) assertEquals(r, FolloDraw.metres(lat, lon, ring[2 * i], ring[2 * i + 1]), r * 1e-6 + 1e-4)
      // starts due north of the centre and goes clockwise (east next)
      assertEquals(lon, ring[1], 1e-9)
      assertTrue(ring[0] > lat)
      assertTrue(ring[3] > lon)
    }
    // about one corner every 8 m of radius, 72 to 720
    assertEquals(listOf(72, 72, 312, 720), listOf(25.0, 576.0, 2500.0, 50000.0).map(FolloDraw::circlePoints))
    // a circle wider than 500 km is not drawn; one of exactly 500 km is
    assertTrue(FolloDraw.parse("""{"v":"x","items":[{"id":1,"k":"circle","p":[-41,146],"r":500001}]}""")!!.items.isEmpty())
    assertEquals(1, FolloDraw.parse("""{"v":"x","items":[{"id":1,"k":"circle","p":[-41,146],"r":500000}]}""")!!.items.size)
    // one degree of latitude is about 111.2 km on this sphere
    assertEquals(111195.0, FolloDraw.metres(-41.0, 146.0, -42.0, 146.0), 5.0)
  }

  @Test
  fun `a line's label sits half way along its length`() {
    // two legs, the second three times the first: half way is a third of the way along the second
    val (la, lo) = FolloDraw.midPoint(doubleArrayOf(-41.0, 146.0, -41.01, 146.0, -41.04, 146.0))
    assertEquals(-41.02, la, 1e-6)
    assertEquals(146.0, lo, 1e-9)
    // a straight two-point line: its middle
    val (la2, lo2) = FolloDraw.midPoint(doubleArrayOf(-41.0, 146.0, -41.0, 146.2))
    assertEquals(-41.0, la2, 1e-9)
    assertEquals(146.1, lo2, 1e-6)
    // all points the same: that point
    assertEquals(-41.0 to 146.0, FolloDraw.midPoint(doubleArrayOf(-41.0, 146.0, -41.0, 146.0)))
    assertEquals(-41.1 to 146.1, FolloDraw.centroid(doubleArrayOf(-41.0, 146.0, -41.2, 146.2)).let { (a, b) -> Math.round(a * 1e6) / 1e6 to Math.round(b * 1e6) / 1e6 })
  }

  @Test
  fun `colours`() {
    assertEquals(0xFFFF3B30.toInt(), FolloDraw.color("#FF3B30"))
    assertEquals(0xFF27774A.toInt(), FolloDraw.color("27774a"))
    assertEquals(FolloDraw.DEFAULT_COLOR, FolloDraw.color("red"))
    assertEquals(FolloDraw.DEFAULT_COLOR, FolloDraw.color(null))
    assertEquals(0xFFFFD60A.toInt(), FolloDraw.DEFAULT_COLOR)
  }

  @Test
  fun `the status element carries drawv and survives being saved`() {
    val parser = Parser()
    val m = parser.fromJson("""{"_type":"follo","state":"outside","gridv":"0","drawv":"3fa91c0e"}""") as MessageFollo
    assertEquals("3fa91c0e", m.drawv)
    val again = parser.fromJson(parser.toJson(m)) as MessageFollo // FolloStatusRepo persists it this way
    assertEquals("3fa91c0e", again.drawv)
    // an older server, or one that could not tell just now: the phone keeps what it has
    assertNull((parser.fromJson("""{"_type":"follo","state":"nosearch"}""") as MessageFollo).drawv)
  }
}
