package org.owntracks.android.ui.main

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.owntracks.android.data.repos.ContactsRepo
import org.owntracks.android.data.repos.EndpointStateRepo
import org.owntracks.android.data.repos.FolloStatusRepo
import org.owntracks.android.data.repos.FolloTrackRepo
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.R
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageConfiguration
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.support.DeviceMetricsProvider
import timber.log.Timber

/**
 * State behind the one FOLLO Tracks screen. Everything the banner and the card show comes from
 * here: the phone's own fix for the blue dot, the other trackers (contacts), the search-area
 * waypoints, the upload queue, the last published fix and whether the phone is set up at all.
 */
@HiltViewModel
class MainViewModel
@Inject
constructor(
    application: Application,
    private val preferences: Preferences,
    private val contactsRepo: ContactsRepo,
    private val waypointsRepo: WaypointsRepo,
    locationRepo: LocationRepo,
    endpointStateRepo: EndpointStateRepo,
    private val folloStatusRepo: FolloStatusRepo,
    private val folloTrackRepo: FolloTrackRepo,
    private val deviceMetricsProvider: DeviceMetricsProvider,
    private val parser: Parser,
    @param:CoroutineScopes.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

  private val locationUpdatesRequested = MutableStateFlow(false)

  /** The device's own fix, for the blue dot. Only flows once [requestLocationUpdates] was called. */
  @Suppress("MissingPermission") // The activity only asks once the permission is granted
  @OptIn(ExperimentalCoroutinesApi::class)
  val currentLocation: StateFlow<Location?> =
      locationUpdatesRequested
          .flatMapLatest { if (it) locationCallbackFlow(application) else emptyFlow() }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  fun requestLocationUpdates() {
    locationUpdatesRequested.value = true
  }

  /** Fires every 15 s so "Last sent 3 min ago" stays honest while the screen is open. */
  val tick: Flow<Unit> = flow {
    while (true) {
      emit(Unit)
      delay(15_000)
    }
  }

  val monitoring = MutableStateFlow(preferences.monitoring)
  /** The map's imagery: tile URL template and the deepest zoom it has tiles for. */
  val tileState = MutableStateFlow(preferences.mapTileUrl to preferences.mapTileMaxZoom)
  val displayNameState = MutableStateFlow(preferences.displayName)

  /** Last status element from the server; null until the phone has posted once. */
  val folloStatus: StateFlow<FolloStatusRepo.Status?> = folloStatusRepo.status
  /** The server answered 401/403 to the last send (review 28 Oct 2026, B2). */
  val rejected: StateFlow<Boolean> = folloStatusRepo.rejected
  /** When the server last accepted a position from this phone (0 = never). */
  val lastSentAt: StateFlow<Long> = folloStatusRepo.lastSentAt
  val setupCompleted = MutableStateFlow(preferences.setupCompleted)

  val lastPublished: StateFlow<Location?> = locationRepo.currentPublishedLocation

  /** Changes to the phone's own walking track (magenta line). */
  val trackUpdates: StateFlow<FolloTrackRepo.Update> = folloTrackRepo.updates

  fun addTrack(l: Location) = folloTrackRepo.add(l)

  fun trackSnapshot() = folloTrackRepo.snapshot()

  fun trackSegments(points: List<FolloTrackRepo.Point>) = folloTrackRepo.segments(points)

  fun saveTrack() = folloTrackRepo.save()
  val endpointState = endpointStateRepo.endpointState
  val queueLength = endpointStateRepo.endpointQueueLength

  val contactEvents = contactsRepo.repoChangedEvent
  val allContacts
    get() = contactsRepo.all

  val waypointEvents = waypointsRepo.repoChangedEvent

  suspend fun waypoints(): List<WaypointModel> = waypointsRepo.getAll()

  val batteryLevel: Int?
    get() = deviceMetricsProvider.batteryLevel

  val trackerId: String
    get() = folloStatus.value?.message?.tid?.takeIf { it.isNotBlank() } ?: preferences.tid.toString()

  val displayName: String
    get() = preferences.displayName


  private val preferenceChangeListener =
      object : Preferences.OnPreferenceChangeListener {
        override fun onPreferenceChanged(properties: Set<String>) {
          if (Preferences::monitoring.name in properties) monitoring.value = preferences.monitoring
          if (Preferences::setupCompleted.name in properties)
              setupCompleted.value = preferences.setupCompleted
          if (Preferences::mapTileUrl.name in properties || Preferences::mapTileMaxZoom.name in properties)
              tileState.value = preferences.mapTileUrl to preferences.mapTileMaxZoom
          if (Preferences::displayName.name in properties)
              displayNameState.value = preferences.displayName
        }
      }

  init {
    preferences.registerOnPreferenceChangedListener(preferenceChangeListener)
  }

  override fun onCleared() {
    preferences.unregisterOnPreferenceChangedListener(preferenceChangeListener)
    super.onCleared()
  }

  // ── Setup links ────────────────────────────────────────────────────────────────────────────

  sealed class SetupLink {
    data class Ready(val config: MessageConfiguration, val host: String, val tid: String) :
        SetupLink()

    data class Bad(val reason: String) : SetupLink()

    /**
     * A real FOLLO link that gave no setup: [status] = what /appcfg answered, null when nothing came
     * back (no signal, timeout). 403 = the signature is wrong (old or garbled link, or the secret
     * was rotated), 410 = the link was turned off with `ft_admin.py block`.
     */
    data class Unfetched(val status: Int?) : SetupLink() {
      /** What to tell the user. Same words as iOS (review B9 add-on, 29 Oct 2026). */
      val messageRes: Int
        get() =
            when (status) {
              410 -> R.string.follo_setup_off
              403 -> R.string.follo_setup_stale
              else -> R.string.follo_setup_unreachable
            }
    }
  }

  /** /appcfg answered, but not with 2xx. */
  private class HttpStatus(val code: Int) : java.io.IOException("HTTP $code")

  /**
   * Reads a setup link. Accepted forms:
   * - `follotracks:///config?inline=<base64 configuration>` (what the FOLLO setup page serves
   *   today, with the scheme renamed so real OwnTracks never sees it)
   *
   * The configuration must point at a FOLLO server over HTTPS in HTTP mode; anything else is
   * refused before the user is even asked. That, plus the confirm dialog, is what stands in for
   * OwnTracks' "allow external configuration" switch.
   */
  suspend fun parseSetupLink(uri: Uri): SetupLink {
    val bytes: ByteArray =
        when {
          uri.scheme == "follotracks" && uri.path == "/config" -> {
            val inline = uri.getQueryParameter("inline")
            if (inline.isNullOrBlank()) return SetupLink.Bad("no inline configuration")
            try {
              Base64.getDecoder().decode(inline)
            } catch (e: IllegalArgumentException) {
              return SetupLink.Bad("inline configuration is not base64")
            }
          }
          // The https link from Messages, handed straight to the app as a verified App Link: ask the
          // server for the configuration it stands for. Only FOLLO hosts, only /app.
          uri.scheme == "https" && isFolloHost(uri.host) && uri.path == "/app" -> {
            val cfgUrl =
                uri.buildUpon().path("/appcfg").build().toString()
            try {
              withContext(ioDispatcher) {
                val client =
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                client.newCall(Request.Builder().url(cfgUrl).build()).execute().use { r ->
                  if (!r.isSuccessful) throw HttpStatus(r.code)
                  r.body.bytes()
                }
              }
            } catch (e: HttpStatus) {
              Timber.w("FOLLO answered the setup link with HTTP ${e.code}")
              return SetupLink.Unfetched(e.code)
            } catch (e: Exception) {
              Timber.w(e, "Could not fetch the setup configuration")
              return SetupLink.Unfetched(null)
            }
          }
          // Scheme, host and path only: the query of a real link carries the signing key (review B5, 28 Oct 2026).
          else -> return SetupLink.Bad("not a setup link: ${uri.scheme}://${uri.host}${uri.path}")
        }
    val config =
        try {
          parser.fromJson(bytes) as? MessageConfiguration
        } catch (e: Exception) {
          Timber.w(e, "Setup link could not be decoded")
          null
        } ?: return SetupLink.Bad("not a configuration")
    val url = config["url"] as? String ?: return SetupLink.Bad("no url")
    val host = Uri.parse(url).host ?: return SetupLink.Bad("bad url")
    if (Uri.parse(url).scheme != "https" || !isFolloHost(host))
        return SetupLink.Bad("not a FOLLO server: $host")
    if ((config["mode"] as? Int) != 3) return SetupLink.Bad("not HTTP mode")
    val tid = (config["tid"] as? String).orEmpty()
    return SetupLink.Ready(config, host, tid)
  }

  /** Applies a confirmed setup link (only the settings FOLLO needs, [FolloRules.importable]) and marks the phone as set up. */
  fun applySetup(link: MessageConfiguration, onDone: () -> Unit) {
    val config = FolloRules.importable(link)
    viewModelScope.launch(ioDispatcher) {
      preferences.importConfiguration(config)
      if (config.waypoints.isNotEmpty()) waypointsRepo.importFromMessage(config.waypoints)
      (config["folloName"] as? String)?.let { preferences.displayName = it }
      folloTrackRepo.clear() // a new setup starts a clean track
      folloStatusRepo.setRejected(false) // and a clean slate with the server
      preferences.setupCompleted = true
      setupCompleted.value = true
      Timber.i("FOLLO setup applied for tid=${preferences.tid}")
      launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
    }
  }

  val isMoving: Boolean
    get() = monitoring.value == MonitoringMode.Move

  companion object {
    /** Servers a setup link may point at. Add a host here before the server moves. */
    val FOLLO_HOSTS = setOf("follo-tracks.pages.dev", "tracks.follo.org.au")

    /** The FOLLO hosts, plus the Cloudflare preview deployments of the same project. */
    fun isFolloHost(host: String?): Boolean =
        host != null && (host in FOLLO_HOSTS || host.endsWith(".follo-tracks.pages.dev"))
  }
}
