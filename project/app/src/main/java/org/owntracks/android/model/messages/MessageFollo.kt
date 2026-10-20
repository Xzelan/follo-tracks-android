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
  /** The other line leader of this phone's team, when it has exactly two: the map draws the line between them. */
  var pair: FolloPair? = null

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
