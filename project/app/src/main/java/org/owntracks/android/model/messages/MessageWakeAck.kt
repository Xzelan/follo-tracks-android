package org.owntracks.android.model.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * FOLLO Edit Circles (1 Nov 2026): the location-free answer to a `wake` push. Base camp changed a
 * search area's wake-up circles; the FOLLO server pushed `{act: "wake", area, v}` (FCM data, no
 * notification) and the phone posts this to its usual endpoint instead of a position. The reply is
 * the same as to a location post (clearWaypoints + setWaypoints + the `follo` status with the new
 * boundary) and is processed the same way. It carries NO lat/lon/acc, ever: the server refuses one
 * that does (Alex: "I really want to be able to honestly say it doesn't get their location outside
 * of the search area"). `fd.pv` = the boundary version the phone holds, like a location's `fd.pv`.
 */
@Serializable
@SerialName(MessageWakeAck.TYPE)
class MessageWakeAck(
    /** Area code from the push, e.g. "MV". */
    var area: String = "",
    /** The circles' version from the push (wake_at ms as a string); echoed so the server can match it. */
    var v: String = "",
    /** Only `pv` is ever set here; null (omitted) when the phone holds no boundary. */
    @SerialName("fd") var fd: FolloDiag? = null,
) : MessageBase(), MessageWithId {
  // No id on the wire: the server does not use one and the queue copes with "0" (as MessageClear).
  @Transient override var messageId: MessageId = ZeroMessageId

  override fun toString(): String = "[MessageWakeAck area=$area v=$v]"

  companion object {
    const val TYPE = "wakeack"

    fun of(area: String, v: String, heldPolyVersion: String?): MessageWakeAck =
        MessageWakeAck(area = area, v = v, fd = heldPolyVersion?.let { FolloDiag(pv = it) })
  }
}
