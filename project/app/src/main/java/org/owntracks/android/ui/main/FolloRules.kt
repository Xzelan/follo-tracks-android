package org.owntracks.android.ui.main

import org.owntracks.android.model.messages.MessageConfiguration
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
}
