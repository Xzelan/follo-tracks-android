package org.owntracks.android.data.repos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.net.http.HttpMessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.ui.main.FolloLay
import org.owntracks.android.ui.main.FolloMenu
import org.owntracks.android.ui.main.FolloOffline
import org.owntracks.android.ui.main.FolloPack
import org.owntracks.android.ui.main.FolloRules
import timber.log.Timber

/**
 * The map layers (Alex, 19 Nov 2026; FOLLO_tracks/docs/SIMPLE_MODE_SPEC_20261119.md §5): hillshade, slope, contours,
 * POI and tracklogs for the search areas this phone holds circles for.
 *
 * Every status element names the version of this phone's layer LIST (`layv`); when it differs from the list the phone
 * holds, the phone asks `GET /lay` (the same credentials it posts positions with, at most once a minute). The list says
 * which layers exist per area, their versions and sizes; each one is then fetched whole (`GET /lay?a=&l=&v=`) into one
 * file, in advance, for the areas the offline map would save — never tile by tile as the map moves, so the server
 * learns nothing about where a phone is or looks. Small layers on any network; hillshade and slope by themselves on
 * Wi-Fi, on mobile data only when small or after the volunteer said Download. Off the position queue entirely.
 */
@Singleton
class FolloLayRepo
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val waypointsRepo: WaypointsRepo,
    private val folloStatusRepo: FolloStatusRepo,
    private val folloOfflineRepo: FolloOfflineRepo,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  /** The layer list held; null until the first one is read or fetched. */
  val list = MutableStateFlow<FolloLay.LayList?>(null)

  /** The layer files on the phone: "AREA/layer" → the version held. */
  val held = MutableStateFlow<Map<String, String>>(emptyMap())

  /** Hillshade / slope layers that wait for Wi-Fi ("AREA/layer"): a tap on their button asks first. */
  val waiting = MutableStateFlow<Set<String>>(emptySet())

  /** Debug builds only: the layers are made up on the phone (FolloLayDemo), nothing is fetched. */
  @Volatile var demo = false

  private val dir = File(context.filesDir, DIR)
  private val listFile = File(dir, LIST_FILE)
  private val kicks = Channel<Unit>(Channel.CONFLATED)
  private val askedAt = ConcurrentHashMap<String, Long>()
  private val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
  @Volatile private var wanted: Set<String> = emptySet()
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
  }

  init {
    scope.launch(io) {
      load()
      watchNetwork()
      launch {
        var last: String? = null
        var first = true
        folloStatusRepo.status.collect { st ->
          val v = st?.message?.layv
          if (first || v != last) kicks.trySend(Unit) // not on every 5-second reply: only when the list's version moves
          first = false
          last = v
        }
      }
      launch { waypointsRepo.repoChangedEvent.collect { kicks.trySend(Unit) } }
      for (k in kicks) {
        delay(SETTLE_MS)
        try {
          pass()
        } catch (e: Exception) {
          Timber.w(e, "Layers pass failed")
        }
      }
    }
  }

  /** Look again now (the app came forward). */
  fun refresh() {
    kicks.trySend(Unit)
  }

  /**
   * The layers switched ON for the search area in view ("AREA/layer"; spec §5, added 19 Nov 2026): fetched now when
   * they are not on the phone, whatever the distance. MainActivity says so whenever the view or a button changes.
   */
  fun want(keys: Set<String>) {
    if (keys == wanted) return
    val more = keys.any { it !in wanted }
    wanted = keys
    if (more) kicks.trySend(Unit)
  }

  /** A hillshade or slope that is not on the phone and may not be fetched by itself here (mobile data, too big): ask first. */
  fun needsAsking(area: String, layer: FolloMenu.Layer): Boolean =
      layer.raster && !demo && file(area, layer.key) == null && list.value?.lay?.get(area)?.containsKey(layer.key) == true &&
          !FolloMenu.mayFetch(true, metered, kb(area, layer.key), key(area, layer.key) in allowed)

  /** The volunteer said Download for this layer: mobile data may be used for it. */
  fun allowMetered(area: String, layer: String) {
    allowed.add(key(area, layer))
    askedAt.remove(key(area, layer))
    kicks.trySend(Unit)
  }

  /**
   * The layer's file on the phone, while the server still lists that layer for the area. A version the server has
   * since replaced keeps drawing until the new one has landed.
   */
  fun file(area: String, layer: String): File? {
    val v = held.value[key(area, layer)] ?: return null
    if (list.value?.lay?.get(area)?.containsKey(layer) != true) return null
    return fileOf(area, layer, v).takeIf { it.exists() }
  }

  /** The listed size of a layer in kB (0 = not listed). */
  fun kb(area: String, layer: String): Int = list.value?.lay?.get(area)?.get(layer)?.kb ?: 0

  /** Debug builds only: a made-up list and its files, written by FolloLayDemo. */
  fun setDemo(l: FolloLay.LayList) {
    demo = true
    list.value = l
    scan()
  }

  /** Where a layer's file goes (FolloLayDemo writes there too). */
  fun fileOf(area: String, layer: String, v: String): File = File(dir, "$area.$layer.$v")

  private fun key(area: String, layer: String) = "$area/$layer"

  private fun load() {
    try {
      dir.mkdirs()
      if (listFile.exists()) FolloLay.parseList(listFile.readText())?.let { list.value = it }
      scan()
    } catch (e: Exception) {
      Timber.w(e, "Could not read the saved layers")
    }
  }

  /** The layer files in the folder: one per area + layer (a newer one replaces the older). */
  private fun scan() {
    val found = HashMap<String, String>()
    dir.listFiles()?.forEach { f ->
      val parts = f.name.split('.')
      if (parts.size == 3 && parts.all(FolloLay::safe)) found[key(parts[0], parts[1])] = parts[2]
    }
    held.value = found
  }

  private fun endpoint(): Triple<HttpUrl, String, String>? {
    val url = preferences.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.resolve("/lay") ?: return null
    val user = url.username.ifEmpty { preferences.username }
    val pass = url.password.ifEmpty { preferences.password }
    if (user.isEmpty() || pass.isEmpty()) return null
    return Triple(url.newBuilder().username("").password("").build(), user, pass)
  }

  private fun request(url: HttpUrl, user: String, pass: String): Request =
      Request.Builder()
          .url(url)
          .header(HttpMessageProcessorEndpoint.HEADER_USERAGENT, HttpMessageProcessorEndpoint.USERAGENT)
          .header(HttpMessageProcessorEndpoint.HEADER_AUTHORIZATION, Credentials.basic(user, pass))
          .build()

  /** At most one request a minute for [what]. */
  private fun mayAsk(what: String): Boolean {
    val now = System.currentTimeMillis()
    val last = askedAt[what]
    if (last != null && now - last < ASK_EVERY_MS) return false
    askedAt[what] = now
    return true
  }

  @Volatile private var online = false
  @Volatile private var metered = true

  private fun watchNetwork() {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun judge(caps: NetworkCapabilities?) {
      val on = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      val paid = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
      if (on == online && paid == metered) return
      online = on
      metered = paid
      kicks.trySend(Unit)
    }
    try {
      judge(cm.getNetworkCapabilities(cm.activeNetwork))
      cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = judge(caps)

            override fun onLost(network: Network) = judge(null)
          })
    } catch (e: Exception) {
      Timber.w(e, "Layers: cannot watch the network")
    }
  }

  private suspend fun pass() {
    if (demo || !preferences.setupCompleted) return
    val status = folloStatusRepo.status.value?.message ?: return
    val layv = status.layv?.takeIf { it.isNotBlank() } ?: "0"
    var again = false
    // 1. the list
    if (layv == "0") {
      if (list.value?.v != "0") {
        list.value = FolloLay.LayList("0", emptyMap(), emptyMap())
        runCatching { listFile.delete() }
      }
    } else if (list.value?.v != layv) {
      if (!online) return
      if (mayAsk("list")) fetchList()
      if (list.value?.v != layv) again = true // not asked this minute, or the answer did not come: once more later
    }
    val l = list.value ?: return
    // 2. layers that are no longer listed go (only once the list itself is the server's current one)
    if (l.v == layv) {
      var removed = false
      dir.listFiles()?.forEach { f ->
        val parts = f.name.split('.')
        if (parts.size == 3 && l.lay[parts[0]]?.get(parts[1])?.v != parts[2]) {
          // an older version is kept until its replacement has landed
          val listedNow = l.lay[parts[0]]?.get(parts[1])
          if (listedNow == null || fileOf(parts[0], parts[1], listedNow.v).exists()) {
            f.delete()
            removed = true
          }
        }
      }
      if (removed) scan()
    }
    // 3. the layers, whole: first the ones switched on for the area in view (whatever the distance), then, in
    // advance, those of the areas the offline map would save: this phone's circles within `off.km`, nearest first
    val at = folloOfflineRepo.lastPosition()
    val km = status.off?.km ?: DEFAULT_KM
    val near =
        if (at == null) emptyList()
        else
            waypointsRepo
                .getAll()
                .mapNotNull { w ->
                  if (w.geofenceRadius <= 0) return@mapNotNull null
                  FolloRules.wakeArea(w.description)?.let {
                    FolloRules.Circle(w.geofenceLatitude.value, w.geofenceLongitude.value, w.geofenceRadius.toDouble(), it)
                  }
                }
                .filter { it.area in l.lay }
                .groupBy { it.area }
                .mapNotNull { (area, cs) -> FolloOffline.distanceToCirclesM(at.first, at.second, cs)?.let { area to it } }
                .filter { it.second <= km * 1000.0 }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .map { it.first }
    // a file that has gone missing does not count as held
    val have = held.value.filter { (k, v) -> fileOf(k.substringBefore('/'), k.substringAfter('/'), v).exists() }
    val wait = HashSet<String>()
    for (k in FolloMenu.fetchOrder(l.lay.mapValues { (_, m) -> m.mapValues { it.value.v } }, have, near, wanted)) {
      val area = k.substringBefore('/')
      val layer = FolloMenu.Layer.entries.firstOrNull { it.key == k.substringAfter('/') } ?: continue
      val e = l.lay[area]?.get(layer.key) ?: continue
      if (!online) continue
      if (!FolloMenu.mayFetch(layer.raster, metered, e.kb, k in allowed)) {
        wait.add(k)
        continue
      }
      if (!mayAsk(k)) {
        again = true
        continue
      }
      if (!fetchLayer(area, layer, e)) again = true
    }
    waiting.value = wait
    if (again) {
      scope.launch(io) {
        delay(ASK_EVERY_MS + 5_000L)
        kicks.trySend(Unit)
      }
    }
  }

  private fun fetchList() {
    val (url, user, pass) = endpoint() ?: return
    try {
      client.newCall(request(url, user, pass)).execute().use { response ->
        if (!response.isSuccessful) {
          Timber.w("Layer list: HTTP ${response.code}")
          return
        }
        val body = response.body.string()
        val parsed = FolloLay.parseList(body)
        if (parsed == null) {
          Timber.w("Layer list: unreadable reply")
          return
        }
        dir.mkdirs()
        val tmp = File(dir, "list.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(listFile)) {
          listFile.delete()
          tmp.renameTo(listFile)
        }
        list.value = parsed
        Timber.d("Layer list ${parsed.v}: ${parsed.lay.size} areas")
      }
    } catch (e: Exception) {
      Timber.w(e, "Could not fetch the layer list")
    }
  }

  /** One whole layer into its file; true = it is on the phone now. */
  private fun fetchLayer(area: String, layer: FolloMenu.Layer, e: FolloLay.Entry): Boolean {
    val (base, user, pass) = endpoint() ?: return false
    val url = base.newBuilder().addQueryParameter("a", area).addQueryParameter("l", layer.key).addQueryParameter("v", e.v).build()
    val tmp = File(dir, "tmp-$area-${layer.key}")
    try {
      client.newCall(request(url, user, pass)).execute().use { response ->
        if (!response.isSuccessful) {
          Timber.w("Layer $area ${layer.key}: HTTP ${response.code}")
          return false
        }
        dir.mkdirs()
        tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
      }
      val good =
          if (layer.raster) FolloPack.open(tmp)?.also { it.close() } != null
          else
              tmp.readText().let { body ->
                when (layer) {
                  FolloMenu.Layer.CONTOURS -> FolloLay.parseContours(body) != null
                  FolloMenu.Layer.POI -> FolloLay.parsePois(body) != null
                  else -> FolloLay.parseLines(body) != null
                }
              }
      if (!good) {
        Timber.w("Layer $area ${layer.key}: unreadable body")
        tmp.delete()
        return false
      }
      val file = fileOf(area, layer.key, e.v)
      if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) return false
      }
      // the version it replaces goes
      dir.listFiles()?.forEach { f ->
        val parts = f.name.split('.')
        if (parts.size == 3 && parts[0] == area && parts[1] == layer.key && parts[2] != e.v) f.delete()
      }
      allowed.remove(key(area, layer.key))
      scan()
      Timber.d("Layer $area ${layer.key} ${e.v}: ${file.length() / 1024} kB")
      return true
    } catch (ex: Exception) {
      Timber.w(ex, "Could not fetch layer $area ${layer.key}")
      runCatching { tmp.delete() }
      return false
    }
  }

  companion object {
    private const val DIR = "follo_lay"
    private const val LIST_FILE = "list.json"
    private const val ASK_EVERY_MS = 60_000L
    private const val SETTLE_MS = 2_000L
    private const val DEFAULT_KM = 300.0
  }
}
