package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * The search grids on the map (Alex, 10 Nov 2026; docs/OFFLINE_GRIDS_20261110.md §3; the same on iOS): every team's
 * lines in its colour over a white casing, the phone's own team bold, a name tag at each grid's start. Display only:
 * no taps. One overlay for all of it — MV alone is ~2,300 lines, far too many to be Polyline objects — projecting the
 * points of the areas on screen at every draw.
 *
 * Lines from map zoom [MIN_ZOOM_LINES], names from [MIN_ZOOM_NAMES]. Own team [OWN_DP] wide and opaque; other teams
 * [OTHER_DP] wide at [OTHER_ALPHA]; the casing white at [CASING_ALPHA], [CASING_EXTRA_DP] wider than its line.
 */
class FolloGridOverlay(private val density: Float, scaledDensity: Float) : Overlay() {
  var grid: FolloGrid? = null
  /** The phone's own team (status `team`); its lines are the bold ones. */
  var ownTeam: String? = null

  private val casing =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        alpha = CASING_ALPHA
      }
  private val line =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
      }
  private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK; alpha = TAG_ALPHA }
  private val tagEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = TAG_EDGE_DP * density }
  private val tagText =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TAG_TEXT_SP * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
  private var buf = FloatArray(8192)
  private val clip = Rect()
  private val pill = RectF()

  override fun draw(c: Canvas, p: Projection) {
    val g = grid ?: return
    val zoom = p.zoomLevel
    if (zoom < MIN_ZOOM_LINES) return
    val view = p.boundingBox
    if (!c.getClipBounds(clip)) return
    for (a in g.areas) {
      if (a.north < view.latSouth || a.south > view.latNorth || a.east < view.lonWest || a.west > view.lonEast) continue
      // the cells past a U-turn (15 Nov 2026): grey, thin for every team, under all the coloured lines
      for (t in a.teams) if (t.grey.isNotEmpty()) drawLines(c, p, t.grey, GREY_COLOR, OTHER_DP * density, 255)
      // the phone's own team last, so its bold lines lie over the others where grids meet
      for (t in a.teams) if (!FolloGrid.sameTeam(t.name, ownTeam)) drawTeam(c, p, t, own = false)
      for (t in a.teams) if (FolloGrid.sameTeam(t.name, ownTeam)) drawTeam(c, p, t, own = true)
    }
    if (zoom < MIN_ZOOM_NAMES) return
    for (a in g.areas) {
      if (a.north < view.latSouth || a.south > view.latNorth || a.east < view.lonWest || a.west > view.lonEast) continue
      for (n in a.names) drawName(c, p, n)
    }
  }

  private fun drawTeam(c: Canvas, p: Projection, t: FolloGrid.Team, own: Boolean) {
    drawLines(c, p, t.lines, t.color, (if (own) OWN_DP else OTHER_DP) * density, if (own) 255 else OTHER_ALPHA)
  }

  /** Lines in one colour over the white casing; pieces wholly off screen are not drawn. */
  private fun drawLines(c: Canvas, p: Projection, lines: List<DoubleArray>, color: Int, width: Float, alpha: Int) {
    val reach = width + CASING_EXTRA_DP * density
    val left = clip.left - reach
    val right = clip.right + reach
    val top = clip.top - reach
    val bottom = clip.bottom + reach
    var n = 0
    for (l in lines) {
      var x0 = p.getLongPixelXFromLongitude(l[1]).toFloat()
      var y0 = p.getLongPixelYFromLatitude(l[0]).toFloat()
      var i = 2
      while (i + 1 < l.size) {
        val x1 = p.getLongPixelXFromLongitude(l[i + 1]).toFloat()
        val y1 = p.getLongPixelYFromLatitude(l[i]).toFloat()
        // a piece wholly off one side of the screen is not drawn
        if (!((x0 < left && x1 < left) || (x0 > right && x1 > right) || (y0 < top && y1 < top) || (y0 > bottom && y1 > bottom))) {
          if (n + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
          buf[n++] = x0
          buf[n++] = y0
          buf[n++] = x1
          buf[n++] = y1
        }
        x0 = x1
        y0 = y1
        i += 2
      }
    }
    if (n == 0) return
    casing.strokeWidth = width + CASING_EXTRA_DP * density
    line.strokeWidth = width
    line.color = color
    line.alpha = alpha
    c.drawLines(buf, 0, n, casing)
    c.drawLines(buf, 0, n, line)
  }

  private fun drawName(c: Canvas, p: Projection, n: FolloGrid.Name) {
    val x = p.getLongPixelXFromLongitude(n.lon).toFloat()
    val y = p.getLongPixelYFromLatitude(n.lat).toFloat()
    val w = tagText.measureText(n.text) + 2 * TAG_PAD_X_DP * density
    val fm = tagText.fontMetrics
    val h = (fm.descent - fm.ascent) + 2 * TAG_PAD_Y_DP * density
    if (x + w < clip.left || x - w > clip.right || y + w < clip.top || y - w > clip.bottom) return
    c.save()
    c.rotate(-p.orientation, x, y) // upright whichever way the map is turned
    pill.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    c.drawRoundRect(pill, h / 2, h / 2, tagFill)
    tagEdge.color = n.color
    c.drawRoundRect(pill, h / 2, h / 2, tagEdge)
    c.drawText(n.text, x, y - (fm.ascent + fm.descent) / 2, tagText)
    c.restore()
  }

  companion object {
    const val MIN_ZOOM_LINES = 14.0
    const val MIN_ZOOM_NAMES = 15.0
    const val OWN_DP = 3f
    const val OTHER_DP = 1.5f
    const val CASING_EXTRA_DP = 2f
    const val CASING_ALPHA = 179 // 70 %
    const val OTHER_ALPHA = 217 // 85 %
    /** The cells past a U-turn (spec 15 Nov 2026): #8E949A, opaque. */
    const val GREY_COLOR = -0x716b66 // 0xFF8E949A
    const val TAG_ALPHA = 166 // 65 % black
    const val TAG_EDGE_DP = 2f
    const val TAG_TEXT_SP = 12f
    const val TAG_PAD_X_DP = 7f
    const val TAG_PAD_Y_DP = 3f
  }
}
