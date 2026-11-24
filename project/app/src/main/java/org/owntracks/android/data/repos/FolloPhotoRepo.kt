package org.owntracks.android.data.repos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.net.http.HttpMessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.services.worker.FolloPhotoWorker
import org.owntracks.android.support.FolloPhoto
import org.owntracks.android.support.FolloPhotoMaker
import org.owntracks.android.support.FolloPhotoRules
import org.owntracks.android.support.FolloPhotoRules.Job
import timber.log.Timber

/**
 * Photos & video from the field (Alex 23 Nov 2026; FOLLO_tracks/docs/PHOTO_BUILD_SPEC_20261123.md). Each item keeps three
 * copies in noBackupFilesDir/photos/<pid>/ — the camera's own file, the thumb, the preview once made — and a record in
 * photos/index.json. One queue, worked by [pump] (here while the app runs; [FolloPhotoWorker] wakes it when the process
 * was gone): the details + thumb straight away on any network; a preview ONLY when the server's status element (or
 * GET /pho/want) lists it, in 64 KB resumable pieces; the original on unmetered Wi-Fi, or at once when basecamp asked for
 * it. Same credentials and user agent as the position posts. Nothing goes to the gallery.
 */
@Singleton
class FolloPhotoRepo
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val folloStatusRepo: FolloStatusRepo,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  val items = MutableStateFlow<List<FolloPhoto>>(emptyList())
  /** The copies the server wants from this phone, newest ask first (status element or GET /pho/want). */
  val wants = MutableStateFlow<List<Pair<String, String>>>(emptyList())
  val online = MutableStateFlow(false)
  val unmetered = MutableStateFlow(false)

  private val dir = File(context.noBackupFilesDir, "photos")
  private val index = File(dir, "index.json")
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
  private val lock = Mutex()
  private val save = Any()
  private var lastPollAt = 0L
  private var polledAt = 0L
  private var polled: List<Pair<String, String>> = emptyList()
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.MINUTES).build()
  }

  init {
    dir.mkdirs()
    load()
    watchNetwork()
    scope.launch(io) {
      folloStatusRepo.status.collect { s ->
        val w = s?.message?.pho?.wants()
        if (w != null) {
          wants.value = w
          kick()
        }
      }
    }
    scope.launch(io) { // a phone that is not posting still hears basecamp's asks; expired copies go; back-offs end
      while (true) {
        delay(15_000)
        try { tick() } catch (e: Exception) { Timber.w(e, "Photos: tick") }
      }
    }
    kick()
  }

  fun dirOf(pid: String) = File(dir, pid)

  fun file(p: FolloPhoto, tier: String): File? =
      when (tier) {
        "o" -> File(dirOf(p.pid), "o." + FolloPhotoRules.extOf(p.oct))
        "t" -> File(dirOf(p.pid), "t")
        "p" -> File(dirOf(p.pid), "p")
        else -> null
      }

  /**
   * Keep what the camera wrote to [shot] as a new item (the sheet's Keep). Makes the thumb now; the record goes in the
   * queue and the details + thumb are sent at once. Null when the file is empty or unreadable.
   */
  suspend fun add(shot: File, video: Boolean, note: String?, find: Boolean, lat: Double?, lon: Double?, acc: Double?, fixt: Long?, head: Double?, t: Long): FolloPhoto? =
      lock.withLock {
        if (!shot.exists() || shot.length() == 0L) return null
        val head8 = ByteArray(16)
        shot.inputStream().use { it.read(head8) }
        val oct = FolloPhotoRules.sniff(head8) ?: if (video) "video/mp4" else "image/jpeg"
        val pid = FolloPhotoRules.newPid()
        val d = dirOf(pid)
        d.mkdirs()
        val orig = File(d, "o." + FolloPhotoRules.extOf(oct))
        if (!shot.renameTo(orig)) {
          shot.copyTo(orig, overwrite = true)
          shot.delete()
        }
        // the fingerprint of the camera's file exactly as it will be uploaded, before anything is sent (add() runs on
        // the io dispatcher: the sheet calls it in withContext(Dispatchers.IO))
        val sha = FolloPhotoRules.sha256Hex(orig)
        val info = FolloPhotoMaker.probe(orig, video)
        val th = FolloPhotoMaker.thumb(orig, video)
        if (th != null) File(d, "t").writeBytes(th.bytes)
        val p =
            FolloPhoto(
                pid = pid, type = if (video) "video" else "photo", t = t, lat = lat, lon = lon, acc = acc, fixt = fixt, head = head,
                tag = if (find) "find" else null, note = note?.trim()?.take(FolloPhotoRules.NOTE_MAX)?.ifEmpty { null },
                w = info.w, h = info.h, dur = info.dur, osz = orig.length(), oct = oct, oname = (if (video) "VID_" else "IMG_") + pid.take(8) + "." + orig.extension, sha = sha,
                tct = th?.ct ?: "image/avif", user = currentUser())
        put(p)
        Timber.i("Photos: kept ${p.type} ${p.pid} ${p.osz} bytes, thumb ${th?.bytes?.size ?: 0} bytes ${th?.ct}")
        kick()
        p
      }

  /** Delete on the phone (asks first in the UI); the server is told, then every local copy goes. */
  fun delete(pid: String) {
    scope.launch(io) {
      lock.withLock {
        val p = items.value.firstOrNull { it.pid == pid } ?: return@withLock
        if (p.id == null) drop(pid) else put(p.copy(del = true, fails = 0, nextTry = 0))
      }
      kick()
    }
  }

  fun waiting(): Int = FolloPhotoRules.waiting(items.value)

  fun words(p: FolloPhoto): String = FolloPhotoRules.stateWords(p, wants.value, online.value, unmetered.value)

  private var pumping = false

  /** Work the queue now (on io), unless it is already being worked. */
  fun kick() {
    scope.launch(io) { pump() }
    schedule()
  }

  /** Works through every job that can go now; returns when nothing more can. Also the worker's body. */
  suspend fun pump() {
    synchronized(this) {
      if (pumping) return
      pumping = true
    }
    try {
      var guard = 0
      while (guard++ < 500) {
        val now = System.currentTimeMillis()
        val me = currentUser()
        val mine = items.value.filter { it.user == me || it.user.isEmpty() }
        val job = FolloPhotoRules.nextJob(mine, currentWants(now), online.value, unmetered.value, now) ?: break
        val ok = try { run(job) } catch (e: Exception) { Timber.w(e, "Photos: ${job::class.simpleName} ${job.pid}"); false }
        if (!ok) fail(job.pid)
      }
    } finally {
      synchronized(this) { pumping = false }
    }
  }

  private fun currentWants(now: Long): List<Pair<String, String>> {
    val st = folloStatusRepo.status.value
    return if (polledAt > (st?.receivedAt ?: 0L) && now - polledAt < 120_000) polled else wants.value
  }

  private suspend fun tick() {
    val now = System.currentTimeMillis()
    // copies kept 7 days after the original arrived
    for (p in FolloPhotoRules.expired(items.value, now)) lock.withLock { drop(p.pid) }
    val lastStatus = folloStatusRepo.status.value?.receivedAt ?: 0L
    if (online.value && FolloPhotoRules.pollWant(items.value, lastStatus, lastPollAt, now)) {
      lastPollAt = now
      call("GET", "/pho/want", null)?.let { (code, body) ->
        if (code == 200) {
          polled = body?.get("w")?.jsonArray?.mapNotNull { r ->
            val a = r.jsonArray
            if (a.size >= 2) a[0].jsonPrimitive.content to a[1].jsonPrimitive.content else null
          }.orEmpty()
          polledAt = now
          wants.value = polled
        }
      }
    }
    if (FolloPhotoRules.nextWake(items.value, now)?.let { it <= now } == true || FolloPhotoRules.nextJob(items.value, currentWants(now), online.value, unmetered.value, now) != null) pump()
  }

  // ---- the jobs ----

  private suspend fun run(job: Job): Boolean {
    val p = items.value.firstOrNull { it.pid == job.pid } ?: return true
    return when (job) {
      is Job.Delete -> {
        val r = call("POST", "/pho/del", buildJsonObject { put("pid", p.pid) }) ?: return false
        if (r.first == 200 || r.first == 410 || r.first == 404) { lock.withLock { drop(p.pid) }; true } else false
      }
      is Job.New -> sendNew(p)
      is Job.Preview -> sendPreview(p)
      is Job.Original -> sendOriginal(p)
    }
  }

  private suspend fun sendNew(p: FolloPhoto): Boolean {
    val t = file(p, "t")?.takeIf { it.exists() }?.readBytes()
    val body = buildJsonObject {
      put("pid", p.pid); put("type", p.type); put("t", p.t)
      p.lat?.let { put("lat", it) }; p.lon?.let { put("lon", it) }; p.acc?.let { put("acc", it) }; p.fixt?.let { put("fixt", it) }
      p.head?.let { put("head", it) }; p.tag?.let { put("tag", it) }; p.note?.let { put("note", it) }
      p.w?.let { put("w", it) }; p.h?.let { put("h", it) }; p.dur?.let { put("dur", it) }
      put("osz", p.osz); put("oct", p.oct); put("oname", p.oname)
      p.sha?.let { put("sha", it) }
      if (t != null) { put("thumb", Base64.encodeToString(t, Base64.NO_WRAP)); put("tct", p.tct) }
    }
    val (code, r) = call("POST", "/pho/new", body) ?: return false
    if (gone(code, p)) return true
    if (code != 200 || r == null) return false
    val have = r["have"]?.jsonObject
    update(p.pid) {
      it.copy(id = r["id"]?.jsonPrimitive?.content, sentT = have?.get("t")?.jsonPrimitive?.content == "true" || t == null,
              sentP = have?.get("p")?.jsonPrimitive?.content == "true" || it.sentP, sentO = have?.get("o")?.jsonPrimitive?.content == "true" || it.sentO,
              fails = 0, nextTry = 0)
    }
    return true
  }

  private suspend fun sendPreview(p0: FolloPhoto): Boolean {
    var p = p0
    val pf = file(p, "p")!!
    if (p.pct == null || !pf.exists()) {
      val o = file(p, "o")!!
      if (!o.exists()) { lock.withLock { drop(p.pid) }; return true }
      val ct =
          if (p.type == "video") {
            if (FolloPhotoMaker.videoPreview(context, o, pf)) "video/mp4" else return false
          } else {
            val e = FolloPhotoMaker.preview(o) ?: return false
            pf.writeBytes(e.bytes)
            e.ct
          }
      p = update(p.pid) { it.copy(pct = ct, pGot = 0, pTotal = pf.length()) } ?: return true
      Timber.i("Photos: preview of ${p.pid} made, ${pf.length()} bytes $ct")
    }
    val total = pf.length()
    var got = if (p.pTotal == total) p.pGot else 0L
    RandomAccessFile(pf, "r").use { raf ->
      while (true) {
        val range = FolloPhotoRules.piece(got, total) ?: break
        val buf = ByteArray((range.last - range.first + 1).toInt())
        raf.seek(range.first)
        raf.readFully(buf)
        val url = base()?.newBuilder()?.encodedPath("/pho/put")?.addQueryParameter("pid", p.pid)?.addQueryParameter("tier", "p")
            ?.addQueryParameter("off", range.first.toString())?.addQueryParameter("total", total.toString())?.addQueryParameter("ct", p.pct)?.build() ?: return false
        val (code, r) = raw("POST", url, buf.toRequestBody("application/octet-stream".toMediaType())) ?: return false
        if (gone(code, p)) return true
        val g = r?.get("got")?.jsonPrimitive?.long
        when {
          code == 200 && g != null -> got = g
          code == 409 && g != null -> got = g // the server holds another amount: go on from there
          else -> return false // includes 409 "not asked for"
        }
        update(p.pid) { it.copy(pGot = got, pTotal = total) }
        if (r?.get("done")?.jsonPrimitive?.content == "true") break
      }
    }
    update(p.pid) { it.copy(sentP = true, pGot = total, pTotal = total, fails = 0, nextTry = 0) }
    wants.value = wants.value.filterNot { it.first == p.pid && it.second == "p" }
    Timber.i("Photos: preview of ${p.pid} sent")
    return true
  }

  private suspend fun sendOriginal(p: FolloPhoto): Boolean {
    val o = file(p, "o")!!
    if (!o.exists()) { lock.withLock { drop(p.pid) }; return true }
    val (code, r) = call("POST", "/pho/orig", buildJsonObject { put("pid", p.pid) }) ?: return false
    if (gone(code, p)) return true
    if (code == 409) { update(p.pid) { it.copy(sentO = true, oDoneAt = it.oDoneAt ?: System.currentTimeMillis()) }; return true }
    if (code != 200 || r == null) return false
    val ct = r["ct"]?.jsonPrimitive?.content ?: p.oct
    val done: JsonObject
    val single = r["put"]?.jsonPrimitive?.content
    if (single != null) {
      val url = single.toHttpUrlOrNull() ?: return false
      val res = raw("PUT", url, fileBody(o, 0, o.length(), ct), wasabi = true) ?: return false
      if (res.first !in 200..299) return false
      done = buildJsonObject { put("pid", p.pid) }
    } else {
      val parts = r["parts"]?.jsonArray ?: return false
      val partSize = r["part"]?.jsonPrimitive?.long ?: return false
      val etags = p.oParts.toMutableMap()
      for (x in parts) {
        val n = x.jsonObject["n"]!!.jsonPrimitive.int
        if (etags.containsKey(n)) continue
        val size = x.jsonObject["size"]!!.jsonPrimitive.long
        val url = x.jsonObject["url"]!!.jsonPrimitive.content.toHttpUrlOrNull() ?: return false
        val req = Request.Builder().url(url).put(fileBody(o, (n - 1) * partSize, size, null)).build()
        val tag = client.newCall(req).execute().use { if (it.isSuccessful) it.header("ETag") else null } ?: return false
        etags[n] = tag
        update(p.pid) { it.copy(oParts = etags.toMap()) }
      }
      done = buildJsonObject {
        put("pid", p.pid)
        put("parts", buildJsonArray { for (n in etags.keys.sorted()) add(buildJsonObject { put("n", n); put("etag", etags[n]) }) })
      }
    }
    val (c2, _) = call("POST", "/pho/odone", done) ?: return false
    if (gone(c2, p)) return true
    if (c2 != 200) {
      update(p.pid) { it.copy(oParts = emptyMap()) } // start the original again
      return false
    }
    update(p.pid) { it.copy(sentO = true, oDoneAt = System.currentTimeMillis(), oParts = emptyMap(), fails = 0, nextTry = 0) }
    wants.value = wants.value.filterNot { it.first == p.pid && it.second == "o" }
    Timber.i("Photos: original of ${p.pid} confirmed")
    return true
  }

  /** 410 / 404 from the server: removed at basecamp (or never there): dropped here too. */
  private suspend fun gone(code: Int, p: FolloPhoto): Boolean {
    if (code != 410 && code != 404) return false
    Timber.i("Photos: ${p.pid} gone at the server: dropped")
    lock.withLock { drop(p.pid) }
    return true
  }

  private fun fileBody(f: File, start: Long, len: Long, ct: String?): RequestBody =
      object : RequestBody() {
        override fun contentType() = ct?.toMediaType()
        override fun contentLength() = len
        override fun writeTo(sink: BufferedSink) {
          RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
              val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
              if (n < 0) break
              sink.write(buf, 0, n)
              left -= n
            }
          }
        }
      }

  // ---- HTTP ----

  private fun currentUser(): String {
    val url = preferences.url.toHttpUrlOrNull()
    return (url?.username?.ifEmpty { null } ?: preferences.username).lowercase()
  }

  private fun base(): HttpUrl? = preferences.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.newBuilder()?.username("")?.password("")?.build()

  private fun auth(): String? {
    val url = preferences.url.toHttpUrlOrNull() ?: return null
    val user = url.username.ifEmpty { preferences.username }
    val pass = url.password.ifEmpty { preferences.password }
    return if (user.isEmpty() || pass.isEmpty()) null else Credentials.basic(user, pass)
  }

  /** A JSON call to the FOLLO server: (code, body) or null when it could not be made. */
  private fun call(method: String, path: String, body: JsonObject?): Pair<Int, JsonObject?>? {
    val url = base()?.newBuilder()?.encodedPath(path)?.build() ?: return null
    return raw(method, url, body?.toString()?.toRequestBody("application/json".toMediaType()))
  }

  private fun raw(method: String, url: HttpUrl, body: RequestBody?, wasabi: Boolean = false): Pair<Int, JsonObject?>? {
    val b = Request.Builder().url(url).method(method, body)
    if (!wasabi) {
      b.header(HttpMessageProcessorEndpoint.HEADER_USERAGENT, HttpMessageProcessorEndpoint.USERAGENT)
      b.header(HttpMessageProcessorEndpoint.HEADER_AUTHORIZATION, auth() ?: return null)
    }
    return try {
      client.newCall(b.build()).execute().use { r ->
        val text = r.body.string()
        if (r.code == 401) Timber.w("Photos: HTTP 401 from $url")
        r.code to (try { if (wasabi || text.isEmpty()) null else json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null })
      }
    } catch (e: Exception) {
      Timber.w("Photos: ${e.javaClass.simpleName} ${e.message} on ${url.encodedPath}")
      null
    }
  }

  // ---- the record ----

  private suspend fun fail(pid: String) {
    update(pid) { val f = it.fails + 1; it.copy(fails = f, nextTry = System.currentTimeMillis() + FolloPhotoRules.backoffMs(f)) }
  }

  private fun update(pid: String, f: (FolloPhoto) -> FolloPhoto): FolloPhoto? =
      synchronized(save) {
        val cur = items.value.firstOrNull { it.pid == pid } ?: return null
        val n = f(cur)
        items.value = items.value.map { if (it.pid == pid) n else it }
        persist()
        n
      }

  private fun put(p: FolloPhoto) =
      synchronized(save) {
        items.value = items.value.filterNot { it.pid == p.pid } + p
        persist()
      }

  private fun drop(pid: String) =
      synchronized(save) {
        dirOf(pid).deleteRecursively()
        items.value = items.value.filterNot { it.pid == pid }
        persist()
      }

  private fun persist() {
    try {
      val tmp = File(dir, "index.json.tmp")
      tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FolloPhoto.serializer()), items.value))
      if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
    } catch (e: Exception) {
      Timber.w(e, "Photos: could not save the list")
    }
  }

  private fun load() {
    try {
      if (index.exists()) items.value = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FolloPhoto.serializer()), index.readText())
    } catch (e: Exception) {
      Timber.w(e, "Photos: could not read the list")
    }
  }

  // ---- network + WorkManager ----

  private fun watchNetwork() {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun look() {
      val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
      online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      unmetered.value = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    look()
    try {
      cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { look(); kick() }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
              val was = unmetered.value to online.value
              look()
              if ((unmetered.value to online.value) != was) kick()
            }
            override fun onLost(network: Network) = look()
          })
    } catch (e: Exception) {
      Timber.w(e, "Photos: no network callback")
    }
  }

  /** WorkManager jobs that wake the queue when the process was gone: any network for details / asks, Wi-Fi for originals. */
  fun schedule() {
    val all = items.value.filter { !it.del || it.id != null }
    val anyNet = all.any { it.del || it.id == null || !it.sentT } || (wants.value.isNotEmpty())
    val wifi = all.any { !it.sentO && !it.del && it.id != null }
    try {
      val wm = WorkManager.getInstance(context)
      if (anyNet) wm.enqueueUniqueWork(WORK_ANY, ExistingWorkPolicy.KEEP, request(NetworkType.CONNECTED))
      if (wifi) wm.enqueueUniqueWork(WORK_WIFI, ExistingWorkPolicy.KEEP, request(NetworkType.UNMETERED))
    } catch (e: Exception) {
      Timber.w(e, "Photos: could not schedule the upload job")
    }
  }

  private fun request(n: NetworkType) =
      OneTimeWorkRequestBuilder<FolloPhotoWorker>()
          .setInputData(androidx.work.workDataOf(FolloPhotoWorker.WIFI to (n == NetworkType.UNMETERED)))
          .setConstraints(Constraints.Builder().setRequiredNetworkType(n).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
          .build()

  /** For the worker: anything still to send that needs [unmeteredOnly] = false any network, true Wi-Fi. */
  fun stillOwes(wifiJob: Boolean): Boolean =
      if (wifiJob) items.value.any { !it.sentO && !it.del && it.id != null }
      else items.value.any { it.del || it.id == null || !it.sentT } ||
          wants.value.any { (pid, tier) -> items.value.any { it.pid == pid && (if (tier == "p") !it.sentP else !it.sentO) } }

  companion object {
    const val WORK_ANY = "follo_photos"
    const val WORK_WIFI = "follo_photos_wifi"
  }
}
