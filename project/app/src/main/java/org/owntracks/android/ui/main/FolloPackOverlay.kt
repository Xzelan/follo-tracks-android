package org.owntracks.android.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.ln
import org.osmdroid.util.TileSystem
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * A tile pack on the map (Alex, 19 Nov 2026; SIMPLE_MODE_SPEC §5–6): the hillshade (a base map, in place of the
 * imagery) or the slope colours (over whichever base). The tiles come straight out of the pack file the phone already
 * holds ([FolloPack]) — nothing is fetched as the map moves. Past the pack's deepest level its tiles are drawn larger;
 * where the pack has no tile nothing is drawn. Tiles are decoded off the main thread and kept in a small cache.
 */
class FolloPackOverlay(private val opaque: Boolean, private val redraw: () -> Unit) : Overlay() {
  var pack: FolloPack? = null
    set(value) {
      if (field === value) return
      field = value
      cache.evictAll()
      failed.clear()
      generation++
    }

  @Volatile private var generation = 0
  private val cache = LruCache<Long, Bitmap>(CACHE_TILES)
  private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()
  private val failed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
  private val exec = Executors.newSingleThreadExecutor()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val dst = RectF()

  private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

  override fun draw(c: Canvas, p: Projection) {
    val pk = pack ?: return
    if (pk.maxZ < pk.minZ) return
    // the level whose tiles are about one picture pixel per screen pixel here, kept inside what the pack has
    val want = p.zoomLevel + ln(TileSystem.getTileSize() / 256.0) / ln(2.0)
    val z = ceil(want - 0.5).toInt().coerceIn(pk.minZ, pk.maxZ)
    val view = p.boundingBox
    val x0 = FolloOffline.tileX(view.lonWest, z)
    val x1 = FolloOffline.tileX(view.lonEast, z)
    val y0 = FolloOffline.tileY(view.latNorth, z)
    val y1 = FolloOffline.tileY(view.latSouth, z)
    if (x1 < x0 || y1 < y0 || (x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_TILES) return
    for (x in x0..x1) {
      for (y in y0..y1) {
        if (!pk.has(z, x, y)) continue
        val k = key(z, x, y)
        val b = cache.get(k)
        if (b == null) {
          load(pk, z, x, y, k)
          continue
        }
        dst.set(
            p.getLongPixelXFromLongitude(FolloOffline.tileLon(x, z)).toFloat() - 0.5f,
            p.getLongPixelYFromLatitude(FolloOffline.tileLat(y, z)).toFloat() - 0.5f,
            p.getLongPixelXFromLongitude(FolloOffline.tileLon(x + 1, z)).toFloat() + 0.5f,
            p.getLongPixelYFromLatitude(FolloOffline.tileLat(y + 1, z)).toFloat() + 0.5f,
        )
        c.drawBitmap(b, null, dst, paint)
      }
    }
  }

  private fun load(pk: FolloPack, z: Int, x: Int, y: Int, k: Long) {
    if (k in failed || !pending.add(k)) return
    val gen = generation
    exec.execute {
      try {
        val bytes = pk.tile(z, x, y)
        val options = BitmapFactory.Options().apply { inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888 }
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) }
        if (gen == generation) {
          if (bitmap != null) cache.put(k, bitmap) else failed.add(k)
          if (bitmap != null) redraw()
        }
      } catch (e: Throwable) {
        failed.add(k)
      } finally {
        pending.remove(k)
      }
    }
  }

  companion object {
    private const val CACHE_TILES = 72
    private const val MAX_TILES = 400L
  }
}
