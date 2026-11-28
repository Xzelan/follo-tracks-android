package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * The line and point layers of the simple screen (Alex, 19 Nov 2026; SIMPLE_MODE_SPEC §6), one overlay per kind so
 * each sits at its own height on the map:
 * - [Kind.CONTOURS]: brown rgb(180,120,60) at 90 %, 1.2 dp; every 50 m 2 dp with its height in upright brown bold text
 *   with a white halo at the line's middle, from map zoom 16. Lines from zoom 13.
 * - [Kind.TRACKLOGS]: magenta, 1 m on the ground like the other phones' logs.
 * - [Kind.POI]: a yellow pin from zoom 12, its name (white bold on a 65 % black pill) under it whenever the pin shows (20 Nov 2026; was from zoom 15).
 * Display only: no taps. The points of what is on screen are projected at every draw, as the search grids are.
 */
class FolloLayOverlay(private val kind: Kind, private val density: Float, scaledDensity: Float) : Overlay() {
  enum class Kind {
    CONTOURS,
    TRACKLOGS,
    POI,
  }

  var contours: List<FolloLay.Contour> = emptyList()
  var lines: List<DoubleArray> = emptyList()
  var pois: List<FolloLay.Poi> = emptyList()

  private val stroke =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }
  private val text =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
      }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val pinText = 12f * scaledDensity
  private val heightText = 11f * scaledDensity
  private var buf = FloatArray(8192)
  private val clip = Rect()
  private val pill = RectF()
  private val pin = Path()

  override fun draw(c: Canvas, p: Projection) {
    if (!c.getClipBounds(clip)) return
    val zoom = p.zoomLevel
    when (kind) {
      Kind.CONTOURS -> {
        if (contours.isEmpty() || zoom < CONTOUR_MIN_ZOOM) return
        stroke.color = BROWN
        stroke.alpha = CONTOUR_ALPHA
        var n = 0
        for (ct in contours) if (!FolloLay.isIndexContour(ct.elev)) n = collect(p, ct.line, n, 2f * density)
        stroke.strokeWidth = 1.2f * density
        if (n > 0) c.drawLines(buf, 0, n, stroke)
        n = 0
        for (ct in contours) if (FolloLay.isIndexContour(ct.elev)) n = collect(p, ct.line, n, 2f * density)
        stroke.strokeWidth = 2f * density
        if (n > 0) c.drawLines(buf, 0, n, stroke)
        if (zoom < CONTOUR_LABEL_ZOOM) return
        text.textSize = heightText
        val fm = text.fontMetrics
        for (ct in contours) {
          if (!FolloLay.isIndexContour(ct.elev)) continue
          val mid = (ct.line.size / 4) * 2 // the line's middle point
          val x = p.getLongPixelXFromLongitude(ct.line[mid + 1]).toFloat()
          val y = p.getLongPixelYFromLatitude(ct.line[mid]).toFloat()
          if (x < clip.left - 40 || x > clip.right + 40 || y < clip.top - 40 || y > clip.bottom + 40) continue
          val label = Math.round(ct.elev).toString()
          c.save()
          c.rotate(-p.orientation, x, y) // upright whichever way the map is turned
          val base = y - (fm.ascent + fm.descent) / 2
          text.style = Paint.Style.STROKE
          text.strokeWidth = 3f * density
          text.color = Color.WHITE
          c.drawText(label, x, base, text)
          text.style = Paint.Style.FILL
          text.color = BROWN
          c.drawText(label, x, base, text)
          c.restore()
        }
      }
      Kind.TRACKLOGS -> {
        if (lines.isEmpty()) return
        val w = FolloScale.trackWidthPx(p.metersToPixels(1f, p.currentCenter.latitude, zoom), density)
        stroke.color = FolloWatchOverlay.TRACK
        stroke.alpha = 255
        stroke.strokeWidth = w
        var n = 0
        for (l in lines) n = collect(p, l, n, w)
        if (n > 0) c.drawLines(buf, 0, n, stroke)
      }
      Kind.POI -> {
        if (pois.isEmpty() || zoom < POI_MIN_ZOOM) return
        val s = density * 1.25f // the 24-box pin drawn 30 dp tall
        text.textSize = pinText
        val fm = text.fontMetrics
        for (poi in pois) {
          val x = p.getLongPixelXFromLongitude(poi.lon).toFloat()
          val y = p.getLongPixelYFromLatitude(poi.lat).toFloat()
          if (x < clip.left - 60 * density || x > clip.right + 60 * density || y < clip.top - 60 * density || y > clip.bottom + 60 * density) continue
          c.save()
          c.rotate(-p.orientation, x, y)
          // the pin's tip on the point
          pin.reset()
          pin.moveTo(x, y)
          pin.cubicTo(x, y, x - 7 * s, y - 7.6f * s, x - 7 * s, y - 13 * s)
          pin.arcTo(x - 7 * s, y - 20 * s, x + 7 * s, y - 6 * s, 180f, 180f, false)
          pin.cubicTo(x + 7 * s, y - 7.6f * s, x, y, x, y)
          pin.close()
          fill.color = PIN
          c.drawPath(pin, fill)
          stroke.color = INK
          stroke.alpha = 255
          stroke.strokeWidth = 1.5f * s
          c.drawPath(pin, stroke)
          fill.color = INK
          c.drawCircle(x, y - 13 * s, 2.6f * s, fill)
          // the name shows whenever the pin does (Alex 20 Nov 2026: "They will be labelled all the time")
          if (poi.name.isNotBlank()) {
            val w = text.measureText(poi.name) + 14f * density
            val h = (fm.descent - fm.ascent) + 6f * density
            val top = y + 4f * density
            pill.set(x - w / 2, top, x + w / 2, top + h)
            fill.color = Color.BLACK
            fill.alpha = 166 // 65 %
            c.drawRoundRect(pill, h / 2, h / 2, fill)
            fill.alpha = 255
            text.style = Paint.Style.FILL
            text.color = Color.WHITE
            c.drawText(poi.name, x, top + h / 2 - (fm.ascent + fm.descent) / 2, text)
          }
          c.restore()
        }
      }
    }
  }

  /** The on-screen pieces of a flat lat,lon,… line appended to [buf] from [n]; the new count. */
  private fun collect(p: Projection, l: DoubleArray, from: Int, reach: Float): Int {
    var n = from
    val left = clip.left - reach
    val right = clip.right + reach
    val top = clip.top - reach
    val bottom = clip.bottom + reach
    var x0 = p.getLongPixelXFromLongitude(l[1]).toFloat()
    var y0 = p.getLongPixelYFromLatitude(l[0]).toFloat()
    var i = 2
    while (i + 1 < l.size) {
      val x1 = p.getLongPixelXFromLongitude(l[i + 1]).toFloat()
      val y1 = p.getLongPixelYFromLatitude(l[i]).toFloat()
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
    return n
  }

  companion object {
    const val BROWN = -0x4b87c4 // 0xFFB4783C = rgb(180,120,60)
    const val CONTOUR_ALPHA = 230 // 90 %
    const val CONTOUR_MIN_ZOOM = 13.0
    const val CONTOUR_LABEL_ZOOM = 16.0
    const val POI_MIN_ZOOM = 12.0
    const val PIN = -0x174b00 // 0xFFE8B500
    const val INK = -0xeeeeef // 0xFF111111
  }
}
