package org.owntracks.android.data.repos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * FOLLO push "check in" (29 Oct 2026): the phone's Firebase Cloud Messaging token, and when it was
 * last told to the FOLLO server. The token rides along inside the `fd` diagnostics of a location
 * post (`pt` + `pp`), but only when it is worth the ~150 bytes: it changed since it was last
 * included, this is one of the first three posts after the process started, or more than an hour
 * has passed since it was last included. The server keeps it on the roster row and uses it when
 * base camp asks every phone to check in.
 *
 * Kept in its own small preferences file, not the exported OwnTracks configuration: a token is per
 * install and must never travel in a setup link or a config export.
 */
@Singleton
class PushTokenRepo
internal constructor(
    private val store: Store,
    private val now: () -> Long,
    private val notificationsEnabled: () -> Boolean,
) {
  @Inject
  constructor(
      @ApplicationContext context: Context
  ) : this(
      SharedPrefsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
      System::currentTimeMillis,
      { NotificationManagerCompat.from(context).areNotificationsEnabled() },
  )

  interface Store {
    fun get(key: String): String?

    fun put(key: String, value: String)
  }

  private class SharedPrefsStore(private val prefs: SharedPreferences) : Store {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
      prefs.edit().putString(key, value).apply()
    }
  }

  /**
   * Posts since the process started that asked for the fd fields (the first three carry the token).
   */
  @Volatile private var postsSinceStart = 0

  var token: String?
    get() = store.get(KEY_TOKEN)?.takeIf { it.isNotEmpty() }
    set(value) {
      if (value.isNullOrEmpty()) return
      if (value != token) {
        Timber.i("push token ${if (token == null) "received" else "changed"}")
        store.put(KEY_TOKEN, value)
      }
    }

  /** `pt` and `pp` for this post, or null when the token need not be repeated. */
  fun fdFields(): Pair<String, String>? {
    val t = token ?: return null
    val n = postsSinceStart++
    val sentToken = store.get(KEY_SENT_TOKEN)
    val sentAt = store.get(KEY_SENT_AT)?.toLongOrNull() ?: 0L
    val due = sentToken != t || n < FIRST_POSTS || now() - sentAt > REPEAT_MS
    if (!due) return null
    store.put(KEY_SENT_TOKEN, t)
    store.put(KEY_SENT_AT, now().toString())
    return t to PLATFORM
  }

  /**
   * `pn`: whether this phone shows notifications at all (the fallback when a check-in cannot be
   * made).
   */
  fun notificationsAllowed(): Boolean = notificationsEnabled()

  companion object {
    const val PREFS_NAME = "follo_push"
    const val PLATFORM = "fcm"
    internal const val KEY_TOKEN = "token"
    internal const val KEY_SENT_TOKEN = "sentToken"
    internal const val KEY_SENT_AT = "sentAt"
    internal const val FIRST_POSTS = 3
    internal const val REPEAT_MS = 3_600_000L
  }
}
