package org.owntracks.android.ui.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The search grids the phone draws (Alex, 10 Nov 2026: a fallback if QField fails; all teams in their colours, the
 * phone's own team bold, display only). The body of GET /grid (docs/OFFLINE_GRIDS_20261110.md §2):
 * `{"v", "col": {team: "#RRGGBB"}, "areas": {code: {"teams": {team: {"l": [[lat,lon,lat,lon,…],…]}}, "names":
 * [[lat, lon, "Blue 1", "Blue"],…]}}}`. Malformed pieces are skipped, never fatal.
 */
class FolloGrid(val v: String, val areas: List<Area>) {
  /** One team's lines in an area: each a flat lat,lon,lat,lon… array. */
  class Team(val name: String, val color: Int, val lines: List<DoubleArray>)

  /** A grid's name tag at its start. */
  class Name(val lat: Double, val lon: Double, val text: String, val team: String, val color: Int)

  /** An area's grids with the box that holds them (for skipping areas off screen). */
  class Area(
      val code: String,
      val south: Double,
      val west: Double,
      val north: Double,
      val east: Double,
      val teams: List<Team>,
      val names: List<Name>,
  )

  val lineCount: Int
    get() = areas.sumOf { a -> a.teams.sumOf { it.lines.size } }

  companion object {
    const val WHITE = -1 // 0xFFFFFFFF

    /** "#1A6DB5" → opaque ARGB; anything else → white. */
    fun color(hex: String?): Int {
      val h = hex?.trim()?.removePrefix("#") ?: return WHITE
      if (h.length != 6) return WHITE
      val rgb = h.toIntOrNull(16) ?: return WHITE
      return (0xFF shl 24) or rgb
    }

    /** Whether two team names are the same team (case and outer spaces do not count). */
    fun sameTeam(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && a.trim().equals(b.trim(), ignoreCase = true)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** The grid set in a /grid body, or null when the body is not one. */
    fun parse(body: String): FolloGrid? {
      val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
      val v = root["v"].str() ?: return null
      val colours = (root["col"] as? JsonObject)?.mapValues { color(it.value.str()) }.orEmpty()
      fun colourOf(team: String): Int = colours.entries.firstOrNull { sameTeam(it.key, team) }?.value ?: WHITE
      val areas =
          (root["areas"] as? JsonObject)?.mapNotNull { (code, a) ->
            val o = a as? JsonObject ?: return@mapNotNull null
            var south = 90.0
            var west = 180.0
            var north = -90.0
            var east = -180.0
            val teams =
                (o["teams"] as? JsonObject)?.mapNotNull { (team, t) ->
                  val lines =
                      ((t as? JsonObject)?.get("l") as? JsonArray)?.mapNotNull { l ->
                        val flat = (l as? JsonArray)?.map { it.num() ?: return@mapNotNull null } ?: return@mapNotNull null
                        if (flat.size < 4 || flat.size % 2 != 0) return@mapNotNull null
                        for (i in flat.indices step 2) {
                          val lat = flat[i]
                          val lon = flat[i + 1]
                          if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                          if (lat < south) south = lat
                          if (lat > north) north = lat
                          if (lon < west) west = lon
                          if (lon > east) east = lon
                        }
                        flat.toDoubleArray()
                      }
                  if (lines.isNullOrEmpty()) null else Team(team, colourOf(team), lines)
                }.orEmpty()
            val names =
                (o["names"] as? JsonArray)?.mapNotNull { n ->
                  val row = n as? JsonArray ?: return@mapNotNull null
                  val lat = row.getOrNull(0).num() ?: return@mapNotNull null
                  val lon = row.getOrNull(1).num() ?: return@mapNotNull null
                  val text = row.getOrNull(2).str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                  val team = row.getOrNull(3).str().orEmpty()
                  if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                  if (lat < south) south = lat
                  if (lat > north) north = lat
                  if (lon < west) west = lon
                  if (lon > east) east = lon
                  Name(lat, lon, text, team, colourOf(team))
                }.orEmpty()
            if (teams.isEmpty()) null else Area(code, south, west, north, east, teams, names)
          }.orEmpty()
      return FolloGrid(v, areas)
    }
  }
}
