package org.owntracks.android.model.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

  override fun toString(): String = "[MessageFollo state=$state]"

  companion object {
    const val TYPE = "follo"
  }
}
