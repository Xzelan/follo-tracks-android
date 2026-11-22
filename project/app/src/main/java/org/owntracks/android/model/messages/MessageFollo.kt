package org.owntracks.android.model.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * FOLLO's status element, sent by the FOLLO server in the reply to a location post alongside the
 * usual OwnTracks commands. Stock OwnTracks apps ignore it (unknown `_type`). It drives the second
 * line of the status banner; the phone decides the red "action needed" states itself.
 *
 * `state`: `sharing` (inside an open search window and fence), `outside` (window open, phone
 * outside the fence), `nosearch` (no window now), `off` (phone not on any roster).
 */
@Serializable
@SerialName(MessageFollo.TYPE)
class MessageFollo(@Transient private val messageWithId: MessageWithId = MessageWithRandomId()) :
    MessageBase(), MessageWithId {
  @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
  @SerialName("_id")
  override var messageId: MessageId = messageWithId.messageId

  var state: String? = null
  /** Search name, e.g. "Search One search". */
  var search: String? = null
  /** Local time the current window closes, already formatted, e.g. "5:00 pm". */
  var until: String? = null
  /** Next window, already formatted, e.g. "Fri 6 Nov, 8:00 am". */
  var next: String? = null
  /** Optional complete second line that overrides the phone's wording. */
  var line: String? = null
  /** Volunteer's name as the roster has it, for the card. */
  var name: String? = null
  /** The volunteer's team as the roster has it ("Blue"): its search grids are drawn bold (10 Nov 2026). */
  var team: String? = null
  /**
   * Version of the search grids the server holds for this phone (10 Nov 2026), "0" = none. The grids themselves come
   * from GET /grid when this differs from the version the phone holds (FolloGridRepo). Null = an older server.
   */
  var gridv: String? = null
  /**
   * Version of the drawings base camp put on THIS phone's map (12 Nov 2026), "0" = none. The drawings themselves come
   * from GET /draw when this differs from the version the phone holds (FolloDrawRepo). Null = an older server, or the
   * server could not tell just now: the phone keeps what it has.
   */
  var drawv: String? = null
  /**
   * Version of this phone's layer list (19 Nov 2026, simple screen: hillshade, slope, contours, POI, tracklogs), "0" or
   * null = no layers. The list itself comes from GET /lay when this differs from the version the phone holds
   * (FolloLayRepo).
   */
  var layv: String? = null
  /**
   * Offline map (10 Nov 2026): which areas' circles to save imagery for and how much. Null = save nothing (what is
   * saved already stays).
   */
  var off: FolloOff? = null
  /** Volunteer's tracker ID as the roster has it (3 characters since 24 Oct 2026, e.g. JE1); the phone's own is only 2. */
  var tid: String? = null
  /** XYZ tile URL template for the map; applied when it changes. */
  var tile: String? = null
  /**
   * The deepest zoom [tile] has tiles for (server 24 Oct 2026): LIST stops at 19 and answers deeper requests with a
   * blank tile, so the map enlarges this level instead. Absent ⇒ 20, as before.
   */
  var tilez: Int? = null
  /**
   * The search area's storage box "minlat,minlon,maxlat,maxlon" while a window is open for this
   * phone: drawn on the map, and used by the phone itself to go green/grey at the edge even when
   * it has no signal. Null when no window is open.
   */
  var box: String? = null
  /** Epoch seconds the open window closes; the phone stops trusting [box] after that. */
  var ends: Long? = null
  /**
   * The area's drawn boundary as [[lat, lon], …] when it has one (FOLLO server 20 Oct 2026): it replaces [box] as the
   * line where sharing starts and stops, and is what the map draws.
   */
  var poly: List<List<Double>>? = null
  /**
   * Version of the boundary the server holds (21 Oct 2026). Sent with every reply; [poly] itself only comes when the
   * phone's last post did not acknowledge this version (`fd.pv`), so a 10 KB outline is not repeated every 5 s.
   */
  var polyv: String? = null
  /**
   * This phone's search windows (1 Nov 2026): [[area, start, end], …] in epoch seconds, recon days included. A fix goes
   * only while one is open (FolloRules.timedArea). Null = a server older than this build.
   */
  var wins: List<List<kotlinx.serialization.json.JsonPrimitive>>? = null

  /** [wins] as windows; malformed rows are skipped. Null when the server sent none. */
  fun windows(): List<org.owntracks.android.ui.main.FolloRules.Window>? =
      wins?.mapNotNull { row ->
        val area = row.getOrNull(0)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val start = row.getOrNull(1)?.content?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        val end = row.getOrNull(2)?.content?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        org.owntracks.android.ui.main.FolloRules.Window(area, start, end)
      }
  /**
   * The search areas the server already holds this phone's fixes for (FP055, 19 Nov 2026): [[area, newest fix epoch
   * seconds], …], only areas this phone is allowed in. What the screen shows when the phone is not in a search area
   * (FolloScope); merged into the phone's own record, which is never sent. Null = none, or an older server.
   */
  var been: kotlinx.serialization.json.JsonElement? = null

  /** [been] as (area, time) rows; anything that is not a [string, number] row is skipped. */
  fun beenRows(): List<Pair<String, Long>> =
      (been as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { row ->
        val r = row as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
        val area = (r.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val t = (r.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        area to t
      }

  /**
   * The newest builds the volunteers can install (server 14 Nov 2026): the third tick at the bottom right says whether
   * this phone has it. Null = an older server, or not known.
   */
  var latest: FolloLatest? = null
  /**
   * 1 = this server takes "Share everywhere I go" posts from this phone (22 Nov 2026): the row is offered in the
   * Searches drop-down. Absent / anything else = it does not; a status that arrives without it switches the mode off.
   */
  var ev: Int? = null
  /** Superseded by [leaders] (21 Oct 2026); still parsed so older server replies load, no longer used. */
  var pair: FolloPair? = null
  /**
   * This phone's own team's line leaders (FOLLO server 21 Oct 2026). The map shows only these people (besides the
   * phone itself), and when there are exactly two it draws the line between them, for every member of the team.
   */
  var leaders: List<FolloLeader>? = null
  /**
   * 1 = this phone may see every phone in a search circle and every live-map search (GET /all; server SEE_ALL, Alex's
   * iPhone 13 Nov 2026, the "Mobile Live Map" Samsung 18 Nov 2026). Absent = not this phone.
   */
  var all: Int? = null
  /**
   * Who this phone draws (server 21 Oct 2026, Alex: "a line leader should see all their team"): a leader gets every
   * teammate, a member gets the team's line leaders; never itself. Absent on older servers ⇒ fall back to [leaders].
   */
  var show: List<FolloLeader>? = null

  /**
   * Carries the boundary over from the [previous] status when this reply names the same version but leaves the
   * outline out: the server only repeats [poly] when the phone has not acknowledged [polyv]. A reply naming a
   * different version without the outline leaves [poly] empty, so nothing stale is drawn until the next reply.
   */
  fun carryPolyFrom(previous: MessageFollo?) {
    if (poly == null && polyv != null && previous?.poly != null && previous.polyv == polyv) poly = previous.poly
  }

  /** The version of the boundary this status actually holds, for the `fd.pv` acknowledgement. */
  fun heldPolyVersion(): String? = if (poly != null) polyv else null

  /** [poly] as (lat, lon) points, or null if absent or malformed. */
  fun polyPoints(): List<Pair<Double, Double>>? =
      poly?.mapNotNull { p -> if (p.size >= 2) p[0] to p[1] else null }?.takeIf { it.size >= 3 }

  /** [box] as (minLat, minLon, maxLat, maxLon), or null if absent or malformed. */
  fun boxBounds(): DoubleArray? =
      box?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray()

  override fun toString(): String = "[MessageFollo state=$state]"

  companion object {
    const val TYPE = "follo"
  }
}

/**
 * The offline map's settings, all the server's: [z] = area code → deepest zoom to save, [km] = only areas whose nearest
 * circle is this close to the phone, [cap] = most tiles in total, [free] = most tiles fetched on a metered connection
 * without asking.
 */
@Serializable
class FolloOff {
  var z: Map<String, Int>? = null
  var km: Double? = null
  var cap: Int? = null
  var free: Int? = null
}

/** Newest volunteer builds (D1 meta 'appver'): TestFlight external build, Play closed-testing versionCode and name. */
@Serializable
class FolloLatest {
  var ios: Long? = null
  var android: Long? = null
  var androidv: String? = null
  /** Since when (epoch seconds) the iOS / Android build named here has been the newest the volunteers can install
   *  (22 Nov 2026, FP083: an older build is "in progress" for 48 h, then "wrong"). Null = not known. */
  var iat: Long? = null
  var aat: Long? = null
}

/** The pair partner: its tracker ID and its location topic (= the teammate's contact id on this phone). */
@Serializable
class FolloPair {
  var tid: String? = null
  var topic: String? = null
}

/** One of the team's line leaders: tracker ID, location topic (= contact id), and whether it is this phone. */
@Serializable
class FolloLeader {
  var tid: String? = null
  var topic: String? = null
  @SerialName("self") var isSelf: Boolean = false
}
