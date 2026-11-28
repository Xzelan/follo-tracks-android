package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Other people's dots, the same as the live map (FP078, Alex 21 Nov 2026: "I like it. We'll do the same with FOLLO
 * Tracks next time we do a build"): a circle with a 1 dp black edge, the team's colour inside, nothing written in it;
 * to its right a small dark label with the tracker ID whose pointer triangle is the colour of that person's vest
 * (hi-vis yellow, orange, pink; white = no vest). Always upright and to the right on the SCREEN, whichever way the
 * map is turned. Team-mates from the server's replies and, on the phones that may see everyone, the phones in search
 * circles are drawn alike. This phone's own arrow is not one of these.
 *
 * True scale (0.1.52, 28 Nov 2026, as the live map does since that day): the circle is ALWAYS [FolloScale.DOT_M]
 * (half a metre) across ON THE GROUND, edge included, with no minimum size, so it shows how much room a person takes
 * up next to the 1 m tracklogs. Zoomed out it shrinks to a black speck the size of its edge and the team colour is
 * gone; the label does not shrink, and its pointer's tip stays on the circle's edge at every zoom. Display only.
 */
object FolloDots {
  /** One dot: where, the tracker ID, the fill (FolloRules.dotColour of the server's `tc`), the vest (Y / O / P / null). */
  data class Dot(val lat: Double, val lon: Double, val tid: String, val fill: Int, val vest: String?)

  const val EDGE_DP = 1f
  /** The pointer triangle: its tip on the dot's right edge, however big the dot is drawn */
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

    private val speck = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val labelReach: Float = (TRI_W_DP + 2 * LABEL_PAD_X_DP) * density + text.measureText("WWWW")

    /** The dot's outer radius in pixels where the map has [pxPerMetre]: half a metre across on the ground. */
    fun radius(pxPerMetre: Float): Float = FolloScale.dotOuterRadiusPx(pxPerMetre, EDGE_DP * density)

    /** How far the dot and its label reach from the dot's middle at most (for leaving out what is off the screen). */
    fun reach(pxPerMetre: Float): Float = radius(pxPerMetre) + labelReach

    /**
     * The dot with its middle at ([x], [y]), drawn [FolloScale.DOT_M] across on the ground at [pxPerMetre] (the map's
     * pixels per metre where the dot is); [mapOrientation] = the map's turn (degrees), undone for the label.
     */
    fun draw(c: Canvas, x: Float, y: Float, mapOrientation: Float, pxPerMetre: Float, d: Dot) {
      val edgePx = EDGE_DP * density
      val r = radius(pxPerMetre) // outer edge
      val mid = FolloScale.dotStrokeRadiusPx(r, edgePx) // the edge is centred on its circle: 2 × mid + the edge = across
      c.save()
      c.rotate(-mapOrientation, x, y)
      if (mid > edgePx / 2) {
        fill.color = d.fill
        c.drawCircle(x, y, mid, fill)
        c.drawCircle(x, y, mid, edge)
      } else {
        c.drawCircle(x, y, r, speck) // no room for colour inside the edge: only the edge is left, a black speck
      }
      val t = d.tid.take(3)
      if (t.isNotEmpty()) {
        val tw = TRI_W_DP * density
        val th = TRI_H_DP * density
        vest.color = FolloRules.vestColour(d.vest)
        tri.rewind()
        val tip = x + FolloScale.labelTipOffsetPx(r)
        tri.moveTo(tip, y)
        tri.lineTo(tip + tw, y - th / 2)
        tri.lineTo(tip + tw, y + th / 2)
        tri.close()
        c.drawPath(tri, vest)
        val fm = text.fontMetrics
        val h = fm.descent - fm.ascent + 2 * LABEL_PAD_Y_DP * density
        val w = text.measureText(t) + 2 * LABEL_PAD_X_DP * density
        val left = tip + tw
        box.set(left, y - h / 2, left + w, y + h / 2)
        c.drawRoundRect(box, LABEL_RADIUS_DP * density, LABEL_RADIUS_DP * density, label)
        c.drawText(t, left + LABEL_PAD_X_DP * density, y - (fm.ascent + fm.descent) / 2, text)
      }
      c.restore()
    }
  }
}
