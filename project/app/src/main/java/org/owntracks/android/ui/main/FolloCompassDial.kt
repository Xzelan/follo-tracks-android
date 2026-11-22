package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation

/**
 * The small compass dial at the left of the heading pill (spec §5, mockup 15 Nov 2026): a white ring with a short north
 * mark at the top, and a needle turned to the heading, its red half pointing the way the top of the screen points.
 * North-up, like a baseplate compass card read from above. [setAlpha] dims it with the text when calibration is poor.
 */
class FolloCompassDial(private val sizePx: Int, private val stroke: Float) : Drawable() {
  /** The heading the needle shows, degrees clockwise from north. */
  var heading = 0f
    set(v) {
      if (field != v) {
        field = v
        invalidateSelf()
      }
    }

  private var alphaValue = 255
  private val ring =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = stroke
      }
  private val mark =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
      }
  private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5392F.toInt() }
  private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
  private val half = Path()

  override fun getIntrinsicWidth() = sizePx

  override fun getIntrinsicHeight() = sizePx

  override fun draw(canvas: Canvas) {
    val b = bounds
    val cx = b.exactCenterX()
    val cy = b.exactCenterY()
    val r = minOf(b.width(), b.height()) / 2f - stroke / 2f
    for (p in listOf(ring, mark, red, white)) p.alpha = alphaValue
    canvas.drawCircle(cx, cy, r, ring)
    canvas.drawLine(cx, cy - r, cx, cy - r + r * 0.32f, mark) // north mark: a short tick in from the top
    val len = r * 0.72f
    val w = r * 0.26f
    canvas.withRotation(heading, cx, cy) {
      needleHalf(cx, cy, cy - len, w)
      drawPath(half, red) // the way the top of the screen points
      needleHalf(cx, cy, cy + len, w)
      drawPath(half, white)
    }
  }

  private fun needleHalf(cx: Float, cy: Float, tipY: Float, w: Float) {
    half.reset()
    half.moveTo(cx, tipY)
    half.lineTo(cx + w, cy)
    half.lineTo(cx - w, cy)
    half.close()
  }

  override fun setAlpha(alpha: Int) {
    if (alphaValue != alpha) {
      alphaValue = alpha
      invalidateSelf()
    }
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {}

  @Deprecated("Deprecated in Java")
  override fun getOpacity() = PixelFormat.TRANSLUCENT
}
