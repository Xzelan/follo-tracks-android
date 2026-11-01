package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.messages.MessageConfiguration
import org.owntracks.android.preferences.types.MonitoringMode

class FolloRulesTest {
  @Test
  fun `a setup link only sets the settings FOLLO needs`() {
    val link =
        MessageConfiguration().apply {
          this["url"] = "https://follo-tracks.pages.dev/pub"
          this["username"] = "u"
          this["password"] = "p"
          this["mode"] = 3
          this["tid"] = "AB"
          this["folloName"] = "Alex"
          this["mapTileUrl"] = "http://evil.example/{z}/{x}/{y}"
          this["autostartOnBoot"] = false
          this["osmTileScaleFactor"] = 9.0
        }
    val clean = FolloRules.importable(link)
    assertEquals(setOf("url", "username", "password", "mode", "tid", "folloName"), clean.keys)
    assertEquals("https://follo-tracks.pages.dev/pub", clean["url"])
    assertFalse(clean.containsKey("mapTileUrl"))
    assertFalse(clean.containsKey("autostartOnBoot"))
  }

  @Test
  fun `a teammate arriving over HTTP keeps the server's topic as their contact id`() {
    assertEquals("owntracks/follo-r/phone", FolloRules.incomingTopic("owntracks/follo-r/phone", "R"))
    assertEquals("owntracks/http/R", FolloRules.incomingTopic("", "R"))
    assertEquals("owntracks/http/", FolloRules.incomingTopic("", null))
  }

  @Test
  fun `a leader is shown for ten minutes after their last fix, then not`() {
    val now = 1_792_600_000L
    assertTrue(FolloRules.leaderFresh(now, now))
    assertTrue(FolloRules.leaderFresh(now - 10 * 60, now))
    assertFalse(FolloRules.leaderFresh(now - 10 * 60 - 1, now))
    assertFalse(FolloRules.leaderFresh(0, now))
  }

  // ── Edit Circles (1 Nov 2026): wake circle names with and without the date word ──────────

  @Test
  fun `the area of a wake circle is word 2, with or without the date word`() {
    assertEquals("MV", FolloRules.wakeArea("FOLLO MV wake 3"))
    assertEquals("MV", FolloRules.wakeArea("FOLLO MV wake 3 2026-11-01"))
    assertEquals("MV", FolloRules.wakeArea("FOLLO MV wake 3 2026-11-01|2|2"))
    assertEquals("Basecamp", FolloRules.wakeArea("FOLLO Basecamp wake 1"))
    assertNull(FolloRules.wakeArea("FOLLO MV"))
    assertNull(FolloRules.wakeArea("Marker 3"))
    assertNull(FolloRules.wakeArea(""))
  }

  @Test
  fun `the date word is read only when it is a yyyy-mm-dd date`() {
    assertEquals("2026-11-01", FolloRules.wakeDate("FOLLO MV wake 3 2026-11-01"))
    assertNull(FolloRules.wakeDate("FOLLO MV wake 3"))
    assertNull(FolloRules.wakeDate("FOLLO MV wake 3 2026-11-01|2|2"))
    assertNull(FolloRules.wakeDate("FOLLO MV wake 3 soon"))
    assertNull(FolloRules.wakeDate("Marker 3 2026-11-01"))
  }

  @Test
  fun `the Ready line's update date is the newest held date, shown without leading zeros`() {
    assertEquals("1-11-2026", FolloRules.readyUpdate(listOf("FOLLO MV wake 1 2026-11-01", "FOLLO MV wake 2 2026-11-01")))
    assertEquals("12-10-2026", FolloRules.readyUpdate(listOf("FOLLO MV wake 1 2026-10-12", "FOLLO MV wake 2 2026-10-03")))
    // several areas: the newest wins across all of them
    assertEquals(
        "3-11-2026",
        FolloRules.readyUpdate(listOf("FOLLO CB wake 1 2026-10-28", "FOLLO MV wake 1 2026-11-03", "FOLLO MV wake 2 2026-11-03", "FOLLO Basecamp wake 1")),
    )
    // one dated area among undated ones still gives a date
    assertEquals("5-1-2027", FolloRules.readyUpdate(listOf("FOLLO CB wake 1", "FOLLO MV wake 1 2027-01-05")))
  }

  @Test
  fun `no date word anywhere means no update suffix`() {
    assertNull(FolloRules.readyUpdate(listOf("FOLLO MV wake 1", "FOLLO CB wake 2", "FOLLO Basecamp wake 1")))
    assertNull(FolloRules.readyUpdate(emptyList()))
  }

  // ── "No fix on open" (Alex, 1 Nov 2026): the judgement ───────────────────────────────────

  private val house = -26.6289 to 153.0355 // Bli Bli
  private val circles =
      listOf(FolloRules.Circle(-26.628964, 153.035474, 158.0), FolloRules.Circle(-26.626543, 153.033677, 348.0))
  private val boundary = listOf(-26.40 to 153.09, -26.40 to 153.11, -26.41 to 153.11, -26.41 to 153.09) // Sunshine Beach box

  @Test
  fun `inside a held circle means the position is posted`() {
    assertTrue(FolloRules.insideSearch(house.first, house.second, circles, null))
    assertEquals(FolloRules.CheckInAction.POST_FIX, FolloRules.checkInAction(true, secondStep = false))
  }

  @Test
  fun `inside the boundary only, with no circle around, still counts as inside`() {
    assertTrue(FolloRules.insideSearch(-26.405, 153.10, circles, boundary))
    assertFalse(FolloRules.insideSearch(-26.405, 153.10, circles, null))
  }

  @Test
  fun `outside every circle and the boundary means a hello instead of the position`() {
    assertFalse(FolloRules.insideSearch(-26.50, 153.05, circles, boundary))
    assertEquals(FolloRules.CheckInAction.HELLO, FolloRules.checkInAction(false, secondStep = false))
  }

  @Test
  fun `nothing held at all means a hello`() {
    assertFalse(FolloRules.insideSearch(house.first, house.second, emptyList(), null))
    assertFalse(FolloRules.insideSearch(house.first, house.second, emptyList(), emptyList()))
    assertEquals(FolloRules.CheckInAction.HELLO, FolloRules.checkInAction(false, secondStep = false))
  }

  @Test
  fun `a point on the circle's edge counts as inside`() {
    val c = FolloRules.Circle(-26.628964, 153.035474, 150.0)
    val edgeLat = c.lat + 150.0 / 111_195.0 // 150 m north along the meridian
    assertTrue(FolloRules.insideCircle(edgeLat, c.lon, c))
    assertTrue(FolloRules.distanceM(c.lat, c.lon, edgeLat, c.lon) in 149.0..151.0)
    assertFalse(FolloRules.insideCircle(c.lat + 160.0 / 111_195.0, c.lon, c))
  }

  @Test
  fun `the second step posts only when the phone is inside the newly received circles or boundary`() {
    assertEquals(FolloRules.CheckInAction.POST_FIX, FolloRules.checkInAction(true, secondStep = true))
    assertEquals(FolloRules.CheckInAction.NOTHING, FolloRules.checkInAction(false, secondStep = true))
  }

  @Test
  fun `only FOLLO wake circles with a radius are judged, markers are not`() {
    assertTrue(FolloRules.isWakeCircle("FOLLO MV wake 3 2026-11-01", 150))
    assertTrue(FolloRules.isWakeCircle("FOLLO Basecamp wake 1", 200))
    assertFalse(FolloRules.isWakeCircle("FOLLO MV wake 3", 0))
    assertFalse(FolloRules.isWakeCircle("Marker: car", 50))
  }

  // Never a position from outside the search (1 Nov 2026)
  @Test
  fun `outside the search the phone stands by as the server would put it`() {
    assertEquals(MonitoringMode.Manual, FolloRules.standbyMode(MonitoringMode.Move, holdsCircles = true))
    assertEquals(MonitoringMode.Manual, FolloRules.standbyMode(MonitoringMode.Significant, holdsCircles = true))
    assertEquals(MonitoringMode.Significant, FolloRules.standbyMode(MonitoringMode.Move, holdsCircles = false))
    assertNull(FolloRules.standbyMode(MonitoringMode.Significant, holdsCircles = false))
    assertNull(FolloRules.standbyMode(MonitoringMode.Manual, holdsCircles = true))
    assertNull(FolloRules.standbyMode(MonitoringMode.Quiet, holdsCircles = true))
  }

  @Test
  fun `one hello at once for a dropped fix, then at most every 15 minutes`() {
    assertTrue(FolloRules.outHelloDue(null, 1_000L))
    assertFalse(FolloRules.outHelloDue(1_000L, 1_000L + 14 * 60_000L))
    assertTrue(FolloRules.outHelloDue(1_000L, 1_000L + 15 * 60_000L))
  }

  // Search windows (1 Nov 2026)
  private val mv = FolloRules.Circle(-41.1, 146.8, 300.0, "MV")
  private val cb = FolloRules.Circle(-40.8, 145.1, 300.0, "CB")
  private val bc = FolloRules.Circle(-41.12, 146.85, 100.0, "Basecamp")
  private val ring = listOf(-41.0 to 146.0, -41.0 to 147.0, -42.0 to 147.0)
  private val wins = listOf(FolloRules.Window("MV", 1_793_912_400, 1_793_944_800)) // 6 Nov 08:00-17:00 AEDT

  @Test
  fun `while a window is open only its area's circles, Basecamp and the boundary count`() {
    val (cs, b) = FolloRules.timedArea(listOf(mv, cb, bc), ring, null, wins, 1_793_920_000)
    assertEquals(listOf("MV", "Basecamp"), cs.map { it.area })
    assertEquals(ring, b)
  }

  @Test
  fun `outside the window nothing counts`() {
    for (t in listOf(1_793_912_399L, 1_793_944_800L, 1_794_000_000L)) {
      val (cs, b) = FolloRules.timedArea(listOf(mv, bc), ring, null, wins, t)
      assertTrue("$t", cs.isEmpty())
      assertNull("$t", b)
    }
  }

  @Test
  fun `no windows known means nothing counts, and an ended boundary is dropped`() {
    assertTrue(FolloRules.timedArea(listOf(mv, bc), ring, null, null, 1_793_920_000).first.isEmpty())
    assertNull(FolloRules.timedArea(listOf(mv), ring, 1_793_915_000, wins, 1_793_920_000).second)
  }

  @Test
  fun `windows are read from the status element`() {
    val m =
        org.owntracks.android.model.Parser()
            .fromJson(
                """{"_type":"follo","state":"nosearch","wins":[["MV",1793912400,1793944800],["bad"],["CB",1,2]]}""".toByteArray()
            ) as org.owntracks.android.model.messages.MessageFollo
    assertEquals(
        listOf(FolloRules.Window("MV", 1_793_912_400, 1_793_944_800), FolloRules.Window("CB", 1, 2)),
        m.windows(),
    )
  }
}
