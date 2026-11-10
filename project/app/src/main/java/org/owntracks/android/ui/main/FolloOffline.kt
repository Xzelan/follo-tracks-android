package org.owntracks.android.ui.main

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import org.osmdroid.util.MapTileIndex

/**
 * The offline map's plan (Alex, 10 Nov 2026: a fallback map if QField fails): which imagery tiles the phone saves so
 * the map inside the wake-up circles draws with no signal. Pure arithmetic, the same rules as iOS
 * (docs/OFFLINE_GRIDS_20261110.md §4): for every area the server names (status `off.z`: area → deepest zoom) whose
 * nearest circle is within `off.km` of the phone, every tile from zoom [MIN_Z] to that zoom whose rectangle touches a
 * circle of the area (radius + [MARGIN_M]); nearest area first, lower zooms first, at most `off.cap` tiles in all.
 */
object FolloOffline {
  /** The state imagery starts here ([FolloImagery.STATE_FROM_Z]); below it the map is a handful of Esri tiles. */
  const val MIN_Z = 10
  /** Tiles this far outside a circle's edge are saved too, so the edge itself is never blank. */
  const val MARGIN_M = 30.0
  /** A state imagery tile is about this big (LIST: 9–14 KB), for "About 140 MB of mobile data". */
  const val TILE_KB = 11

  /** The server's settings (status `off`). */
  data class Settings(val z: Map<String, Int>, val km: Double, val cap: Int, val free: Int)

  /** Web-mercator tile column holding this longitude at zoom [z]. */
  fun tileX(lon: Double, z: Int): Int {
    val n = 1 shl z
    return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
  }

  /** Web-mercator tile row holding this latitude at zoom [z]. */
  fun tileY(lat: Double, z: Int): Int {
    val n = 1 shl z
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
  }

  /** West edge of tile column [x]. */
  fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

  /** North edge of tile row [y]. */
  fun tileLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y.toDouble() / (1 shl z)))))

  /** Whether tile ([x], [y]) at zoom [z] comes within [reachM] of the point: the tile's nearest point is that close. */
  fun tileTouches(x: Int, y: Int, z: Int, lat: Double, lon: Double, reachM: Double): Boolean {
    val nearLat = lat.coerceIn(tileLat(y + 1, z), tileLat(y, z))
    val nearLon = lon.coerceIn(tileLon(x, z), tileLon(x + 1, z))
    return FolloRules.distanceM(lat, lon, nearLat, nearLon) <= reachM
  }

  /** Every tile at zoom [z] touching the circle (radius + [MARGIN_M]), as osmdroid tile indices, added to [into]. */
  fun tilesOfCircle(c: FolloRules.Circle, z: Int, into: MutableCollection<Long>) {
    val reach = c.r + MARGIN_M
    val dLat = reach / 110540.0
    val dLon = reach / (111320.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(0.01))
    for (x in tileX(c.lon - dLon, z)..tileX(c.lon + dLon, z)) {
      for (y in tileY(c.lat + dLat, z)..tileY(c.lat - dLat, z)) {
        if (tileTouches(x, y, z, c.lat, c.lon, reach)) into.add(MapTileIndex.getTileIndex(z, x, y))
      }
    }
  }

  /** Metres from the point to the nearest circle's edge (0 inside one); null without circles. */
  fun distanceToCirclesM(lat: Double, lon: Double, circles: List<FolloRules.Circle>): Double? =
      circles.minOfOrNull { (FolloRules.distanceM(lat, lon, it.lat, it.lon) - it.r).coerceAtLeast(0.0) }

  /**
   * The tiles to hold, in the order they are fetched. [circles] carry their area code; [lat]/[lon] = the phone's last
   * known position (none yet ⇒ nothing: the `km` rule cannot be judged). [maxZoomOf] = the deepest level a tile's
   * imagery really has (LIST answers z20 with a blank picture, which must never be saved): deeper tiles are left out.
   */
  fun plan(
      circles: List<FolloRules.Circle>,
      settings: Settings,
      lat: Double?,
      lon: Double?,
      maxZoomOf: (x: Int, y: Int, z: Int) -> Int = { x, y, z -> FolloImagery.layer(x, y, z).maxZ },
  ): List<Long> {
    if (lat == null || lon == null || settings.cap <= 0) return emptyList()
    val areas =
        circles
            .filter { it.area in settings.z && it.r > 0 }
            .groupBy { it.area }
            .mapNotNull { (area, cs) -> distanceToCirclesM(lat, lon, cs)?.let { Triple(area, cs, it) } }
            .filter { it.third <= settings.km * 1000.0 }
            .sortedWith(compareBy({ it.third }, { it.first }))
    val out = LinkedHashSet<Long>()
    for ((area, cs, _) in areas) {
      val top = (settings.z[area] ?: 0).coerceAtMost(MAP_MAX_ZOOM)
      for (z in MIN_Z..top) {
        val level = LinkedHashSet<Long>()
        for (c in cs) tilesOfCircle(c, z, level)
        for (t in level) {
          if (z > maxZoomOf(MapTileIndex.getX(t), MapTileIndex.getY(t), z)) continue
          out.add(t)
          if (out.size >= settings.cap) return out.toList()
        }
      }
    }
    return out.toList()
  }

  /** "About 140 MB": megabytes for this many tiles, at least 1. */
  fun megabytes(tiles: Int): Int = ((tiles.toLong() * TILE_KB + 1023) / 1024).toInt().coerceAtLeast(1)

  /** Percent saved, 100 only when nothing is missing. */
  fun percent(total: Int, missing: Int): Int =
      if (total <= 0 || missing <= 0) 100 else ((total - missing) * 100L / total).toInt().coerceIn(0, 99)

  /** On a metered connection the phone fetches by itself only a small remainder; more waits for Wi-Fi or a tap. */
  fun mayFetch(metered: Boolean, missing: Int, free: Int, userAllowed: Boolean): Boolean =
      !metered || userAllowed || missing <= free
}
