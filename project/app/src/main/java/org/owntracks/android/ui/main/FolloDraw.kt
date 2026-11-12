package org.owntracks.android.ui.main

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The drawings base camp put on THIS phone's map (Alex, 12 Nov 2026: "we will need to be able to draw lines, etc. on
 * the phone"; docs/DRAW_20261112.md §2–3; the same on iOS): lines, areas, circles, pins and labels. The body of
 * GET /draw: `{"v", "items": [{"id", "k": line|poly|circle|pin|text, "p": [lat,lon,…], "r", "c", "w", "wm", "nc",
 * "f", "d", "t"}]}`. An item of a kind this build does not know, or with a malformed piece, is skipped, never fatal. Display only.
 */
class FolloDraw(val v: String, val items: List<Item>) {
  enum class Kind { LINE, POLY, CIRCLE, PIN, TEXT }

  /**
   * One drawing. [pts] = what the server sent, a flat lat,lon,… array (one point for a circle, pin or label).
   * [outline] = the flat points to stroke: the line itself, the area's corners (the map closes it), the circle's ring;
   * empty for a pin or label. [labelLat]/[labelLon] = where the label goes. The box holds every point drawn.
   */
  class Item(
      val id: Long,
      val kind: Kind,
      val pts: DoubleArray,
      val radius: Double,
      val color: Int,
      val width: Float,
      /** Width in metres on the ground (13 Nov 2026), 0 = [width] in dp instead: the line is this wide at every zoom. */
      val metres: Float,
      /** A solid line lies on a white casing unless base camp said not to (a background line, like old tracklogs). */
      val casing: Boolean,
      val fill: Float,
      val dashed: Boolean,
      val text: String?,
      val outline: DoubleArray,
      val labelLat: Double,
      val labelLon: Double,
      val south: Double,
      val west: Double,
      val north: Double,
      val east: Double,
  )

  companion object {
    /** The version a phone with no drawings holds. */
    const val NONE = "0"
    const val DEFAULT_COLOR = 0xFFFFD60A.toInt() // yellow
    const val DEFAULT_WIDTH = 3f
    const val WIDTH_MIN = 1f
    const val WIDTH_MAX = 12f
    const val METRES_MIN = 0.1f
    const val METRES_MAX = 100f
    const val RADIUS_MAX_M = 500_000.0 // a circle wider than this is not drawn
    const val LABEL_MAX = 60
    private const val EARTH_M = 6371008.8 // mean radius, the sphere the server's circles are measured on

    /** "#FF3B30" → opaque ARGB; anything else → the default yellow. */
    fun color(hex: String?): Int {
      val h = hex?.trim()?.removePrefix("#") ?: return DEFAULT_COLOR
      if (h.length != 6) return DEFAULT_COLOR
      val rgb = h.toIntOrNull(16) ?: return DEFAULT_COLOR
      return (0xFF shl 24) or rgb
    }

    /** Great-circle metres between two points. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
      val p1 = Math.toRadians(lat1)
      val p2 = Math.toRadians(lat2)
      val dp = p2 - p1
      val dl = Math.toRadians(lon2 - lon1)
      val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
      return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Corners of a circle's outline: about one every 8 m of radius, 72 to 720 (the same on iOS). */
    fun circlePoints(r: Double): Int = (r / 8).toInt().coerceIn(72, 720)

    /**
     * The ground circle of [r] metres round a point as a flat lat,lon ring of [n] points (not repeated at the end):
     * every point is [r] great-circle metres from the centre, starting north and going clockwise.
     */
    fun circleRing(lat: Double, lon: Double, r: Double, n: Int = circlePoints(r)): DoubleArray {
      val out = DoubleArray(n * 2)
      val p1 = Math.toRadians(lat)
      val l1 = Math.toRadians(lon)
      val d = r / EARTH_M
      for (i in 0 until n) {
        val b = 2 * Math.PI * i / n
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b)).coerceIn(-1.0, 1.0))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        out[2 * i] = Math.toDegrees(p2)
        out[2 * i + 1] = ((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0
      }
      return out
    }

    /** The point half way along a line's length (flat lat,lon,…), for its label. */
    fun midPoint(flat: DoubleArray): Pair<Double, Double> {
      val n = flat.size / 2
      if (n == 1) return flat[0] to flat[1]
      val legs = DoubleArray(n - 1) { i -> metres(flat[2 * i], flat[2 * i + 1], flat[2 * i + 2], flat[2 * i + 3]) }
      val total = legs.sum()
      if (total <= 0.0) return flat[0] to flat[1]
      var left = total / 2
      for (i in legs.indices) {
        if (left <= legs[i]) {
          val t = if (legs[i] > 0.0) left / legs[i] else 0.0
          return (flat[2 * i] + t * (flat[2 * i + 2] - flat[2 * i])) to (flat[2 * i + 1] + t * (flat[2 * i + 3] - flat[2 * i + 1]))
        }
        left -= legs[i]
      }
      return flat[flat.size - 2] to flat[flat.size - 1]
    }

    /** The average of an area's corners (flat lat,lon,…), for its label. */
    fun centroid(flat: DoubleArray): Pair<Double, Double> {
      val n = flat.size / 2
      var la = 0.0
      var lo = 0.0
      for (i in 0 until n) {
        la += flat[2 * i]
        lo += flat[2 * i + 1]
      }
      return (la / n) to (lo / n)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** The drawing set in a /draw body, or null when the body is not one. */
    fun parse(body: String): FolloDraw? {
      val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
      val v = root["v"].str() ?: return null
      val items = (root["items"] as? JsonArray)?.mapIndexedNotNull { i, e -> item(e as? JsonObject ?: return@mapIndexedNotNull null, i) }.orEmpty()
      return FolloDraw(v, items)
    }

    private fun item(o: JsonObject, index: Int): Item? {
      val kind =
          when (o["k"].str()) {
            "line" -> Kind.LINE
            "poly" -> Kind.POLY
            "circle" -> Kind.CIRCLE
            "pin" -> Kind.PIN
            "text" -> Kind.TEXT
            else -> return null // a kind a later build draws
          }
      val flat = (o["p"] as? JsonArray)?.map { it.num() ?: return null } ?: return null
      if (flat.size < 2 || flat.size % 2 != 0) return null
      for (i in flat.indices step 2) if (flat[i] !in -90.0..90.0 || flat[i + 1] !in -180.0..180.0) return null
      val least = when (kind) { Kind.LINE -> 2; Kind.POLY -> 3; else -> 1 }
      if (flat.size / 2 < least) return null
      val pts = if (least == 1) doubleArrayOf(flat[0], flat[1]) else flat.toDoubleArray()
      val radius = o["r"].num() ?: 0.0
      if (kind == Kind.CIRCLE && !(radius > 0.0 && radius <= RADIUS_MAX_M)) return null
      val text = o["t"].str()?.trim()?.take(LABEL_MAX)?.takeIf { it.isNotEmpty() }
      if (kind == Kind.TEXT && text == null) return null // a label with no words is nothing
      val outline =
          when (kind) {
            Kind.LINE, Kind.POLY -> pts
            Kind.CIRCLE -> circleRing(pts[0], pts[1], radius)
            else -> DoubleArray(0)
          }
      val (labelLat, labelLon) =
          when (kind) {
            Kind.LINE -> midPoint(pts)
            Kind.POLY -> centroid(pts)
            else -> pts[0] to pts[1]
          }
      var south = pts[0]
      var north = pts[0]
      var west = pts[1]
      var east = pts[1]
      val box = if (outline.isNotEmpty()) outline else pts
      for (i in box.indices step 2) {
        if (box[i] < south) south = box[i]
        if (box[i] > north) north = box[i]
        if (box[i + 1] < west) west = box[i + 1]
        if (box[i + 1] > east) east = box[i + 1]
      }
      return Item(
          id = o["id"].num()?.toLong() ?: -(index + 1L),
          kind = kind,
          pts = pts,
          radius = radius,
          color = color(o["c"].str()),
          width = (o["w"].num()?.toFloat() ?: DEFAULT_WIDTH).takeIf { it.isFinite() }?.coerceIn(WIDTH_MIN, WIDTH_MAX) ?: DEFAULT_WIDTH,
          metres = o["wm"].num()?.toFloat()?.takeIf { it.isFinite() && it > 0f }?.coerceIn(METRES_MIN, METRES_MAX) ?: 0f,
          casing = !((o["nc"].num() ?: 0.0) != 0.0 || (o["nc"] as? JsonPrimitive)?.content == "true"),
          fill = if (kind == Kind.POLY || kind == Kind.CIRCLE) (o["f"].num()?.toFloat() ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f else 0f,
          dashed = (o["d"].num() ?: 0.0) != 0.0 || (o["d"] as? JsonPrimitive)?.content == "true",
          text = text,
          outline = outline,
          labelLat = labelLat,
          labelLon = labelLon,
          south = south,
          west = west,
          north = north,
          east = east,
      )
    }
  }
}
