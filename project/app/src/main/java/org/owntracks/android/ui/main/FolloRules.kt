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
}
