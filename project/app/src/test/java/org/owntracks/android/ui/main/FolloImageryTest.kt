package org.owntracks.android.ui.main

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.MapTileIndex
import org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL

/** Map imagery chosen per tile on the phone (5 Nov 2026: QLD imagery in TAS from a stale server pick). As iOS. */
class FolloImageryTest {
  @Test
  fun `each state's imagery where it is, Esri elsewhere`() {
    assertEquals(FolloImagery.tas, FolloImagery.state(-41.10, 146.82)) // George Town
    assertEquals(FolloImagery.qld, FolloImagery.state(-26.63, 153.03)) // Bli Bli
    assertEquals(FolloImagery.nsw, FolloImagery.state(-33.87, 151.21)) // Sydney
    assertNull(FolloImagery.state(-37.81, 144.96)) // Melbourne: Esri
    assertNull(FolloImagery.state(-31.95, 115.86)) // Perth: Esri
  }

  @Test
  fun `a tile by its own place`() {
    // z17 tiles over George Town and Bli Bli
    assertEquals(FolloImagery.tas, FolloImagery.layer(tileX(146.82, 17), tileY(-41.10, 17), 17))
    assertEquals(FolloImagery.qld, FolloImagery.layer(tileX(153.03, 17), tileY(-26.63, 17), 17))
    assertEquals(FolloImagery.esri, FolloImagery.layer(0, 0, 5)) // zoomed out: Esri everywhere
    assertEquals(FolloImagery.esri, FolloImagery.layer(tileX(146.82, 9), tileY(-41.10, 9), 9)) // below z10 too
    assertTrue(FolloImagery.url(FolloImagery.tas.template, 1, 2, 3).endsWith("/tile/3/2/1"))
  }

  @Test
  fun `the server's layers mean auto`() {
    assertTrue(FolloImagery.isAuto(FOLLO_DEFAULT_TILE_URL))
    assertTrue(FolloImagery.isAuto(FolloImagery.qld.template))
    assertFalse(FolloImagery.isAuto("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
    assertFalse(FolloImagery.isAuto(null))
  }

  @Test
  fun `the auto source asks each tile's own imagery, under one cache name`() {
    // The server picked QLD (a fix in Brisbane) but the phone looks at George Town
    val qld = FolloTileSource.of(FolloImagery.qld.template, 20)
    val tas = FolloTileSource.of(FolloImagery.tas.template, 19)
    assertTrue(qld.auto)
    assertEquals(qld.name(), tas.name())
    assertTrue(qld.matches(FolloImagery.tas.template, 19)) // a new server pick changes nothing
    assertTrue(qld.matches(null, 20)) // nor does no tile at all (Esri, the default)
    assertFalse(qld.matches("https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19))
    val gt = MapTileIndex.getTileIndex(17, tileX(146.82, 17), tileY(-41.10, 17))
    val bb = MapTileIndex.getTileIndex(17, tileX(153.03, 17), tileY(-26.63, 17))
    assertTrue(qld.getTileURLString(gt).startsWith("https://services.thelist.tas.gov.au/"))
    assertTrue(qld.getTileURLString(bb).startsWith("https://spatial-img.information.qld.gov.au/"))
    assertTrue(qld.getTileURLString(MapTileIndex.getTileIndex(5, 0, 0)).startsWith("https://server.arcgisonline.com/"))
    // LIST stops at 19 (enlarged past it), QLD goes to 20
    assertEquals(19, qld.nativeMaxZoom(MapTileIndex.getTileIndex(20, tileX(146.82, 20), tileY(-41.10, 20))))
    assertEquals(20, qld.nativeMaxZoom(MapTileIndex.getTileIndex(20, tileX(153.03, 20), tileY(-26.63, 20))))
    // Esri moved south only where Esri is drawn
    assertTrue(qld.isEsriTile(0, 0, 5))
    assertFalse(qld.isEsriTile(tileX(146.82, 17), tileY(-41.10, 17), 17))
    assertFalse(qld.isEsri)
  }

  @Test
  fun `any other template is drawn as given`() {
    val osm = FolloTileSource.of("https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19)
    assertFalse(osm.auto)
    assertEquals("https://tile.openstreetmap.org/17/3/4.png", osm.getTileURLString(MapTileIndex.getTileIndex(17, 3, 4)))
    assertTrue(osm.matches("https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19))
    assertFalse(osm.matches(FolloImagery.tas.template, 19))
    assertTrue(FolloTileSource.of("http://evil.example/{z}/{x}/{y}", 19).auto) // not https: the built-in (auto) imagery
  }

  @Test
  fun `the credit follows where the map looks`() {
    val auto = FolloTileSource.of(FolloImagery.qld.template, 20)
    assertEquals("Imagery © State of Tasmania (LIST)", auto.creditAt(-41.10, 146.82, 17))
    assertEquals("Imagery: Esri and partners", auto.creditAt(-41.10, 146.82, 6))
    assertEquals("Imagery © State of Queensland", auto.creditAt(-26.63, 153.03, 17))
  }

  private fun tileX(lon: Double, z: Int) = ((lon + 180) / 360 * (1 shl z)).toInt()

  private fun tileY(lat: Double, z: Int): Int {
    val r = lat * PI / 180
    return ((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt()
  }
}
