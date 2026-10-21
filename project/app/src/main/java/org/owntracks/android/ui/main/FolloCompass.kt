package org.owntracks.android.ui.main

import android.app.Activity
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The way the phone is pointing, as a TRUE bearing of the screen's "up" (Alex, 21 Oct 2026: the map turns with the
 * compass, like Google Maps on foot, so it turns even when standing still). Magnetic north from the rotation-vector
 * sensor (or accelerometer + magnetometer where a phone has none), plus the declination at the last fix (≈ +14° in
 * Tasmania). Remapped for the screen's rotation, so landscape works. Runs only while [start]ed (activity resumed).
 *
 * The bearing is that of the screen-up axis plus the into-the-screen axis, flattened: lying flat, that is where the
 * top edge points; held upright, where the back of the phone faces; anywhere in between, both agree. So it never goes
 * wild when the phone is tilted up.
 */
class FolloCompass(private val activity: Activity, private val onChange: () -> Unit) : SensorEventListener {
  private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val accelSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val magnetSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

  private val rot = FloatArray(9)
  private val screenRot = FloatArray(9)
  private val gravity = FloatArray(3)
  private val geomag = FloatArray(3)
  private var haveGravity = false
  private var haveGeomag = false

  private var declination = 0f
  private var declinationAt: Location? = null
  private var target: Float? = null
  private var targetAt = 0L

  /** The latest true heading, or null when there has been no usable reading for [STALE_MS] (or no compass at all). */
  fun heading(): Float? = target?.takeIf { SystemClock.elapsedRealtime() - targetAt < STALE_MS }

  fun start() {
    if (rotationSensor != null) {
      sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    } else if (accelSensor != null && magnetSensor != null) {
      sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
      sensors.registerListener(this, magnetSensor, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  fun stop() {
    sensors.unregisterListener(this)
    haveGravity = false
    haveGeomag = false
  }

  /** Declination for true north; recomputed only when the phone has moved more than [DECLINATION_REFRESH_M]. */
  fun updateDeclination(l: Location) {
    val at = declinationAt
    if (at != null && at.distanceTo(l) < DECLINATION_REFRESH_M) return
    declinationAt = Location(l)
    declination =
        GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis())
            .declination
  }

  override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
      Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(rot, event.values)
      Sensor.TYPE_ACCELEROMETER -> {
        lowPass(event.values, gravity, haveGravity)
        haveGravity = true
        return
      }
      Sensor.TYPE_MAGNETIC_FIELD -> {
        lowPass(event.values, geomag, haveGeomag)
        haveGeomag = true
        if (!haveGravity || !SensorManager.getRotationMatrix(rot, null, gravity, geomag)) return
      }
      else -> return
    }
    val (x, y) =
        when (displayRotation()) {
          Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
          Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
          Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
          else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    if (!SensorManager.remapCoordinateSystem(rot, x, y, screenRot)) return
    // World (east, north) of screen-up (column 1) plus into-the-screen (minus column 2).
    val east = screenRot[1] - screenRot[2]
    val north = screenRot[4] - screenRot[5]
    if (hypot(east, north) < 0.2f) return // pointing straight up or down: no direction to speak of
    val magnetic = Math.toDegrees(atan2(east, north).toDouble()).toFloat()
    val h = ((magnetic + declination) % 360f + 360f) % 360f
    targetAt = SystemClock.elapsedRealtime()
    val old = target
    // Dead-band: changes under DEADBAND_DEG are hand tremor, not a turn.
    if (old == null || abs(((h - old + 540f) % 360f) - 180f) >= DEADBAND_DEG) {
      target = h
      onChange()
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  @Suppress("DEPRECATION")
  private fun displayRotation(): Int =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation ?: Surface.ROTATION_0
      else activity.windowManager.defaultDisplay.rotation

  private fun lowPass(input: FloatArray, out: FloatArray, primed: Boolean) {
    for (i in 0..2) out[i] = if (primed) out[i] + LOW_PASS * (input[i] - out[i]) else input[i]
  }

  companion object {
    /** Without a reading for this long the camera falls back to the walking direction. */
    const val STALE_MS = 3_000L
    const val DEADBAND_DEG = 2f
    private const val DECLINATION_REFRESH_M = 10_000f
    private const val LOW_PASS = 0.15f
  }
}
