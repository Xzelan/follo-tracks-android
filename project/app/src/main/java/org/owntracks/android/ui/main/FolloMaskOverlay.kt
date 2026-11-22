package org.owntracks.android.ui.main

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * The grey mask of FP055 (Alex 19 Nov 2026: "grey right up to the circle's own outline"): everything outside the search
 * area the screen is on — the union of its circles — is covered by one opaque fill, so no imagery, grid, drawing or
 * track shows from outside it. It goes above every line layer and under the pins, team-mates and this phone's arrow.
 *
 * Each circle is cut out of the fill in turn, so where two circles overlap the union stays clear. The circles are the
 * same points the green outline is drawn from (FolloScope.ring), so the mask's edge lies on the line; the outline's
 * outer half, which the fill would hide, is drawn again on top of it. Display only: no taps.
 */
class FolloMaskOverlay(density: Float, lineColor: Int) : Overlay() {
  /** The area's circles as closed rings of flat lat,lon,…; empty = no mask. */
  var rings: List<DoubleArray> = emptyList()

  private val paths = ArrayList<Path>()
  private val line =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = lineColor
        strokeWidth = LINE_DP * density
      }

  override fun draw(c: Canvas, p: Projection) {
    val rings = rings
    if (rings.isEmpty()) return
    while (paths.size < rings.size) paths.add(Path())
    c.save()
    for ((k, r) in rings.withIndex()) {
      val path = paths[k]
      path.rewind()
      var i = 0
      while (i + 1 < r.size) {
        val x = p.getLongPixelXFromLongitude(r[i + 1]).toFloat()
        val y = p.getLongPixelYFromLatitude(r[i]).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i += 2
      }
      path.close()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.clipOutPath(path)
      else @Suppress("DEPRECATION") c.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
    }
    c.drawColor(FolloScope.MASK_COLOR)
    // the outer half of the outline (the clip keeps it outside every circle, so no arc shows inside another circle)
    for (k in rings.indices) c.drawPath(paths[k], line)
    c.restore()
  }

  companion object {
    /** As wide as the circles' own outline. */
    const val LINE_DP = 3f
  }
}
