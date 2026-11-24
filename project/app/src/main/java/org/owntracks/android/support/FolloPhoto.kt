package org.owntracks.android.support

import kotlinx.serialization.Serializable

/**
 * One photo or video taken with the 📷 button (23 Nov 2026; FOLLO_tracks/docs/PHOTO_BUILD_SPEC_20261123.md). Kept on the
 * phone in noBackupFilesDir/photos/<pid>/ with three copies: the camera's own file `o.<ext>` (never re-encoded), the
 * thumb `t` (made at once) and the preview `p` (made only when basecamp asks). This record is what the upload queue
 * works from; it survives restarts in photos/index.json.
 */
@Serializable
data class FolloPhoto(
    /** 20 lowercase hex, random, made at capture: the retry key the server knows the item by. */
    val pid: String,
    /** "photo" | "video" */
    val type: String,
    /** Capture time, epoch ms (phone clock). */
    val t: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Double? = null,
    /** The fix [lat]/[lon] came from, epoch ms. */
    val fixt: Long? = null,
    /** Compass heading, degrees true, when the compass had one. */
    val head: Double? = null,
    /** "find" = ⚠ Possible remains. */
    val tag: String? = null,
    val note: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    /** Video length, seconds. */
    val dur: Double? = null,
    /** The original: bytes, content type, file name. */
    val osz: Long = 0,
    val oct: String = "image/jpeg",
    val oname: String = "",
    /**
     * The original's fingerprint: SHA-256 of its bytes, 64 lowercase hex, taken once when it was kept (before anything
     * was sent) and sent with the details, so the server can show the original that reaches it later is byte for byte
     * what the camera saved (Alex 24 Nov 2026: "Camera's file + fingerprint"). Null on items kept before 24 Nov.
     */
    val sha: String? = null,
    /** Thumb content type ("image/avif", or "image/jpeg" when AVIF failed on this phone). */
    val tct: String = "image/avif",
    /** Preview content type once made ("image/avif" | "image/jpeg" | "video/mp4"), null = not made yet. */
    val pct: String? = null,
    /** The server's id once /pho/new was answered (null = the details have not reached it yet). */
    val id: String? = null,
    /** The server has the thumb / the preview / the original (from its answers). */
    val sentT: Boolean = false,
    val sentP: Boolean = false,
    val sentO: Boolean = false,
    /** Preview bytes the server holds (resume point), and the total it was being sent as. */
    val pGot: Long = 0,
    val pTotal: Long = 0,
    /** When the server confirmed the original (/pho/odone ok), epoch ms; local copies go 7 days later. */
    val oDoneAt: Long? = null,
    /** Multipart original in progress: ETags of the parts already sent, by part number. */
    val oParts: Map<Int, String> = emptyMap(),
    /** Failures in a row of the current step, and when to try again (epoch ms). */
    val fails: Int = 0,
    val nextTry: Long = 0,
    /** The phone's /pub user when it was taken: never sent under another sign-in (a phone set up again for someone else). */
    val user: String = "",
    /** Deleted on the phone: the server is told (/pho/del), then the record goes. */
    val del: Boolean = false,
)

/** The queue's rules, apart from the phone so they can be tested (docs/PHOTO_BUILD_SPEC_20261123.md §Upload queue). */
object FolloPhotoRules {
  const val CHUNK = 65_536
  const val THUMB_EDGE = 211
  const val PREVIEW_EDGE = 1600
  /**
   * AVIF quality for thumb + preview: 50 with avif-coder speed FAST (Alex 24 Nov 2026: "AVIF speed 10, quality 60" —
   * that is libaom's scale, used on iOS). avif-coder 3.0.0 is the Rust maroontree encoder, whose quality scale differs:
   * on FOLLO-R (Galaxy A14 5G, 17 bench photos, SSIMULACRA2) FAST q50 preview = 274 KB / 71.6 / 0.8 s vs libaom s10 q60
   * 293 KB / 71.7; thumb FAST q50 = 7.2 KB / 70.1 / 62 ms.
   */
  const val QUALITY = 50
  const val JPEG_QUALITY = 80
  const val THUMB_MAX = 64 * 1024
  const val PREVIEW_PHOTO_MAX = 2L * 1024 * 1024
  const val PREVIEW_VIDEO_MAX = 8L * 1024 * 1024
  const val VIDEO_EDGE = 640
  const val VIDEO_BITRATE = 500_000
  const val AUDIO_BITRATE = 32_000 // HE-AAC, mono (24 Nov 2026: plain AAC at 32 kbps sounds muffled; HE-AAC keeps voices clear)
  const val AUDIO_CHANNELS = 1
  const val VIDEO_MAX_S = 120
  const val FIX_MAX_AGE_MS = 5 * 60_000L
  const val KEEP_AFTER_DONE_MS = 7 * 86_400_000L
  const val WANT_POLL_MS = 60_000L
  const val WANT_POLL_AGE_MS = 3 * 3_600_000L
  const val NOTE_MAX = 200
  private const val BACKOFF_FIRST_MS = 5_000L
  private const val BACKOFF_MAX_MS = 300_000L

  /** What to do next. */
  sealed class Job {
    abstract val pid: String
    data class New(override val pid: String) : Job()
    data class Preview(override val pid: String) : Job()
    data class Original(override val pid: String, val asked: Boolean) : Job()
    data class Delete(override val pid: String) : Job()
  }

  /** 5 s, 10 s, 20 s … up to 5 min after [fails] failures in a row. */
  fun backoffMs(fails: Int): Long =
      if (fails <= 0) 0L else minOf(BACKOFF_MAX_MS, BACKOFF_FIRST_MS shl minOf(fails - 1, 10))

  /**
   * The next job, or null. Order (spec): every item's details + thumb first, oldest first; then the previews basecamp
   * asked for, newest ask first ([wants] is in the server's order); then originals: the ones asked for now on any
   * network, the rest only when [unmetered] (Wi-Fi, not mobile data, not a hotspot), oldest first. An item waiting out
   * its back-off is skipped. Nothing goes without [online].
   */
  fun nextJob(items: List<FolloPhoto>, wants: List<Pair<String, String>>, online: Boolean, unmetered: Boolean, now: Long): Job? {
    if (!online) return null
    items.firstOrNull { it.del && it.id != null && it.nextTry <= now }?.let { return Job.Delete(it.pid) }
    val ready = items.filter { it.nextTry <= now && !it.del }
    ready.filter { it.id == null || !it.sentT }.minByOrNull { it.t }?.let { return Job.New(it.pid) }
    val byPid = ready.associateBy { it.pid }
    for ((pid, tier) in wants) {
      val it = byPid[pid] ?: continue
      if (tier == "p" && !it.sentP && it.id != null) return Job.Preview(pid)
    }
    for ((pid, tier) in wants) {
      val it = byPid[pid] ?: continue
      if (tier == "o" && !it.sentO && it.id != null) return Job.Original(pid, asked = true)
    }
    if (unmetered) ready.filter { it.id != null && !it.sentO }.minByOrNull { it.t }?.let { return Job.Original(it.pid, asked = false) }
    return null
  }

  /** The earliest moment a waiting item may be tried again (for a timer), or null when nothing waits on a back-off. */
  fun nextWake(items: List<FolloPhoto>, now: Long): Long? = items.filter { it.nextTry > now && (!it.sentO || it.del) }.minOfOrNull { it.nextTry }

  /** A piece of the preview to send: where it starts and how long it is. The server's [got] is where to go on from. */
  fun piece(got: Long, total: Long): LongRange? = if (got >= total) null else got until minOf(total, got + CHUNK)

  /** Items still owing the server something (the "…" button's count). */
  fun waiting(items: List<FolloPhoto>): Int = items.count { !it.sentO && !it.del }

  /** Items whose local copies may go: the original was confirmed at least 7 days ago. */
  fun expired(items: List<FolloPhoto>, now: Long): List<FolloPhoto> =
      items.filter { it.oDoneAt != null && now - it.oDoneAt >= KEEP_AFTER_DONE_MS }

  /**
   * Ask GET /pho/want now? Only for a phone that is not posting locations (its status replies carry the list) but holds
   * items less than 3 h old whose preview was never sent, at most every 60 s.
   */
  fun pollWant(items: List<FolloPhoto>, lastStatusAt: Long, lastPollAt: Long, now: Long): Boolean =
      now - lastStatusAt >= WANT_POLL_MS && now - lastPollAt >= WANT_POLL_MS &&
          items.any { it.id != null && !it.sentP && now - it.t < WANT_POLL_AGE_MS }

  /** The 📷 button: while the server takes photos AND the phone is in a search (sharing / outside) or Share everywhere. */
  fun buttonShown(phoOn: Boolean, state: String?, everywhere: Boolean): Boolean =
      phoOn && (everywhere || state == "sharing" || state == "outside")

  /** The longest edge scaled to at most [edge], aspect kept, never enlarged. */
  fun fit(w: Int, h: Int, edge: Int): Pair<Int, Int> {
    if (w <= 0 || h <= 0) return 0 to 0
    val long = maxOf(w, h)
    if (long <= edge) return w to h
    val s = edge.toDouble() / long
    return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
  }

  /** Content type of a camera file from its first bytes (the extension a camera app chose is not trusted). */
  fun sniff(head: ByteArray): String? {
    fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
    if (at(0) == 0xff && at(1) == 0xd8) return "image/jpeg"
    if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4e && at(3) == 0x47) return "image/png"
    if (head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
      val brand = String(head, 8, 4, Charsets.ISO_8859_1)
      return when {
        brand.startsWith("heic") || brand.startsWith("heix") || brand == "mif1" || brand == "msf1" -> "image/heic"
        brand == "avif" -> "image/avif"
        brand == "qt  " -> "video/quicktime"
        brand.startsWith("3gp") || brand.startsWith("3g2") -> "video/3gpp"
        else -> "video/mp4"
      }
    }
    if (head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP") return "image/webp"
    return null
  }

  fun extOf(ct: String): String =
      when (ct) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/heic" -> "heic"
        "image/avif" -> "avif"
        "image/webp" -> "webp"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        else -> "mp4"
      }

  /** SHA-256 of everything [input] gives, 64 lowercase hex, read in 1 MB pieces (videos can be gigabytes). */
  fun sha256Hex(input: java.io.InputStream): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 20)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      if (n > 0) md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /** The fingerprint of a whole file (see [FolloPhoto.sha]); null when it cannot be read. */
  fun sha256Hex(file: java.io.File): String? = try { file.inputStream().use { sha256Hex(it) } } catch (e: java.io.IOException) { null }

  /** A new pid: 20 lowercase hex. */
  fun newPid(random: java.util.Random = java.security.SecureRandom()): String {
    val b = ByteArray(10)
    random.nextBytes(b)
    return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /** The state of one item in words, for the list (spec). [progress] = preview percent while it is being sent. */
  fun stateWords(p: FolloPhoto, wants: List<Pair<String, String>>, online: Boolean, unmetered: Boolean): String {
    val askedP = wants.any { it.first == p.pid && it.second == "p" }
    val askedO = wants.any { it.first == p.pid && it.second == "o" }
    val big = if (p.type == "video") "clip" else "copy"
    return when {
      p.sentO -> "all sent"
      p.id == null || !p.sentT -> if (online) "sending the small copy" else "waiting for signal"
      askedP && !p.sentP ->
          if (p.pTotal > 0 && p.pGot > 0) "bigger $big asked for — sending ${(100 * p.pGot / p.pTotal).toInt()} %"
          else "bigger $big asked for" + if (online) " — sending" else " — waiting for signal"
      askedO -> "original asked for" + if (online) " — sending" else " — waiting for signal"
      p.sentP -> "bigger $big sent · original waits for Wi-Fi"
      else -> "small copy sent · original waits for Wi-Fi" + if (unmetered) " (sending)" else ""
    }
  }
}
