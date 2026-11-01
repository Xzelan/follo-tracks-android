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

  /** On the edge counts as inside (1 m of slack: the server's and the phone's geometry differ by less). */
  fun insideCircle(lat: Double, lon: Double, c: Circle): Boolean = distanceM(lat, lon, c.lat, c.lon) <= c.r + 1.0

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

  /** What an automatic check-in does with its fix; [secondStep] = judging again after the hello's reply. */
  fun checkInAction(inside: Boolean, secondStep: Boolean): CheckInAction =
      when {
        inside -> CheckInAction.POST_FIX
        secondStep -> CheckInAction.NOTHING
        else -> CheckInAction.HELLO
      }
}
