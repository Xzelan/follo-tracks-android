package org.owntracks.android.model.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * FOLLO "no fix on open" (Alex, 1 Nov 2026): what an automatic check-in posts INSTEAD of a position
 * when the phone is not inside a held wake-up circle or boundary (or holds none yet). Same shape as
 * a location post minus the position: `src` says what caused it (open | push | token), `m` the
 * monitoring mode, `tid` the tracker ID, `fd` the same diagnostics a location post carries. NEVER
 * lat/lon/acc/alt/vel/tst: the server refuses a hello that has any. The reply is a normal reply
 * array (setConfiguration, clearWaypoints/setWaypoints, the `follo` status) and is applied like any
 * other; then the phone judges its fix again (LocationProcessor.onHelloAnswered).
 */
@Serializable
@SerialName(MessageHello.TYPE)
class MessageHello(
    /** open (app came to the front / set up / permission granted), push (base camp's check-in ask), token (new FCM token). */
    var src: String = "",
    /** Monitoring mode as in a location post's `m` (0 Manual, 1 Significant, 2 Move). */
    @SerialName("m") var m: Int? = null,
    @SerialName("tid") var tid: String? = null,
    @SerialName("fd") var fd: FolloDiag? = null,
) : MessageBase(), MessageWithId {
  @Transient override var messageId: MessageId = ZeroMessageId

  override fun toString(): String = "[MessageHello src=$src]"

  companion object {
    const val TYPE = "hello"
  }
}
