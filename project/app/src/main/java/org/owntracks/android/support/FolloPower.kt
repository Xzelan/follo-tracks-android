package org.owntracks.android.support

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * FOLLO Tracks power button (Alex, 13 Nov 2026): "a power icon button in the top right that if pressed for 3 seconds
 * changes from Standby to Off … Tapping it immediately turns it back to standby. Just for people really worried about
 * privacy and power savings."
 *
 * Off means the app uses NO location at all: no fix for the screen, no tracking, no check-in fix, no near-circle watch,
 * and the wake-up circles are taken off Play services (a geofence is location use too). The tracking service and its
 * notification go; the map is not shown. Base camp's pushes still arrive (FolloPushService).
 *
 * This object is the one flag, kept across restarts, updates and reboots. Everything that could ask for a position
 * looks at it: the Play services clients (GMSLocationProviderClient, GMSGeofencingClient) refuse while it is set, so a
 * caller that forgets to look still gets nothing; BackgroundService, the starters (ServiceStarter, the boot receiver,
 * FolloPushService, PushTokenRegistrar) and MainActivity look first so nothing is even started. The same on iOS
 * (FolloPower.swift).
 */
object FolloPower {
  private const val PREFS = "follo_power"
  private const val KEY_OFF = "off"

  private val mutableOff = MutableStateFlow(false)
  @Volatile private var loaded = false

  /** True while the app is turned Off. Collect it to follow the button. */
  val offState: StateFlow<Boolean> = mutableOff

  /** The flag as last read or set in this process; [load] runs when the app starts (App.onCreate). */
  val isOff: Boolean
    get() = mutableOff.value

  /** Reads the stored flag once per process. */
  fun load(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      mutableOff.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFF, false)
      loaded = true
    }
  }

  /** The flag, read from storage first if this process has not yet (a receiver that runs before anything else). */
  fun isOff(context: Context): Boolean {
    load(context)
    return mutableOff.value
  }

  /** Turn the app Off or back on; written before anyone is told, so a process that dies now still comes back right. */
  fun set(context: Context, off: Boolean) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFF, off).commit()
    loaded = true
    Timber.i("FOLLO power: ${if (off) "off" else "on"}")
    mutableOff.value = off
  }
}
