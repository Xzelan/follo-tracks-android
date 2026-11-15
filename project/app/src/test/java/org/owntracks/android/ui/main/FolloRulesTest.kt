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
  fun `the circles' day on the bottom line has dots`() {
    assertEquals("9.11.2026", FolloRules.circlesDay(listOf("FOLLO MV wake 1 2026-11-09", "FOLLO MV wake 2 2026-11-01")))
    assertNull(FolloRules.circlesDay(listOf("FOLLO MV wake 1")))
  }

  @Test
  fun `the server's clock is shortened for the one-line banner`() {
    assertEquals("5pm", FolloRules.shortClock("5:00 pm"))
    assertEquals("5:30pm", FolloRules.shortClock("5:30 pm"))
    assertEquals("10am", FolloRules.shortClock("10:00 am"))
    assertEquals("", FolloRules.shortClock(null))
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
    val edgeLat = c.lat + 149.999 / 111_195.08 // a millimetre inside the 150 m edge, north along the meridian
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

  // The held map (13 Nov 2026)
  @Test
  fun `a drag, a pinch, a turn or a double tap holds the map until the card is tapped`() {
    assertTrue(FolloRules.holdsMap(dragged = true, pinched = false, turned = false, doubleTap = false))
    assertTrue(FolloRules.holdsMap(dragged = false, pinched = true, turned = false, doubleTap = false))
    assertTrue(FolloRules.holdsMap(dragged = false, pinched = false, turned = true, doubleTap = false))
    assertTrue(FolloRules.holdsMap(dragged = false, pinched = false, turned = false, doubleTap = true))
  }

  @Test
  fun `a plain tap on the map holds nothing`() {
    assertFalse(FolloRules.holdsMap(dragged = false, pinched = false, turned = false, doubleTap = false))
  }

  // Power button (13 Nov 2026)
  @Test
  fun `the power button is held two seconds and its ring fills over them`() {
    assertEquals(2_000L, FolloRules.POWER_HOLD_MS)
    assertEquals(0f, FolloRules.powerProgress(0), 0.0001f)
    assertEquals(0.5f, FolloRules.powerProgress(1_000), 0.0001f)
    assertEquals(1f, FolloRules.powerProgress(2_000), 0.0001f)
    assertEquals(1f, FolloRules.powerProgress(10_000), 0.0001f)
    assertEquals(0f, FolloRules.powerProgress(-5), 0.0001f)
  }

  @Test
  fun `one tap turns an Off app back on and a short press on a running app only shows the hint`() {
    assertEquals(FolloRules.PowerRelease.TURN_ON, FolloRules.powerRelease(off = true, inside = true, turnedOffByThisPress = false))
    assertEquals(FolloRules.PowerRelease.HINT, FolloRules.powerRelease(off = false, inside = true, turnedOffByThisPress = false))
  }

  @Test
  fun `letting go after the hold does not turn the app straight back on, nor does sliding off the button`() {
    assertEquals(FolloRules.PowerRelease.NOTHING, FolloRules.powerRelease(off = true, inside = true, turnedOffByThisPress = true))
    assertEquals(FolloRules.PowerRelease.NOTHING, FolloRules.powerRelease(off = true, inside = false, turnedOffByThisPress = false))
    assertEquals(FolloRules.PowerRelease.NOTHING, FolloRules.powerRelease(off = false, inside = false, turnedOffByThisPress = false))
  }

  @Test
  fun `the Off hello's source fits what the server keeps`() {
    assertTrue(FolloRules.POWER_OFF_HELLO_SRC.length <= 10)
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

  // Edge hold (Alex's walk 2 Nov 2026): 2 minutes or 50 m beyond the edge, whichever first; only in Move
  @Test
  fun `a fix just outside is held in Move until 2 minutes or 50 m`() {
    val t0 = 1_793_606_000_000L
    val move = MonitoringMode.Move
    assertEquals(FolloRules.GateVerdict.SHARE, FolloRules.gateVerdict(0.0, null, t0, move))
    assertEquals(FolloRules.GateVerdict.HOLD, FolloRules.gateVerdict(2.0, t0, t0, move))
    assertEquals(FolloRules.GateVerdict.HOLD, FolloRules.gateVerdict(49.0, t0, t0 + 119_000L, move))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(50.0, t0, t0 + 10_000L, move))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(3.0, t0, t0 + 120_000L, move))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(2.0, t0, t0, MonitoringMode.Manual))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(2.0, t0, t0, MonitoringMode.Significant))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(2.0, t0, t0, move, checkIn = true))
    assertEquals(FolloRules.GateVerdict.STANDBY, FolloRules.gateVerdict(Double.POSITIVE_INFINITY, t0, t0, move))
  }

  @Test
  fun `metres outside are measured to the nearest edge`() {
    // the TEST circle Alex skirted on 2 Nov 2026: r 100 m, east edge on the road
    val c = FolloRules.Circle(-26.627339, 153.036445, 100.0, "TEST")
    val east = 100.0 / (111_195.0 * Math.cos(Math.toRadians(-26.627339)))
    assertEquals(0.0, FolloRules.outsideByM(-26.627339, 153.036445 + east * 0.97, listOf(c), null), 0.0)
    assertEquals(3.0, FolloRules.outsideByM(-26.627339, 153.036445 + east * 1.03, listOf(c), null), 0.6)
    assertEquals(60.0, FolloRules.outsideByM(-26.627339, 153.036445 + east * 1.6, listOf(c), null), 1.0)
    assertEquals(Double.POSITIVE_INFINITY, FolloRules.outsideByM(-26.6, 153.0, emptyList(), null), 0.0)
    val ring = listOf(-26.63 to 153.03, -26.63 to 153.04, -26.62 to 153.04, -26.62 to 153.03)
    assertEquals(30.0, FolloRules.outsideByM(-26.63 - 30 / 111_320.0, 153.035, emptyList(), ring), 0.5)
  }

  // 2 Nov 2026 (Alex): the circle IS the line — no slack beyond it
  @Test
  fun `a fix 0_3 m outside a circle is outside, 0_3 m inside is inside`() {
    val c = FolloRules.Circle(-41.125442, 146.846931, 1562.0, "MV")
    val k = 6_371_008.8 * Math.PI / 180
    assertTrue(FolloRules.insideCircle(c.lat + 1561.7 / k, c.lon, c))
    assertFalse(FolloRules.insideCircle(c.lat + 1562.3 / k, c.lon, c))
    assertTrue(FolloRules.insideCircle(c.lat, c.lon + 1561.7 / (k * Math.cos(Math.toRadians(c.lat))), c))
    assertFalse(FolloRules.insideCircle(c.lat, c.lon + 1562.3 / (k * Math.cos(Math.toRadians(c.lat))), c))
  }

  // Near-circle watch (Alex, 2 Nov 2026): within 100 m of a circle a GPS fix every 30 s, else a rough one every 2 min
  @Test
  fun `the watch looks closely within 100 m of a circle and starts only on a good fix inside`() {
    assertEquals(FolloRules.WatchStage.NEAR, FolloRules.watchStage(80.0, 10f))
    assertEquals("a rough fix that may be within 100 m", FolloRules.WatchStage.NEAR, FolloRules.watchStage(130.0, 40f))
    assertEquals(FolloRules.WatchStage.FAR, FolloRules.watchStage(400.0, 20f))
    assertEquals("no window open", FolloRules.WatchStage.OFF, FolloRules.watchStage(Double.POSITIVE_INFINITY, 5f))
    assertEquals(FolloRules.WatchStage.NEAR, FolloRules.watchStage(0.0, 5f))
    assertTrue(FolloRules.watchStarts(0.0, 8f))
    assertFalse("a rough network fix inside waits for GPS", FolloRules.watchStarts(0.0, 45f))
    assertFalse(FolloRules.watchStarts(3.0, 5f))
  }

  @Test
  fun `the rotate button turns a quarter at a time, and the fourth tap is upright again`() {
    var d = 0
    val seen = mutableListOf<Int>()
    repeat(4) {
      d = FolloRules.nextTurn(d)
      seen += d
    }
    assertEquals(listOf(90, 180, 270, 0), seen)
    assertEquals(90, FolloRules.nextTurn(360))
    assertEquals(0, FolloRules.nextTurn(-90))
    assertEquals(180, FolloRules.nextTurn(100))
  }

  // ── Screen, round 2 (14 Nov 2026) ─────────────────────────────────────────────────────

  private val hobart = java.time.ZoneId.of("Australia/Hobart")
  private val brisbane = java.time.ZoneId.of("Australia/Brisbane")

  private fun at(zone: java.time.ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
      java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toEpochSecond()

  @Test
  fun `short times drop the minutes on the hour`() {
    assertEquals("6am", FolloRules.shortTime(at(hobart, 2026, 11, 15, 6), hobart))
    assertEquals("6pm", FolloRules.shortTime(at(hobart, 2026, 11, 15, 18), hobart))
    assertEquals("12pm", FolloRules.shortTime(at(hobart, 2026, 11, 15, 12), hobart))
    assertEquals("12am", FolloRules.shortTime(at(hobart, 2026, 11, 15, 0), hobart))
    assertEquals("6:30am", FolloRules.shortTime(at(hobart, 2026, 11, 15, 6, 30), hobart))
  }

  @Test
  fun `the search hours are the open window, else the next one, near areas first`() {
    val wins =
        listOf(
            FolloRules.Window("MV", at(hobart, 2026, 11, 15, 6), at(hobart, 2026, 11, 15, 18)),
            FolloRules.Window("DW", at(brisbane, 2026, 11, 15, 6), at(brisbane, 2026, 11, 15, 18)),
            FolloRules.Window("MV", at(hobart, 2026, 11, 16, 6), at(hobart, 2026, 11, 16, 18)),
        )
    // the night before: the next window
    assertEquals("6am-6pm", FolloRules.hoursText(wins, at(hobart, 2026, 11, 14, 22), hobart, "MV"))
    // during the day: the open one
    assertEquals("6am-6pm", FolloRules.hoursText(wins, at(hobart, 2026, 11, 15, 12), hobart, "MV"))
    // a Queensland phone near DW sees DW's hours in its own time, not the Tasmanian ones (an hour off in summer)
    assertEquals("6am-6pm", FolloRules.hoursText(wins, at(brisbane, 2026, 11, 15, 12), brisbane, "DW"))
    assertEquals("5am-5pm", FolloRules.hoursText(wins, at(brisbane, 2026, 11, 15, 12), brisbane, "MV"))
    // no nearby area known: any window
    assertEquals("6am-6pm", FolloRules.hoursText(wins, at(hobart, 2026, 11, 15, 12), hobart, null))
    // nothing known, or everything over
    assertNull(FolloRules.hoursText(null, 0, hobart))
    assertNull(FolloRules.hoursText(wins, at(hobart, 2026, 11, 17, 12), hobart, "MV"))
  }

  @Test
  fun `the first tick needs a status from today`() {
    val now = at(hobart, 2026, 11, 15, 9) * 1000
    assertTrue(FolloRules.checkedInToday(at(hobart, 2026, 11, 15, 0, 5) * 1000, now, hobart))
    assertFalse(FolloRules.checkedInToday(at(hobart, 2026, 11, 14, 23, 55) * 1000, now, hobart))
    assertFalse(FolloRules.checkedInToday(0L, now, hobart))
  }

  @Test
  fun `the third tick compares version codes, unknown counts as latest`() {
    assertTrue(FolloRules.isLatest(400139000, 400138000))
    assertTrue(FolloRules.isLatest(400139000, 400139000))
    assertFalse(FolloRules.isLatest(400135000, 400139000))
    assertTrue(FolloRules.isLatest(400139000, null))
  }

  @Test
  fun `the check text says each tick in words`() {
    val now = at(hobart, 2026, 11, 15, 9, 41) * 1000
    val good =
        FolloRules.checkText(
            "Green-IP · tracker IP", "Samsung SM-A145P", "Android 14",
            at(hobart, 2026, 11, 15, 9, 12) * 1000, now, hobart,
            FolloRules.MapCheck(saved = true), "0.1.39", 400139000, 400139000, "0.1.39",
        )
    assertEquals(
        "FOLLO Tracks check\n" +
            "Phone: Green-IP · tracker IP (Samsung SM-A145P, Android 14)\n" +
            "✅ App: 0.1.39, the latest\n" +
            "✅ Circles: checked in today, 9:12 am\n" +
            "✅ Offline map: on this phone",
        good,
    )
    val bad =
        FolloRules.checkText(
            "Tracker AB", "Samsung SM-A145P", "Android 14",
            at(hobart, 2026, 11, 9, 16, 5) * 1000, now, hobart,
            FolloRules.MapCheck(saved = false, savingPercent = 45), "0.1.35", 400135000, 400139000, "0.1.39",
        )
    assertTrue(bad.contains("⚠️ Circles: last checked in Mon 9 Nov, 4:05 pm"))
    val dated =
        FolloRules.checkText("x", "y", "Android 14", at(hobart, 2026, 11, 15, 9, 12) * 1000, now, hobart,
            FolloRules.MapCheck(saved = true), "0.1.39", 400139000, 400139000, "0.1.39", circlesDay = "9.11.2026")
    assertTrue(dated.contains("✅ Circles: checked in today, 9:12 am (circles of 9.11.2026)"))
    assertTrue(bad.contains("⚠️ Offline map: not on this phone, saving 45%"))
    // the same order as the ticks on screen (Alex 15 Nov 2026): app, circles, map
    assertTrue(bad.contains("Android 14)\n⚠️ App: 0.1.35, latest is 0.1.39\n⚠️ Circles:"))
    assertTrue(bad.endsWith("⚠️ Offline map: not on this phone, saving 45%"))
    val never =
        FolloRules.checkText("FOLLO Tracks", "x", "Android 14", 0L, now, hobart,
            FolloRules.MapCheck(saved = false, waitingForWifi = true), "0.1.39", 400139000, null, null)
    assertTrue(never.contains("⚠️ Circles: never checked in"))
    assertTrue(never.contains("not on this phone, waiting for Wi-Fi"))
    assertTrue(never.contains("\n✅ App: 0.1.39\n"))
  }

  @Test
  fun `MGA2020 matches the reference projection to a centimetre`() {
    // Reference values: PROJ (pyproj) EPSG:7844 GDA2020 -> EPSG:7855 / 7856, 14 Nov 2026
    fun check(lat: Double, lon: Double, zone: Int, e: Double, n: Double) {
      val g = FolloRules.mga(lat, lon)
      assertEquals(zone, g.zone)
      assertEquals(e, g.easting, 0.01)
      assertEquals(n, g.northing, 0.01)
    }
    check(-41.123456, 146.654321, 55, 470982.15622550825, 5447480.43256749)
    check(-42.8821, 147.3272, 55, 526720.478479885, 5252225.743666746)
    check(-41.0, 147.0, 55, 500000.00000000215, 5461242.938201998)
    check(-27.62, 153.09, 56, 508879.87688887987, 6944887.7825414)
  }

  @Test
  fun `the coordinates pill shows MGA easting and northing to the metre`() {
    // Alex 15 Nov 2026: "526599 E, 5252393 N" rather than a "-" and decimals. Reference values: PROJ cs2cs, UTM on GRS80.
    assertEquals("526599 E, 5252393 N", FolloRules.coordsText(-42.8806, 147.3257)) // E 526598.627, N 5252392.788
    assertEquals("485188 E, 5449494 N", FolloRules.coordsText(-41.1057, 146.8236)) // E 485188.212, N 5449494.137
    assertEquals("500000 E, 6958183 N", FolloRules.coordsText(-27.5, 153.0)) // zone 56: E 500000.000, N 6958183.184
    assertEquals(56, FolloRules.mga(-27.5, 153.0).zone)
  }

  @Test
  fun `a long press shares MGA with the zone, then GDA2020 latitude and longitude`() {
    assertEquals(
        "MGA55 526599 E, 5252393 N (GDA2020 -42.880600, 147.325700)",
        FolloRules.coordsShare(-42.8806, 147.3257),
    )
    assertEquals(
        "MGA56 500000 E, 6958183 N (GDA2020 -27.500000, 153.000000)",
        FolloRules.coordsShare(-27.5, 153.0),
    )
    assertEquals("-41.123456, 146.654321", FolloRules.latLonText(-41.123456, 146.654321))
  }
}
