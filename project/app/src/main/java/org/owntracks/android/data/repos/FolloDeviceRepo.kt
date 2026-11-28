package org.owntracks.android.data.repos

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * FP122 (28 Nov 2026, Alex, after a recon morning where the accuracy table had "L5 unknown" for six of eight
 * phones): which phone is this, and does its GPS hear the second band (L5)? Two keys in the `fd` diagnostics:
 * - `md` the phone's model as the share check words it, "Samsung SM-S918B";
 * - `l5` 1 = the phone tracks satellite signals on the second band (L5 / E5 / L2: dual-band GPS), 0 = it does not,
 *   absent = not known yet.
 *
 * Not on every post: the first three posts after the process started, when `l5` has changed since it was last sent,
 * and otherwise once in twelve hours, so a search day always carries it at least once. The server keeps both against
 * the phone. Neither says where the phone is or anything about anyone else.
 *
 * How `l5` is found, in this order:
 * 1. Android 14 and later can list the signal types the GPS chip supports. A list with a second-band signal = 1 and
 *    nothing more is asked; a list without one = 0 for now. Many phones give an empty list, which says nothing.
 * 2. From the satellites being tracked, while the app is using GPS anyway (listening adds no GPS use of its
 *    own): one signal on the second band = 1, and that is kept for good. After [PLAIN_NEEDED] reports that each named
 *    the frequency of at least [MIN_SATS] satellites, none of them on the second band = 0 (which a later second-band
 *    signal still turns into 1). A chip that does not report frequencies leaves it unknown.
 */
@Singleton
class FolloDeviceRepo
internal constructor(
    private val store: Store,
    private val now: () -> Long,
    private val model: String,
    private val source: Source,
) {
  @Inject
  constructor(
      @ApplicationContext context: Context
  ) : this(
      SharedPrefsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
      System::currentTimeMillis,
      deviceName(Build.MANUFACTURER, Build.MODEL),
      AndroidSource(context),
  )

  interface Store {
    fun get(key: String): String?

    fun put(key: String, value: String)
  }

  private class SharedPrefsStore(private val prefs: SharedPreferences) : Store {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
      prefs.edit().putString(key, value).apply()
    }
  }

  /** What the phone's GPS says about itself. */
  interface Source {
    /** Carrier frequencies (Hz) of the signal types the chip says it supports; empty when it does not say. */
    fun supportedHz(): List<Double>

    /**
     * Starts handing over, about once a second while GPS is in use, the carrier frequencies (Hz) of the satellites in
     * view that report one. False when it could not start (no precise-location permission yet).
     */
    fun startWatching(onStatus: (List<Float>) -> Unit): Boolean

    fun stopWatching()
  }

  private var postsSinceStart = 0
  private var askedChip = false
  private var watching = false
  private var doneWatching = false
  private var plainReports = 0

  /** `l5` as known now: 1, 0 or null. */
  val l5: Int?
    @Synchronized get() = store.get(KEY_L5)?.toIntOrNull()?.takeIf { it == 0 || it == 1 }

  /** `md` and `l5` for this post, or null when they need not be repeated. */
  @Synchronized
  fun fdFields(): Pair<String, Int?>? {
    look()
    val n = postsSinceStart++
    val value = l5
    val sentAt = store.get(KEY_SENT_AT)?.toLongOrNull() ?: 0L
    val due = n < FIRST_POSTS || store.get(KEY_SENT_L5) != value.toString() || now() - sentAt > REPEAT_MS || now() < sentAt
    if (!due) return null
    store.put(KEY_SENT_L5, value.toString())
    store.put(KEY_SENT_AT, now().toString())
    return model to value
  }

  /** Asks the chip once per process; failing a clear answer, listens to the satellites until one is reached. */
  private fun look() {
    if (!askedChip) {
      askedChip = true
      val hz = runCatching { source.supportedHz() }.getOrDefault(emptyList())
      if (hz.any { secondBand(it) }) {
        store.put(KEY_L5, "1")
        store.put(KEY_L5_BY, BY_CHIP)
      } else if (hz.isNotEmpty() && store.get(KEY_L5) == null) {
        // the chip's list has no second band: 0 for now, and the satellites still get a say (below)
        store.put(KEY_L5, "0")
        store.put(KEY_L5_BY, BY_CHIP)
      }
    }
    if (store.get(KEY_L5) == "1") doneWatching = true
    if (watching || doneWatching) return
    watching = runCatching { source.startWatching(::onStatus) }.getOrDefault(false)
  }

  @Synchronized
  internal fun onStatus(hz: List<Float>) {
    if (doneWatching) return
    if (hz.any { secondBand(it.toDouble()) }) {
      Timber.i("FOLLO device: a second-band (L5) signal is being tracked")
      store.put(KEY_L5, "1")
      store.put(KEY_L5_BY, BY_SEEN)
      finish()
    } else if (hz.size >= MIN_SATS && ++plainReports >= PLAIN_NEEDED) {
      Timber.i("FOLLO device: $plainReports satellite reports, none on the second band")
      store.put(KEY_L5, "0")
      store.put(KEY_L5_BY, BY_SEEN)
      finish()
    }
  }

  private fun finish() {
    doneWatching = true
    watching = false
    runCatching { source.stopWatching() }
  }

  private class AndroidSource(private val context: Context) : Source {
    private val manager
      get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var callback: GnssStatusCompat.Callback? = null

    override fun supportedHz(): List<Double> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            manager?.gnssCapabilities?.gnssSignalTypes?.map { it.carrierFrequencyHz } ?: emptyList()
        else emptyList()

    override fun startWatching(onStatus: (List<Float>) -> Unit): Boolean {
      val lm = manager ?: return false
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
      val cb =
          object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
              val hz = ArrayList<Float>(status.satelliteCount)
              for (i in 0 until status.satelliteCount) if (status.hasCarrierFrequencyHz(i)) hz.add(status.getCarrierFrequencyHz(i))
              onStatus(hz)
            }
          }
      val ok = LocationManagerCompat.registerGnssStatusCallback(lm, cb, Handler(Looper.getMainLooper()))
      if (ok) callback = cb
      return ok
    }

    override fun stopWatching() {
      val lm = manager ?: return
      callback?.let { LocationManagerCompat.unregisterGnssStatusCallback(lm, it) }
      callback = null
    }
  }

  companion object {
    const val PREFS_NAME = "follo_device"
    internal const val KEY_L5 = "l5"
    internal const val KEY_L5_BY = "l5By"
    internal const val KEY_SENT_L5 = "sentL5"
    internal const val KEY_SENT_AT = "sentAt"
    internal const val BY_CHIP = "chip"
    internal const val BY_SEEN = "seen"
    internal const val FIRST_POSTS = 3
    internal const val REPEAT_MS = 12 * 3_600_000L
    internal const val MIN_SATS = 4
    internal const val PLAIN_NEEDED = 300
    internal const val MODEL_MAX = 48

    /**
     * The second GPS band as phones use it: L5 / E5a / B2a at 1176.45 MHz, E5b / B2b at 1207.14, L2 at 1227.6 and their
     * neighbours up to 1300 MHz. The first band (L1 / E1 / B1 / G1) sits at 1559 to 1610 MHz.
     */
    fun secondBand(hz: Double): Boolean = hz in 1.1e9..1.3e9

    /** "Samsung SM-S918B", "Google Pixel 8 Pro"; just the model when it already starts with the maker's name. */
    fun deviceName(manufacturer: String?, model: String?): String {
      val maker = manufacturer.orEmpty().trim()
      val m = model.orEmpty().trim()
      val name =
          when {
            m.isEmpty() -> maker.replaceFirstChar { it.uppercase() }
            maker.isEmpty() || m.startsWith(maker, ignoreCase = true) -> m
            else -> "${maker.replaceFirstChar { it.uppercase() }} $m"
          }
      return name.filter { it >= ' ' }.take(MODEL_MAX)
    }
  }
}
