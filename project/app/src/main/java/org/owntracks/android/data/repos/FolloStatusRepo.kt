package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageFollo
import timber.log.Timber

/**
 * The last FOLLO status element the server sent, when it arrived, and when the server last accepted
 * a position. Persisted, so the banner says the right thing straight after a reboot or an update
 * instead of guessing until the next post.
 */
@Singleton
class FolloStatusRepo
@Inject
constructor(@ApplicationContext context: Context, private val parser: Parser) {
  data class Status(val message: MessageFollo, val receivedAt: Long)

  private val prefs = context.getSharedPreferences("follo_status", Context.MODE_PRIVATE)

  val status = MutableStateFlow(load())

  /** Epoch ms of the last location the server actually accepted (0 = none yet). */
  val lastSentAt = MutableStateFlow(prefs.getLong(KEY_LAST_SENT, 0L))

  fun setStatus(message: MessageFollo, receivedAt: Long = System.currentTimeMillis()) {
    status.value = Status(message, receivedAt)
    try {
      prefs.edit().putString(KEY_JSON, parser.toJson(message)).putLong(KEY_AT, receivedAt).apply()
    } catch (e: Exception) {
      Timber.w(e, "Could not persist FOLLO status")
    }
  }

  fun markSent(at: Long = System.currentTimeMillis()) {
    lastSentAt.value = at
    prefs.edit().putLong(KEY_LAST_SENT, at).apply()
  }

  private fun load(): Status? {
    val json = prefs.getString(KEY_JSON, null) ?: return null
    val at = prefs.getLong(KEY_AT, 0L)
    return try {
      (parser.fromJson(json) as? MessageFollo)?.let { Status(it, at) }
    } catch (e: Exception) {
      Timber.w(e, "Could not read the saved FOLLO status")
      null
    }
  }

  companion object {
    private const val KEY_JSON = "status_json"
    private const val KEY_AT = "status_at"
    private const val KEY_LAST_SENT = "last_sent_at"
  }
}
