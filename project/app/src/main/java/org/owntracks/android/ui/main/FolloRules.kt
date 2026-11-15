package org.owntracks.android.ui.main

import org.owntracks.android.model.messages.MessageConfiguration
import org.owntracks.android.preferences.types.MonitoringMode
import timber.log.Timber

/** Small FOLLO rules kept free of Android classes so they can be unit-tested (review 21 Oct 2026). */
object FolloRules {
  /** Settings a setup link may set. */
  val IMPORT_KEYS =
      setOf(
          "url", "username", "password", "deviceId", "tid", "mode", "monitoring",
          "moveModeLocatorInterval", "pegLocatorFastestIntervalToInterval", "locatorInterval", "locatorDisplacement",
          "ignoreInaccurateLocations", "ignoreStaleLocations", "cmd", "remoteConfiguration", "allowRemoteLocation",
          "ranging", "pubTopicBase", "reverseGeocodeProvider", "extendedData", "mapLayerStyle", "waypoints", "folloName",
      )

  /** A leader not heard from for this long comes off the map. */
  const val LEADER_STALE_S = 10 * 60L

  /**
   * Only the settings a FOLLO setup needs are taken from a link; every other key is dropped, so a crafted link can
   * not, say, point the map tiles at a stranger's server. Waypoints come along as they are.
   */
  fun importable(config: MessageConfiguration): MessageConfiguration {
    val clean = MessageConfiguration()
    val dropped = mutableListOf<String>()
    for (k in config.keys) if (k in IMPORT_KEYS) clean[k] = config[k] else dropped += k
    clean.waypoints = config.waypoints
    if (dropped.isNotEmpty()) Timber.d("Setup link keys not imported: $dropped")
    return clean
  }

  /**
   * Whether a touch on the map holds it where the volunteer left it (Alex 13 Nov 2026, after Jon E: "it always takes you
   * back to your current location"): any move, zoom or turn made by hand does, and the map then stays put, at that
   * zoom, until the card (or the banner) is tapped. A plain tap changes nothing, so it holds nothing.
   * [dragged] = one finger moved the map, [pinched] = two fingers moved or zoomed it, [turned] = a two-finger twist
   * turned it, [doubleTap] = the double tap that zooms in.
   */
  fun holdsMap(dragged: Boolean, pinched: Boolean, turned: Boolean, doubleTap: Boolean): Boolean =
      dragged || pinched || turned || doubleTap

  // ── Power button (Alex, 13 Nov 2026; what Off means: support/FolloPower) ─────────────────────────

  /** How long the power button must be held to turn the app Off: 2 s (Alex, 13 Nov 2026; it began the day at 3 s). */
  const val POWER_HOLD_MS = 2_000L

  /** The location-free hello that tells base camp a phone was turned Off; the server keeps 10 characters of it. */
  const val POWER_OFF_HELLO_SRC = "off"

  enum class PowerRelease {
    /** the finger left the button, or this is the end of the hold that has just turned the app Off */
    NOTHING,
    /** the app is Off: one tap turns it back on, at once */
    TURN_ON,
    /** the app is on and the hold was too short: say how long it takes */
    HINT,
  }

  /**
   * What letting go of the power button does. [off] = the app's state when the finger lifts, [inside] = it lifted on
   * the button, [turnedOffByThisPress] = this same press reached [POWER_HOLD_MS] and turned the app Off (its release
   * must not turn it straight back on).
   */
  fun powerRelease(off: Boolean, inside: Boolean, turnedOffByThisPress: Boolean): PowerRelease =
      when {
        turnedOffByThisPress || !inside -> PowerRelease.NOTHING
        off -> PowerRelease.TURN_ON
        else -> PowerRelease.HINT
      }

  /** How far round the button's ring is after [heldMs] (0…1). */
  fun powerProgress(heldMs: Long): Float = (heldMs.toFloat() / POWER_HOLD_MS).coerceIn(0f, 1f)

  /**
   * The rotate button (Alex 14 Nov 2026: "make sure the rotate button rotates by 90, 180, 270, 360 degrees"): the app
   * opens upright (0), and each tap turns the screen a further quarter turn clockwise: 90, 180, 270, then upright
   * again (360 = 0). Anything else given is taken round to the nearest quarter below it first.
   */
  fun nextTurn(degrees: Int): Int = (Math.floorMod(degrees, 360) / 90 * 90 + 90) % 360

  /** Whether a leader's last fix (epoch seconds) is recent enough to show. */
  fun leaderFresh(lastFixS: Long, nowS: Long): Boolean = nowS - lastFixS <= LEADER_STALE_S

  /** Contact id of a location that arrived over HTTP: the topic in the body, else upstream's pseudo topic. */
  fun incomingTopic(visibleTopic: String, trackerId: String?): String =
      visibleTopic.ifBlank { "owntracks/http/" + (trackerId ?: "") }

  // ── Wake-up circle names ────────────────────────────────────────────────────────────────
  // "FOLLO <AREA> wake <n> [yyyy-mm-dd …]" (server wakeWaypoints). The word after <n> is the day the area's circles were
  // last written from the live map (Edit Circles, 1 Nov 2026); older names have no 4th word, iOS local builds may
  // carry a "|2|2" suffix. Only words 1-3 decide whether it is a wake circle and which area it belongs to.

  private val WAKE_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

  /** "FOLLO MV wake 3 2026-11-01" -> "MV"; null when the name is not a wake-up circle's. */
  fun wakeArea(description: String): String? =
      description.split(' ').takeIf { it.size >= 3 && it[0] == "FOLLO" && it[2] == "wake" }?.get(1)

  /** The circles' date (yyyy-mm-dd, the word after the circle number) of a wake-up circle name; null when absent or not a date. */
  fun wakeDate(description: String): String? =
      description.split(' ').takeIf { wakeArea(description) != null && it.size >= 5 }?.get(4)?.takeIf { WAKE_DATE.matches(it) }

  /**
   * The newest circles' date among the held wake-up circle names, as the Ready line shows it: d-M-yyyy with no
   * leading zeros ("2026-11-01" -> "1-11-2026"), the same on iOS; null when no name carries a date.
   */
  fun readyUpdate(descriptions: List<String>): String? =
      descriptions.mapNotNull(::wakeDate).maxOrNull()?.let { iso ->
        val (y, m, d) = WAKE_DATE.find(iso)!!.destructured
        "${d.toInt()}-${m.toInt()}-$y"
      }

  /** The circles' day on the bottom line (13 Nov 2026): [readyUpdate] with dots, "9.11.2026"; the same on iOS. */
  fun circlesDay(descriptions: List<String>): String? = readyUpdate(descriptions)?.replace('-', '.')

  /** The server's clock, short enough for the banner's one line: "5:00 pm" -> "5pm", "5:30 pm" -> "5:30pm". */
  fun shortClock(clock: String?): String =
      (clock ?: "").trim().replace(":00", "").replace(" am", "am").replace(" pm", "pm")

  // ── "No fix on open" (Alex, 1 Nov 2026) ─────────────────────────────────────────────────
  // A check-in that the phone makes by itself (app opened, base camp's check-in push, new push token) only sends a
  // position when the phone is INSIDE a held wake-up circle (search circles and the Basecamp circle alike) or inside
  // the held boundary outline. Otherwise it sends a location-free "hello", and once the reply is applied it judges
  // again with the same fix: inside a newly received circle/boundary => the position goes after all, else nothing.
  // The long-press "check in now" is the person's own act and is not judged.

  /** A wake-up circle: centre and radius in metres. */
  data class Circle(val lat: Double, val lon: Double, val r: Double, val area: String = "")

  /** One of this phone's search windows (status `wins`, 1 Nov 2026): area code, start and end in epoch seconds. */
  data class Window(val area: String, val start: Long, val end: Long) {
    fun openAt(t: Long): Boolean = t >= start && t < end
  }

  /**
   * Only what counts NOW (Alex, 1 Nov 2026: "before each post it needs to make sure it's within the time window for
   * the search"): circles of an area with one of this phone's windows open at [nowS] (a Basecamp circle: any window
   * open), and the boundary only while a window is open and its own window ([ends]) not over. No windows known (an
   * older server) ⇒ nothing counts: the fix is not sent and the hello's reply brings the windows.
   */
  fun timedArea(
      circles: List<Circle>,
      boundary: List<Pair<Double, Double>>?,
      ends: Long?,
      wins: List<Window>?,
      nowS: Long,
  ): Pair<List<Circle>, List<Pair<Double, Double>>?> {
    val open = wins.orEmpty().filter { it.openAt(nowS) }
    if (open.isEmpty()) return emptyList<Circle>() to null
    val areas = open.map { it.area }.toSet()
    val ring = if (ends != null && nowS >= ends) null else boundary
    return circles.filter { it.area in areas || it.area == "Basecamp" } to ring
  }

  /** A circle name a [Circle] is taken from: "FOLLO <AREA> wake <n> …" with a radius above 0. */
  fun isWakeCircle(description: String, radius: Int): Boolean = radius > 0 && wakeArea(description) != null

  /** Metres between two points (haversine). */
  fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_008.8
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  }

  /**
   * On the edge counts as inside. No slack (2 Nov 2026, Alex: "just use the circles (aka green lines)"): the server
   * judges the circles with the same haversine on the same sphere (R 6371008.8 m), so the phone's line is the server's.
   */
  fun insideCircle(lat: Double, lon: Double, c: Circle): Boolean = distanceM(lat, lon, c.lat, c.lon) <= c.r

  /** Ray cast, the same rule the map uses for the edge colour; a ring of fewer than 3 points holds nothing. */
  fun insideRing(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
    if (ring.size < 3) return false
    var c = false
    var j = ring.size - 1
    for (i in ring.indices) {
      val (yi, xi) = ring[i]
      val (yj, xj) = ring[j]
      if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
      j = i
    }
    return c
  }

  /** Inside any held wake-up circle or the held boundary (lat, lon pairs). Nothing held => false. */
  fun insideSearch(lat: Double, lon: Double, circles: List<Circle>, boundary: List<Pair<Double, Double>>?): Boolean =
      circles.any { insideCircle(lat, lon, it) } || (boundary != null && insideRing(boundary, lat, lon))

  enum class CheckInAction {
    /** Inside: post the position as before. */
    POST_FIX,
    /** Outside or nothing held: post a location-free hello, then judge again when its reply has been applied. */
    HELLO,
    /** Still outside after the hello's reply: nothing more leaves the phone. */
    NOTHING,
  }

  // ── Never a position from outside the search (Alex, 1 Nov 2026) ─────────────────────────────
  // Every position the phone would post is judged first (LocationProcessor.publishLocationMessage): outside every held
  // circle and the held boundary ⇒ it is NOT sent, the phone stands itself by and says so with a location-free hello.

  /** A hello for a dropped fix at most this often while the phone stays outside (the first one goes at once). */
  const val OUT_HELLO_EVERY_MS = 15 * 60 * 1000L

  /**
   * The mode a phone puts itself in when a fix is outside the search — the server's own rule for a phone outside
   * (helloPost/ingest): standing by (Manual) when it holds circles to wake it, else Significant when it was moving.
   * null = leave the mode as it is.
   */
  fun standbyMode(current: MonitoringMode, holdsCircles: Boolean): MonitoringMode? =
      when {
        current == MonitoringMode.Quiet || current == MonitoringMode.Manual -> null
        holdsCircles -> MonitoringMode.Manual
        current == MonitoringMode.Move -> MonitoringMode.Significant
        else -> null
      }

  /** Whether a dropped fix should be followed by a hello: none yet since the phone was last inside, or long enough ago. */
  fun outHelloDue(lastOutHelloMs: Long?, nowMs: Long): Boolean =
      lastOutHelloMs == null || nowMs - lastOutHelloMs >= OUT_HELLO_EVERY_MS

  // ── Edge hold (Alex's walk 2 Nov 2026) ────────────────────────────────────────────────────────
  // A TEST circle's edge ran along the road: one fix 2 m out stood the phone by and the walk back 1–4 m INSIDE was never
  // recorded. Alex: "Ok to cache some locally … they stay on phone and are discarded if they prove not to be within the
  // circle the next time it starts transmitting or it's been 2 minutes or 50m (whichever occurs sooner)". In Move an
  // outside fix is held on the phone (never sent) and tracking carries on; the next fix inside sends the held ones that
  // are inside what the phone then holds and drops the rest; 2 min outside or 50 m beyond the nearest edge drops them all
  // and stands the phone by as before.

  const val HOLD_FOR_MS = 2 * 60 * 1000L
  const val HOLD_BEYOND_M = 50.0
  const val HOLD_CAP = 200

  enum class GateVerdict { SHARE, HOLD, STANDBY }

  /** Metres beyond the nearest held circle edge or boundary side; 0 inside; infinity when nothing is held. */
  fun outsideByM(lat: Double, lon: Double, circles: List<Circle>, boundary: List<Pair<Double, Double>>?): Double {
    if (insideSearch(lat, lon, circles, boundary)) return 0.0
    var best = Double.POSITIVE_INFINITY
    for (c in circles) best = minOf(best, distanceM(lat, lon, c.lat, c.lon) - c.r)
    if (boundary != null && boundary.size >= 2) {
      val ky = 111_320.0
      val kx = ky * Math.cos(Math.toRadians(lat))
      var j = boundary.size - 1
      for (i in boundary.indices) {
        val ax = (boundary[j].second - lon) * kx
        val ay = (boundary[j].first - lat) * ky
        val bx = (boundary[i].second - lon) * kx
        val by = (boundary[i].first - lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
        j = i
      }
    }
    return maxOf(best, 0.001)
  }

  /** The edge-hold rule: [beyondM] outside, outside since [sinceMs], in mode [mode]; a check-in never holds. */
  fun gateVerdict(beyondM: Double, sinceMs: Long?, nowMs: Long, mode: MonitoringMode, checkIn: Boolean = false): GateVerdict =
      when {
        beyondM <= 0.0 -> GateVerdict.SHARE
        checkIn || mode != MonitoringMode.Move || beyondM >= HOLD_BEYOND_M -> GateVerdict.STANDBY
        sinceMs != null && nowMs - sinceMs >= HOLD_FOR_MS -> GateVerdict.STANDBY
        else -> GateVerdict.HOLD
      }

  // ── Near-circle watch (Alex's walk 2 Nov 2026) ───────────────────────────────────────────────
  // FOLLO-R in a pocket: Play services checked the circles only every ~5 min (network fixes, no GPS), so walking in
  // started nothing until the screen came on. Alex: "make it 100m from a circle … it's just locally trying to work out
  // if it's inside or outside". While a search window is open and the phone stands by holding circles it watches its
  // own position: within 100 m of a circle edge a GPS fix every 30 s, further out a rough (Wi-Fi/tower) fix every 2 min
  // just to notice the approach. The fixes are only judged on the phone, never sent or kept; the first good one inside
  // starts tracking exactly as a circle crossing does.

  const val NEAR_WATCH_M = 100.0
  const val NEAR_WATCH_EVERY_S = 30L
  const val FAR_WATCH_EVERY_S = 120L
  /** A watch fix starts tracking only this good (a rough network fix inside waits for the next GPS one). */
  const val NEAR_WATCH_START_ACC_M = 30f

  enum class WatchStage { OFF, FAR, NEAR }

  /** The watch's next stage after a fix [beyondM] outside (0 inside; infinity = nothing counts now) with [accM]. */
  fun watchStage(beyondM: Double, accM: Float): WatchStage =
      when {
        beyondM.isInfinite() -> WatchStage.OFF
        beyondM - accM <= NEAR_WATCH_M -> WatchStage.NEAR
        else -> WatchStage.FAR
      }

  /** Whether a watch fix starts tracking: inside, and good enough to trust. */
  fun watchStarts(beyondM: Double, accM: Float): Boolean = beyondM <= 0.0 && accM <= NEAR_WATCH_START_ACC_M

  /** What an automatic check-in does with its fix; [secondStep] = judging again after the hello's reply. */
  fun checkInAction(inside: Boolean, secondStep: Boolean): CheckInAction =
      when {
        inside -> CheckInAction.POST_FIX
        secondStep -> CheckInAction.NOTHING
        else -> CheckInAction.HELLO
      }

  // ── Check in whatever the GPS does (Alex, 15 Nov 2026: "it should be able to get to the latest version regardless
  // of the GPS situation"). FOLLO-L, indoors with no fix, never checked in: every automatic check-in waited for a fix
  // under 100 m before anything left, even the location-free hello that carries the version and fetches the circles.

  /** How long an automatic check-in (open, push, token) waits for a usable fix before the location-free hello goes. */
  const val NO_FIX_HELLO_AFTER_MS = 15_000L
  /** A check-in fix must be better than this… */
  const val CHECK_IN_FIX_MAX_ACC_M = 100f
  /** …and no older than this (a cached fix from hours or days ago is not where the phone is). */
  const val CHECK_IN_FIX_MAX_AGE_MS = 60_000L

  /** Whether a fix may be used by an automatic check-in. */
  fun usableCheckInFix(hasAccuracy: Boolean, accM: Float, ageMs: Long): Boolean =
      hasAccuracy && accM < CHECK_IN_FIX_MAX_ACC_M && ageMs <= CHECK_IN_FIX_MAX_AGE_MS

  /** A usable fix that arrives after the no-fix hello went. */
  enum class LateFix {
    /** the hello's reply is still on its way: the fix waits for it and is judged once the new circles are in */
    KEEP_FOR_REPLY,
    /** the reply has been applied: judged now as the second step (inside → the position goes, outside → nothing) */
    JUDGE_NOW,
  }

  fun lateFix(replyPending: Boolean): LateFix = if (replyPending) LateFix.KEEP_FOR_REPLY else LateFix.JUDGE_NOW

  /** One automatic check-in per [OPEN_CHECK_IN_DEDUPE_MS], except that one made before location was allowed doesn't
   *  hold back the first one after (that one can take a fix). */
  const val OPEN_CHECK_IN_DEDUPE_MS = 10_000L

  fun openCheckInDue(sinceLastMs: Long, lastHadLocation: Boolean, hasLocation: Boolean): Boolean =
      sinceLastMs >= OPEN_CHECK_IN_DEDUPE_MS || (hasLocation && !lastHadLocation)

  /** The screen's own fix is older than this: not shown as "your position" (FOLLO-L showed a 7-day-old Tasmanian fix). */
  const val STALE_FIX_MS = 5 * 60_000L

  /** [ageMs] null = no fix at all. A fix from the future (clock change) counts as fresh. */
  fun fixIsStale(ageMs: Long?): Boolean = ageMs == null || ageMs > STALE_FIX_MS

  // ── Screen, round 2 (Alex, 14 Nov 2026; wording page version 21) ────────────────────────────────

  /** A local clock time, short: 6:00 → "6am", 18:30 → "6:30pm". */
  fun shortTime(epochS: Long, zone: java.time.ZoneId): String {
    val t = java.time.Instant.ofEpochSecond(epochS).atZone(zone)
    val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    val ampm = if (t.hour < 12) "am" else "pm"
    return if (t.minute == 0) "$h12$ampm" else "$h12:${"%02d".format(t.minute)}$ampm"
  }

  /**
   * The search hours for the "Waiting until you're in the search area (6am-6pm)" banner (tile 3, which since 14 Nov
   * 2026 also stands in for the old "no search right now" and plain "Standing by" banners): the window open now, else
   * the next one, in the phone's own time zone. Windows of [preferArea] (the area of the nearest held circle) win, so a
   * phone in Tasmania is not shown a Queensland window an hour off. Null when no window is known.
   */
  fun hoursText(windows: List<Window>?, nowS: Long, zone: java.time.ZoneId, preferArea: String? = null): String? {
    val ahead = windows.orEmpty().filter { it.end > nowS && it.end > it.start }
    if (ahead.isEmpty()) return null
    val pool = ahead.filter { it.area == preferArea }.ifEmpty { ahead }
    val w = pool.filter { it.openAt(nowS) }.minByOrNull { it.start } ?: pool.minByOrNull { it.start } ?: return null
    return "${shortTime(w.start, zone)}-${shortTime(w.end, zone)}"
  }

  /** First tick: a FOLLO status element arrived today (local date), so the phone holds today's circles. */
  fun checkedInToday(receivedAtMs: Long, nowMs: Long, zone: java.time.ZoneId): Boolean =
      receivedAtMs > 0L &&
          java.time.Instant.ofEpochMilli(receivedAtMs).atZone(zone).toLocalDate() ==
              java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

  /** Third tick: this build is the newest the volunteers can install (status `latest`); not known ⇒ yes. */
  fun isLatest(ownCode: Long, latestCode: Long?): Boolean = latestCode == null || ownCode >= latestCode

  const val TICK_OK = "✅"
  const val TICK_WARN = "⚠️"

  /** The offline map as the check text tells it. */
  data class MapCheck(val saved: Boolean, val savingPercent: Int? = null, val waitingForWifi: Boolean = false)

  /**
   * The text the ticks share (Alex, 14 Nov 2026: "they may want to SMS me their version number, circle date, and other
   * diagnostic info"). Nothing about where the phone is.
   */
  fun checkText(
      phone: String,
      device: String,
      os: String,
      statusAtMs: Long,
      nowMs: Long,
      zone: java.time.ZoneId,
      map: MapCheck,
      version: String,
      versionCode: Long,
      latestCode: Long?,
      latestVersion: String?,
      circlesDay: String? = null,
  ): String {
    val today = checkedInToday(statusAtMs, nowMs, zone)
    // the held circles' own date from Edit Circles (Alex asked for the "circle date"), when they carry one
    val set = circlesDay?.let { " (circles of $it)" } ?: ""
    val circles =
        when {
          today -> "$TICK_OK Circles: checked in today, ${clock(statusAtMs, zone)}$set"
          statusAtMs > 0L -> "$TICK_WARN Circles: last checked in ${dayClock(statusAtMs, zone)}$set"
          else -> "$TICK_WARN Circles: never checked in"
        }
    val offline =
        if (map.saved) "$TICK_OK Offline map: on this phone"
        else
            "$TICK_WARN Offline map: not on this phone" +
                when {
                  map.savingPercent != null -> ", saving ${map.savingPercent}%"
                  map.waitingForWifi -> ", waiting for Wi-Fi"
                  else -> ""
                }
    val app =
        when {
          latestCode == null -> "$TICK_OK App: $version"
          versionCode >= latestCode -> "$TICK_OK App: $version, the latest"
          else -> "$TICK_WARN App: $version, latest is ${latestVersion ?: latestCode.toString()}"
        }
    // Same order as the ticks on screen (Alex 15 Nov 2026: "✅app ✅⭕ ✅map")
    return listOf("FOLLO Tracks check", "Phone: $phone ($device, $os)", app, circles, offline).joinToString("\n")
  }

  private fun clock(ms: Long, zone: java.time.ZoneId): String =
      java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)
          .format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
          .lowercase(java.util.Locale.ENGLISH)

  private fun dayClock(ms: Long, zone: java.time.ZoneId): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    return java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH).format(t) + ", " + clock(ms, zone)
  }

  // ── The phone's own position (Alex 13 Nov 2026: "Need GDA2020 coordinates somewhere. Long press on them will offer
  // OS's share option"; 14 Nov: the pill drops the "GDA2020" word, the shared text keeps it; 15 Nov: the pill shows
  // MGA "526599 E, 5252393 N" rather than a "-" and decimals) ─────────────────────────────────────────────────

  /** Latitude and longitude, 6 decimals: "-41.123456, 146.654321". */
  fun latLonText(lat: Double, lon: Double): String =
      String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon)

  /** The pill over the map: MGA easting and northing to the metre, "526599 E, 5252393 N" (no zone, no separators). */
  fun coordsText(lat: Double, lon: Double): String {
    val g = mga(lat, lon)
    return "${Math.round(g.easting)} E, ${Math.round(g.northing)} N"
  }

  /** What a long press shares: "MGA55 526599 E, 5252393 N (GDA2020 -42.880600, 147.325700)". */
  fun coordsShare(lat: Double, lon: Double): String {
    val g = mga(lat, lon)
    return "MGA${g.zone} ${coordsText(lat, lon)} (GDA2020 ${latLonText(lat, lon)})"
  }

  data class Grid(val zone: Int, val easting: Double, val northing: Double)

  /**
   * MGA2020 (UTM on GRS80) for a GDA2020 latitude and longitude, zone from the longitude (55 Tasmania, 56 south-east
   * Queensland). Krüger series to the 4th order (Karney 2011): well under a millimetre inside a zone. The phone's fix is
   * taken as GDA2020 (it differs from WGS84 by centimetres).
   */
  fun mga(lat: Double, lon: Double): Grid {
    val zone = Math.floorDiv((lon + 180.0).toInt(), 6) + 1
    val lon0 = Math.toRadians(zone * 6.0 - 183.0)
    val a = 6378137.0
    val f = 1 / 298.257222101
    val k0 = 0.9996
    val n = f / (2 - f)
    val n2 = n * n
    val n3 = n2 * n
    val n4 = n3 * n
    val bigA = a / (1 + n) * (1 + n2 / 4 + n4 / 64)
    val alpha =
        doubleArrayOf(
            n / 2 - 2.0 / 3 * n2 + 5.0 / 16 * n3 + 41.0 / 180 * n4,
            13.0 / 48 * n2 - 3.0 / 5 * n3 + 557.0 / 1440 * n4,
            61.0 / 240 * n3 - 103.0 / 140 * n4,
            49561.0 / 161280 * n4,
        )
    val phi = Math.toRadians(lat)
    val dl = Math.toRadians(lon) - lon0
    val e2n = 2 * kotlin.math.sqrt(n) / (1 + n)
    val t = kotlin.math.sinh(atanhOf(kotlin.math.sin(phi)) - e2n * atanhOf(e2n * kotlin.math.sin(phi)))
    val xi0 = kotlin.math.atan2(t, kotlin.math.cos(dl))
    val eta0 = atanhOf(kotlin.math.sin(dl) / kotlin.math.sqrt(1 + t * t))
    var xi = xi0
    var eta = eta0
    for (j in 1..4) {
      xi += alpha[j - 1] * kotlin.math.sin(2 * j * xi0) * kotlin.math.cosh(2 * j * eta0)
      eta += alpha[j - 1] * kotlin.math.cos(2 * j * xi0) * kotlin.math.sinh(2 * j * eta0)
    }
    val easting = 500000.0 + k0 * bigA * eta
    val northing = (if (lat < 0) 10000000.0 else 0.0) + k0 * bigA * xi
    return Grid(zone, easting, northing)
  }

  private fun atanhOf(x: Double): Double = 0.5 * kotlin.math.ln((1 + x) / (1 - x))
}

