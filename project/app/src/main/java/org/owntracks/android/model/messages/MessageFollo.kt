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
  /** Search name, e.g. "Michael Vrankovic search". */
  var search: String? = null
  /** Local time the current window closes, already formatted, e.g. "5:00 pm". */
  var until: String? = null
  /** Next window, already formatted, e.g. "Fri 6 Nov, 8:00 am". */
  var next: String? = null
  /** Optional complete second line that overrides the phone's wording. */
  var line: String? = null
  /** Volunteer's name as the roster has it, for the card. */
  var name: String? = null
  /** Volunteer's tracker ID as the roster has it (3 characters since 24 Oct 2026, e.g. JE1); the phone's own is only 2. */
  var tid: String? = null
  /** XYZ tile URL template for the map; applied when it changes. */
  var tile: String? = null
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
  /** Superseded by [leaders] (21 Oct 2026); still parsed so older server replies load, no longer used. */
  var pair: FolloPair? = null
  /**
   * This phone's own team's line leaders (FOLLO server 21 Oct 2026). The map shows only these people (besides the
   * phone itself), and when there are exactly two it draws the line between them, for every member of the team.
   */
  var leaders: List<FolloLeader>? = null
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
