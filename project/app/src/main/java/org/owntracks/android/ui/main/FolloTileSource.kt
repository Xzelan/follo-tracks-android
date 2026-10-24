package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.TilesOverlay

/**
 * An XYZ tile source from a URL template with `{z}`, `{x}` and `{y}` placeholders, so the server
 * can hand the phone whichever imagery the web map is using without an app update.
 *
 * [nativeMaxZoom] is the deepest level the imagery has (LIST: 19, which answers z20 with a blank tile); the source
 * still offers every level to [MAP_MAX_ZOOM] and [FolloTileProvider] cuts the deeper ones out of the native level.
 * The name, which keys osmdroid's tile cache, comes from the template, so tiles of one imagery are never shown for
 * another after a switch (24 Oct 2026: Esri → state imagery).
 */
class FolloTileSource private constructor(private val template: String, val nativeMaxZoom: Int) :
    OnlineTileSourceBase(
        "follo-" + Integer.toHexString(template.hashCode()),
        0,
        maxOf(MAP_MAX_ZOOM, nativeMaxZoom),
        256,
        "",
        arrayOf(template),
        creditFor(template)) {
  companion object {
    /** Only https templates are used; anything else (a crafted setup link, say) gets the built-in imagery. */
    fun of(template: String?, maxZoom: Int = 20): FolloTileSource =
        template?.takeIf { it.startsWith("https://") }?.let { FolloTileSource(it, maxZoom.coerceIn(1, 22)) }
            ?: FolloTileSource(
                org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL,
                org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_MAX_ZOOM)
  }

  fun matches(template: String?, maxZoom: Int): Boolean = template == this.template && maxZoom == nativeMaxZoom

  /** Esri World Imagery, which [ShiftedTilesOverlay] draws [ESRI_SHIFT_SOUTH_M] further south. */
  val isEsri: Boolean
    get() = "World_Imagery" in template

  override fun getTileURLString(pMapTileIndex: Long): String =
      template
          .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
          .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
          .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

/** The deepest zoom the map allows. */
const val MAP_MAX_ZOOM = 20

/**
 * The map's tile provider: osmdroid's own (memory, file cache, enlarging cached tiles offline, download), except that a
 * tile deeper than its imagery's [FolloTileSource.nativeMaxZoom] is cut from the native-level tile above it and
 * enlarged — like the web map's maxNativeZoom. osmdroid on its own only enlarges tiles already in the file cache, so
 * zoomed right in over new ground (a volunteer walking) the map would stay blank.
 */
class FolloTileProvider(context: Context, source: ITileSource) : MapTileProviderBasic(context, source) {
  override fun createDownloaderProvider(network: INetworkAvailablityCheck?, source: ITileSource?): MapTileDownloader =
      MapTileDownloader(source, tileWriter, network).apply { setTileDownloader(EnlargingTileDownloader()) }
}

private class EnlargingTileDownloader : TileDownloader() {
  override fun downloadTile(
      index: Long,
      redirectCount: Int,
      url: String?,
      cache: IFilesystemCache?,
      source: OnlineTileSourceBase
  ): Drawable? {
    val native = (source as? FolloTileSource)?.nativeMaxZoom ?: return super.downloadTile(index, redirectCount, url, cache, source)
    val dz = MapTileIndex.getZoom(index) - native
    if (dz <= 0) return super.downloadTile(index, redirectCount, url, cache, source)
    val parent = MapTileIndex.getTileIndex(native, MapTileIndex.getX(index) shr dz, MapTileIndex.getY(index) shr dz)
    // the parent from the file cache if it is there (its four children share it), else downloaded and cached
    val got =
        runCatching { cache?.loadTile(source, parent) }.getOrNull()
            ?: super.downloadTile(parent, redirectCount, source.getTileURLString(parent), cache, source)
    val bitmap = (got as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled } ?: return null
    val n = 1 shl dz
    val w = bitmap.width / n
    val h = bitmap.height / n
    if (w < 1 || h < 1) return null
    val x = (MapTileIndex.getX(index) - (MapTileIndex.getX(parent) shl dz)) * w
    val y = (MapTileIndex.getY(index) - (MapTileIndex.getY(parent) shl dz)) * h
    val piece = Bitmap.createBitmap(bitmap, x, y, w, h)
    @Suppress("DEPRECATION") // osmdroid draws tiles with no Resources too
    return BitmapDrawable(Bitmap.createScaledBitmap(piece, bitmap.width, bitmap.height, true))
  }
}

/** The credit line under the map, by imagery host (each licence asks for one). */
internal fun creditFor(template: String): String =
    when {
      "thelist.tas.gov.au" in template -> "Imagery © State of Tasmania (LIST)"
      "information.qld.gov.au" in template -> "Imagery © State of Queensland"
      "six.nsw.gov.au" in template -> "Imagery © NSW Spatial Services"
      "World_Imagery" in template -> "Imagery: Esri and partners"
      else -> ""
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
