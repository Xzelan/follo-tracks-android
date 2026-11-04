package org.owntracks.android.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import org.osmdroid.util.RectL
import org.osmdroid.util.TileSystem
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
 *
 * [auto] (Alex, 5 Nov 2026: QLD imagery in TAS): a server template of the state/Esri set means "the imagery for where
 * each tile is" ([FolloImagery]), not one fixed layer, so the server's pick — from the phone's newest STORED fix, which
 * can be a state away now that a phone outside the circles sends no position — changes nothing on screen. One cache
 * name for all of it is safe: the layer is a fixed function of the tile index, so a QLD tile can never be cached under
 * a TAS index. Any other template is drawn as given.
 */
class FolloTileSource
private constructor(private val template: String, val nativeMaxZoom: Int, val auto: Boolean) :
    OnlineTileSourceBase(
        if (auto) AUTO_NAME else "follo-" + Integer.toHexString(template.hashCode()),
        0,
        maxOf(MAP_MAX_ZOOM, nativeMaxZoom),
        256,
        "",
        arrayOf(template),
        if (auto) "" else creditFor(template)) {
  companion object {
    /** The per-tile source's cache name. Bump the number when [FolloImagery]'s outlines change, so no tile cached
     * under the old outlines (another layer at the same index) is shown again. */
    private const val AUTO_NAME = "follo-auto-1"

    /** Only https templates are used; anything else (a crafted setup link, say) gets the built-in imagery. */
    fun of(template: String?, maxZoom: Int = 20): FolloTileSource {
      val t = template?.takeIf { it.startsWith("https://") } ?: org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL
      return if (FolloImagery.isAuto(t)) FolloTileSource(t, FolloImagery.esri.maxZ, true)
      else FolloTileSource(t, maxZoom.coerceIn(1, 22), false)
    }
  }

  /** True when this source already shows what [template] asks for (any state/Esri template = the per-tile source). */
  fun matches(template: String?, maxZoom: Int): Boolean =
      if (auto) FolloImagery.isAuto(template?.takeIf { it.startsWith("https://") } ?: org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL)
      else template == this.template && maxZoom == nativeMaxZoom

  /** Esri World Imagery, which [ShiftedTilesOverlay] draws [ESRI_SHIFT_SOUTH_M] further south. Fixed sources only;
   * the per-tile source asks [isEsriTile]. */
  val isEsri: Boolean
    get() = !auto && "World_Imagery" in template

  /** Whether this tile is Esri imagery (per tile for the [auto] source). */
  fun isEsriTile(x: Int, y: Int, z: Int): Boolean =
      if (auto) FolloImagery.layer(x, y, z) == FolloImagery.esri else isEsri

  /** The deepest level this tile's imagery has; deeper tiles are cut from the tile at that level. */
  fun nativeMaxZoom(index: Long): Int =
      if (auto) FolloImagery.layer(MapTileIndex.getX(index), MapTileIndex.getY(index), MapTileIndex.getZoom(index)).maxZ
      else nativeMaxZoom

  /** The credit for what the map shows around this point at tile zoom [z]. */
  fun creditAt(lat: Double, lon: Double, z: Int): String =
      if (auto) creditFor(FolloImagery.layer(lat, lon, z).template) else copyrightNotice.orEmpty()

  override fun getTileURLString(pMapTileIndex: Long): String {
    val z = MapTileIndex.getZoom(pMapTileIndex)
    val x = MapTileIndex.getX(pMapTileIndex)
    val y = MapTileIndex.getY(pMapTileIndex)
    return FolloImagery.url(if (auto) FolloImagery.layer(x, y, z).template else template, x, y, z)
  }
}

/**
 * Which imagery each map tile shows, chosen ON THE PHONE by where the tile is (Alex, 5 Nov 2026: in TAS the app still
 * drew Queensland imagery — the server picks one layer from the phone's newest STORED fix, and since 1 Nov a phone
 * outside the circles sends no position, so that fix can be a state away). The same state outlines as the worker's
 * `imageryState`: a state layer is blank outside its state. Esri where no state publishes imagery, and for the whole
 * map below zoom [STATE_FROM_Z] (the states mix badly that far out). The same as iOS FolloImagery.
 */
object FolloImagery {
  data class Layer(val template: String, val maxZ: Int)

  val esri = Layer(org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_URL, org.owntracks.android.preferences.FOLLO_DEFAULT_TILE_MAX_ZOOM)
  val tas = Layer("https://services.thelist.tas.gov.au/arcgis/rest/services/Basemaps/Orthophoto/MapServer/tile/{z}/{y}/{x}", 19)
  val qld =
      Layer("https://spatial-img.information.qld.gov.au/arcgis/rest/services/Basemaps/LatestStateProgram_AllUsers/ImageServer/tile/{z}/{y}/{x}", 20)
  val nsw = Layer("https://maps.six.nsw.gov.au/arcgis/rest/services/public/NSW_Imagery/MapServer/tile/{z}/{y}/{x}", 20)
  const val STATE_FROM_Z = 10

  /** A server template from this set means "the imagery for where you look", not one fixed layer. */
  fun isAuto(template: String?): Boolean = listOf(esri, tas, qld, nsw).any { it.template == template }

  // Border points (lon, lat), joined by straight lines (worker QLD_SOUTH / VIC_NORTH)
  private val qldSouth =
      listOf(138.0 to -29.0, 148.95 to -29.0, 150.3 to -28.55, 151.17 to -28.85, 151.93 to -28.92, 152.72 to -28.35, 152.9 to -28.17, 154.0 to -28.17)
  private val vicNorth =
      listOf(
          140.0 to -34.0, 141.0 to -34.0, 142.2 to -34.2, 143.55 to -35.35, 144.75 to -36.13, 146.9 to -36.08, 148.2 to -36.8,
          149.98 to -37.5, 154.0 to -37.5)

  private fun borderLat(line: List<Pair<Double, Double>>, lon: Double): Double {
    for (k in 1 until line.size) {
      val (x0, y0) = line[k - 1]
      val (x1, y1) = line[k]
      if (lon <= x1) return y0 + (y1 - y0) * (lon - x0) / (x1 - x0)
    }
    return line.last().second
  }

  /** The state imagery covering this point, or null where none does (Esri there). */
  fun state(lat: Double, lon: Double): Layer? {
    if (lat < -39.4 && lat > -43.9 && lon > 143.5 && lon < 148.7) return tas
    if (lat > -9 || lat < -37.6 || lon > 154 || lon < 138) return null
    if (lat > borderLat(qldSouth, lon)) return if (lon >= 141 || lat > -26) qld else null // west of 141° only north of 26°S is QLD
    return if (lon >= 141 && lat > borderLat(vicNorth, lon)) nsw else null
  }

  fun layer(lat: Double, lon: Double, z: Int): Layer = if (z >= STATE_FROM_Z) state(lat, lon) ?: esri else esri

  /** The layer for a web-mercator tile, judged at its centre. */
  fun layer(x: Int, y: Int, z: Int): Layer {
    val n = (1 shl z).toDouble()
    val lon = (x + 0.5) / n * 360 - 180
    val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * (y + 0.5) / n))))
    return layer(lat, lon, z)
  }

  fun url(template: String, x: Int, y: Int, z: Int): String =
      template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
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
    // per tile: the auto source's layers stop at different levels (LIST and Esri 19, QLD and NSW 20)
    val native = (source as? FolloTileSource)?.nativeMaxZoom(index) ?: return super.downloadTile(index, redirectCount, url, cache, source)
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
 *
 * The per-tile source (5 Nov 2026) mixes Esri and state tiles on one screen, so there only the Esri TILES are moved:
 * each tile's index is worked out from where osmdroid draws it. Where an Esri tile meets a state tile (a state border
 * at zoom 10 and in, e.g. the Murray) that leaves a 1.5 m seam: a sliver of background above the Esri tile and its
 * bottom 1.5 m over the state tile below — under 3 px even at zoom 20, and far from any search so far.
 */
class ShiftedTilesOverlay(provider: MapTileProviderBase, context: Context) : TilesOverlay(provider, context) {
  private var tileZ = 0
  private var shiftPx = 0f

  override fun draw(c: Canvas, pProjection: Projection) {
    if ((mTileProvider.tileSource as? FolloTileSource)?.isEsri != true) return super.draw(c, pProjection)
    c.save()
    c.translate(0f, pProjection.metersToPixels(ESRI_SHIFT_SOUTH_M))
    super.draw(c, pProjection)
    c.restore()
  }

  override fun drawTiles(c: Canvas, projection: Projection, zoomLevel: Double, viewPort: RectL) {
    // the tile level osmdroid's looper draws at, and the shift for this frame
    tileZ = TileSystem.getInputTileZoomLevel(zoomLevel)
    shiftPx = if ((mTileProvider.tileSource as? FolloTileSource)?.auto == true) projection.metersToPixels(ESRI_SHIFT_SOUTH_M) else 0f
    super.drawTiles(c, projection, zoomLevel, viewPort)
  }

  override fun onTileReadyToDraw(c: Canvas, currentMapTile: Drawable, tileRect: Rect) {
    val source = mTileProvider.tileSource as? FolloTileSource
    val p = projection
    if (shiftPx == 0f || source == null || !source.auto || p == null) return super.onTileReadyToDraw(c, currentMapTile, tileRect)
    // the tile under this rectangle (the looper's index is not passed on); x wraps round the world
    val n = 1 shl tileZ
    val x = Math.floorMod(p.getTileFromMercator(p.getMercatorXFromPixel(tileRect.centerX())), n)
    val y = p.getTileFromMercator(p.getMercatorYFromPixel(tileRect.centerY())).coerceIn(0, n - 1)
    if (!source.isEsriTile(x, y, tileZ)) return super.onTileReadyToDraw(c, currentMapTile, tileRect)
    c.save()
    c.translate(0f, shiftPx)
    super.onTileReadyToDraw(c, currentMapTile, tileRect)
    c.restore()
  }
}
