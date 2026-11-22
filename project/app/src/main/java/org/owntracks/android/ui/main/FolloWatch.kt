package org.owntracks.android.ui.main

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Every phone in a search circle, on ONE phone (iOS FolloWatch.swift, Alex 13 Nov 2026: "Only my iphone, make it so I
 * can always see any phone in a search circle anywhere in Australia … a drop down of all phones in the search circles at
 * the moment that if I select someone, it centres on them … defaults to most recent if I haven't selected someone."),
 * on the "Mobile Live Map" Samsung too since 18 Nov 2026, with every live-map search in the drop-down ("include all the
 * live map searches, even if no one is in them. eg someone may tell me they are on their way and I may have phone
 * ready and watching").
 *
 * The server decides who may: such a phone gets `all: 1` in its status element and may GET /all (worker: SEE_ALL,
 * re-keyed phones only). These are the rules, apart from the screen and the network so they can be tested; the store is
 * FolloWatchRepo, the drawing FolloWatchOverlay, the drop-down MainActivity.
 */
object FolloWatch {
  /** One phone in a search circle, as GET /all gives it. [at] = its newest fix, epoch seconds. */
  data class Phone(
      val user: String,
      val name: String,
      val tid: String,
      val team: String,
      val area: String,
      val lat: Double,
      val lon: Double,
      val at: Double,
      /** The dot's fill "#rrggbb" and the vest (Y / O / P) as the server sends them (22 Nov 2026, FP078); null = none */
      val tc: String? = null,
      val vest: String? = null,
  )

  /** One live-map search: code, name, colour, the box of its circles' outline (s, w, n, e), phones in it now. */
  data class Area(
      val code: String,
      val name: String,
      val color: Int,
      val south: Double,
      val west: Double,
      val north: Double,
      val east: Double,
      val count: Int,
  )

  /** One fix of a phone's line. */
  data class Point(val t: Double, val lat: Double, val lon: Double)

  /** The `logs` part: the fixes that reached the server after `seen`, per phone, oldest first. */
  class Logs(val seen: Long, val more: Boolean, val users: Map<String, List<Point>>)

  /**
   * One answer. [areas] / [av] null = the server sent no searches (an older server); [outlines] null = the outlines
   * the phone holds for [av] are still the right ones.
   */
  class Reply(
      val phones: List<Phone>,
      val logs: Logs?,
      val areas: List<Area>?,
      val av: String?,
      val outlines: Map<String, DoubleArray>?,
  )

  /** What the drop-down is set to. [Auto] = nobody chosen: the most recent phone, unless this phone is itself sharing. */
  sealed class Choice {
    object Auto : Choice()

    object Me : Choice()

    data class OnPhone(val user: String) : Choice()

    /** A search, kept even while nobody is in it (18 Nov 2026). */
    data class OnArea(val code: String) : Choice()
  }

  /** What the map is on. */
  sealed class Target {
    data class OnPhone(val user: String) : Target()

    data class OnArea(val code: String) : Target()
  }

  /** A line's points nearer than this to the one before are left out (m) */
  const val THIN_M = 3.0
  /** A line is cut at every step this long or longer (m), as the tracklogs are */
  const val CUT_M = 50.0
  /** Fixes older than this are let go (s; the server sends no older ones either) */
  const val KEEP_S = 24 * 3600.0
  /** At most this many points are held per phone (the oldest go first) */
  const val MOST = 20_000
  val GREY = 0xFF607D8B.toInt()

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

  /** {"now", "phones": [...], "logs": {...}, "areas": [...], "av", "outlines": {...}}; phones newest fix first. */
  fun parse(body: String): Reply? {
    val root = try {
      json.parseToJsonElement(body) as? JsonObject
    } catch (e: Exception) {
      null
    } ?: return null
    val rows = root["phones"] as? JsonArray ?: return null
    val phones = rows.mapNotNull(::phone).sortedByDescending { it.at }
    val logs = (root["logs"] as? JsonObject)?.let(::logs)
    val areas = (root["areas"] as? JsonArray)?.mapNotNull(::area)
    val outlines =
        (root["outlines"] as? JsonObject)?.let { o ->
          o.mapNotNull { (code, v) ->
                val a = v as? JsonArray ?: return@mapNotNull null
                val nums = a.mapNotNull { it.num() }
                if (nums.size != a.size || nums.size % 2 != 0) return@mapNotNull null
                code to nums.toDoubleArray()
              }
              .toMap()
        }
    return Reply(phones, logs, areas, root["av"].str(), outlines)
  }

  private fun phone(e: JsonElement): Phone? {
    val d = e as? JsonObject ?: return null
    val u = d["u"].str()?.takeIf { it.isNotEmpty() } ?: return null
    val lat = d["lat"].num() ?: return null
    val lon = d["lon"].num() ?: return null
    val tst = d["tst"].num() ?: return null
    if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) return null
    val n = d["n"].str()?.trim().orEmpty()
    return Phone(
        user = u,
        name = n.ifEmpty { u },
        tid = d["tid"].str() ?: u.takeLast(2).uppercase(),
        team = d["team"].str().orEmpty(),
        area = d["area"].str().orEmpty(),
        lat = lat,
        lon = lon,
        at = tst,
        tc = d["tc"].str(),
        vest = d["vest"].str(),
    )
  }

  private fun logs(l: JsonObject): Logs? {
    val seen = l["seen"].num() ?: return null
    val by = l["u"] as? JsonObject ?: return null
    val users = mutableMapOf<String, List<Point>>()
    for ((user, any) in by) {
      val p = any as? JsonArray ?: continue
      val points = mutableListOf<Point>()
      var i = 0
      while (i + 2 < p.size) {
        val t = p[i].num()
        val lat = p[i + 1].num()
        val lon = p[i + 2].num()
        if (t != null && lat != null && lon != null && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180) points += Point(t, lat, lon)
        i += 3
      }
      if (points.isNotEmpty()) users[user] = points
    }
    return Logs(seen.toLong(), l["more"].num() == 1.0, users)
  }

  private fun area(e: JsonElement): Area? {
    val d = e as? JsonObject ?: return null
    val c = d["c"].str()?.takeIf { it.isNotEmpty() } ?: return null
    val b = (d["b"] as? JsonArray)?.mapNotNull { it.num() }?.takeIf { it.size == 4 } ?: return null
    if (b[0] > b[2] || b[1] > b[3]) return null
    return Area(c, d["n"].str()?.trim()?.ifEmpty { null } ?: c, FolloGrid.color(d["col"].str()), b[0], b[1], b[2], b[3], (d["k"].num() ?: 0.0).toInt())
  }

  /**
   * The phones still in their search (FP079, 22 Nov 2026): one whose newest fix is 30 s old or more and lies within
   * 30 m of the edge of its area's held circles has LEFT (FolloRules.hasLeft) and is not drawn, counted or listed
   * until its next fix. [areas] = the held circles by search area; an area this phone holds no circles for is left to
   * the server's own judgement.
   */
  fun present(phones: List<Phone>, areas: Map<String, List<FolloRules.Circle>>, nowS: Double): List<Phone> =
      phones.filter { p ->
        !FolloRules.hasLeft((nowS - p.at).toLong(), FolloRules.dotDepthM(p.lat, p.lon, p.area.ifEmpty { null }, areas))
      }

  /** [areas] with each search's phone count taken from [phones] (those still in it), not the server's own number. */
  fun counted(areas: List<Area>?, phones: List<Phone>): List<Area>? =
      areas?.map { a -> a.copy(count = phones.count { it.area == a.code }) }

  /** Metres between two places a walk apart (flat-earth, as the server thins with). */
  fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dy = (aLat - bLat) * 111_320
    val dx = (aLon - bLon) * 111_320 * cos(aLat * Math.PI / 180)
    return sqrt(dx * dx + dy * dy)
  }

  /**
   * New fixes into a phone's line, kept in time order. A fix already held (the server hands the newest second again)
   * and one under [THIN_M] from the fix before it are left out, so taking the same fixes twice changes nothing. True
   * when the line changed.
   */
  fun merge(new: List<Point>, held: MutableList<Point>): Boolean {
    var changed = false
    for (p in new) {
      var at = held.size
      val last = held.lastOrNull()
      if (last != null && p.t <= last.t) {
        var lo = 0
        var hi = held.size
        while (lo < hi) {
          val mid = (lo + hi) / 2
          if (held[mid].t < p.t) lo = mid + 1 else hi = mid
        }
        at = lo
        if (at < held.size && held[at].t == p.t) continue
      }
      if (at > 0 && metres(held[at - 1].lat, held[at - 1].lon, p.lat, p.lon) < THIN_M) continue
      held.add(at, p)
      changed = true
    }
    if (held.size > MOST) {
      held.subList(0, held.size - MOST).clear()
      changed = true
    }
    return changed
  }

  /**
   * A phone's line as it is drawn: cut at every step of [CUT_M] or more, lone points left out; each piece a flat
   * lat,lon,… array. [end] (the phone's dot) is joined to the last piece when it is a step away.
   */
  fun lines(points: List<Point>, end: Pair<Double, Double>? = null): List<DoubleArray> {
    val out = mutableListOf<DoubleArray>()
    val piece = mutableListOf<Double>()
    fun flush() {
      if (piece.size >= 4) out += piece.toDoubleArray()
      piece.clear()
    }
    for (p in points) {
      if (piece.size >= 2 && metres(piece[piece.size - 2], piece[piece.size - 1], p.lat, p.lon) >= CUT_M) flush()
      piece += p.lat
      piece += p.lon
    }
    if (end != null && piece.size >= 2) {
      val step = metres(piece[piece.size - 2], piece[piece.size - 1], end.first, end.second)
      if (step > 0 && step < CUT_M) {
        piece += end.first
        piece += end.second
      }
    }
    flush()
    return out
  }

  /**
   * The choice once a new list is in: a chosen phone that has left falls back to [Choice.Auto] (as iOS); a chosen
   * search stays while the server still lists it, even with nobody in it; a search the server no longer lists (or
   * a list without searches from an older server, [areas] null) falls back to Auto too; "My location" goes back to
   * Auto once nobody is in a circle.
   */
  fun settle(choice: Choice, phones: List<Phone>, areas: List<Area>?): Choice =
      when (choice) {
        is Choice.OnPhone -> if (phones.any { it.user == choice.user }) choice else Choice.Auto
        is Choice.OnArea -> if (areas?.any { it.code == choice.code } == true) choice else Choice.Auto
        // nobody left: the next phone in a circle is shown by itself again (iOS)
        Choice.Me -> if (phones.isEmpty()) Choice.Auto else choice
        Choice.Auto -> choice
      }

  /**
   * What the map is on (null = this phone's own position).
   * - a chosen search, whoever is in it;
   * - a chosen phone, while it is in a circle;
   * - nobody chosen (or the chosen phone has left): the phone the map was already on while it stays in a circle
   *   ([held]), else the one with the newest fix — but only when this phone is not itself sharing in a search, where
   *   the volunteer's own position comes first;
   * - "My location": this phone.
   */
  fun target(choice: Choice, held: String?, phones: List<Phone>, sharing: Boolean): Target? =
      when (choice) {
        Choice.Me -> null
        is Choice.OnArea -> Target.OnArea(choice.code)
        is Choice.OnPhone ->
            if (phones.any { it.user == choice.user }) Target.OnPhone(choice.user) else target(Choice.Auto, null, phones, sharing)
        Choice.Auto ->
            when {
              sharing -> null
              held != null && phones.any { it.user == held } -> Target.OnPhone(held)
              else -> phones.firstOrNull()?.let { Target.OnPhone(it.user) }
            }
      }

  /** "now", "40 s", "12 min", "2 h": how old a fix is. */
  fun age(seconds: Double): String {
    val s = seconds.roundToInt().coerceAtLeast(0)
    return when {
      s < 10 -> "now"
      s < 90 -> "$s s"
      s < 5400 -> "${(s / 60.0).roundToInt()} min"
      else -> "${(s / 3600.0).roundToInt()} h"
    }
  }

  /** One line for a phone: "Jon (JE1) · MV · 40 s". */
  fun line(p: Phone, now: Double): String {
    val parts = mutableListOf(if (p.tid.isEmpty() || p.tid == p.name) p.name else "${p.name} (${p.tid})")
    if (p.area.isNotEmpty()) parts += p.area
    parts += age(now - p.at)
    return parts.joinToString(" · ")
  }

  /** "no phones", "1 phone", "3 phones". */
  fun phoneCount(n: Int): String =
      when (n) {
        0 -> "no phones"
        1 -> "1 phone"
        else -> "$n phones"
      }

  /** The search's name up to its first comma: "Search Two, Othertown QLD" → "Search Two". */
  fun shortName(name: String): String = name.substringBefore(',').trim().ifEmpty { name }

  /** One line for a search in the list: "DW · Search Two · 2 phones". */
  fun areaLine(a: Area): String = "${a.code} · ${shortName(a.name)} · ${phoneCount(a.count)}"

  /** What the drop-down's button says. A chosen search: "DW · no phones". */
  fun title(target: Phone?, area: Area?, count: Int, now: Double): String {
    if (area != null) return "${area.code} · ${phoneCount(area.count)}"
    if (target != null) return line(target, now)
    return when (count) {
      0 -> "No phones in circles"
      1 -> "1 phone in a circle"
      else -> "$count phones in circles"
    }
  }

  /** The team's colour (the search teams' own), else grey. */
  fun color(team: String): Int =
      when (team.lowercase()) {
        "red" -> 0xFFE53935.toInt()
        "blue" -> 0xFF1E88E5.toInt()
        "purple" -> 0xFF8E24AA.toInt()
        "orange" -> 0xFFFB8C00.toInt()
        "green" -> 0xFF43A047.toInt()
        else -> GREY
      }

  /**
   * The zoom at which the box (s, w, n, e) fills [fraction] of a [visW] × [visH] px view, given the box's size in px at
   * [zoom] ([boxW] × [boxH]); never under [min] nor over [max].
   */
  fun fitZoom(zoom: Double, boxW: Double, boxH: Double, visW: Double, visH: Double, fraction: Double, min: Double, max: Double): Double {
    if (boxW <= 0 && boxH <= 0) return max
    val k = minOf(if (boxW > 0) visW * fraction / boxW else Double.MAX_VALUE, if (boxH > 0) visH * fraction / boxH else Double.MAX_VALUE)
    if (!k.isFinite() || k <= 0) return zoom.coerceIn(min, max)
    return (zoom + kotlin.math.ln(k) / kotlin.math.ln(2.0)).coerceIn(min, max)
  }
}
