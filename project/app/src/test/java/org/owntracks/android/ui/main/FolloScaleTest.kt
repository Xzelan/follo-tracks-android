package org.owntracks.android.ui.main

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** True-scale drawing (0.1.52, 28 Nov 2026): 1 m tracklogs, half-metre dots, the label's tip on the dot's edge. */
class FolloScaleTest {
  /** The live map's own sums (site/index.html `trkW()` and `dotR()`), for 256-pixel tiles and a 1-pixel edge. */
  private fun mPerPx(lat: Double, z: Double) = 40075016.686 * cos(lat * PI / 180) / (256 * 2.0.pow(z))

  private fun liveTrkW(lat: Double, z: Double) = max(1.0, 1.0 / mPerPx(lat, z))

  private fun liveDotR(lat: Double, z: Double) = max(0.0, 0.5 / 2 / mPerPx(lat, z) - 0.5)

  @Test
  fun `pixels per metre is the web-map rule`() {
    // at the equator, zoom 0, the 256-pixel world is the equator's length
    assertEquals(256 / 40075016.686, FolloScale.pxPerMetre(0.0, 0.0), 1e-12)
    // one zoom level doubles it; a tile twice as wide doubles it; higher latitudes stretch it by 1 / cos
    assertEquals(2 * FolloScale.pxPerMetre(-27.0, 18.0), FolloScale.pxPerMetre(-27.0, 19.0), 1e-9)
    assertEquals(2 * FolloScale.pxPerMetre(-27.0, 18.0), FolloScale.pxPerMetre(-27.0, 18.0, 512.0), 1e-9)
    assertEquals(FolloScale.pxPerMetre(0.0, 18.0) / cos(-42.0 * PI / 180), FolloScale.pxPerMetre(-42.0, 18.0), 1e-9)
    for (lat in listOf(-42.0, -27.0, 0.0)) for (z in listOf(10.0, 16.5, 20.0)) assertEquals(1 / mPerPx(lat, z), FolloScale.pxPerMetre(lat, z), 1e-9)
  }

  @Test
  fun `a tracklog is one metre wide on the ground`() {
    val density = 2.625f
    val tile = 256.0 * density // the app's tiles are scaled to the screen's density
    for (z in listOf(17.0, 18.5, 20.0, 22.0)) {
      val ppm = FolloScale.pxPerMetre(-27.0, z, tile).toFloat()
      assertEquals(ppm, FolloScale.trackWidthPx(ppm, density), 1e-4f) // 1 m = the map's pixels per metre
    }
    // zooming in one level doubles the line; it is the same number of metres
    val a = FolloScale.trackWidthPx(FolloScale.pxPerMetre(-27.0, 19.0, tile).toFloat(), density)
    val b = FolloScale.trackWidthPx(FolloScale.pxPerMetre(-27.0, 20.0, tile).toFloat(), density)
    assertEquals(2 * a, b, 1e-4f)
  }

  @Test
  fun `a tracklog never goes under its floor`() {
    val density = 2f
    val floor = FolloScale.TRACK_MIN_DP * density
    assertEquals(1f, floor, 0f)
    assertEquals(floor, FolloScale.trackWidthPx(FolloScale.pxPerMetre(-27.0, 10.0).toFloat(), density), 0f) // zoomed far out
    assertEquals(floor, FolloScale.trackWidthPx(0f, density), 0f)
    assertEquals(floor, FolloScale.trackWidthPx(Float.NaN, density), 0f)
    assertEquals(floor, FolloScale.trackWidthPx(Float.POSITIVE_INFINITY, density), 0f)
    // with a 1-pixel floor (density 2) it is the live map's line at every zoom
    for (z in listOf(8.0, 14.0, 17.0, 19.25, 21.0))
        assertEquals(liveTrkW(-27.0, z).toFloat(), FolloScale.trackWidthPx(FolloScale.pxPerMetre(-27.0, z).toFloat(), density), 1e-3f)
  }

  @Test
  fun `a dot is half a metre across on the ground, edge included`() {
    val edge = 2.625f // 1 dp
    for (z in listOf(20.0, 21.0, 22.0)) {
      val ppm = FolloScale.pxPerMetre(-27.0, z, 256.0 * 2.625).toFloat()
      val outer = FolloScale.dotOuterRadiusPx(ppm, edge)
      assertEquals(0.5f * ppm, 2 * outer, 1e-4f)
      // the edge is centred on its circle, so stroke radius + half the edge = the outer radius
      assertEquals(outer, FolloScale.dotStrokeRadiusPx(outer, edge) + edge / 2, 1e-4f)
    }
  }

  @Test
  fun `a dot has no minimum size but its edge`() {
    val edge = 1f
    // zoomed out, half a metre is far under a pixel: just the edge is left, 1 pixel across, no colour inside
    val far = FolloScale.dotOuterRadiusPx(FolloScale.pxPerMetre(-27.0, 12.0).toFloat(), edge)
    assertEquals(0.5f, far, 0f)
    assertEquals(0f, FolloScale.dotStrokeRadiusPx(far, edge), 0f)
    assertEquals(0.5f, FolloScale.dotOuterRadiusPx(0f, edge), 0f)
    assertEquals(0.5f, FolloScale.dotOuterRadiusPx(Float.NaN, edge), 0f)
    // it only ever grows with the zoom
    var last = 0f
    for (z in 3..24) {
      val r = FolloScale.dotOuterRadiusPx(FolloScale.pxPerMetre(-27.0, z.toDouble()).toFloat(), edge)
      assertTrue(r >= last)
      last = r
    }
  }

  @Test
  fun `a dot is the live map's dot at every zoom`() {
    // the live map: circle radius dotR with a 1-pixel edge centred on it, so the outside is dotR + 0.5
    for (lat in listOf(-42.0, -27.0)) for (z in listOf(6.0, 15.0, 18.0, 19.5, 21.0, 23.0)) {
      val outer = FolloScale.dotOuterRadiusPx(FolloScale.pxPerMetre(lat, z).toFloat(), 1f)
      assertEquals((liveDotR(lat, z) + 0.5).toFloat(), outer, 1e-3f)
      assertEquals(liveDotR(lat, z).toFloat(), FolloScale.dotStrokeRadiusPx(outer, 1f), 1e-3f)
    }
  }

  @Test
  fun `the label's tip is on the dot's edge at every zoom`() {
    val edge = 2f
    for (z in listOf(5.0, 14.0, 18.5, 20.0, 22.0)) {
      val outer = FolloScale.dotOuterRadiusPx(FolloScale.pxPerMetre(-27.0, z, 512.0).toFloat(), edge)
      assertEquals(outer, FolloScale.labelTipOffsetPx(outer), 0f)
    }
    // zoomed right out the tip is half an edge from the middle: touching the speck
    assertEquals(edge / 2, FolloScale.labelTipOffsetPx(FolloScale.dotOuterRadiusPx(0f, edge)), 0f)
  }
}
