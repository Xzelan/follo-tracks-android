package org.owntracks.android.data.repos

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import org.owntracks.android.model.messages.MessageFollo

/** The last FOLLO status element the server sent, and when it arrived. */
@Singleton
class FolloStatusRepo @Inject constructor() {
  data class Status(val message: MessageFollo, val receivedAt: Long)

  val status = MutableStateFlow<Status?>(null)

  /** Epoch ms of the last location the server actually accepted (0 = none yet). */
  val lastSentAt = MutableStateFlow(0L)
}
