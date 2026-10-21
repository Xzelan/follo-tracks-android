package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Canvas
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.TilesOverlay

/**
 * An XYZ tile source from a URL template with `{z}`, `{x}` and `{y}` placeholders, so the server
 * can hand the phone whichever imagery the web map is using without an app update.
 */
class FolloTileSource private constructor(private val template: String) :
    OnlineTileSourceBase("follo", 0, 20, 256, "", arrayOf(template), "Imagery: Esri and partners") {
  companion object {
    /** Only https templates are used; anything else (a crafted setup link, say) gets the built-in imagery. */
    fun of(template: String?): FolloTileSource =
        FolloTileSource(template?.takeIf { it.startsWith("https://") } ?: org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL)
  }

  /** Esri World Imagery, which [ShiftedTilesOverlay] draws [ESRI_SHIFT_SOUTH_M] further south. */
  val isEsri: Boolean
    get() = "World_Imagery" in template

  override fun getTileURLString(pMapTileIndex: Long): String =
      template
          .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
          .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
          .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

/**
 * Esri World Imagery sits ~1.5 m NORTH of true ground in Australia (21 Oct 2026: a ZED-X20P rover on a road-marking
 * corner at Bli Bli matched the QLD 10 cm imagery within 0.5 m and Esri ~1.9 m north; tile correlation against the
 * QLD and LIST imagery agrees, east–west inconsistent so left alone). The web map shifts it by the same amount.
 */
const val ESRI_SHIFT_SOUTH_M = 1.5f

/**
 * The map's tile layer, drawing Esri imagery [ESRI_SHIFT_SOUTH_M] south of where its tiles say. Tiles are drawn in
 * the north-up pixel space (the map rotates the canvas around them), so south is +y at any compass rotation.
 */
class ShiftedTilesOverlay(provider: MapTileProviderBase, context: Context) : TilesOverlay(provider, context) {
  override fun draw(c: Canvas, pProjection: Projection) {
    if ((mTileProvider.tileSource as? FolloTileSource)?.isEsri != true) return super.draw(c, pProjection)
    c.save()
    c.translate(0f, pProjection.metersToPixels(ESRI_SHIFT_SOUTH_M))
    super.draw(c, pProjection)
    c.restore()
  }
}
