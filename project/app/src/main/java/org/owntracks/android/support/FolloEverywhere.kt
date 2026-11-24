package org.owntracks.android.support

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * "⚠ Share everywhere I go ⚠" (Alex, 22 Nov 2026): "a special 'search' … which ignores all the rules about tracking
 * (e.g. geofencing circles) and allows someone who is really keen to record something in a random place to record it …
 * A couple of people have requested it, so I want them to have it but with plenty of warning." While it is on the
 * phone shares every position, wherever it is and at any time, until the person changes back to a normal search.
 *
 * This object is the ONE flag, kept across restarts, updates and reboots (like [FolloPower]). Everything that decides
 * whether a fix may leave the phone, and whether a post says `ev: 1`, reads it at that moment (LocationProcessor);
 * nothing keeps a copy. The rules are in FolloRules, the screen in MainActivity, the contract in
 * FOLLO_tracks/docs/NEXT_BUILD_SPEC_20261122.md §D.
 */
object FolloEverywhere {
  private const val PREFS = "follo_everywhere"
  private const val KEY_ON = "on"
  private const val KEY_SINCE = "since"
  private const val KEY_SHOWN = "shown_at"
  private const val KEY_NOTICE = "server_off_notice"

  /** The flag and the moment it was set, read together. */
  class State(val on: Boolean, val since: Long)

  @Volatile private var current = State(false, 0L)
  private val mutableOn = MutableStateFlow(false)
  private val mutableNotice = MutableStateFlow(false)
  @Volatile private var prefs: SharedPreferences? = null

  /** True while the mode is on. Collect it to follow the switch. */
  val onState: StateFlow<Boolean> = mutableOn

  /** The flag, as stored: read HERE whenever a fix is judged or a body is built. */
  val isOn: Boolean
    get() = current.on

  /**
   * The flag together with the moment OK was tapped (epoch ms; 0 while off). A fix whose own time is earlier than that
   * moment is never sent under the mode (FolloRules.everywhereMaySend; spec §H, 22 Nov 2026: the switch-on check-in
   * had reused a fix taken 2 s before the OK).
   */
  val state: State
    get() = current

  /** True from when the server switched the mode off until the notice saying so has been shown and gone. */
  val serverOffNotice: StateFlow<Boolean> = mutableNotice

  /** Reads the stored flag once per process (App.onCreate, before anything can post). */
  fun load(context: Context) {
    if (prefs != null) return
    synchronized(this) {
      if (prefs != null) return
      val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      current = State(p.getBoolean(KEY_ON, false), p.getLong(KEY_SINCE, 0L))
      // 24 Nov 2026: a reinstall came back ON from a Google backup, without anyone tapping OK on this install. An "on"
      // older than this install of the app was never OK'd here ⇒ off (the backup rules leave the file out as well).
      val installed = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
      }.getOrDefault(0L)
      if (restoredOn(current.on, current.since, installed)) {
        p.edit().putBoolean(KEY_ON, false).putLong(KEY_SINCE, 0L).commit()
        current = State(false, 0L)
        Timber.i("FOLLO share everywhere: off (restored from a backup, never OK'd on this install)")
      }
      mutableOn.value = current.on
      mutableNotice.value = p.getBoolean(KEY_NOTICE, false)
      prefs = p
    }
  }

  /** True when a stored "on" predates this install of the app (restored from a backup): it must not count. */
  fun restoredOn(on: Boolean, since: Long, installedAt: Long): Boolean = on && installedAt > 0L && since < installedAt

  /** On or off; written before anyone is told, so a process that dies now still comes back right. */
  fun set(on: Boolean, byServer: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
    val since = if (on) nowMs else 0L
    prefs?.edit()?.apply {
      putBoolean(KEY_ON, on)
      putLong(KEY_SINCE, since)
      if (on) putBoolean(KEY_NOTICE, false) else if (byServer) putBoolean(KEY_NOTICE, true)
    }?.commit()
    Timber.i("FOLLO share everywhere: ${if (on) "on" else if (byServer) "off (by the server)" else "off"}")
    if (on) mutableNotice.value = false else if (byServer) mutableNotice.value = true
    current = State(on, since)
    mutableOn.value = on
  }

  /** The "turned off by FOLLO" notice has been shown for its time, or dismissed. */
  fun noticeDone() {
    prefs?.edit()?.putBoolean(KEY_NOTICE, false)?.apply()
    mutableNotice.value = false
  }

  /** When the pop-up (or its reminder) was last shown, epoch ms; 0 = never. */
  var shownAt: Long
    get() = prefs?.getLong(KEY_SHOWN, 0L) ?: 0L
    set(value) {
      prefs?.edit()?.putLong(KEY_SHOWN, value)?.apply()
    }
}
