package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * What the phones-in-circles drop-down draws (FolloWatch; iOS FolloViewController "Every phone in a search circle").
 * Two of these go on the map:
 * - [Kind.LOGS]: every phone's line but the one the map is on, magenta, 1 m on the ground like the drawn tracklogs, no
 *   casing (Alex 13 Nov 2026: "It should have turned magenta and become part of all the other tracklogs"). Straight
 *   above the search grids, under the drawings.
 * - [Kind.TOP]: the chosen search's outline in its colour (18 Nov 2026), the watched phone's line in lime, and a dot per
 *   person ([FolloDots], 22 Nov 2026: the phones in circles AND this phone's team-mates, drawn alike). Over everything
 *   but this phone's own arrow.
 * Display only: no taps.
 */
class FolloWatchOverlay(private val kind: Kind, private val density: Float, scaledDensity: Float) : Overlay() {
  enum class Kind {
    LOGS,
    TOP,
  }

  /** Lines as flat lat,lon,… arrays */
  var logs: List<DoubleArray> = emptyList()
  var live: List<DoubleArray> = emptyList()
  /** The chosen search's outline (closed by the drawing), and its colour */
  var outline: DoubleArray? = null
  var outlineColor: Int = Color.WHITE
  var dots: List<FolloDots.Dot> = emptyList()

  private val stroke =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }
  private val casing =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        alpha = 110
      }
  private val dotPainter = FolloDots.Painter(density, scaledDensity)
  private var buf = FloatArray(4096)
  private val clip = Rect()

  override fun draw(c: Canvas, p: Projection) {
    if (!c.getClipBounds(clip)) return
    when (kind) {
      Kind.LOGS -> {
        if (logs.isEmpty()) return
        val pxPerMetre = p.metersToPixels(1f, p.currentCenter.latitude, p.zoomLevel)
        val w = (1f * pxPerMetre).takeIf { it.isFinite() }?.coerceAtLeast(LOG_MIN_DP * density) ?: (LOG_MIN_DP * density)
        stroke.color = TRACK
        stroke.strokeWidth = w
        for (l in logs) drawLine(c, p, l, false, stroke, null)
      }
      Kind.TOP -> {
        outline?.let { o ->
          stroke.color = outlineColor
          stroke.strokeWidth = OUTLINE_DP * density
          casing.strokeWidth = (OUTLINE_DP + 2f) * density
          drawLine(c, p, o, true, stroke, casing)
        }
        if (live.isNotEmpty()) {
          stroke.color = LIME
          stroke.strokeWidth = LIVE_DP * density
          casing.strokeWidth = (LIVE_DP + 2f) * density
          for (l in live) drawLine(c, p, l, false, stroke, casing)
        }
        for (d in dots) drawDot(c, p, d)
      }
    }
  }

  /** One line (flat lat,lon,…) as segments; segments wholly off screen are left out. */
  private fun drawLine(c: Canvas, p: Projection, l: DoubleArray, closed: Boolean, paint: Paint, under: Paint?) {
    if (l.size < 4) return
    val reach = paint.strokeWidth + 4f
    var n = 0
    var x0 = p.getLongPixelXFromLongitude(l[1]).toFloat()
    var y0 = p.getLongPixelYFromLatitude(l[0]).toFloat()
    val fx = x0
    val fy = y0
    var i = 2
    val count = if (closed) l.size + 2 else l.size
    while (i + 1 < count) {
      val lat = if (i < l.size) l[i] else l[0]
      val lon = if (i < l.size) l[i + 1] else l[1]
      val x1 = if (i < l.size) p.getLongPixelXFromLongitude(lon).toFloat() else fx
      val y1 = if (i < l.size) p.getLongPixelYFromLatitude(lat).toFloat() else fy
      if (!((x0 < clip.left - reach && x1 < clip.left - reach) || (x0 > clip.right + reach && x1 > clip.right + reach) ||
              (y0 < clip.top - reach && y1 < clip.top - reach) || (y0 > clip.bottom + reach && y1 > clip.bottom + reach))) {
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
    if (n == 0) return
    under?.let { c.drawLines(buf, 0, n, it) }
    c.drawLines(buf, 0, n, paint)
  }

  private fun drawDot(c: Canvas, p: Projection, d: FolloDots.Dot) {
    val x = p.getLongPixelXFromLongitude(d.lon).toFloat()
    val y = p.getLongPixelYFromLatitude(d.lat).toFloat()
    val r = dotPainter.reach // the label may point any way on a turned map
    if (x + r < clip.left || x - r > clip.right || y + r < clip.top || y - r > clip.bottom) return
    dotPainter.draw(c, x, y, p.orientation, d)
  }

  companion object {
    /** The tracklogs' magenta (follo_track) */
    const val TRACK = -0xff01 // 0xFFFF00FF
    /** The watched phone's line: lime, unlike every magenta line (iOS 0x76FF03) */
    const val LIME = -0x8900fd // 0xFF76FF03
    const val LIVE_DP = 3f
    const val OUTLINE_DP = 3f
    const val LOG_MIN_DP = 0.5f
  }
}
