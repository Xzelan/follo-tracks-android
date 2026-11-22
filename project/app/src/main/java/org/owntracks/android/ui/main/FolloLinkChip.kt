package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser

/**
 * The two-way symbol at the right end of the banner's title line (Alex, 19 Nov 2026; SIMPLE_MODE_SPEC §3): two arrows
 * (the top one pointing right, the bottom one left) on a dark chip. Green = nothing waits and the server answered;
 * orange = something has waited a minute; red with a slash through the arrows = five minutes. A shape, not a font
 * glyph, sized from the title's cap height ([capHeight]). The view is [TOUCH_DP] larger all round than the chip it
 * draws, so it can be tapped (the tracker's name and when it last sent) and held (check in now).
 */
class FolloLinkChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  var state = FolloMenu.Link.GREEN
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /** The title's cap height in px: the arrows are 1.3 × as tall. */
  var capHeight = 0f
    set(value) {
      if (field == value) return
      field = value
      requestLayout()
    }

  private val density = resources.displayMetrics.density
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val arrows: Path = PathParser.createPathFromPathData("M2,6h22m-5,-4.5L24,6l-5,4.5M25,14H3m5,-4.5L3,14l5,4.5")
  private val slash: Path = PathParser.createPathFromPathData("M5,19L22,1")
  private val box = RectF()

  private fun glyphH() = (if (capHeight > 0f) capHeight else 11.5f * density) * 1.3f

  private fun glyphW() = glyphH() * 27f / 20f

  /** The chip's own height (without the touch margin), for placing it on the title's first line. */
  fun chipHeight(): Float = glyphH() + 2 * PAD_Y_DP * density

  val touchPx: Int
    get() = (TOUCH_DP * density).toInt()

  init {
    isClickable = true
    isFocusable = true
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(
        (glyphW() + 2 * PAD_X_DP * density).toInt() + 2 * touchPx,
        chipHeight().toInt() + 2 * touchPx,
    )
  }

  override fun onDraw(c: Canvas) {
    val t = touchPx.toFloat()
    box.set(t, t, width - t, height - t)
    paint.style = Paint.Style.FILL
    paint.color = CHIP
    c.drawRoundRect(box, 8f * density, 8f * density, paint)
    val k = glyphH() / 20f
    c.save()
    c.translate(box.left + PAD_X_DP * density, box.top + PAD_Y_DP * density)
    c.scale(k, k)
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = 2.6f
    paint.color =
        when (state) {
          FolloMenu.Link.GREEN -> GREEN
          FolloMenu.Link.ORANGE -> ORANGE
          FolloMenu.Link.RED -> RED
        }
    c.drawPath(arrows, paint)
    if (state == FolloMenu.Link.RED) {
      paint.strokeWidth = 3f
      c.drawPath(slash, paint)
    }
    c.restore()
  }

  companion object {
    const val CHIP = -0x62000000 // rgba(0,0,0,.62)
    const val GREEN = -0xff0100 // 0xFF00FF00
    const val ORANGE = -0x60e6 // 0xFFFF9F1A
    const val RED = -0xb2b3 // 0xFFFF4D4D
    const val PAD_X_DP = 6f
    const val PAD_Y_DP = 4f
    const val TOUCH_DP = 8f
  }
}
