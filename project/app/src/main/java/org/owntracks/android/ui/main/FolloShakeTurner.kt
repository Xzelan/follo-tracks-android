package org.owntracks.android.ui.main

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Portrait / landscape only on purpose (Alex, 21 Oct 2026): the screen stays locked the way it is, and turns only when
 * the phone has been held the other way for a moment AND given a gentle shake (gentler than iPhone's shake-to-undo).
 * Works both ways, and between the two landscapes. Tilting alone never turns it, so a phone waved about while
 * walking keeps its layout. If the phone is held the other way for a while without a shake, [onHint] fires (at most
 * every [HINT_EVERY_MS]) so the app can say how.
 */
class FolloShakeTurner(private val activity: Activity, private val onHint: () -> Unit) : SensorEventListener {
  private enum class Way(val requested: Int) {
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), // top of the phone to the left
    REVERSE_LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE), // top to the right
  }

  private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val accel: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private var screen = Way.PORTRAIT
  private val gravity = FloatArray(3)
  private val lin = FloatArray(3)
  private var primed = false

  /** The way the phone is physically held (last clear reading) and since when. */
  private var held: Way? = null
  private var heldSince = 0L
  private var heldNow = false

  /** Shake peaks: alternating-sign spikes along one axis, newest last. */
  private val peakTimes = ArrayDeque<Long>()
  private var peakAxis = -1
  private var peakSign = 0
  private var lastTurn = 0L
  private var lastHint = 0L

  /** Locks the screen the way it is now. Call once, from onCreate. */
  fun lockCurrent() {
    screen =
        when (displayRotation()) {
          Surface.ROTATION_90 -> Way.LANDSCAPE
          Surface.ROTATION_270 -> Way.REVERSE_LANDSCAPE
          else -> Way.PORTRAIT
        }
    activity.requestedOrientation = screen.requested
  }

  fun start() {
    accel?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
  }

  fun stop() {
    sensors.unregisterListener(this)
    primed = false
    held = null
    peakTimes.clear()
  }

  override fun onSensorChanged(event: SensorEvent) {
    val v = event.values
    val now = SystemClock.elapsedRealtime()
    for (i in 0..2) gravity[i] = if (primed) gravity[i] + GRAVITY_LOW_PASS * (v[i] - gravity[i]) else v[i]
    primed = true

    // Which way is the phone held? Angle of gravity in the screen's plane; nothing when nearly flat.
    val gx = gravity[0]
    val gy = gravity[1]
    val way =
        if (hypot(gx, gy) < SensorManager.GRAVITY_EARTH * FLAT_SIN) null
        else {
          val theta = Math.toDegrees(atan2(gx, gy).toDouble())
          when {
            abs(theta) < WAY_TOLERANCE_DEG -> Way.PORTRAIT
            abs(theta - 90) < WAY_TOLERANCE_DEG -> Way.LANDSCAPE
            abs(theta + 90) < WAY_TOLERANCE_DEG -> Way.REVERSE_LANDSCAPE
            else -> null
          }
        }
    heldNow = way != null
    if (way != null && way != held) {
      held = way
      heldSince = now
    }

    // Shake: spikes of movement (gravity taken out) that flip direction along the main axis.
    for (i in 0..2) lin[i] = v[i] - gravity[i]
    var axis = 0
    for (i in 1..2) if (abs(lin[i]) > abs(lin[axis])) axis = i
    if (abs(lin[axis]) > SHAKE_G * SensorManager.GRAVITY_EARTH) {
      val sign = if (lin[axis] > 0) 1 else -1
      if (axis != peakAxis) {
        peakTimes.clear()
        peakAxis = axis
        peakSign = 0
      }
      if (sign != peakSign) {
        peakSign = sign
        peakTimes.addLast(now)
        while (peakTimes.isNotEmpty() && now - peakTimes.first() > SHAKE_WINDOW_MS) peakTimes.removeFirst()
        if (peakTimes.size >= SHAKE_PEAKS) {
          peakTimes.clear()
          peakSign = 0
          onShake(now)
        }
      }
    }

    val h = held
    if (heldNow && h != null && h != screen && now - heldSince >= HINT_AFTER_MS && now - lastHint >= HINT_EVERY_MS) {
      lastHint = now
      onHint()
    }
  }

  private fun onShake(now: Long) {
    val h = held ?: return
    if (h == screen || now - heldSince < HOLD_MS || now - lastTurn < TURN_COOLDOWN_MS) return
    screen = h
    lastTurn = now
    activity.requestedOrientation = h.requested
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  @Suppress("DEPRECATION")
  private fun displayRotation(): Int =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation ?: Surface.ROTATION_0
      else activity.windowManager.defaultDisplay.rotation

  companion object {
    /** Held the new way at least this long before a shake counts. */
    const val HOLD_MS = 400L
    /** A shake = this many direction-flipping spikes above [SHAKE_G] within [SHAKE_WINDOW_MS]. */
    const val SHAKE_PEAKS = 3
    const val SHAKE_G = 0.7f
    const val SHAKE_WINDOW_MS = 1_000L
    const val WAY_TOLERANCE_DEG = 30.0
    /** Within 30° of flat (sin 30° of gravity in the screen's plane) the way it is held is undecided. */
    const val FLAT_SIN = 0.5f
    const val HINT_AFTER_MS = 2_000L
    const val HINT_EVERY_MS = 30_000L
    private const val TURN_COOLDOWN_MS = 1_500L
    /** Gravity estimate: slow enough that a shake barely moves it (≈ 0.5 s at the 50 Hz game rate). */
    private const val GRAVITY_LOW_PASS = 0.04f
  }
}
