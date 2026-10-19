package org.owntracks.android.ui.main

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * An XYZ tile source from a URL template with `{z}`, `{x}` and `{y}` placeholders, so the server
 * can hand the phone whichever imagery the web map is using without an app update.
 */
class FolloTileSource(private val template: String) :
    OnlineTileSourceBase("follo", 0, 20, 256, "", arrayOf(template), "Imagery: Esri and partners") {
  override fun getTileURLString(pMapTileIndex: Long): String =
      template
          .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
          .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
          .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}
