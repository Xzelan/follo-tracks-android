package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Base camp's drawings on the map (Alex, 12 Nov 2026; docs/DRAW_20261112.md §3; the same on iOS): lines, areas and
 * circles in their colour, and the labels of lines, areas, circles and plain labels as name tags (the search grids'
 * tag: white bold text on a dark pill edged in the item's colour). Pins are ordinary map markers, drawn by the
 * activity like a live-map marker. At every zoom. Display only: this overlay takes no touches and hands none to the
 * shapes it holds.
 *
 * A solid line lies on a white casing ([CASING_ALPHA], [CASING_EXTRA_DP] wider); a dashed one ([DASH_DP] on,
 * [GAP_DP] off, butt ends) has none. An area or circle is filled in its colour at the item's opacity when it asks for one.
 * The shapes are osmdroid's own line and area objects (they clip to the screen at any zoom), drawn in the order sent.
 */
class FolloDrawOverlay(private val density: Float, scaledDensity: Float) : Overlay() {
  private class Tag(val lat: Double, val lon: Double, val text: String, val color: Int)

  private var shapes: List<Overlay> = emptyList()
  private var tags: List<Tag> = emptyList()

  private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK; alpha = FolloGridOverlay.TAG_ALPHA }
  private val tagEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = FolloGridOverlay.TAG_EDGE_DP * density }
  private val tagText =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = FolloGridOverlay.TAG_TEXT_SP * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
  private val clip = Rect()
  private val pill = RectF()

  /** Show this set (null or empty = nothing). Pins are left to the caller. */
  fun show(draw: FolloDraw?, map: MapView) {
    val made = mutableListOf<Overlay>()
    val labels = mutableListOf<Tag>()
    for (it in draw?.items.orEmpty()) {
      when (it.kind) {
        FolloDraw.Kind.LINE -> {
          val pts = geo(it.outline)
          if (!it.dashed) made += line(map, pts, Color.WHITE, it.width + CASING_EXTRA_DP, CASING_ALPHA, false)
          made += line(map, pts, it.color, it.width, 255, it.dashed)
        }
        FolloDraw.Kind.POLY, FolloDraw.Kind.CIRCLE -> {
          val pts = geo(it.outline).let { ring -> ring + ring.first() }
          if (!it.dashed) made += area(map, pts, Color.WHITE, it.width + CASING_EXTRA_DP, CASING_ALPHA, false, Color.TRANSPARENT)
          val fill = if (it.fill > 0f) withAlpha(it.color, (it.fill * 255).roundToInt()) else Color.TRANSPARENT
          made += area(map, pts, it.color, it.width, 255, it.dashed, fill)
        }
        FolloDraw.Kind.PIN -> Unit // a marker, drawn by the activity
        FolloDraw.Kind.TEXT -> Unit // its tag only, below
      }
      if (it.kind != FolloDraw.Kind.PIN && it.text != null) labels += Tag(it.labelLat, it.labelLon, it.text, it.color)
    }
    shapes = made
    tags = labels
  }

  override fun draw(c: Canvas, p: Projection) {
    if (shapes.isEmpty() && tags.isEmpty()) return
    for (s in shapes) s.draw(c, p)
    if (tags.isEmpty() || !c.getClipBounds(clip)) return
    for (t in tags) drawTag(c, p, t)
  }

  private fun geo(flat: DoubleArray): List<GeoPoint> = (0 until flat.size / 2).map { GeoPoint(flat[2 * it], flat[2 * it + 1]) }

  private fun stroke(paint: Paint, color: Int, widthDp: Float, alpha: Int, dashed: Boolean) {
    paint.color = color
    paint.alpha = alpha
    paint.strokeWidth = widthDp * density
    paint.strokeCap = if (dashed) Paint.Cap.BUTT else Paint.Cap.ROUND // butt ends keep the dash pattern true
    paint.strokeJoin = Paint.Join.ROUND
    paint.isAntiAlias = true
    paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f) else null
  }

  private fun line(map: MapView, pts: List<GeoPoint>, color: Int, widthDp: Float, alpha: Int, dashed: Boolean): Overlay =
      Polyline(map, true).apply { // as one path: round joins, and a dash pattern that runs on through the corners
        setPoints(pts)
        stroke(outlinePaint, color, widthDp, alpha, dashed)
        infoWindow = null
      }

  private fun area(map: MapView, pts: List<GeoPoint>, color: Int, widthDp: Float, alpha: Int, dashed: Boolean, fill: Int): Overlay =
      Polygon(map).apply {
        points = pts
        stroke(outlinePaint, color, widthDp, alpha, dashed)
        fillPaint.color = fill
        infoWindow = null
      }

  private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

  private fun drawTag(c: Canvas, p: Projection, t: Tag) {
    val x = p.getLongPixelXFromLongitude(t.lon).toFloat()
    val y = p.getLongPixelYFromLatitude(t.lat).toFloat()
    val w = tagText.measureText(t.text) + 2 * FolloGridOverlay.TAG_PAD_X_DP * density
    val fm = tagText.fontMetrics
    val h = (fm.descent - fm.ascent) + 2 * FolloGridOverlay.TAG_PAD_Y_DP * density
    if (x + w < clip.left || x - w > clip.right || y + w < clip.top || y - w > clip.bottom) return
    c.save()
    c.rotate(-p.orientation, x, y) // upright whichever way the map is turned
    pill.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    c.drawRoundRect(pill, h / 2, h / 2, tagFill)
    tagEdge.color = t.color
    c.drawRoundRect(pill, h / 2, h / 2, tagEdge)
    c.drawText(t.text, x, y - (fm.ascent + fm.descent) / 2, tagText)
    c.restore()
  }

  companion object {
    const val CASING_EXTRA_DP = 2f
    const val CASING_ALPHA = 179 // 70 %
    const val DASH_DP = 10f
    const val GAP_DP = 6f
  }
}
