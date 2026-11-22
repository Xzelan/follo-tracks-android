package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.PathParser

/**
 * One round button of the simple screen (Alex, 19 Nov 2026; SIMPLE_MODE_SPEC §2): the "…" button and everything in the
 * column under it. Two looks that must be told apart at a glance:
 * - a LAYER button: a colour picture filling the circle with a 3.5 dp ring INSIDE the circle — ring #39F03C = on; ring
 *   white and a 30 % black veil over the picture = off;
 * - a FUNCTION button: one colour, no ring — #F6F7F3 with a green symbol; a toggle that is on = green with a white
 *   symbol (the open "…" too).
 */
class FolloMenuButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  enum class Look {
    LAYER,
    FUNCTION,
  }

  /** One piece of a picture on a 24 × 24 box: filled and / or stroked; [TINT] = the button's symbol colour. */
  class Shape(data: String, val fill: Int? = null, val stroke: Int? = null, val width: Float = 2f) {
    val path: Path = PathParser.createPathFromPathData(data)
  }

  var look = Look.FUNCTION
    set(value) {
      field = value
      invalidate()
    }

  var on = false
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /** The picture as shapes on a 24 × 24 box, drawn [pictureScale] × the usual 24-in-40 size */
  var shapes: List<Shape> = emptyList()
  var pictureScale = 1f
  /** A LAYER button's photo (hillshade, slope, contours): fills the circle */
  var photo: Bitmap? = null
  /** A FUNCTION button's symbol as a drawable (tinted) */
  var symbol: Drawable? = null
  /** The Labels button: a mini name tag with this text */
  var tag: String? = null

  private val density = resources.displayMetrics.density
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val clip = Path()
  private val box = RectF()

  init {
    isClickable = true
    isFocusable = true
    elevation = 4f * density
    outlineProvider =
        object : ViewOutlineProvider() {
          override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
          }
        }
  }

  override fun drawableStateChanged() {
    super.drawableStateChanged()
    invalidate()
  }

  override fun onDraw(c: Canvas) {
    val side = minOf(width, height).toFloat()
    val cx = width / 2f
    val cy = height / 2f
    val r = side / 2f
    clip.reset()
    clip.addCircle(cx, cy, r, Path.Direction.CW)
    c.save()
    c.clipPath(clip)
    val tint: Int
    if (look == Look.LAYER) {
      paint.style = Paint.Style.FILL
      paint.color = CARD
      c.drawCircle(cx, cy, r, paint)
      photo?.let {
        box.set(cx - r, cy - r, cx + r, cy + r)
        paint.color = WHITE
        c.drawBitmap(it, null, box, paint)
      }
      tint = GREEN
    } else {
      paint.style = Paint.Style.FILL
      paint.color = if (on) GREEN else if (isPressed) PRESSED else CARD
      c.drawCircle(cx, cy, r, paint)
      tint = if (on) WHITE else GREEN
    }
    // the picture: a 24 × 24 box drawn 24/40 of the button wide, in the middle
    val k = side / 40f * pictureScale
    if (shapes.isNotEmpty()) {
      c.save()
      c.translate(cx - 12f * k, cy - 12f * k)
      c.scale(k, k)
      for (s in shapes) {
        s.fill?.let {
          paint.style = Paint.Style.FILL
          paint.color = if (it == TINT) tint else it
          c.drawPath(s.path, paint)
        }
        s.stroke?.let {
          paint.style = Paint.Style.STROKE
          paint.strokeWidth = s.width
          paint.strokeCap = Paint.Cap.ROUND
          paint.strokeJoin = Paint.Join.ROUND
          paint.color = if (it == TINT) tint else it
          c.drawPath(s.path, paint)
        }
      }
      c.restore()
    }
    symbol?.let {
      val half = (13f * side / 40f).toInt()
      it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
      it.setTint(tint)
      it.draw(c)
    }
    tag?.let { text ->
      paint.style = Paint.Style.FILL
      paint.typeface = Typeface.DEFAULT_BOLD
      paint.textSize = 12f * side / 40f
      paint.textAlign = Paint.Align.CENTER
      val w = paint.measureText(text) + 10f * side / 40f
      val h = 19f * side / 40f
      box.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
      val rr = 8f * side / 40f
      paint.color = TAG_BG
      c.drawRoundRect(box, rr, rr, paint)
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = 2f * side / 40f
      paint.color = TEAM_BLUE
      c.drawRoundRect(box, rr, rr, paint)
      paint.style = Paint.Style.FILL
      paint.color = WHITE
      val fm = paint.fontMetrics
      c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, paint)
    }
    if (look == Look.LAYER) {
      if (!on) {
        paint.style = Paint.Style.FILL
        paint.color = VEIL
        c.drawCircle(cx, cy, r, paint)
      }
      val ring = RING_DP * side / 40f
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = ring
      paint.color = if (on) RING_ON else WHITE
      c.drawCircle(cx, cy, r - ring / 2f, paint)
    }
    c.restore()
  }

  companion object {
    /** A shape colour meaning "the button's symbol colour" (green, or white on a green button) */
    const val TINT = 1
    const val CARD = -0x9080d // 0xFFF6F7F3
    const val PRESSED = -0x1b1620 // 0xFFE4E9E0
    const val GREEN = -0xe194c6 // 0xFF1E6B3A
    const val WHITE = -1
    const val RING_ON = -0xc60fc4 // 0xFF39F03C
    const val VEIL = 0x4D000000 // 30 % black
    const val RING_DP = 3.5f
    const val TAG_BG = -0xeaeaeb // 0xFF151515
    const val TEAM_BLUE = -0xe5924b // 0xFF1A6DB5
    const val MAGENTA = -0xff01 // 0xFFFF00FF
    const val PIN = -0x174b00 // 0xFFE8B500
    const val INK = -0xeeeeef // 0xFF111111

    /** The "…" button */
    fun dots() =
        listOf(
            Shape("M2.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z", fill = TINT),
            Shape("M9.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z", fill = TINT),
            Shape("M16.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z", fill = TINT),
        )

    fun tracklogs() = listOf(Shape("M3,19c3,-9 6,1 9,-6s5,-6 9,-8", stroke = MAGENTA, width = 3f))

    fun grids() =
        listOf(
            Shape(
                "M7,3.5L5,20.5M12.5,4l-2,17M18,4.5l-2,17M7,3.5l11,1M6.3,9.2l11,1M5.7,14.8l11,1M5,20.5l11,1",
                stroke = TEAM_BLUE,
                width = 2f,
            ))

    fun poi() =
        listOf(
            Shape("M12,22s7,-7.6 7,-13a7,7 0 0,0 -14,0c0,5.4 7,13 7,13z", fill = PIN, stroke = INK, width = 1.5f),
            Shape("M9.4,9a2.6,2.6 0 1,0 5.2,0a2.6,2.6 0 1,0 -5.2,0z", fill = INK),
        )

    fun compass() =
        listOf(
            Shape("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0z", stroke = TINT, width = 2f),
            Shape("M12,5.5l2.8,6.5H9.2z", fill = TINT, stroke = TINT, width = 1f),
            Shape("M12,18.5l2.8,-6.5H9.2z", stroke = TINT, width = 1.6f),
        )

    /** Australia with Tasmania (docs/simple_mode/aus_path.txt, traced from a real outline) */
    fun australia() = listOf(Shape(AUSTRALIA, fill = TINT))

    private const val AUSTRALIA =
        "M19.0,20.7 18.7,21.1 18.2,21.1 17.6,19.5 18.5,19.8 19.3,19.6 19.2,20.9 19.0,20.7zM7.6,6.0 7.5,5.6 7.9,5.5 7.8,5.3 8.1,5.3 7.9,5.1 8.2,4.8 8.3,5.1 8.4,4.6 9.0,4.7 9.4,5.1 9.3,5.5 9.6,5.1 10.2,5.4 10.3,5.1 10.0,4.9 10.6,3.8 11.7,3.7 11.3,3.1 12.9,3.8 13.2,3.5 13.3,3.9 13.6,3.5 13.8,3.8 13.2,4.3 13.0,5.2 15.5,6.7 16.0,5.8 16.0,4.1 16.3,4.0 16.1,3.9 16.5,2.9 17.1,4.9 17.5,4.8 17.9,5.2 18.4,7.5 19.6,8.2 20.0,9.4 20.5,9.3 20.6,10.0 21.8,11.3 21.7,12.0 22.0,12.8 21.7,14.2 20.6,16.4 20.2,17.7 19.1,18.0 18.3,18.4 18.4,18.7 18.0,18.1 17.6,18.2 17.7,17.9 17.0,18.5 15.5,18.0 14.7,16.7 15.1,17.0 14.8,16.7 14.9,16.6 14.3,16.7 14.5,16.2 14.3,15.9 14.2,16.4 13.7,16.5 14.2,15.6 14.2,15.0 13.3,16.4 12.8,16.1 13.0,16.1 12.9,15.8 12.1,14.8 10.9,14.4 8.4,14.8 7.5,15.3 7.1,15.8 5.4,15.8 4.4,16.4 3.6,16.4 2.9,15.9 2.9,15.5 3.3,15.4 3.3,14.6 2.0,11.4 2.4,11.6 2.2,11.1 2.5,11.5 2.1,10.5 2.2,9.5 2.4,9.1 2.6,9.4 3.8,8.3 5.9,7.8 6.5,7.0 6.5,6.5 6.8,6.0 7.1,6.7 7.3,6.3 7.1,5.9 7.6,6.0z"
  }
}
