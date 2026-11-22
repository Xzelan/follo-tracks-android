package org.owntracks.android.ui.main

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.42 (16 Nov 2026): compass heading pill (FP019), arrow spot (FP033), offline-map check-in (FP034). */
class FolloCompassRulesTest {
  // ── heading text ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun `the pill reads whole degrees, a degree sign, two spaces and the 16-point cardinal`() {
    assertEquals("112°  ESE", FolloRules.headingText(112f))
    assertEquals("112°  ESE", FolloRules.headingText(111.6f))
    assertEquals("0°  N", FolloRules.headingText(0f))
    assertEquals("270°  W", FolloRules.headingText(270.2f))
  }

  @Test
  fun `degrees are 0 to 359, never 360`() {
    assertEquals(0, FolloRules.headingDegrees(359.6f))
    assertEquals(359, FolloRules.headingDegrees(359.4f))
    assertEquals(0, FolloRules.headingDegrees(360f))
    assertEquals(350, FolloRules.headingDegrees(-10f))
    assertEquals("0°  N", FolloRules.headingText(359.7f))
  }

  @Test
  fun `each cardinal is 22_5 degrees wide and centred, so 349 to 11 is N`() {
    val want =
        listOf(
            349 to "N", 0 to "N", 11 to "N", 12 to "NNE", 33 to "NNE", 34 to "NE", 56 to "NE", 57 to "ENE",
            78 to "ENE", 79 to "E", 101 to "E", 102 to "ESE", 123 to "ESE", 124 to "SE", 146 to "SE", 147 to "SSE",
            168 to "SSE", 169 to "S", 191 to "S", 192 to "SSW", 213 to "SSW", 214 to "SW", 236 to "SW", 237 to "WSW",
            258 to "WSW", 259 to "W", 281 to "W", 282 to "WNW", 303 to "WNW", 304 to "NW", 326 to "NW", 327 to "NNW",
            348 to "NNW",
        )
    for ((d, c) in want) assertEquals("$d°", c, FolloRules.cardinal16(d))
  }

  @Test
  fun `all 16 cardinals are used and N covers 349 to 11`() {
    val seen = (0..359).map { FolloRules.cardinal16(it) }
    assertEquals(16, seen.distinct().size)
    assertEquals(23, seen.count { it == "N" })
  }

  // ── which way the top of the screen points ────────────────────────────────────────────────────

  /** Rotation matrix (row-major, rows = world east/north/up, columns = device X/Y/Z) of a phone lying flat, top at [a]°. */
  private fun flat(a: Double): FloatArray {
    val s = sin(Math.toRadians(a)).toFloat()
    val c = cos(Math.toRadians(a)).toFloat()
    // device X = (cos a, -sin a, 0), Y = (sin a, cos a, 0), Z = up
    return floatArrayOf(c, s, 0f, -s, c, 0f, 0f, 0f, 1f)
  }

  /**
   * A phone held up in front of you, its back towards [a]°, with the screen turned [rot] by the rotate button and the
   * phone turned to match (the drawn top of the screen points at the sky). Z (towards the user) = (-sin a, -cos a, 0).
   */
  private fun heldUp(a: Double, rot: Int): FloatArray {
    val s = sin(Math.toRadians(a)).toFloat()
    val c = cos(Math.toRadians(a)).toFloat()
    return when (rot) {
      90 -> floatArrayOf(0f, -c, -s, 0f, s, -c, 1f, 0f, 0f) // X up, Y = Z × X
      180 -> floatArrayOf(-c, 0f, -s, s, 0f, -c, 0f, -1f, 0f) // Y down, X = Y × Z
      270 -> floatArrayOf(0f, c, -s, 0f, -s, -c, -1f, 0f, 0f) // X down, Y = Z × X
      else -> floatArrayOf(c, 0f, -s, -s, 0f, -c, 0f, 1f, 0f) // Y up, X = Y × Z
    }
  }

  private fun near(want: Float, got: Float?) {
    requireNotNull(got)
    val d = Math.abs(((got - want + 540f) % 360f) - 180f)
    assertTrue("want $want got $got", d < 0.01f)
  }

  @Test
  fun `upright, the bearing is where the top edge points lying flat and where the back faces held up`() {
    for (a in listOf(0.0, 45.0, 112.0, 200.0, 359.0)) {
      near(a.toFloat(), FolloRules.screenBearing(flat(a), 0))
      near(a.toFloat(), FolloRules.screenBearing(heldUp(a, 0), 0))
    }
  }

  @Test
  fun `lying flat, each turn of the rotate button moves the top of the screen a quarter round`() {
    for ((turn, want) in listOf(0 to 0f, 90 to 90f, 180 to 180f, 270 to 270f, 360 to 0f)) {
      near(want, FolloRules.screenBearing(flat(0.0), FolloRules.displayRotationOfTurn(turn)))
    }
    near(120f, FolloRules.screenBearing(flat(30.0), FolloRules.displayRotationOfTurn(90)))
    near(210f, FolloRules.screenBearing(flat(30.0), FolloRules.displayRotationOfTurn(180)))
    near(300f, FolloRules.screenBearing(flat(30.0), FolloRules.displayRotationOfTurn(270)))
  }

  @Test
  fun `held up in front of you at any turn, the back of the phone decides`() {
    for (rot in listOf(0, 90, 180, 270)) near(112f, FolloRules.screenBearing(heldUp(112.0, rot), rot))
  }

  @Test
  fun `no bearing when screen-up and into-the-screen cancel out sideways`() {
    // tilted 45° back with the top towards north: screen-up leans north, into-the-screen leans south, both 45° up
    val h = (1.0 / Math.sqrt(2.0)).toFloat()
    // device X = west, Y = (0, h, h), Z = (0, h, -h); rows = world east/north/up
    assertNull(FolloRules.screenBearing(floatArrayOf(-1f, 0f, 0f, 0f, h, h, 0f, h, -h), 0))
  }

  @Test
  fun `the rotate button's turn is the display rotation, in quarter turns`() {
    assertEquals(listOf(0, 90, 180, 270, 0), listOf(0, 90, 180, 270, 360).map { FolloRules.displayRotationOfTurn(it) })
    assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, FolloScreenTurner.requested(90))
  }

  // ── calibration, the share check, placement ───────────────────────────────────────────────────

  @Test
  fun `poor calibration greys the pill`() {
    assertTrue(FolloRules.compassPoor(0, null)) // UNRELIABLE
    assertTrue(FolloRules.compassPoor(1, null)) // LOW
    assertFalse(FolloRules.compassPoor(2, null)) // MEDIUM
    assertFalse(FolloRules.compassPoor(3, 10f))
    assertFalse(FolloRules.compassPoor(3, 25f))
    assertTrue(FolloRules.compassPoor(3, 30f))
  }

  @Test
  fun `the share check ends with one compass line`() {
    assertEquals("Compass: 112° ESE true (±10°)", FolloRules.compassCheckLine(112.2f, true, 9.6f))
    assertEquals("Compass: 112° ESE magnetic", FolloRules.compassCheckLine(112f, false, null))
    assertEquals("Compass: 0° N true", FolloRules.compassCheckLine(359.8f, true, -1f))
    assertEquals("Compass: none", FolloRules.compassCheckLine(null, true, 5f))
    val text =
        FolloRules.checkText(
            phone = "Demo phone · tracker DP",
            device = "Samsung SM-A155F",
            os = "Android 15",
            statusAtMs = 0L,
            nowMs = 1_000L,
            zone = java.time.ZoneId.of("Australia/Brisbane"),
            map = FolloRules.MapCheck(saved = true),
            version = "0.1.42",
            versionCode = 400142000L,
            latestCode = 400142000L,
            latestVersion = "0.1.42",
            compass = "Compass: 112° ESE true (±10°)",
        )
    assertEquals(6, text.lines().size)
    assertEquals("Compass: 112° ESE true (±10°)", text.lines().last())
  }

  @Test
  fun `sideways the heading pill goes above the position pill only when they would come within the gap`() {
    assertFalse(FolloRules.pillsStack(rowWidth = 600f, headingWidth = 150f, coordsWidth = 250f, gap = 20f))
    assertFalse(FolloRules.pillsStack(rowWidth = 420f, headingWidth = 150f, coordsWidth = 250f, gap = 20f))
    assertTrue(FolloRules.pillsStack(rowWidth = 419f, headingWidth = 150f, coordsWidth = 250f, gap = 20f))
    assertFalse(FolloRules.pillsStack(rowWidth = 100f, headingWidth = 150f, coordsWidth = 0f, gap = 20f)) // no position
  }

  // ── FP033: the arrow at the middle of the whole screen ────────────────────────────────────────

  @Test
  fun `the arrow sits at the middle of the whole screen, whatever covers the map`() {
    assertEquals(1200f, FolloRules.arrowSpotY(screenH = 2400f, mapTop = 0f, mapH = 2400f), 0.001f)
    // a map view that starts under the status bar: the same screen middle, in the map's own pixels
    assertEquals(1110f, FolloRules.arrowSpotY(screenH = 2400f, mapTop = 90f, mapH = 2310f), 0.001f)
    // sideways (1080 tall): still the middle, top to bottom
    assertEquals(540f, FolloRules.arrowSpotY(screenH = 1080f, mapTop = 0f, mapH = 1080f), 0.001f)
    // never outside the map
    assertEquals(0f, FolloRules.arrowSpotY(screenH = 2400f, mapTop = 1500f, mapH = 100f), 0.001f)
    assertEquals(100f, FolloRules.arrowSpotY(screenH = 2400f, mapTop = 0f, mapH = 100f), 0.001f)
  }

  // ── FP034: check in when the offline map finishes or stops ────────────────────────────────────

  @Test
  fun `the map check-in goes once at 100 percent`() {
    assertTrue(FolloRules.mapCheckInDue(fetched = true, percent = 100, lastSent = null, sinceLastMs = 0L))
    assertTrue(FolloRules.mapCheckInDue(fetched = true, percent = 100, lastSent = 60, sinceLastMs = 5_000L))
    assertFalse(FolloRules.mapCheckInDue(fetched = true, percent = 100, lastSent = 100, sinceLastMs = 999_999L))
  }

  @Test
  fun `a map that stopped part-way checks in at a new percent, at most once a minute`() {
    assertTrue(FolloRules.mapCheckInDue(fetched = true, percent = 60, lastSent = null, sinceLastMs = 61_000L))
    assertFalse(FolloRules.mapCheckInDue(fetched = true, percent = 61, lastSent = 60, sinceLastMs = 10_000L))
    assertTrue(FolloRules.mapCheckInDue(fetched = true, percent = 61, lastSent = 60, sinceLastMs = 60_000L))
    assertFalse(FolloRules.mapCheckInDue(fetched = true, percent = 60, lastSent = 60, sinceLastMs = 600_000L))
  }

  @Test
  fun `no check-in without fetching or without a map`() {
    assertFalse(FolloRules.mapCheckInDue(fetched = false, percent = 100, lastSent = null, sinceLastMs = 999_999L))
    assertFalse(FolloRules.mapCheckInDue(fetched = true, percent = null, lastSent = null, sinceLastMs = 999_999L))
  }
}
