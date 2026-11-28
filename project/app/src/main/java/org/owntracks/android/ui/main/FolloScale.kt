package org.owntracks.android.ui.main

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/**
 * True-scale drawing, the same as the live map (28 Nov 2026: "Tracklogs should be 1 metre at scale", people's dots
 * "always 0.5m at scale and the labels use the other method so we get best of both worlds"; own track "1 m too"):
 * - every tracklog, this phone's own track included, is [TRACK_M] wide ON THE GROUND, so it thins as the map zooms
 *   out, down to a small floor that keeps it visible ([TRACK_MIN_DP]);
 * - a person's dot is ALWAYS [DOT_M] across on the ground, its black edge included, with no minimum size: zoomed out
 *   it is just the edge, a black speck, and the team colour is gone (the label still says who);
 * - the label stays screen-sized and its pointer's tip sits ON the dot's edge at every zoom.
 *
 * Plain numbers only (no Android classes), so the rules are unit-tested. The overlays hand in the map's pixels per
 * metre (osmdroid `Projection.metersToPixels(1f, latitude, zoom)`, which knows the tile size on this screen).
 */
object FolloScale {
  /** A tracklog's width on the ground, metres. */
  const val TRACK_M = 1f
  /** A tracklog is never drawn thinner than this (dp), so it does not fade out when the map is zoomed out. */
  const val TRACK_MIN_DP = 0.5f
  /** A person's dot on the ground, metres across, edge included. */
  const val DOT_M = 0.5f

  private const val EQUATOR_M = 40_075_016.686

  /**
   * Screen pixels per metre of ground at [latitude] and [zoom] for tiles [tilePx] across: the web-map rule, and what
   * the live map's `trkW()` / `dotR()` work from (there 256). For the tests and for checking osmdroid's own figure.
   */
  fun pxPerMetre(latitude: Double, zoom: Double, tilePx: Double = 256.0): Double =
      tilePx * 2.0.pow(zoom) / (EQUATOR_M * cos(latitude * PI / 180))

  /** A tracklog's line width in pixels: [TRACK_M] on the ground, at least [TRACK_MIN_DP]. */
  fun trackWidthPx(pxPerMetre: Float, density: Float): Float {
    val floor = TRACK_MIN_DP * density
    return if (pxPerMetre.isFinite()) max(TRACK_M * pxPerMetre, floor) else floor
  }

  /**
   * The dot's OUTER radius in pixels (its black edge, [edgePx] thick, included): half of [DOT_M] on the ground. No
   * minimum but the edge itself: once the dot is narrower than its edge it is a speck [edgePx] across.
   */
  fun dotOuterRadiusPx(pxPerMetre: Float, edgePx: Float): Float =
      if (pxPerMetre.isFinite()) max(DOT_M * pxPerMetre, edgePx) / 2 else edgePx / 2

  /**
   * The radius the edge is stroked at (a stroke is centred on its circle) and the colour is filled to; 0 when the dot
   * is no wider than its edge, i.e. there is no colour left to show.
   */
  fun dotStrokeRadiusPx(outerRadiusPx: Float, edgePx: Float): Float = max(0f, outerRadiusPx - edgePx / 2)

  /** How far right of the dot's middle the label's pointer tip sits: on the dot's outer edge, whatever its size. */
  fun labelTipOffsetPx(outerRadiusPx: Float): Float = outerRadiusPx
}
