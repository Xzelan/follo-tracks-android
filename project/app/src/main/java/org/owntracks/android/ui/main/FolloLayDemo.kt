package org.owntracks.android.ui.main

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.owntracks.android.data.repos.FolloGridRepo
import org.owntracks.android.data.repos.FolloLayRepo

/**
 * Debug builds only (MainActivity `follo_lay_demo`; SIMPLE_MODE_SPEC §10): every layer of the simple screen made up on
 * the phone for one made-up search circle — a hillshade pack, a slope pack, contours, POIs, tracklogs and a small
 * search grid — so the screens can be looked at with no server and no real search area. The ground is three smooth
 * hills; the hillshade, the slope colours and the contours all come from the same heights. Nothing is fetched or sent.
 */
object FolloLayDemo {
  private class Hill(val east: Double, val north: Double, val sigma: Double, val height: Double)

  private const val M_PER_DEG = 111_194.93

  /** Height (m) and its slope east / north at a point [e], [n] metres from the circle's centre. */
  private fun ground(hills: List<Hill>, e: Double, n: Double): DoubleArray {
    var h = 120.0
    var dx = 0.0
    var dy = 0.0
    for (k in hills) {
      val ex = e - k.east
      val ny = n - k.north
      val g = k.height * exp(-(ex * ex + ny * ny) / (2 * k.sigma * k.sigma))
      h += g
      dx += -g * ex / (k.sigma * k.sigma)
      dy += -g * ny / (k.sigma * k.sigma)
    }
    return doubleArrayOf(h, dx, dy)
  }

  private fun pack(tiles: List<Triple<IntArray, ByteArray, Unit>>): ByteArray {
    val sorted = tiles.sortedWith(compareBy({ it.first[0] }, { it.first[1] }, { it.first[2] }))
    val head = 8 + 20 * sorted.size
    val out = ByteBuffer.allocate(head + sorted.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
    out.put("FTP1".toByteArray(Charsets.US_ASCII))
    out.putInt(sorted.size)
    var offset = head
    for ((zxy, bytes, _) in sorted) {
      out.putInt(zxy[0]).putInt(zxy[1]).putInt(zxy[2]).putInt(offset).putInt(bytes.size)
      offset += bytes.size
    }
    for ((_, bytes, _) in sorted) out.put(bytes)
    return out.array()
  }

  fun make(repo: FolloLayRepo, grids: FolloGridRepo, area: String, lat: Double, lon: Double, r: Double) {
    val cosLat = cos(Math.toRadians(lat))
    val hills =
        listOf(
            // heights in step with the circle's size, so the slopes are the same whatever its radius
            Hill(-0.25 * r, 0.2 * r, 0.45 * r, 260.0 * r / 600.0),
            Hill(0.4 * r, -0.3 * r, 0.3 * r, 150.0 * r / 600.0),
            Hill(0.1 * r, 0.55 * r, 0.15 * r, 120.0 * r / 600.0),
        )
    fun toE(lo: Double) = (lo - lon) * M_PER_DEG * cosLat
    fun toN(la: Double) = (la - lat) * M_PER_DEG
    fun toLat(n: Double) = lat + n / M_PER_DEG
    fun toLon(e: Double) = lon + e / (M_PER_DEG * cosLat)

    // ── the two tile packs, z13–16, every tile touching the circle
    val hs = ArrayList<Triple<IntArray, ByteArray, Unit>>()
    val sl = ArrayList<Triple<IntArray, ByteArray, Unit>>()
    val circle = FolloRules.Circle(lat, lon, r, area)
    val pixels = IntArray(256 * 256)
    val slopePx = IntArray(256 * 256)
    for (z in 13..16) {
      val tiles = LinkedHashSet<Long>()
      FolloOffline.tilesOfCircle(circle, z, tiles)
      for (t in tiles) {
        val x = org.osmdroid.util.MapTileIndex.getX(t)
        val y = org.osmdroid.util.MapTileIndex.getY(t)
        val west = FolloOffline.tileLon(x, z)
        val east = FolloOffline.tileLon(x + 1, z)
        var any = false
        for (py in 0 until 256) {
          // the row's latitude through the mercator row, so the picture sits where the tile says
          val la = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * (y + (py + 0.5) / 256.0) / (1 shl z).toDouble()))))
          for (px in 0 until 256) {
            val lo = west + (east - west) * (px + 0.5) / 256.0
            val g = ground(hills, toE(lo), toN(la))
            val len = sqrt(g[1] * g[1] + g[2] * g[2] + 1.0)
            // light from the north-west, 45° up
            val shade = ((-g[1] * -0.5 + -g[2] * 0.5 + 0.7071) / len).coerceIn(0.0, 1.0)
            val v = (40 + 215 * shade).toInt().coerceIn(0, 255)
            pixels[py * 256 + px] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            val deg = Math.toDegrees(atan(sqrt(g[1] * g[1] + g[2] * g[2])))
            val c =
                when {
                  deg >= 40 -> 0xFF204D
                  deg >= 35 -> 0xFF9E17
                  deg >= 30 -> 0xFFFF03
                  deg >= 25 -> 0x81FF7C
                  else -> -1
                }
            slopePx[py * 256 + px] = if (c < 0) 0 else (0x99 shl 24) or c
            if (c >= 0) any = true
          }
        }
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        ByteArrayOutputStream().let { o ->
          bmp.compress(Bitmap.CompressFormat.JPEG, 82, o)
          hs.add(Triple(intArrayOf(z, x, y), o.toByteArray(), Unit))
        }
        if (any) {
          bmp.setPixels(slopePx, 0, 256, 0, 0, 256, 256)
          ByteArrayOutputStream().let { o ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
            sl.add(Triple(intArrayOf(z, x, y), o.toByteArray(), Unit))
          }
        }
        bmp.recycle()
      }
    }

    // ── contours every 10 m: marching squares on a 6 m grid, the pieces joined into lines
    val step = r / 110.0
    val nCells = 240
    val half = nCells / 2
    val heights = Array(nCells + 1) { j -> DoubleArray(nCells + 1) { i -> ground(hills, (i - half) * step, (j - half) * step)[0] } }
    val lines = StringBuilder()
    var level = 130.0
    while (level < 400.0) {
      // an edge of the grid is named by its lower-left corner and direction (0 = east, 1 = north)
      fun edgeId(i: Int, j: Int, dir: Int) = (j.toLong() * (nCells + 2) + i) * 2 + dir
      val point = HashMap<Long, DoubleArray>()
      val links = HashMap<Long, MutableList<Long>>()
      fun cross(i0: Int, j0: Int, i1: Int, j1: Int): Long? {
        val a = heights[j0][i0]
        val b = heights[j1][i1]
        if ((a < level) == (b < level)) return null
        val id = edgeId(i0, j0, if (j0 == j1) 0 else 1)
        if (id !in point) {
          val t = (level - a) / (b - a)
          point[id] = doubleArrayOf((i0 + (i1 - i0) * t - half) * step, (j0 + (j1 - j0) * t - half) * step)
        }
        return id
      }
      for (j in 0 until nCells) for (i in 0 until nCells) {
        val hits = listOfNotNull(cross(i, j, i + 1, j), cross(i + 1, j, i + 1, j + 1), cross(i, j + 1, i + 1, j + 1), cross(i, j, i, j + 1))
        var k = 0
        while (k + 1 < hits.size) {
          links.getOrPut(hits[k]) { ArrayList() }.add(hits[k + 1])
          links.getOrPut(hits[k + 1]) { ArrayList() }.add(hits[k])
          k += 2
        }
      }
      val used = HashSet<Long>()
      // open lines first (from an end), then the closed rings
      val starts = links.keys.sortedBy { (links[it]?.size ?: 0) }
      for (start in starts) {
        if (start in used) continue
        val chain = ArrayList<Long>()
        var at: Long? = start
        while (at != null && used.add(at)) {
          chain.add(at)
          at = links[at]?.firstOrNull { it !in used }
        }
        // a ring: close it
        if (chain.size > 2 && links[chain.last()]?.contains(chain.first()) == true) chain.add(chain.first())
        if (chain.size < 2) continue
        // only what lies inside the circle (+30 m), as the server clips
        val kept = chain.mapNotNull { point[it] }.filter { sqrt(it[0] * it[0] + it[1] * it[1]) <= r + 30 }
        if (kept.size < 2) continue
        if (lines.isNotEmpty()) lines.append(',')
        lines.append('[').append(level.toInt())
        for (p in kept) lines.append(',').append(String.format(java.util.Locale.US, "%.6f", toLat(p[1]))).append(',').append(String.format(java.util.Locale.US, "%.6f", toLon(p[0])))
        lines.append(']')
      }
      level += 10.0
    }
    val ct = """{"v":"d1","int":10,"l":[$lines]}"""

    // ── POIs and tracklogs
    fun at(e: Double, n: Double) = String.format(java.util.Locale.US, "%.6f,%.6f", toLat(n), toLon(e))
    val poi =
        """{"v":"d1","p":[[${at(0.3 * r, 0.25 * r)},"Car park","access"],[${at(-0.45 * r, -0.2 * r)},"Old hut","building"],[${at(0.05 * r, -0.55 * r)},"Creek crossing","water"]]}"""
    fun walk(e0: Double, phase: Double): String {
      val b = StringBuilder("[")
      for (i in 0..60) {
        val n = -0.7 * r + 1.4 * r * i / 60.0
        val e = e0 + 0.06 * r * sin(i / 6.0 + phase)
        if (i > 0) b.append(',')
        b.append(at(e, n))
      }
      return b.append(']').toString()
    }
    val tl = """{"v":"d1","l":[${walk(-0.2 * r, 0.0)},${walk(-0.1 * r, 1.5)},${walk(0.0, 3.0)}]}"""

    // ── a small search grid (Grids and Labels buttons): three lanes of the Blue team, two of the Red
    fun lane(e0: Double, w: Double): String {
      val s = -0.5 * r
      val n = 0.3 * r
      val rows = (0..4).map { s + (n - s) * it / 4.0 }
      val parts = ArrayList<String>()
      parts.add("[${at(e0, s)},${at(e0, n)}]")
      parts.add("[${at(e0 + w, s)},${at(e0 + w, n)}]")
      for (y in rows) parts.add("[${at(e0, y)},${at(e0 + w, y)}]")
      return parts.joinToString(",")
    }
    val w = 0.08 * r
    val grid =
        """{"v":"demo","col":{"Blue":"#1A6DB5","Red":"#C0392B"},"areas":{"$area":{"teams":{"Blue":{"l":[${lane(-0.3 * r, w)},${lane(-0.3 * r + w, w)}]},"Red":{"l":[${lane(0.1 * r, w)}]}},"names":[[${at(-0.3 * r, -0.5 * r)},"Blue 1","Blue"],[${at(0.1 * r, -0.5 * r)},"Red 1","Red"]]}}}"""

    // ── on the phone, as the server's layers would be
    val hsBytes = pack(hs)
    val slBytes = pack(sl)
    fun put(layer: String, bytes: ByteArray): File = repo.fileOf(area, layer, "d1").also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
    put("hs", hsBytes)
    put("sl", slBytes)
    put("ct", ct.toByteArray())
    put("poi", poi.toByteArray())
    put("tl", tl.toByteArray())
    fun entry(bytes: Int) = FolloLay.Entry("d1", (bytes + 1023) / 1024)
    repo.setDemo(
        FolloLay.LayList(
            "demo",
            mapOf(
                area to
                    mapOf(
                        "hs" to entry(hsBytes.size),
                        "sl" to entry(slBytes.size),
                        "ct" to entry(ct.length),
                        "poi" to entry(poi.length),
                        "tl" to entry(tl.length),
                    )),
            mapOf(area to "Demo search, nowhere real"),
        ))
    FolloGrid.parse(grid)?.let { grids.grid.value = it }
  }
}
