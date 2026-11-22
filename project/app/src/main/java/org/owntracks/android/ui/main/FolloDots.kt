package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Other people's dots, the same as the live map (FP078, Alex 21 Nov 2026: "I like it. We'll do the same with FOLLO
 * Tracks next time we do a build"): a circle [DOT_DP] across INCLUDING its 1 dp black edge, the team's colour inside,
 * nothing written in it; to its right a small dark label with the tracker ID whose pointer triangle is the colour of
 * that person's vest (hi-vis yellow, orange, pink; white = no vest). Always upright and to the right on the SCREEN,
 * whichever way the map is turned. Team-mates from the server's replies and, on the phones that may see everyone, the
 * phones in search circles are drawn alike. This phone's own arrow is not one of these.
 */
object FolloDots {
  /** One dot: where, the tracker ID, the fill (FolloRules.dotColour of the server's `tc`), the vest (Y / O / P / null). */
  data class Dot(val lat: Double, val lon: Double, val tid: String, val fill: Int, val vest: String?)

  const val DOT_DP = 12f
  const val EDGE_DP = 1f
  /** The pointer triangle: its tip on the dot's right edge */
  const val TRI_W_DP = 6f
  const val TRI_H_DP = 12f
  const val LABEL_RADIUS_DP = 4f
  const val LABEL_PAD_X_DP = 5f
  const val LABEL_PAD_Y_DP = 1f
  const val LABEL_TEXT_SP = 12f
  /** rgba(0, 0, 0, .75), the live map's label */
  val LABEL_FILL = Color.argb(191, 0, 0, 0)

  /** Draws dots; one per overlay, so its paints are made once. */
  class Painter(private val density: Float, scaledDensity: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          style = Paint.Style.STROKE
          color = Color.BLACK
          strokeWidth = EDGE_DP * density
        }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = LABEL_FILL }
    private val vest = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          textSize = LABEL_TEXT_SP * scaledDensity
          typeface = Typeface.DEFAULT_BOLD
          textAlign = Paint.Align.LEFT
        }
    private val tri = Path()
    private val box = RectF()

    /** How far the label reaches right of the dot's middle at most (for leaving out what is off the screen). */
    val reach: Float = (DOT_DP / 2 + TRI_W_DP + 2 * LABEL_PAD_X_DP) * density + text.measureText("WWWW")

    /** The dot with its middle at ([x], [y]); [mapOrientation] = the map's turn (degrees), undone for the label. */
    fun draw(c: Canvas, x: Float, y: Float, mapOrientation: Float, d: Dot) {
      val r = DOT_DP / 2 * density
      c.save()
      c.rotate(-mapOrientation, x, y)
      // the edge is centred on its circle: 2 × (r − half the edge) + the edge = DOT_DP across
      val half = EDGE_DP * density / 2
      fill.color = d.fill
      c.drawCircle(x, y, r - half, fill)
      c.drawCircle(x, y, r - half, edge)
      val t = d.tid.take(3)
      if (t.isNotEmpty()) {
        val tw = TRI_W_DP * density
        val th = TRI_H_DP * density
        vest.color = FolloRules.vestColour(d.vest)
        tri.rewind()
        tri.moveTo(x + r, y)
        tri.lineTo(x + r + tw, y - th / 2)
        tri.lineTo(x + r + tw, y + th / 2)
        tri.close()
        c.drawPath(tri, vest)
        val fm = text.fontMetrics
        val h = fm.descent - fm.ascent + 2 * LABEL_PAD_Y_DP * density
        val w = text.measureText(t) + 2 * LABEL_PAD_X_DP * density
        val left = x + r + tw
        box.set(left, y - h / 2, left + w, y + h / 2)
        c.drawRoundRect(box, LABEL_RADIUS_DP * density, LABEL_RADIUS_DP * density, label)
        c.drawText(t, left + LABEL_PAD_X_DP * density, y - (fm.ascent + fm.descent) / 2, text)
      }
      c.restore()
    }
  }
}
