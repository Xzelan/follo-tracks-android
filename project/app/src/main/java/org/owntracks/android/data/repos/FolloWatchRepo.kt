package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.net.http.HttpMessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.ui.main.FolloWatch
import timber.log.Timber

/**
 * Every phone in a search circle, and every live-map search (iOS FolloWatchStore; Alex 13 + 18 Nov 2026; rules in
 * FolloWatch). Only for a phone whose status element carries `all: 1`; the screen asks [fetch] every [PERIOD_MS] while
 * it is in front and the app is not Off. Holds the list, the searches with their outlines, and every phone's line of
 * the last day. Main thread for the state; the request on io.
 */
@Singleton
class FolloWatchRepo
@Inject
constructor(
    @ApplicationContext context: Context,
    private val preferences: Preferences,
    @CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  class Snapshot(
      val phones: List<FolloWatch.Phone> = emptyList(),
      val areas: List<FolloWatch.Area>? = null,
      /** Search code → its circles' outline as lat,lon,… */
      val outlines: Map<String, DoubleArray> = emptyMap(),
      /** Every phone's line of the last day, oldest fix first: the phones in circles and those that have left */
      val logs: Map<String, List<FolloWatch.Point>> = emptyMap(),
      /** Goes up when that phone's line changes */
      val logVersion: Map<String, Int> = emptyMap(),
      val fetchedAt: Long = 0L,
  )

  val snapshot = MutableStateFlow(Snapshot())

  private val prefs = context.getSharedPreferences("follo_watch", Context.MODE_PRIVATE)
  private val logs = mutableMapOf<String, MutableList<FolloWatch.Point>>()
  private val logVersion = mutableMapOf<String, Int>()
  private var outlines: Map<String, DoubleArray> = emptyMap()
  private var av = ""
  private var logsSeen = 0L
  private var refusedAt = 0L
  private var fetching = false
  /** Goes up on [clear], so an answer asked for before it is not taken */
  private var generation = 0
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }

  /** The search chosen in the drop-down (18 Nov 2026), kept over restarts; null = none. */
  var chosenArea: String?
    get() = prefs.getString(KEY_AREA, null)
    set(value) {
      prefs.edit().apply { if (value == null) remove(KEY_AREA) else putString(KEY_AREA, value) }.apply()
    }

  /** Drop everything held (not allowed any more, Off, or refused). */
  fun clear() {
    generation++
    logs.clear()
    logVersion.clear()
    outlines = emptyMap()
    av = ""
    logsSeen = 0L
    if (snapshot.value.fetchedAt != 0L || snapshot.value.phones.isNotEmpty()) snapshot.value = Snapshot()
  }

  /**
   * Debug builds only (adb: `--ez follo_watch_demo true`): the drop-down with MADE-UP phones and searches near Hobart,
   * no server, so the screen can be checked on the emulator (as iOS's `-FolloWatchDemo YES`). Never real people.
   * 22 Nov 2026: each with a dot colour and a vest (FP078), and "Demo Gone", whose last fix is two minutes old and
   * 10 m inside the edge of a circle AA of 660 m at -42.880, 147.321 (give it with `follo_scope_demo`): a ghost (FP079).
   */
  var demo = false
  private var demoStep = 0
  private var demoT0 = 0L

  /** Ask once (and again at once while the server has more lines to hand over). Call on the main thread. */
  suspend fun fetch() {
    if (fetching) return
    if (demo && org.owntracks.android.BuildConfig.DEBUG) {
      FolloWatch.parse(demoBody())?.let { take(it, true) }
      return
    }
    if (System.currentTimeMillis() - refusedAt < REFUSED_FOR_MS) return
    val base = preferences.url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return
    val user = base.username.ifEmpty { preferences.username }
    val pass = base.password.ifEmpty { preferences.password }
    if (user.isEmpty() || pass.isEmpty()) return
    fetching = true
    try {
      var more = true
      var rounds = 0
      while (more && rounds++ < 10) {
        val gen = generation
        val asked = logsSeen
        val url =
            base.resolve("/all")!!
                .newBuilder()
                .username("")
                .password("")
                .query(null)
                .addQueryParameter("logs", asked.toString())
                .addQueryParameter("av", av)
                .build()
        val (code, body) =
            withContext(io) {
              try {
                val request =
                    Request.Builder()
                        .url(url)
                        .header(HttpMessageProcessorEndpoint.HEADER_USERAGENT, HttpMessageProcessorEndpoint.USERAGENT)
                        .header(HttpMessageProcessorEndpoint.HEADER_AUTHORIZATION, Credentials.basic(user, pass))
                        .build()
                client.newCall(request).execute().use { r -> r.code to (if (r.isSuccessful) r.body.string() else null) }
              } catch (e: Exception) {
                Timber.w(e, "Phones in circles: no answer")
                0 to null
              }
            }
        if (code == 401 || code == 403) {
          refusedAt = System.currentTimeMillis()
          clear()
          return
        }
        if (gen != generation) return // cleared meanwhile (Off and on again): asked afresh next time
        val reply = body?.let { withContext(io) { FolloWatch.parse(it) } } ?: return // no answer: what is held stays
        more = take(reply, asked == logsSeen)
      }
    } finally {
      fetching = false
    }
  }

  /** True when the server has more lines to hand over at once (a full page). */
  private fun take(r: FolloWatch.Reply, withLogs: Boolean): Boolean {
    val l = if (withLogs) r.logs else null
    if (l != null) {
      for ((user, points) in l.users) {
        val line = logs.getOrPut(user) { mutableListOf() }
        if (FolloWatch.merge(points, line)) logVersion[user] = (logVersion[user] ?: 0) + 1
      }
      logsSeen = maxOf(0L, l.seen)
    }
    // what is over a day old goes (the app left open): the line gets shorter from its old end
    val oldest = System.currentTimeMillis() / 1000.0 - FolloWatch.KEEP_S
    val it = logs.entries.iterator()
    while (it.hasNext()) {
      val (user, line) = it.next()
      val first = line.firstOrNull() ?: continue
      if (first.t >= oldest) continue
      line.removeAll { p -> p.t < oldest }
      logVersion[user] = (logVersion[user] ?: 0) + 1
      if (line.isEmpty()) it.remove()
    }
    if (r.outlines != null) outlines = r.outlines
    if (r.av != null) av = r.av
    snapshot.value =
        Snapshot(
            phones = r.phones,
            areas = r.areas,
            outlines = outlines,
            logs = logs.mapValues { (_, v) -> v.toList() },
            logVersion = logVersion.toMap(),
            fetchedAt = System.currentTimeMillis(),
        )
    return l?.more ?: false
  }

  private fun demoBody(): String {
    demoStep++
    val now = System.currentTimeMillis() / 1000
    // the made-up fixes keep the times they were first given (22 Nov 2026: each answer used to re-time them all, which
    // the walking-only rule rightly read as jumping about); Jon's line grows by one fix an answer
    if (demoT0 == 0L) demoT0 = now
    val t0 = demoT0
    val jon = (0..20 + demoStep).joinToString(",") { k -> "${t0 - 200 + (k - 1) * 10},${-42.8800 + k * 0.00005},${147.3200 + k * 0.00005}" }
    val cyn = (0..8).joinToString(",") { k -> "${t0 - 900 + k * 10},${-42.8790 - (8 - k) * 0.00006},147.3230" }
    val left = (0 until 45).joinToString(",") { k -> "${t0 - 3000 + k * 10},${-42.8810 - k * 0.00002 - (if (k < 30) 0.0 else 0.001)},${147.3180 + k * 0.00008}" }
    val ring = (0 until 36).joinToString(",") { k -> val a = k * 10 * Math.PI / 180; "${-42.880 + 0.006 * kotlin.math.sin(a)},${147.321 + 0.008 * kotlin.math.cos(a)}" }
    return """{"now":$now,
      "phones":[{"u":"demo1","n":"Demo Jo","tid":"DJ1","team":"Blue","tc":"#5cc8ff","vest":"O","area":"AA","lat":${-42.8800 + (20 + demoStep) * 0.00005},"lon":${147.3200 + (20 + demoStep) * 0.00005},"tst":${now - 4}},
                {"u":"demo2","n":"Demo Bea","tid":"DC1","team":"Red","tc":"#ff5c5c","vest":"P","area":"AA","lat":-42.8790,"lon":147.3230,"tst":${now - 400}},
                {"u":"demo4","n":"Demo Kim","tid":"DK1","team":"Green","tc":"#8dff5c","vest":"Y","area":"AA","lat":-42.8812,"lon":147.3222,"tst":${now - 8}},
                {"u":"demo5","n":"Demo Pat","tid":"DP1","team":"Pool","tc":"#bbbbbb","area":"AA","lat":-42.8788,"lon":147.3208,"tst":${now - 15}},
                {"u":"demo6","n":"Demo Gone","tid":"DG1","team":"Orange","tc":"#ffa95c","vest":"O","area":"AA","lat":-42.87415,"lon":147.3210,"tst":${now - 120}}],
      "logs":{"seen":$now,"more":0,"u":{"demo1":[$jon],"demo2":[$cyn],"demo3":[$left]}},
      "areas":[{"c":"AA","n":"Demo Hobart, Hobart TAS","col":"#ffff00","b":[-42.886,147.313,-42.874,147.329],"k":5},
               {"c":"BB","n":"Demo Empty, Kingston TAS","col":"#8000ff","b":[-42.99,147.29,-42.96,147.33],"k":0},
               {"c":"TEST","n":"Demo bench, Sandy Bay TAS","col":"#00FF00","b":[-42.905,147.325,-42.900,147.332],"k":0}],
      "av":"demo","outlines":{"AA":[$ring],"BB":[-42.99,147.29,-42.99,147.33,-42.96,147.33,-42.96,147.29],"TEST":[]}}"""
  }

  companion object {
    const val PERIOD_MS = 10_000L
    private const val REFUSED_FOR_MS = 5 * 60_000L
    private const val KEY_AREA = "watch_area"
  }
}
