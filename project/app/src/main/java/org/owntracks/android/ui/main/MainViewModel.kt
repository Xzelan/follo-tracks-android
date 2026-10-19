package org.owntracks.android.ui.main

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Base64
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
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.CoroutineScopes
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
    folloStatusRepo: FolloStatusRepo,
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
  val tileUrlState = MutableStateFlow(preferences.mapTileUrl)
  val displayNameState = MutableStateFlow(preferences.displayName)

  /** Last status element from the server; null until the phone has posted once. */
  val folloStatus: StateFlow<FolloStatusRepo.Status?> = folloStatusRepo.status
  val setupCompleted = MutableStateFlow(preferences.setupCompleted)

  val lastPublished: StateFlow<Location?> = locationRepo.currentPublishedLocation
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
    get() = preferences.tid.toString()

  val displayName: String
    get() = preferences.displayName

  val tileUrl: String
    get() = preferences.mapTileUrl

  private val preferenceChangeListener =
      object : Preferences.OnPreferenceChangeListener {
        override fun onPreferenceChanged(properties: Set<String>) {
          if (Preferences::monitoring.name in properties) monitoring.value = preferences.monitoring
          if (Preferences::setupCompleted.name in properties)
              setupCompleted.value = preferences.setupCompleted
          if (Preferences::mapTileUrl.name in properties) tileUrlState.value = preferences.mapTileUrl
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
  }

  /**
   * Reads a setup link. Accepted forms:
   * - `follotracks:///config?inline=<base64 configuration>` (what the FOLLO setup page serves
   *   today, with the scheme renamed so real OwnTracks never sees it)
   *
   * The configuration must point at a FOLLO server over HTTPS in HTTP mode; anything else is
   * refused before the user is even asked. That, plus the confirm dialog, is what stands in for
   * OwnTracks' "allow external configuration" switch.
   */
  fun parseSetupLink(uri: Uri): SetupLink {
    val inline =
        if (uri.scheme == "follotracks" && uri.path == "/config") uri.getQueryParameter("inline")
        else null
    if (inline.isNullOrBlank()) return SetupLink.Bad("not a setup link: $uri")
    val config =
        try {
          val bytes = Base64.getDecoder().decode(inline)
          parser.fromJson(bytes) as? MessageConfiguration
        } catch (e: Exception) {
          Timber.w(e, "Setup link could not be decoded")
          null
        } ?: return SetupLink.Bad("not a configuration")
    val url = config["url"] as? String ?: return SetupLink.Bad("no url")
    val host = Uri.parse(url).host ?: return SetupLink.Bad("bad url")
    if (Uri.parse(url).scheme != "https" || host !in FOLLO_HOSTS)
        return SetupLink.Bad("not a FOLLO server: $host")
    if ((config["mode"] as? Int) != 3) return SetupLink.Bad("not HTTP mode")
    val tid = (config["tid"] as? String).orEmpty()
    return SetupLink.Ready(config, host, tid)
  }

  /** Applies a confirmed setup link and marks the phone as set up. */
  fun applySetup(config: MessageConfiguration, onDone: () -> Unit) {
    viewModelScope.launch(ioDispatcher) {
      preferences.importConfiguration(config)
      if (config.waypoints.isNotEmpty()) waypointsRepo.importFromMessage(config.waypoints)
      (config["folloName"] as? String)?.let { preferences.displayName = it }
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
  }
}
