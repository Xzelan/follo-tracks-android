package org.owntracks.android.ui.main

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Which way the screen is turned (Alex 13 Nov 2026: "default to portrait and have a rotate icon"; 14 Nov: "make sure
 * the rotate button rotates by 90, 180, 270, 360 degrees"). The app opens upright every time and the screen turns only
 * when the rotate button is tapped: a quarter turn clockwise per tap ([FolloRules.nextTurn]), so four taps go all the
 * way round. Tilting or shaking the phone never turns it (the old tilt-and-shake turning is gone).
 */
class FolloScreenTurner(private val activity: Activity) {
  /** How far the screen is turned clockwise from upright: 0, 90, 180 or 270. */
  var degrees = 0
    private set

  /** Upright: when the app is opened. [restored] = the turn kept over the activity being made again (not an open). */
  fun open(restored: Int?) {
    degrees = restored?.let { Math.floorMod(it, 360) / 90 * 90 } ?: 0
    apply()
  }

  /** The rotate button: a further quarter turn clockwise. */
  fun turn(): Int {
    degrees = FolloRules.nextTurn(degrees)
    apply()
    return degrees
  }

  private fun apply() {
    activity.requestedOrientation = requested(degrees)
  }

  companion object {
    /**
     * The screen orientation that draws the screen turned [degrees] clockwise on a phone (whose natural way is upright):
     * LANDSCAPE = Surface.ROTATION_90 (the top of the screen along the phone's right edge), REVERSE_PORTRAIT = 180,
     * REVERSE_LANDSCAPE = 270.
     */
    fun requested(degrees: Int): Int =
        when (Math.floorMod(degrees, 360)) {
          90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
          180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
          270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
          else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
  }
}
