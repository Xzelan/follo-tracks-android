package org.owntracks.android.ui.main

/**
 * FP066 (Alex 23 Nov 2026: "stop watching for circles when more than 1km away"; FOLLO_tracks/docs/FP066_SPEC_20261123.md,
 * review A3). Every area whose wake-up circles the phone holds gets ONE "approach ring" geofence, watched by the
 * operating system: the bounding circle of that area's circles plus `appr` metres (status element, default 1000). A
 * Basecamp circle gets its own ring. Outside every ring the app takes no location at all (only the ring geofences and
 * the passive provider); inside a ring whose area has a window open, the near-circle watch runs exactly as before. A
 * fix less accurate than 500 m never changes which rings the phone is in.
 */
object FolloRings {
  const val APPR_DEFAULT_M = 1000.0
  /** A fix worse than this cannot tell 1 km: it never changes the stage (review A3/I3). */
  const val STAGE_ACC_MAX_M = 500f
  /** Android allows 100 geofences per app. */
  const val MAX_GEOFENCES = 100
  const val ID_PREFIX = "ring:"

  data class Ring(val area: String, val lat: Double, val lon: Double, val r: Double)

  /** The server's `appr` in metres, or the default when absent / nonsense. */
  fun apprM(appr: Int?): Double = appr?.takeIf { it in 50..50_000 }?.toDouble() ?: APPR_DEFAULT_M

  /**
   * One ring per area (Basecamp its own): centre = middle of the box round the circles' centres, radius = the farthest
   * circle edge from it + [apprM].
   */
  fun rings(circles: List<FolloRules.Circle>, apprM: Double): List<Ring> =
      circles
          .filter { it.area.isNotEmpty() && it.r > 0 }
          .groupBy { it.area }
          .map { (area, cs) ->
            val lat = (cs.minOf { it.lat } + cs.maxOf { it.lat }) / 2
            val lon = (cs.minOf { it.lon } + cs.maxOf { it.lon }) / 2
            val r = cs.maxOf { FolloRules.distanceM(lat, lon, it.lat, it.lon) + it.r } + apprM
            Ring(area, lat, lon, r)
          }
          .sortedBy { it.area }

  /** The areas whose rings contain the point. */
  fun inside(rings: List<Ring>, lat: Double, lon: Double): Set<String> =
      rings.filter { FolloRules.distanceM(lat, lon, it.lat, it.lon) <= it.r }.map { it.area }.toSet()

  /**
   * The rings the phone is in after a fix: worse than 500 m ⇒ unchanged (only areas still held are kept); otherwise
   * exactly the rings the fix lies in.
   */
  fun afterFix(prev: Set<String>, rings: List<Ring>, lat: Double, lon: Double, accM: Float): Set<String> =
      if (accM > STAGE_ACC_MAX_M || accM < 0f) keepHeld(prev, rings) else inside(rings, lat, lon)

  /** An OS ring event: ENTER adds the area, EXIT removes it (the OS's own judgement is trusted). */
  fun afterEvent(prev: Set<String>, area: String, entered: Boolean): Set<String> = if (entered) prev + area else prev - area

  /** Ring state for areas no longer held is forgotten. */
  fun keepHeld(prev: Set<String>, rings: List<Ring>): Set<String> = prev.intersect(rings.map { it.area }.toSet())

  /**
   * The near-circle watch may run only for areas whose circles count now ([countingAreas]: a window of this phone open;
   * "Basecamp" when any is) AND whose ring the phone is in. Empty ⇒ no location request at all.
   */
  fun watchAreas(insideRings: Set<String>, countingAreas: Set<String>): Set<String> = countingAreas.intersect(insideRings)

  /**
   * Which geofences to hand the OS: every ring first (nearest first when there are more than fit, [nearLat]/[nearLon] =
   * the last known position, if any), then the circles of areas whose ring the phone is in, while they fit.
   * [circleAreas] = the area of each wake-up circle (in order); returns the rings kept and the indexes of the circles.
   */
  fun plan(
      rings: List<Ring>,
      insideRings: Set<String>,
      circleAreas: List<String>,
      nearLat: Double? = null,
      nearLon: Double? = null,
      max: Int = MAX_GEOFENCES,
  ): Pair<List<Ring>, List<Int>> {
    val ordered =
        if (nearLat != null && nearLon != null) rings.sortedBy { FolloRules.distanceM(nearLat, nearLon, it.lat, it.lon) - it.r } else rings
    val keptRings = ordered.take(max)
    val room = max - keptRings.size
    val circles = circleAreas.withIndex().filter { it.value in insideRings }.map { it.index }.take(maxOf(0, room))
    return keptRings to circles
  }

  fun ringId(area: String) = ID_PREFIX + area

  fun areaOfId(id: String): String? = id.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.ifEmpty { null }
}
