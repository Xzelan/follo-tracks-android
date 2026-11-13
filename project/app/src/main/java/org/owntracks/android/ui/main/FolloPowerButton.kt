package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import org.owntracks.android.R

/**
 * The round power button at the banner's top right (Alex, 13 Nov 2026). On: hold it for 2 seconds to turn the app Off
 * (a ring fills while it is held; letting go early only shows a hint). Off: one tap turns it back on. The rules are
 * FolloRules.powerRelease / powerProgress; what Off means is FolloPower.
 */
class FolloPowerButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  /** True = the app is Off (white disc, red symbol, a tap turns it on). */
  var off = false
    set(value) {
      if (field == value) return
      field = value
      if (value) stopHold()
      describe()
      invalidate()
    }

  var onTurnOff: (() -> Unit)? = null
  var onTurnOn: (() -> Unit)? = null
  var onHint: (() -> Unit)? = null

  private val density = resources.displayMetrics.density
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val arc = RectF()
  private val red = context.getColor(R.color.follo_red)
  private var holdStart = 0L
  private var holding = false
  private var down = false
  private var turnedOffByThisPress = false
  private var turnOffAction = View.NO_ID
  // A plain delayed call decides, not an animation: "Remove animations" must not shorten the 2 seconds
  private val holdDone = Runnable {
    if (holding && !off) {
      stopHold()
      turnedOffByThisPress = true
      performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      onTurnOff?.invoke()
    }
  }

  init {
    isClickable = true
    isFocusable = true
    describe()
  }

  private fun describe() {
    contentDescription = context.getString(if (off) R.string.follo_power_cd_off else R.string.follo_power_cd_on)
    // TalkBack's double tap cannot be timed, so turning Off is offered as a named action while the app is on
    ViewCompat.removeAccessibilityAction(this, turnOffAction)
    if (!off)
        turnOffAction =
            ViewCompat.addAccessibilityAction(this, context.getString(R.string.follo_power_turn_off)) { _, _ ->
              onTurnOff?.invoke()
              true
            }
  }

  override fun performClick(): Boolean {
    super.performClick()
    if (off) onTurnOn?.invoke() else onHint?.invoke()
    return true
  }

  private fun inside(e: MotionEvent): Boolean {
    val slop = 30 * density
    return e.x >= -slop && e.y >= -slop && e.x <= width + slop && e.y <= height + slop
  }

  @Suppress("ClickableViewAccessibility") // performClick is called for the tap; the hold has its accessibility action
  override fun onTouchEvent(e: MotionEvent): Boolean {
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
        down = true
        turnedOffByThisPress = false
        if (!off) startHold()
        invalidate()
      }
      // sliding well off the button gives the hold up
      MotionEvent.ACTION_MOVE -> if (holding && !inside(e)) stopHold()
      MotionEvent.ACTION_UP -> {
        val wasHolding = holding
        stopHold()
        down = false
        val action = FolloRules.powerRelease(off, inside(e) && (off || wasHolding), turnedOffByThisPress)
        turnedOffByThisPress = false
        if (action != FolloRules.PowerRelease.NOTHING) performClick()
        invalidate()
      }
      MotionEvent.ACTION_CANCEL -> {
        stopHold()
        down = false
        turnedOffByThisPress = false
        invalidate()
      }
    }
    return true
  }

  private fun startHold() {
    stopHold()
    holding = true
    holdStart = SystemClock.uptimeMillis()
    postDelayed(holdDone, FolloRules.POWER_HOLD_MS)
    postInvalidateOnAnimation()
  }

  private fun stopHold() {
    holding = false
    removeCallbacks(holdDone)
    invalidate()
  }

  override fun onDetachedFromWindow() {
    stopHold()
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    val side = minOf(width, height).toFloat()
    val cx = width / 2f
    val cy = height / 2f
    val radius = side / 2f - 2 * density
    // On: a faint disc with a white symbol, quiet on any banner colour. Off: a white disc with the red symbol, the one
    // thing on the screen to tap.
    paint.style = Paint.Style.FILL
    paint.color = if (off) 0xFFFFFFFF.toInt() else if (down) 0x52FFFFFF else 0x2EFFFFFF
    canvas.drawCircle(cx, cy, radius, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = 2.4f * density
    paint.color = if (off) red else 0xFFFFFFFF.toInt()
    val r = side * 0.21f
    arc.set(cx - r, cy - r, cx + r, cy + r)
    val gap = 50f // degrees of opening at the top, either side of the stem
    canvas.drawArc(arc, -90f + gap, 360f - 2 * gap, false, paint)
    canvas.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.1f, paint)

    if (holding) {
      val p = FolloRules.powerProgress(SystemClock.uptimeMillis() - holdStart)
      paint.strokeWidth = 3f * density
      paint.color = 0xFFFFFFFF.toInt()
      val rr = radius - 1.5f * density
      arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
      canvas.drawArc(arc, -90f, 360f * p, false, paint)
      postInvalidateOnAnimation()
    }
  }
}
