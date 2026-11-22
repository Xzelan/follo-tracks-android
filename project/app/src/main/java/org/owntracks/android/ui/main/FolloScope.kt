package org.owntracks.android.ui.main

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * FOLLO Tracks shows search areas ONLY (FP055, Alex 19 Nov 2026: "it needs to only work within the search area, and
 * must not give the false impression that it does anything outside. Loophole to fix: 'you can see my house', also in
 * screenshots people send to show it's working"). Contract: FOLLO_tracks/docs/FP055_SPEC_20261119.md; the same rules
 * and test vectors on iOS.
 *
 * Three views. LIVE: the phone is inside one of the search areas whose circles it holds: everything as before, under a
 * grey mask outside the circles and with the map kept to that area. LAST: it is not, but has been to one: that area's
 * map, north up, with nothing of this phone on it (no arrow, track, coordinates or heading). WAITING: it has been to
 * none: no map, only "Waiting to arrive at your first search area".
 *
 * These are the rules only, free of Android classes so they can be unit-tested. Where the phone has been ([mergeBeen])
 * is kept on the phone (FolloScopeRepo) and never sent; no location request is made for any of this.
 */
object FolloScope {
  /** The area word of a Basecamp circle's name ("FOLLO Basecamp wake 1"). */
  const val BASECAMP = "Basecamp"
  /** Frame(A) = bbox(A) grown by this share of its width / height on each side. */
  const val FRAME_PAD = 0.05
  /** LIVE ends only once fixes have been outside the area this long without a break. */
  const val LEAVE_AFTER_MS = 10_000L
  /** The mask and the WAITING panel: "plain grey" (#4D4D4D; white on it 8.6:1, the pure-green outline 6.2:1). */
  const val MASK_COLOR = 0xFF4D4D4D.toInt()
  /** A place counts as been to at most this often (the record is written to the phone's storage). */
  const val BEEN_EVERY_S = 60L
  /** Metres per degree on the apps' sphere (R = 6 371 008.8 m). */
  const val M_PER_DEG = 2 * Math.PI * 6_371_008.8 / 360.0

  /** A box in degrees. */
  data class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
    val midLat: Double
      get() = (south + north) / 2

    val midLon: Double
      get() = (west + east) / 2

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east

    /** The nearest point of the box to (lat, lon): itself when inside. */
    fun clamp(lat: Double, lon: Double): Pair<Double, Double> = lat.coerceIn(south, north) to lon.coerceIn(west, east)
  }

  enum class Mode { LIVE, LAST, WAITING }

  /** What the screen shows: [area] = V, null while WAITING. */
  data class View(val mode: Mode, val area: String?)

  /** [view] plus, while LIVE, since when (ms) every fix has been outside the area (null = the last one was inside). */
  data class State(val view: View, val outsideSinceMs: Long? = null)

  /** The phone's current fix: fresh (the arrow's own rule), else there is none. */
  data class Fix(val lat: Double, val lon: Double)

  val WAITING = View(Mode.WAITING, null)

  /**
   * §1: whether FP055 applies to this phone. An exempt phone (the last status element says `all: 1`: Alex's iPhone, the
   * Mobile Live Map Samsung) is as it was before, unless its "Volunteer view" switch is on. No status yet = not exempt.
   */
  fun applies(all: Int?, volunteerView: Boolean): Boolean = all != 1 || volunteerView

  /**
   * §1: the held circles by search area, in name order. A Basecamp circle joins the area whose circles are nearest
   * (from its centre to a circle's edge; a tie = the first area in name order); with no other area held it is left out.
   */
  fun areas(circles: List<FolloRules.Circle>): Map<String, List<FolloRules.Circle>> {
    val out = sortedMapOf<String, MutableList<FolloRules.Circle>>()
    for (c in circles) if (c.area.isNotEmpty() && c.area != BASECAMP && c.r > 0) out.getOrPut(c.area) { mutableListOf() }.add(c)
    if (out.isEmpty()) return out
    val own = out.mapValues { it.value.toList() } // a Basecamp circle is measured to the areas' own circles only
    for (b in circles) {
      if (b.area != BASECAMP || b.r <= 0) continue
      var best: String? = null
      var bestM = Double.POSITIVE_INFINITY
      for ((area, cs) in own) {
        val m = cs.minOf { FolloRules.distanceM(b.lat, b.lon, it.lat, it.lon) - it.r }
        if (m < bestM) {
          bestM = m
          best = area
        }
      }
      best?.let { out.getValue(it).add(b) }
    }
    return out
  }

  /** §1: the bounding box of the circles (each: centre ± radius, metres → degrees at the circle's latitude). */
  fun bbox(circles: List<FolloRules.Circle>): Box? {
    if (circles.isEmpty()) return null
    var s = Double.POSITIVE_INFINITY
    var w = Double.POSITIVE_INFINITY
    var n = Double.NEGATIVE_INFINITY
    var e = Double.NEGATIVE_INFINITY
    for (c in circles) {
      val dLat = c.r / M_PER_DEG
      val dLon = c.r / (M_PER_DEG * cos(Math.toRadians(c.lat)).coerceAtLeast(0.01))
      s = minOf(s, c.lat - dLat)
      n = maxOf(n, c.lat + dLat)
      w = minOf(w, c.lon - dLon)
      e = maxOf(e, c.lon + dLon)
    }
    return Box(s, w, n, e)
  }

  /** §1: the box grown by 5 % of its width left and right and 5 % of its height top and bottom. */
  fun frame(b: Box): Box {
    val dLat = (b.north - b.south) * FRAME_PAD
    val dLon = (b.east - b.west) * FRAME_PAD
    return Box(b.south - dLat, b.west - dLon, b.north + dLat, b.east + dLon)
  }

  /** Inside the shape S = the union of the circles (the edge counts; the same haversine as the sharing gate). */
  fun inside(lat: Double, lon: Double, circles: List<FolloRules.Circle>): Boolean =
      circles.any { FolloRules.insideCircle(lat, lon, it) }

  /** The areas whose shape holds (lat, lon): pure geometry, the time window does not matter (§2). */
  fun areasAt(lat: Double, lon: Double, areas: Map<String, List<FolloRules.Circle>>): List<String> =
      areas.filter { inside(lat, lon, it.value) }.keys.toList()

  /**
   * §3 without the flap guard: LIVE in the held area the fix is inside ([prefer] when it is inside several), else
   * LAST in the held area been to most recently, else WAITING. [fix] null = none, or stale.
   */
  fun choose(areas: Map<String, List<FolloRules.Circle>>, been: Map<String, Long>, fix: Fix?, prefer: String? = null): View {
    if (fix != null) {
      val at = areasAt(fix.lat, fix.lon, areas)
      if (at.isNotEmpty()) return View(Mode.LIVE, if (prefer != null && prefer in at) prefer else at.first())
    }
    val last = areas.keys.filter { it in been }.maxWithOrNull(compareBy<String> { been.getValue(it) }.thenByDescending { it })
    return if (last != null) View(Mode.LAST, last) else WAITING
  }

  /**
   * §3 with the flap guard: LIVE ends only when fixes have been outside S(V) for [LEAVE_AFTER_MS] without a break,
   * or at once when there is no fresh fix any more or V is no longer held.
   */
  fun step(prev: State?, areas: Map<String, List<FolloRules.Circle>>, been: Map<String, Long>, fix: Fix?, nowMs: Long): State {
    val live = prev?.view?.takeIf { it.mode == Mode.LIVE }
    val raw = choose(areas, been, fix, live?.area)
    if (raw.mode == Mode.LIVE) return State(raw)
    if (live != null && fix != null && live.area in areas) {
      val since = prev?.outsideSinceMs ?: nowMs
      if (nowMs - since < LEAVE_AFTER_MS) return State(live, since)
    }
    return State(raw)
  }

  /**
   * §3/§4: whether anything of this phone's own position may be on the screen (arrow, track, coordinates, heading):
   * only in LIVE and only while the fix is inside S(V) — strict, also during the flap guard's 10 s.
   */
  fun showsOwn(state: State?, areas: Map<String, List<FolloRules.Circle>>, fix: Fix?): Boolean {
    val v = state?.view ?: return false
    if (v.mode != Mode.LIVE || fix == null) return false
    return inside(fix.lat, fix.lon, areas[v.area].orEmpty())
  }

  /** §2: the phone's own record merged with the server's `been` rows: the newer time per area. */
  fun mergeBeen(own: Map<String, Long>, server: List<Pair<String, Long>>): Map<String, Long> {
    val out = own.toMutableMap()
    for ((area, t) in server) if (area.isNotEmpty() && t > (out[area] ?: Long.MIN_VALUE)) out[area] = t
    return out
  }

  /** The record as kept in the phone's storage: "MV=1794000000;FG=1793000000". */
  fun encodeBeen(been: Map<String, Long>): String = been.entries.joinToString(";") { "${it.key}=${it.value}" }

  fun decodeBeen(text: String?): Map<String, Long> =
      text.orEmpty().split(';').mapNotNull { row ->
        val i = row.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val t = row.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
        row.substring(0, i) to t
      }.toMap()

  /**
   * A circle as [n] points (lat, lon) on the sphere, so its line lies exactly where [inside] changes: the outline and
   * the mask are both drawn from it (§5: "the mask edge must coincide with the drawn circle outline").
   */
  fun ring(c: FolloRules.Circle, n: Int): List<Pair<Double, Double>> {
    val d = c.r / 6_371_008.8
    val lat1 = Math.toRadians(c.lat)
    val lon1 = Math.toRadians(c.lon)
    return (0 until n).map { i ->
      val brg = i * 2 * Math.PI / n
      val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
      val lon2 = lon1 + atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
      Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }
  }

  /** How many points a circle of radius [r] m is drawn with (one every ~8 m, 72…720): the outline's own rule. */
  fun ringPoints(r: Double): Int = (r / 8).toInt().coerceIn(72, 720)
}
