package org.owntracks.android.ui.main

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The simple screen (Alex, 19 Nov 2026; FOLLO_tracks/docs/SIMPLE_MODE_SPEC_20261119.md): the pure rules of the "…"
 * menu, the two-way symbol and the layers. Nothing here touches Android, so the spec's shared test vectors (§9) run as
 * plain unit tests; iOS has the same rules.
 */
object FolloMenu {
  /** The layer buttons, in the order they hang under "…" (§2). [key] = the layer's name in the server's list. */
  enum class Layer(val key: String, val defaultOn: Boolean = false) {
    TRACKLOGS("tl"),
    GRIDS("grids", true),
    LABELS("labels", true),
    HILLSHADE("hs"),
    SLOPE("sl"),
    CONTOURS("ct"),
    POI("poi");

    /** A tile pack (downloaded by itself only on Wi-Fi or when small). */
    val raster: Boolean
      get() = this == HILLSHADE || this == SLOPE
  }

  /** The function buttons, under the layers after a half-button gap (§2). */
  enum class Function {
    ROTATE,
    POWER,
    COMPASS,
    SEARCHES,
    /** Photos & video (23 Nov 2026): this phone's list, with what is still to send */
    PHOTOS,
  }

  /** Where one button of the column goes: [column] 0 = straight under "…", 1 = the next one to its left; [y] from the column's top. */
  data class Slot(val column: Int, val y: Float)

  /**
   * §2 "If the column does not fit": [layers] then [functions] buttons, each [button] tall with [gap] between and
   * [groupGap] between the two kinds — all in one column when that fits in [avail]; otherwise filled top to bottom,
   * a new column whenever the next button would not fit, with no wider gap anywhere.
   */
  fun layout(layers: Int, functions: Int, avail: Float, button: Float = 40f, gap: Float = 10f, groupGap: Float = 20f): List<Slot> {
    val n = layers + functions
    if (n <= 0) return emptyList()
    val both = layers > 0 && functions > 0
    val one = n * button + (n - 1) * gap + if (both) groupGap - gap else 0f
    if (one <= avail) {
      return (0 until n).map { i -> Slot(0, i * (button + gap) + if (both && i >= layers) groupGap - gap else 0f) }
    }
    val per = ((avail + gap) / (button + gap)).toInt().coerceAtLeast(1)
    return (0 until n).map { i -> Slot(i / per, (i % per) * (button + gap)) }
  }

  /** How many buttons each column of [slots] holds, first column first. */
  fun columns(slots: List<Slot>): List<Int> = slots.groupBy { it.column }.toSortedMap().values.map { it.size }

  // ── The two-way symbol (§3) ─────────────────────────────────────────────────────────────

  enum class Link {
    GREEN,
    ORANGE,
    /** Drawn with a slash through the arrows */
    RED,
  }

  const val LINK_ORANGE_MS = 60_000L
  const val LINK_RED_MS = 300_000L

  /**
   * [waitingMs] = how long the oldest thing the app has queued or tried has gone unanswered (null = nothing waits);
   * [answered] = the server has answered this phone at least once.
   */
  fun link(waitingMs: Long?, answered: Boolean): Link =
      when {
        waitingMs != null && waitingMs >= LINK_RED_MS -> Link.RED
        waitingMs != null && waitingMs >= LINK_ORANGE_MS -> Link.ORANGE
        !answered -> Link.ORANGE
        else -> Link.GREEN
      }

  /**
   * How long something has been waiting, from what the app already knows: [queuedSinceMs] = when the send queue was
   * last seen to go from empty to holding something (null = it is empty), [answeredAtMs] = the server's last answer.
   * A queue that keeps being answered (Move, a post every 5 s) is not waiting.
   */
  fun waitingMs(queuedSinceMs: Long?, answeredAtMs: Long, nowMs: Long): Long? =
      queuedSinceMs?.let { (nowMs - maxOf(it, answeredAtMs)).coerceAtLeast(0L) }

  // ── Which layer buttons show (§2, §5) ───────────────────────────────────────────────────

  /**
   * The layer buttons for the area in view, in order: the layers the server lists for it ([lay]: area → layer names),
   * Grids / Labels when the held grids have lines / names there.
   */
  fun layerButtons(lay: Map<String, Set<String>>, gridLines: Boolean, gridNames: Boolean, area: String?): List<Layer> {
    val listed = area?.let { lay[it] }.orEmpty()
    return Layer.entries.filter {
      when (it) {
        Layer.GRIDS -> gridLines
        Layer.LABELS -> gridNames
        else -> it.key in listed
      }
    }
  }

  /** A raster layer this big (kB) or smaller is fetched on mobile data without asking (§5). */
  const val METERED_FREE_KB = 3000

  /** §5: small layers on any network; hillshade and slope by themselves on Wi-Fi, or on mobile data when small or allowed. */
  fun mayFetch(raster: Boolean, metered: Boolean, kb: Int, userAllowed: Boolean = false): Boolean =
      !raster || !metered || userAllowed || kb <= METERED_FREE_KB

  /**
   * §5 "When": the layers to fetch now, as "AREA/layer" keys in the order they are asked for. First the layers that
   * are switched ON for the area in view ([wanted]) — whatever the distance, so a search looked at from far away still
   * draws — then every layer of the areas the offline map would save ([near], nearest first), in the buttons' order.
   * Only layers the server lists ([listed]: area → layer → version), and only when the phone does not already hold
   * that version ([held]: key → version). Always a whole layer of an area the phone is allowed in.
   */
  fun fetchOrder(listed: Map<String, Map<String, String>>, held: Map<String, String>, near: List<String>, wanted: Collection<String>): List<String> {
    val out = LinkedHashSet<String>()
    fun consider(area: String, layer: String) {
      val v = listed[area]?.get(layer) ?: return
      val key = "$area/$layer"
      if (held[key] != v) out.add(key)
    }
    for (key in wanted) {
      val area = key.substringBefore('/')
      val layer = key.substringAfter('/', "")
      if (layer.isNotEmpty() && Layer.entries.any { it.key == layer }) consider(area, layer)
    }
    for (area in near) for (l in Layer.entries) consider(area, l.key)
    return out.toList()
  }

  /** "About 12 MB": megabytes for [kb], at least 1. */
  fun megabytes(kb: Int): Int = ((kb + 1023) / 1024).coerceAtLeast(1)
}

/**
 * The layer list and the layer bodies (spec §5). `GET /lay` → `{"v", "lay": {area: {layer: {"v","kb"}}}, "an": {area:
 * name}}`; a layer's body comes from `GET /lay?a=&l=&v=`. Malformed pieces are skipped, never fatal.
 */
object FolloLay {
  data class Entry(val v: String, val kb: Int)

  class LayList(val v: String, val lay: Map<String, Map<String, Entry>>, val names: Map<String, String>) {
    /** area → the layer names listed for it */
    val listed: Map<String, Set<String>>
      get() = lay.mapValues { it.value.keys }
  }

  class Contour(val elev: Double, val line: DoubleArray)

  class Poi(val lat: Double, val lon: Double, val name: String, val kind: String)

  /** Only these make a file name (area codes, layer names and versions the server sends are plain). */
  private val SAFE = Regex("[A-Za-z0-9_-]{1,40}")

  fun safe(s: String): Boolean = SAFE.matches(s)

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

  private fun root(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject

  fun parseList(body: String): LayList? {
    val root = root(body) ?: return null
    val v = root["v"].str() ?: return null
    val lay =
        (root["lay"] as? JsonObject)?.mapNotNull { (area, layers) ->
          if (!safe(area)) return@mapNotNull null
          val m =
              (layers as? JsonObject)?.mapNotNull { (layer, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val lv = o["v"].str()?.takeIf(::safe) ?: return@mapNotNull null
                if (!safe(layer)) return@mapNotNull null
                layer to Entry(lv, o["kb"].num()?.toInt()?.coerceAtLeast(0) ?: 0)
              }.orEmpty().toMap()
          if (m.isEmpty()) null else area to m
        }.orEmpty().toMap()
    val names = (root["an"] as? JsonObject)?.mapNotNull { (area, n) -> n.str()?.takeIf { it.isNotBlank() }?.let { area to it } }.orEmpty().toMap()
    return LayList(v, lay, names)
  }

  /** A flat lat,lon,… run starting at [from] of a JSON row; null when it is not one. */
  private fun flat(row: JsonArray, from: Int): DoubleArray? {
    val n = row.size - from
    if (n < 4 || n % 2 != 0) return null
    val out = DoubleArray(n)
    for (i in 0 until n) out[i] = row[from + i].num() ?: return null
    for (i in 0 until n step 2) if (out[i] !in -90.0..90.0 || out[i + 1] !in -180.0..180.0) return null
    return out
  }

  /** `ct`: `{"l": [[elev, lat,lon,lat,lon,…], …]}` */
  fun parseContours(body: String): List<Contour>? {
    val rows = root(body)?.get("l") as? JsonArray ?: return null
    return rows.mapNotNull { r ->
      val row = r as? JsonArray ?: return@mapNotNull null
      val elev = row.getOrNull(0).num() ?: return@mapNotNull null
      flat(row, 1)?.let { Contour(elev, it) }
    }
  }

  /** `tl`: `{"l": [[lat,lon,lat,lon,…], …]}` */
  fun parseLines(body: String): List<DoubleArray>? {
    val rows = root(body)?.get("l") as? JsonArray ?: return null
    return rows.mapNotNull { r -> (r as? JsonArray)?.let { flat(it, 0) } }
  }

  /** `poi`: `{"p": [[lat, lon, "name", "kind"], …]}` */
  fun parsePois(body: String): List<Poi>? {
    val rows = root(body)?.get("p") as? JsonArray ?: return null
    return rows.mapNotNull { r ->
      val row = r as? JsonArray ?: return@mapNotNull null
      val lat = row.getOrNull(0).num() ?: return@mapNotNull null
      val lon = row.getOrNull(1).num() ?: return@mapNotNull null
      if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
      Poi(lat, lon, row.getOrNull(2).str().orEmpty(), row.getOrNull(3).str().orEmpty())
    }
  }

  /** An index contour (drawn bold with its height): every [every] metres. */
  fun isIndexContour(elev: Double, every: Int = 50): Boolean {
    val e = Math.round(elev)
    return Math.abs(elev - e) < 0.01 && Math.floorMod(e, every.toLong()) == 0L
  }
}

/**
 * A tile pack (spec §5, hillshade and slope): bytes 0–3 `FTP1`, uint32 LE n, n rows of 5 × uint32 LE `z, x, y,
 * offset, length`, then the tiles (256 px XYZ web-mercator JPEG or PNG). Tiles are read straight out of the file.
 */
class FolloPack private constructor(private val raf: RandomAccessFile, private val index: Map<Long, LongArray>, val minZ: Int, val maxZ: Int) {
  val count: Int
    get() = index.size

  /** The tile's bytes, or null when the pack has none there. */
  @Synchronized
  fun tile(z: Int, x: Int, y: Int): ByteArray? {
    val row = index[key(z, x, y)] ?: return null
    return try {
      val out = ByteArray(row[1].toInt())
      raf.seek(row[0])
      raf.readFully(out)
      out
    } catch (e: Exception) {
      null
    }
  }

  fun has(z: Int, x: Int, y: Int): Boolean = index.containsKey(key(z, x, y))

  @Synchronized
  fun close() {
    runCatching { raf.close() }
  }

  companion object {
    private const val MAX_TILES = 2_000_000L
    private const val MAX_TILE_BYTES = 8L * 1024 * 1024

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** The pack in [file], or null when the file is not one (no `FTP1`, cut short, rows pointing outside it). */
    fun open(file: File): FolloPack? {
      var raf: RandomAccessFile? = null
      try {
        raf = RandomAccessFile(file, "r")
        val size = raf.length()
        if (size < 8) throw IllegalArgumentException("short")
        val head = ByteArray(8)
        raf.readFully(head)
        if (head[0] != 'F'.code.toByte() || head[1] != 'T'.code.toByte() || head[2] != 'P'.code.toByte() || head[3] != '1'.code.toByte())
            throw IllegalArgumentException("not FTP1")
        val n = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (n > MAX_TILES || 8 + n * 20 > size) throw IllegalArgumentException("index")
        val rows = ByteArray((n * 20).toInt())
        raf.readFully(rows)
        val b = ByteBuffer.wrap(rows).order(ByteOrder.LITTLE_ENDIAN)
        val index = HashMap<Long, LongArray>((n * 4 / 3).toInt() + 1)
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (i in 0 until n.toInt()) {
          val z = b.int
          val x = b.int
          val y = b.int
          val offset = b.int.toLong() and 0xFFFFFFFFL
          val length = b.int.toLong() and 0xFFFFFFFFL
          if (z !in 0..24 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
          if (length <= 0 || length > MAX_TILE_BYTES || offset < 8 + n * 20 || offset + length > size) continue
          index[key(z, x, y)] = longArrayOf(offset, length)
          if (z < minZ) minZ = z
          if (z > maxZ) maxZ = z
        }
        if (index.isEmpty()) {
          minZ = 0
          maxZ = -1
        }
        return FolloPack(raf, index, minZ, maxZ)
      } catch (e: Exception) {
        runCatching { raf?.close() }
        return null
      }
    }
  }
}
