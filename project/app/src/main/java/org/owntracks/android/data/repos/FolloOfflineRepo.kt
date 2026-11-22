package org.owntracks.android.data.repos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.model.messages.FolloOff
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.ui.main.FolloOffline
import org.owntracks.android.ui.main.FolloRules
import org.owntracks.android.ui.main.FolloTileCache
import org.owntracks.android.ui.main.FolloTileSource
import org.owntracks.android.ui.main.FolloTileStore
import timber.log.Timber

/**
 * The offline map (Alex, 10 Nov 2026: a fallback map if QField fails; docs/OFFLINE_GRIDS_20261110.md §4): saves the
 * imagery inside the wake-up circles into the map's own tile cache, so the map draws there with no signal. The phone
 * fetches the tiles itself from the same servers the map uses (no tile packs: the imagery licences allow a cache on
 * the phone, not redistribution). What to save is the server's call (status `off`); [FolloOffline.plan] turns it and
 * the held circles into a tile list.
 *
 * A pass runs when the app starts or comes forward, when the circles, the `off` settings or the network change, and
 * after a tap on the waiting line. It skips what is already saved, fetches at most [PARALLEL] tiles at a time, by
 * itself on an unmetered connection (Wi-Fi) and on a metered one only when few tiles are missing (`off.free`) or the
 * volunteer tapped Download. Saved tiles get a [KEEP_MS] expiry: the map then never re-fetches them daily, and the
 * cache's own trimming (earliest expiry first) takes the tiles the map merely passed over before these.
 */
@Singleton
class FolloOfflineRepo
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val waypointsRepo: WaypointsRepo,
    private val folloStatusRepo: FolloStatusRepo,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  /** What the card's third line says. */
  sealed class State {
    /** Nothing to save (no `off`, no circles of a named area nearby, no position yet): no line. */
    object Hidden : State()

    data class Saving(val percent: Int) : State()

    /** On a metered connection with more than `off.free` tiles missing: [megabytes] to fetch after a tap. */
    data class Waiting(val megabytes: Int) : State()

    object Saved : State()
  }

  val state = MutableStateFlow<State>(State.Hidden)

  /** Percent of the offline map saved, for the post's `fd.of`; null while there is nothing to save. */
  @Volatile
  var percent: Int? = null
    private set

  /**
   * FP034 (Alex 15 Nov 2026: "3 green ticks for every person before they leave basecamp"): the percent to check in with
   * when a pass that fetched tiles ends at 100 % or stops part-way, so base camp's phone table (fd.of) moves without the
   * app being reopened. BackgroundService sends the location-free hello (src "map").
   */
  private val mutableMapCheckIns = MutableSharedFlow<Int>(extraBufferCapacity = 1)
  val mapCheckIns: SharedFlow<Int> = mutableMapCheckIns
  @Volatile private var mapCheckInSent: Int? = null
  @Volatile private var mapCheckInAt = 0L

  private val prefs = context.getSharedPreferences("follo_offline", Context.MODE_PRIVATE)
  private val kicks = Channel<Unit>(Channel.CONFLATED)
  private val absentFile = File(context.filesDir, "follo_offline_absent.txt")
  /** Tiles the imagery does not have (HTTP 404, e.g. Esri past its depth): counted as done, never asked twice. */
  private val absent: MutableSet<Long> = Collections.synchronizedSet(HashSet())
  @Volatile private var userAllowed = false
  @Volatile private var online = false
  @Volatile private var metered = true
  /** Counts network changes; a running pass re-judges whether it may go on when this moves. */
  private val netEpoch = AtomicInteger(0)
  private var retries = 0
  private var lastOff: String? = null
  private val store by lazy { FolloTileStore() }
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  }

  init {
    scope.launch(io) {
      runCatching { if (absentFile.exists()) absentFile.readLines().mapNotNullTo(absent) { it.toLongOrNull() } }
      watchNetwork()
      launch {
        folloStatusRepo.status.collect { s ->
          val key = offKey(s?.message?.off)
          if (key != lastOff) {
            lastOff = key
            kick()
          }
        }
      }
      launch { waypointsRepo.repoChangedEvent.collect { kicks.trySend(Unit) } }
      for (k in kicks) {
        delay(SETTLE_MS) // a new circle set arrives as a run of changes: one pass after the last
        try {
          pass()
        } catch (e: Exception) {
          Timber.w(e, "Offline map pass failed")
        }
      }
    }
  }

  /** Look again now (the app came forward, or something it depends on changed). */
  fun kick() {
    retries = 0
    kicks.trySend(Unit)
  }

  /** The volunteer tapped Download on the waiting line: mobile data may be used for this set. */
  fun allowMetered() {
    userAllowed = true
    kick()
  }

  /**
   * The phone's position for the `km` rule, from the screen's own fixes. Kept on the phone only (never sent), and
   * only rewritten after [MOVED_M], so a walk does not write it every second.
   */
  fun notePosition(lat: Double, lon: Double) {
    val had = position()
    if (had != null && FolloRules.distanceM(had.first, had.second, lat, lon) < MOVED_M) return
    prefs.edit().putLong(KEY_LAT, lat.toRawBits()).putLong(KEY_LON, lon.toRawBits()).apply()
    kick()
  }

  /** The phone's last known position for the `km` rule (kept on the phone only), for the layers too (19 Nov 2026). */
  fun lastPosition(): Pair<Double, Double>? = position()

  private fun position(): Pair<Double, Double>? =
      if (prefs.contains(KEY_LAT) && prefs.contains(KEY_LON))
          Double.fromBits(prefs.getLong(KEY_LAT, 0)) to Double.fromBits(prefs.getLong(KEY_LON, 0))
      else null

  private fun offKey(off: FolloOff?): String? =
      off?.let { "${it.z?.toSortedMap()}|${it.km}|${it.cap}|${it.free}" }

  private fun watchNetwork() {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun judge(caps: NetworkCapabilities?) {
      val on = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      val paid = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
      if (on == online && paid == metered) return // signal strength and the like change nothing here
      online = on
      metered = paid
      netEpoch.incrementAndGet()
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
      Timber.w(e, "Offline map: cannot watch the network")
    }
  }

  private fun show(total: Int, missing: Int, waiting: Boolean) {
    val pct = FolloOffline.percent(total, missing)
    percent = pct
    state.value =
        when {
          missing <= 0 -> State.Saved
          waiting -> State.Waiting(FolloOffline.megabytes(missing))
          else -> State.Saving(pct)
        }
  }

  private fun hide() {
    percent = null
    state.value = State.Hidden
  }

  /** The tiles of [plan] not in the cache (or expired there); the saved ones are given the long expiry. */
  private fun missingOf(source: FolloTileSource, plan: List<Long>): List<Long> {
    val now = System.currentTimeMillis()
    val have = store.expiries(source.name())
    val missing = ArrayList<Long>()
    val extend = ArrayList<Long>()
    for (t in plan) {
      val key = SqlTileWriter.getIndex(t)
      val expires = have[key]
      when {
        expires == null || expires < now -> if (t !in absent) missing.add(t)
        expires < now + KEEP_MS - REKEEP_MS -> extend.add(key)
      }
    }
    if (extend.isNotEmpty()) store.keepUntil(source.name(), extend, now + KEEP_MS)
    return missing
  }

  private suspend fun pass() {
    val off = folloStatusRepo.status.value?.message?.off
    val zooms = off?.z
    if (!preferences.setupCompleted || off == null || zooms.isNullOrEmpty()) return hide()
    val settings = FolloOffline.Settings(zooms, off.km ?: DEFAULT_KM, off.cap ?: DEFAULT_CAP, off.free ?: DEFAULT_FREE)
    val circles =
        waypointsRepo.getAll().mapNotNull { w ->
          if (w.geofenceRadius <= 0) return@mapNotNull null
          FolloRules.wakeArea(w.description)?.let {
            FolloRules.Circle(w.geofenceLatitude.value, w.geofenceLongitude.value, w.geofenceRadius.toDouble(), it)
          }
        }
    val at = position()
    FolloTileCache.configure(context)
    // the same source as the map's (MainActivity), so the same cache rows and the same per-tile imagery
    val source = FolloTileSource.of(preferences.mapTileUrl, preferences.mapTileMaxZoom)
    val plan =
        FolloOffline.plan(circles, settings, at?.first, at?.second) { x, y, z ->
          source.nativeMaxZoom(MapTileIndex.getTileIndex(z, x, y))
        }
    if (plan.isEmpty()) return hide()
    var missing = missingOf(source, plan)
    if (missing.isEmpty()) {
      userAllowed = false
      return show(plan.size, 0, false)
    }
    if (!online) return show(plan.size, missing.size, false) // goes on by itself when the network is back
    if (!FolloOffline.mayFetch(metered, missing.size, settings.free, userAllowed)) return show(plan.size, missing.size, true)
    show(plan.size, missing.size, false)
    val ended = fetch(source, missing, plan.size, settings.free)
    missing = missingOf(source, plan)
    if (missing.isEmpty()) userAllowed = false
    show(plan.size, missing.size, missing.isNotEmpty() && online && !FolloOffline.mayFetch(metered, missing.size, settings.free, userAllowed))
    checkInIfDue()
    when {
      missing.isEmpty() -> Unit
      ended == End.NETWORK -> kicks.trySend(Unit)
      retries < MAX_RETRIES -> {
        retries++
        scope.launch(io) {
          delay(RETRY_MS)
          kicks.trySend(Unit)
        }
      }
    }
  }

  /** FP034: after a pass that fetched tiles, check in with the new percent (100 % at once, part-way at most once a minute). */
  private fun checkInIfDue() {
    val pct = percent ?: return
    val now = android.os.SystemClock.elapsedRealtime()
    if (!FolloRules.mapCheckInDue(fetched = true, percent = pct, lastSent = mapCheckInSent, sinceLastMs = now - mapCheckInAt)) return
    if (!mutableMapCheckIns.tryEmit(pct)) return
    mapCheckInSent = pct
    mapCheckInAt = now
    Timber.i("Offline map: the pass ended at $pct %, checking in")
  }

  private enum class End {
    DONE,
    /** The connection changed under the pass (Wi-Fi lost): judged again by the next pass. */
    NETWORK,
    /** Too many failures in a row: tried again later. */
    FAILING,
  }

  private enum class Got {
    SAVED,
    ABSENT,
    FAILED,
  }

  private suspend fun fetch(source: FolloTileSource, missing: List<Long>, total: Int, free: Int): End {
    val next = AtomicInteger(0)
    val settled = AtomicInteger(0)
    val failing = AtomicInteger(0)
    val epoch = netEpoch.get()
    val end = java.util.concurrent.atomic.AtomicReference(End.DONE)
    val absentBefore = absent.size
    coroutineScope {
      repeat(PARALLEL) {
        launch(io) {
          while (isActive && end.get() == End.DONE) {
            val i = next.getAndIncrement()
            if (i >= missing.size) break
            if (netEpoch.get() != epoch && (!online || !FolloOffline.mayFetch(metered, missing.size - settled.get(), free, userAllowed))) {
              end.set(End.NETWORK)
              break
            }
            when (one(source, missing[i])) {
              Got.SAVED -> {
                failing.set(0)
                settled.incrementAndGet()
              }
              Got.ABSENT -> {
                failing.set(0)
                absent.add(missing[i])
                settled.incrementAndGet()
              }
              Got.FAILED -> if (failing.incrementAndGet() >= MAX_FAILING) end.set(End.FAILING)
            }
            val n = settled.get()
            if (n % 25 == 0) show(total, missing.size - n, false)
          }
        }
      }
    }
    if (absent.size != absentBefore) {
      runCatching { absentFile.writeText(synchronized(absent) { absent.joinToString("\n") }) }
    }
    return end.get()
  }

  /** One tile, fetched as the map fetches it and written to the map's cache with the long expiry. */
  private fun one(source: FolloTileSource, tile: Long): Got =
      try {
        val request =
            Request.Builder()
                .url(source.getTileURLString(tile))
                .header("User-Agent", Configuration.getInstance().userAgentValue)
                .build()
        client.newCall(request).execute().use { response ->
          when {
            response.code == 404 -> Got.ABSENT
            !response.isSuccessful -> Got.FAILED
            else -> {
              val type = response.body.contentType()
              val bytes = response.body.bytes()
              if (bytes.isEmpty() || (type != null && type.type != "image")) Got.FAILED
              else {
                store.saveFile(source, tile, ByteArrayInputStream(bytes), System.currentTimeMillis() + KEEP_MS)
                Got.SAVED
              }
            }
          }
        }
      } catch (e: Exception) {
        Got.FAILED
      }

  companion object {
    private const val PARALLEL = 4
    private const val SETTLE_MS = 2_000L
    /** Saved tiles are good for this long; after it they still draw offline and are fetched afresh on Wi-Fi. */
    private const val KEEP_MS = 90L * 24 * 3600 * 1000
    /** A saved tile's expiry is pushed out again only once it has come this much closer. */
    private const val REKEEP_MS = 30L * 24 * 3600 * 1000
    private const val MAX_FAILING = 30
    private const val MAX_RETRIES = 3
    private const val RETRY_MS = 120_000L
    private const val MOVED_M = 2_000.0
    private const val DEFAULT_KM = 300.0
    private const val DEFAULT_CAP = 20_000
    private const val DEFAULT_FREE = 2_500
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
  }
}
