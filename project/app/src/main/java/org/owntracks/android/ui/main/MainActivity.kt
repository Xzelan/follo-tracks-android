package org.owntracks.android.ui.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Choreographer
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.owntracks.android.R
import org.owntracks.android.data.repos.ContactsRepoChange
import org.owntracks.android.data.repos.FolloDiagRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.databinding.ActivityMainBinding
import org.owntracks.android.model.Contact
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.support.ContactImageBindingAdapter
import org.owntracks.android.support.RequirementsChecker
import org.owntracks.android.ui.NotificationsStash
import org.owntracks.android.ui.mixins.BackgroundLocationPermissionRequester
import org.owntracks.android.ui.mixins.LocationPermissionRequester
import org.owntracks.android.ui.mixins.NotificationPermissionRequester
import org.owntracks.android.ui.mixins.ServiceStarter
import org.owntracks.android.ui.mixins.WorkManagerInitExceptionNotifier
import timber.log.Timber

/**
 * The one FOLLO Tracks screen: a live aerial map (you, the search area, FOLLO's markers and the
 * other trackers the server shares), a status banner that says in plain words whether the phone is
 * sharing, standing by or needs something fixed, and a card with who this phone is and when it
 * last sent. There are no settings: the phone is set up by a FOLLO link and the server drives the
 * rest.
 */
@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    ServiceStarter by ServiceStarter.Impl(),
    WorkManagerInitExceptionNotifier by WorkManagerInitExceptionNotifier.Impl() {

  @Inject lateinit var preferences: Preferences
  @Inject lateinit var folloDiagRepo: FolloDiagRepo
  @Inject lateinit var requirementsChecker: RequirementsChecker
  @Inject lateinit var notificationsStash: NotificationsStash
  @Inject lateinit var contactImageBindingAdapter: ContactImageBindingAdapter

  private val viewModel: MainViewModel by viewModels()
  private lateinit var binding: ActivityMainBinding
  private lateinit var map: MapView
  private var myLocationOverlay: MyLocationNewOverlay? = null
  private var service: BackgroundService? = null
  private var bannerBasePaddingTop = 0
  private var cardBaseMarginBottom = 0
  private var state: Banner? = null
  /** True while a system permission prompt is up; the banner then says "Setting up" instead of red. */
  private var promptInFlight = false
  /** The app's own explanation dialog, if one is up (it does not pause the activity). */
  private var ownDialog: androidx.appcompat.app.AlertDialog? = null
  /** Which way the phone points: turns the map (NAV). */
  private lateinit var compass: FolloCompass
  /** Portrait / landscape only by tilt + a gentle shake. */
  private lateinit var shakeTurner: FolloShakeTurner

  /** What the banner is showing; [kind] decides what the one button does. */
  private data class Banner(
      val kind: Kind,
      val color: Int,
      val title: String,
      val sub: String,
      val button: String? = null,
  )

  private enum class Kind {
    NOT_SET_UP,
    NEED_LOCATION,
    LOCATION_OFF,
    /** Approximate location only (Android 12+); every fix fails the 100 m filter (B1, 28 Oct 2026). */
    NEED_PRECISE,
    NEED_BACKGROUND,
    NEED_NOTIFICATIONS,
    NEED_BATTERY,
    SHARING,
    STANDBY,
    SETTING_UP,
  }

  private val serviceConnection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
          service = (binder as BackgroundService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          service = null
        }
      }

  // ── Permission requesters (same mechanics as upstream, FOLLO wording) ────────────────────

  private val notificationPermissionRequester =
      NotificationPermissionRequester(this, ::notificationPermissionGranted) {
        preferences.userDeclinedEnableNotificationPermissions = true
        render()
      }

  private val locationPermissionRequester =
      LocationPermissionRequester(
          this,
          { onLocationPermissionGranted() },
          {
            preferences.userDeclinedEnableLocationPermissions = true
            render()
          },
      )

  private val backgroundLocationPermissionRequester =
      BackgroundLocationPermissionRequester(
          this,
          {
            preferences.userDeclinedEnableBackgroundLocationPermissions = false
            service?.reInitializeLocationRequests()
            startService(this, BackgroundService.INTENT_ACTION_SEND_LOCATION_USER)
            render()
          },
          {
            preferences.userDeclinedEnableBackgroundLocationPermissions = true
            runPermissionChain()
            render()
          },
      )

  private val settingsLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onLocationPermissionGranted()
      }

  @RequiresPermission(POST_NOTIFICATIONS)
  private fun notificationPermissionGranted() {
    notificationsStash.showAll(NotificationManagerCompat.from(this))
    render()
  }

  private fun onLocationPermissionGranted() {
    if (requirementsChecker.hasLocationPermissions()) {
      viewModel.requestLocationUpdates()
      myLocationOverlay?.enableMyLocation()
      service?.reInitializeLocationRequests()
      // First chance to tell the server this phone exists: the post queued at setup time was
      // dropped because there was no location permission yet.
      if (preferences.setupCompleted) startService(this, BackgroundService.INTENT_ACTION_SEND_LOCATION_USER)
    }
    render()
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────────────────────

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    folloDiagRepo.noteLaunch("user")
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    bannerBasePaddingTop = binding.banner.paddingTop
    cardBaseMarginBottom = (binding.card.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
      val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
      binding.banner.updatePadding(top = bannerBasePaddingTop + bars.top)
      binding.card.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
        bottomMargin = cardBaseMarginBottom + bars.bottom
      }
      windowInsets
    }

    compass = FolloCompass(this) { if (camera == Camera.NAV) requestNavFrame() }
    shakeTurner =
        FolloShakeTurner(this) { Toast.makeText(this, R.string.follo_shake_hint, Toast.LENGTH_SHORT).show() }
    shakeTurner.lockCurrent()
    applyOrientation(resources.configuration.orientation)
    setUpMap()
    if (camera == Camera.NAV) setNavLook(true)
    binding.action.setOnClickListener { onActionClicked() }
    binding.card.setOnClickListener { recentre() }
    binding.banner.setOnClickListener { recentre() } // top or bottom, either one brings the arrow back (Alex 24 Oct 2026)
    // Long press: send a position now (the way out of standing still inside a wake-up circle, review 21 Oct 2026).
    binding.card.setOnLongClickListener {
      if (requirementsChecker.hasLocationPermissions() && preferences.setupCompleted) {
        Toast.makeText(this, R.string.follo_send_now, Toast.LENGTH_SHORT).show()
        pendingCheckIn = true
        checkInIfReady()
      }
      true
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          val all = viewModel.waypoints()
          redrawRegions(all)
          refreshWakeAreas(all)
        }
        launch {
          viewModel.waypointEvents.collect {
            when (it) {
              is WaypointsRepo.WaypointOperation.Clear -> redrawRegions(emptyList())
              is WaypointsRepo.WaypointOperation.Delete -> removeRegion(it.waypoint)
              is WaypointsRepo.WaypointOperation.Insert -> addRegion(it.waypoint)
              is WaypointsRepo.WaypointOperation.Update -> {
                removeRegion(it.waypoint)
                addRegion(it.waypoint)
              }
              is WaypointsRepo.WaypointOperation.InsertMany -> it.waypoints.forEach(::addRegion)
            }
            refreshWakeAreas(viewModel.waypoints())
            map.invalidate()
          }
        }
        launch {
          syncContactMarkers()
          viewModel.contactEvents.collect {
            when (it) {
              ContactsRepoChange.AllCleared -> {
                map.overlays.removeAll { o -> o is Marker && o.id.startsWith(CONTACT_PREFIX) }
                map.invalidate()
              }
              is ContactsRepoChange.ContactAdded -> showIfLeader(it.contact).also { drawPair() }
              is ContactsRepoChange.ContactLocationUpdated -> showIfLeader(it.contact).also { drawPair() }
              is ContactsRepoChange.ContactCardUpdated -> showIfLeader(it.contact)
              is ContactsRepoChange.ContactRemoved -> {
                map.overlays.removeAll { o -> o is Marker && o.id == CONTACT_PREFIX + it.contact.id }
                map.invalidate()
              }
            }
          }
        }
        launch {
          combine(
                  viewModel.tick,
                  viewModel.monitoring,
                  viewModel.setupCompleted,
                  viewModel.endpointState,
                  viewModel.queueLength,
              ) { _, _, _, endpoint, _ ->
                trackSendFailures(endpoint.state)
              }
              .collect {
                drawAreaBox(viewModel.folloStatus.value?.message) // hides the area once its window ends
                syncContactMarkers() // and takes a leader off the map once their fix is stale
                drawPair()
                render()
              }
        }
        launch { viewModel.lastSentAt.collect { render() } }
        launch {
          viewModel.folloStatus.collect {
            drawAreaBox(it?.message)
            syncContactMarkers()
            drawPair()
            render()
          }
        }
        // The phone decides green/grey at the area edge itself, so every new fix may change it.
        launch {
          viewModel.currentLocation.collect {
            it?.let(viewModel::addTrack)
            it?.let(compass::updateDeclination)
            it?.let(::followCamera)
            checkInIfReady()
            drawPair()
            render()
          }
        }
        launch { viewModel.lastPublished.collect { render() } }
        launch { viewModel.trackUpdates.collect(::drawTrack) }
        launch { viewModel.displayNameState.collect { render() } }
        launch {
          viewModel.tileState.collect { (url, maxZoom) ->
            if (::map.isInitialized && (map.tileProvider.tileSource as? FolloTileSource)?.matches(url, maxZoom) != true) {
              map.setTileSource(FolloTileSource.of(url, maxZoom))
              map.invalidate()
            }
          }
        }
      }
    }

    startService(this)
    NotificationManagerCompat.from(this)
        .cancel(BackgroundService.BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0)
    notifyOnWorkManagerInitFailure(this)
    handleIntent(intent)
  }

  // The activity keeps running through a rotation (configChanges), so the map and its state survive;
  // the layout is switched here instead: portrait = banner across the top, card along the bottom;
  // landscape (phone in a holder sideways) = status and card in a column on the left, map on the right.
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    applyOrientation(newConfig.orientation)
    // Let the camera place the dot afresh for the new shape of the visible map.
    centredOnce = false
    camRef = null
    // After the layout pass, so visible() sees the side panel where it now is (not where it was).
    if (camera == Camera.NAV) binding.root.doOnLayout {
      setNavLook(true)
      requestNavFrame() // arrow back on its 80 % spot in the new shape at once
    }
  }

  private fun applyOrientation(orientation: Int) {
    val land = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    binding.banner.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      width = if (land) resources.getDimensionPixelSize(R.dimen.follo_side_panel) else android.view.ViewGroup.LayoutParams.MATCH_PARENT
      height = if (land) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
    binding.card.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      width = if (land) resources.getDimensionPixelSize(R.dimen.follo_side_card) else android.view.ViewGroup.LayoutParams.MATCH_PARENT
      gravity = if (land) android.view.Gravity.BOTTOM or android.view.Gravity.START else android.view.Gravity.BOTTOM
    }
    binding.title.textSize = if (land) 22f else 26f
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  override fun onStart() {
    super.onStart()
    bindService(
        Intent(this, BackgroundService::class.java),
        serviceConnection,
        Context.BIND_AUTO_CREATE,
    )
  }

  override fun onStop() {
    viewModel.saveTrack()
    unbindService(serviceConnection)
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    // A system prompt pauses us, so being resumed means it has gone; our own dialogs do not pause us.
    promptInFlight = ownDialog?.isShowing == true
    map.onResume()
    compass.start()
    shakeTurner.start()
    if (requirementsChecker.hasLocationPermissions()) {
      viewModel.requestLocationUpdates()
      myLocationOverlay?.enableMyLocation()
    }
    if (preferences.setupCompleted) runPermissionChain()
    // Check in every time the app comes to the front (Alex 25 Oct 2026, as on iOS): the reply brings the wake-up
    // circles for any search in the next 14 days. Sent once the screen has a fix better than 100 m.
    if (preferences.setupCompleted && requirementsChecker.hasLocationPermissions()) {
      pendingCheckIn = true
      checkInIfReady()
    }
    render()
  }

  /** A check-in (open or long-press) waiting for a usable fix; the app's own 100 m filter would drop a worse one. */
  private var pendingCheckIn = false

  private fun checkInIfReady() {
    if (!pendingCheckIn) return
    val l = viewModel.currentLocation.value ?: return
    if (l.accuracy >= CHECK_IN_MAX_ACC_M || System.currentTimeMillis() - l.time > CHECK_IN_MAX_AGE_MS) return
    pendingCheckIn = false
    startService(this, BackgroundService.INTENT_ACTION_SEND_LOCATION_USER)
  }

  /** The searches whose wake-up circles this phone holds ("FOLLO MV wake 3" -> MV), for "Ready for MV" (Alex 25 Oct 2026). */
  private var wakeAreas: List<String> = emptyList()

  private fun wakeAreaOf(w: WaypointModel): String? =
      w.description.split(' ').takeIf { w.geofenceRadius > 0 && it.size >= 3 && it[0] == "FOLLO" && it[2] == "wake" }?.get(1)

  private fun refreshWakeAreas(all: List<WaypointModel>) {
    drawGeofences(all)
    val areas = all.mapNotNull(::wakeAreaOf).distinct()
    if (areas != wakeAreas) {
      wakeAreas = areas
      render()
    }
  }

  private var geofenceKey = ""
  private var drawnAreaPts: List<GeoPoint>? = null
  private var lastWaypoints: List<WaypointModel> = emptyList()

  private fun insideRing(ring: List<GeoPoint>, lat: Double, lon: Double): Boolean {
    var c = false
    var j = ring.size - 1
    for (i in ring.indices) {
      val (yi, xi) = ring[i].latitude to ring[i].longitude
      val (yj, xj) = ring[j].latitude to ring[j].longitude
      if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
      j = i
    }
    return c
  }

  /**
   * The outline of every search area whose wake-up circles the phone holds (Alex 25 Oct 2026: from now until the
   * circles are reclaimed, "it shows people where the geo-fences are and may be helpful for locals"). Each circle's edge
   * is drawn where it is not inside another circle of the same area, so separate pieces stay separate. Same on iOS.
   */
  private fun drawGeofences(all: List<WaypointModel>) {
    if (!::map.isInitialized) return
    lastWaypoints = all
    // One line per area (Alex 25 Oct 2026): while a window's edge is drawn (drawAreaBox), skip the circles of that area
    // (their centres fall inside it); the edge sits 2 m outside the circles, so both together showed a double line.
    val edge = drawnAreaPts
    val circles =
        all.mapNotNull { w -> wakeAreaOf(w)?.let { a -> a to w } }.let { cs ->
          val drawnAreas = cs.filter { (_, w) -> edge != null && insideRing(edge, w.geofenceLatitude.value, w.geofenceLongitude.value) }.map { it.first }.toSet()
          cs.filter { it.first !in drawnAreas }
        }
    val key = circles.joinToString(";") { (a, w) -> "$a${w.geofenceLatitude.value},${w.geofenceLongitude.value},${w.geofenceRadius}" }
    if (key == geofenceKey) return
    geofenceKey = key
    map.overlays.removeAll { it is Polyline && it.id == GEOFENCE_ID }
    val lines = mutableListOf<Polyline>()
    for ((_, group) in circles.groupBy({ it.first }, { it.second })) {
      for (c in group) {
        val lat = c.geofenceLatitude.value
        val lon = c.geofenceLongitude.value
        val r = c.geofenceRadius.toDouble()
        val kx = 111320 * kotlin.math.cos(Math.toRadians(lat))
        val ky = 110540.0
        val n = (r / 8).toInt().coerceIn(72, 720)
        val pts =
            (0 until n).map { i ->
              val t = i * 2 * Math.PI / n
              val p = GeoPoint(lat + r * kotlin.math.sin(t) / ky, lon + r * kotlin.math.cos(t) / kx)
              val covered =
                  group.any { o ->
                    o !== c &&
                        kotlin.math.hypot((p.longitude - o.geofenceLongitude.value) * kx, (p.latitude - o.geofenceLatitude.value) * ky) <
                            o.geofenceRadius - 0.5
                  }
              p to !covered
            }
        // runs of uncovered points; start just after a covered one so a run never wraps
        val start = pts.indexOfFirst { !it.second }.let { if (it < 0) 0 else it + 1 }
        var run = mutableListOf<GeoPoint>()
        fun flush() {
          if (run.size >= 2) lines += Polyline(map).apply {
            id = GEOFENCE_ID
            setPoints(run)
            infoWindow = null
            outlinePaint.color = getColor(R.color.follo_area)
            outlinePaint.strokeWidth = resources.displayMetrics.density * 3
          }
          run = mutableListOf()
        }
        for (k in 0..n) {
          val (p, show) = pts[(start + k) % n]
          if (show) run.add(p) else flush()
        }
        flush()
      }
    }
    val at = aboveAreaAndTrack()
    lines.forEachIndexed { i, l -> map.overlays.add(at + i, l) }
    keepOwnOnTop()
    map.invalidate()
  }

  /** "✓ Ready for MV" / "No search area loaded yet", under Standing by; the same wording on iOS. */
  private fun readyLine(): String =
      "\n" +
          when (wakeAreas.size) {
            0 -> getString(R.string.follo_ready_none)
            1 -> getString(R.string.follo_ready_for, wakeAreas[0])
            else -> getString(R.string.follo_ready_for, wakeAreas.dropLast(1).joinToString(", ") + " and " + wakeAreas.last())
          }

  override fun onPause() {
    compass.stop()
    shakeTurner.stop()
    map.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    map.onDetach()
    super.onDestroy()
  }

  // ── Setup link ─────────────────────────────────────────────────────────────────────────

  private fun handleIntent(intent: Intent?) {
    // Debug builds only: pick the map-following style for the side-by-side recordings (19 Oct).
    if (org.owntracks.android.BuildConfig.DEBUG)
        intent?.getStringExtra("follo_camera")?.let { name ->
          Camera.entries.firstOrNull { it.name.equals(name, true) }?.let {
            camera = it
            centredOnce = false
            camRef = null
            hasBearing = false
            upBearing = 0f
            headingInit = false
            camAnim?.cancel()
            map.mapOrientation = 0f
            setNavLook(false)
          }
          intent.getStringExtra("follo_zoom")?.toDoubleOrNull()?.let { map.controller.setZoom(it) }
          if (camera == Camera.NAV) setNavLook(true)
        }
    val uri = intent?.data ?: return
    if (intent.action != Intent.ACTION_VIEW) return
    intent.data = null
    lifecycleScope.launch {
      when (val link = viewModel.parseSetupLink(uri)) {
        is MainViewModel.SetupLink.Bad -> {
          Timber.w("Rejected setup link: ${link.reason}")
          Toast.makeText(this@MainActivity, R.string.follo_setup_bad, Toast.LENGTH_LONG).show()
        }
        is MainViewModel.SetupLink.Ready -> confirmSetup(link)
      }
    }
  }

  private fun confirmSetup(link: MainViewModel.SetupLink.Ready) {
          MaterialAlertDialogBuilder(this)
              .setTitle(R.string.follo_setup_title)
              .setMessage(getString(R.string.follo_setup_msg, link.host, link.tid))
              .setPositiveButton(R.string.follo_setup_btn) { _, _ ->
                viewModel.applySetup(link.config) {
                  Snackbar.make(binding.root, R.string.follo_setup_done, Snackbar.LENGTH_LONG).show()
                  // Let the server see this phone straight away, then walk the permissions.
                  startService(this, BackgroundService.INTENT_ACTION_SEND_LOCATION_USER)
                  runPermissionChain()
                  render()
                }
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
  }

  // ── Permissions ─────────────────────────────────────────────────────────────────────────

  /** Asks, in order and one at a time, for what tracking needs; the banner shows what is left. */
  private fun runPermissionChain() {
    if (promptInFlight) return // one Android prompt at a time; a second launch cancels the first
    if (!requirementsChecker.hasLocationPermissions()) {
      if (!preferences.userDeclinedEnableLocationPermissions) requestLocation()
      return
    }
    if (
        !requirementsChecker.hasBackgroundLocationPermission() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !preferences.userDeclinedEnableBackgroundLocationPermissions
    ) {
      requestBackgroundLocation()
      return
    }
    if (
        !notificationPermissionRequester.hasPermission() &&
            !preferences.userDeclinedEnableNotificationPermissions
    ) {
      promptInFlight = true
      render()
      notificationPermissionRequester.requestNotificationPermission()
      return
    }
  }

  /**
   * Google Play's "prominent disclosure": in the app, immediately before the location request, say
   * what is collected, why, and that it continues when the app is closed. Declining is remembered
   * like a declined permission; the red banner's button asks again.
   */
  private fun requestLocation() {
    promptInFlight = true
    render()
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.follo_disclosure_title)
        .setMessage(R.string.follo_disclosure_msg)
        .setCancelable(false)
        .setPositiveButton(R.string.follo_disclosure_continue) { _, _ ->
          // The disclosure is the rationale, so Android's prompt follows it directly.
          locationPermissionRequester.requestLocationPermissions(0, this) { false }
        }
        .setNegativeButton(R.string.follo_disclosure_not_now) { _, _ ->
          promptInFlight = false
          preferences.userDeclinedEnableLocationPermissions = true
          render()
        }
        .show()
        .also { ownDialog = it }
  }

  private fun requestBackgroundLocation() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    // Android refuses to show the "all the time" prompt twice; after that only Settings can fix it.
    if (preferences.userDeclinedEnableBackgroundLocationPermissions) {
      Toast.makeText(this, R.string.follo_location_howto, Toast.LENGTH_LONG).show()
      openAppSettings()
      return
    }
    promptInFlight = true
    render()
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.follo_bg_rationale_title)
        .setMessage(R.string.follo_bg_rationale_msg)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          backgroundLocationPermissionRequester.requestLocationPermissions(this) { false }
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
          promptInFlight = false
          preferences.userDeclinedEnableBackgroundLocationPermissions = true
          runPermissionChain()
          render()
        }
        .show()
        .also { ownDialog = it }
  }

  private fun openAppSettings() {
    settingsLauncher.launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", packageName, null)
        }
    )
  }

  // Battery screens differ. Android 12+ opens FOLLO Tracks' own App info page, where the app is always the one shown
  // (the system battery list hides it behind a filter on some phones: Paul, 26 Oct 2026). From there Samsung has
  // Battery (below the fold) › Unrestricted; stock Android 14+ hides Unrestricted behind the words of the "Allow
  // background usage" row (its switch alone = Optimised); 12-13 and other makers have Battery or App battery usage
  // › Unrestricted. Android 11 and older keep the full list with its Don't optimise dialog.
  private fun batteryText(samsung: Int, android14: Int, android12: Int, older: Int) =
      getString(
          when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> older
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> samsung
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> android14
            else -> android12
          }
      )

  private fun isIgnoringBatteryOptimizations(): Boolean =
      (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(
          packageName
      )

  private fun onActionClicked() {
    when (state?.kind) {
      Kind.NEED_LOCATION ->
          if (preferences.userDeclinedEnableLocationPermissions &&
              !shouldShowRequestPermissionRationale(
                  android.Manifest.permission.ACCESS_FINE_LOCATION
              )) {
            Toast.makeText(this, R.string.follo_location_howto, Toast.LENGTH_LONG).show()
            openAppSettings()
          } else requestLocation()
      Kind.LOCATION_OFF -> settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      Kind.NEED_PRECISE -> {
        // The precise/approximate switch lives on the app's Location permission page (Android 12+).
        Toast.makeText(this, R.string.follo_precise_howto, Toast.LENGTH_LONG).show()
        openAppSettings()
      }
      Kind.NEED_BACKGROUND -> requestBackgroundLocation()
      Kind.NEED_NOTIFICATIONS ->
          if (preferences.userDeclinedEnableNotificationPermissions) openAppSettings()
          else notificationPermissionRequester.requestNotificationPermission()
      Kind.NEED_BATTERY -> {
        Toast.makeText(
                this,
                batteryText(
                    R.string.follo_battery_howto_samsung,
                    R.string.follo_battery_howto_14,
                    R.string.follo_battery_howto_12,
                    R.string.follo_battery_howto,
                ),
                Toast.LENGTH_LONG,
            )
            .show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) openAppSettings()
        else settingsLauncher.launch(BatteryOptimizingIntents(this).settingsIntent)
      }
      else -> {}
    }
  }

  // ── Banner + card ─────────────────────────────────────────────────────────────────────

  private fun currentBanner(): Banner {
    val green = getColor(R.color.follo_green)
    val grey = getColor(R.color.follo_grey)
    val red = getColor(R.color.follo_red)
    fun s(id: Int) = getString(id)
    if (!viewModel.setupCompleted.value)
        return Banner(Kind.NOT_SET_UP, grey, s(R.string.follo_not_setup_title), s(R.string.follo_not_setup_sub))
    if (promptInFlight)
        return Banner(Kind.SETTING_UP, grey, s(R.string.follo_setting_up_title), s(R.string.follo_setting_up_sub))
    if (!requirementsChecker.hasLocationPermissions())
        return Banner(Kind.NEED_LOCATION, red, s(R.string.follo_action_title), s(R.string.follo_perm_location_sub), s(R.string.follo_perm_location_btn))
    if (!requirementsChecker.isLocationServiceEnabled())
        return Banner(Kind.LOCATION_OFF, red, s(R.string.follo_action_title), s(R.string.follo_location_off_sub), s(R.string.follo_location_off_btn))
    // Approximate-only location (Android 12+): the phone has "location" but every fix is ~2 km, so the 100 m filter
    // drops them all, the check-in included, and the phone goes silent (review 28 Oct 2026, B1; iOS has this banner).
    if (!requirementsChecker.hasPreciseLocationPermission())
        return Banner(Kind.NEED_PRECISE, red, s(R.string.follo_action_title), s(R.string.follo_precise_sub), s(R.string.follo_precise_btn))
    if (!requirementsChecker.hasBackgroundLocationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        return Banner(Kind.NEED_BACKGROUND, red, s(R.string.follo_action_title), s(R.string.follo_bg_sub), s(R.string.follo_bg_btn))
    // Notifications off is not red: the location service runs without them, its notification is just hidden
    // (review 21 Oct 2026). The card carries a one-line note instead; the setup chain still asks once.
    if (!isIgnoringBatteryOptimizations())
        return Banner(Kind.NEED_BATTERY, red, s(R.string.follo_action_title),
            batteryText(R.string.follo_battery_sub_samsung, R.string.follo_battery_sub_14, R.string.follo_battery_sub_12, R.string.follo_battery_sub),
            s(R.string.follo_battery_btn))
    val server =
        viewModel.folloStatus.value
            ?.takeIf { System.currentTimeMillis() - it.receivedAt < SERVER_STATUS_TTL_MS }
            ?.message
    // While a window is open the server sends its storage box, and the phone decides in/out from its
    // own fix: that keeps working with no signal, so the edge shows the moment it is crossed. Without
    // a box, the server's word; without any server word yet, Move = sharing.
    val nowS = System.currentTimeMillis() / 1000
    val windowOver = server?.ends?.let { nowS >= it } == true
    val area = server?.boxBounds()?.takeIf { !windowOver }
    val ring = server?.polyPoints()?.takeIf { !windowOver }
    val here = viewModel.currentLocation.value ?: viewModel.lastPublished.value
    val sharing =
        when {
          server == null -> viewModel.isMoving
          windowOver -> false
          ring != null && here != null -> insidePoly(ring, here.latitude, here.longitude)
          area != null && here != null -> insideBox(area, here.latitude, here.longitude)
          else -> server.state == "sharing"
        }
    if (sharing) {
      val queued = backlog()
      val sub =
          when {
            queued == 1 -> s(R.string.follo_offline_sub_one)
            queued > 1 -> getString(R.string.follo_offline_sub, queued)
            !server?.line.isNullOrBlank() -> server!!.line!!
            server?.search != null && server.until != null ->
                getString(R.string.follo_until, server.search, server.until)
            server?.search != null -> server.search!!
            else -> s(R.string.follo_sharing_sub)
          }
      return Banner(Kind.SHARING, green, s(R.string.follo_sharing_title), sub)
    }
    return when (if (area != null || ring != null) "outside" else if (windowOver) null else server?.state) {
      "outside" ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_outside_title),
              (server?.line?.takeIf { it.isNotBlank() } ?: s(R.string.follo_standby_outside_sub)) + readyLine(),
          )
      "nosearch" ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_nosearch_title),
              (server?.line?.takeIf { it.isNotBlank() }
                  ?: ((server?.next?.let { getString(R.string.follo_next_search, it) } ?: "") +
                      s(R.string.follo_nosearch_tail))) + readyLine(),
          )
      else ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_title),
              (server?.line?.takeIf { it.isNotBlank() } ?: s(R.string.follo_standby_sub)) + readyLine(),
          )
    }
  }

  /** When sends started failing (0 = the last attempt went through); retries in between don't reset it. */
  private var failingSince = 0L

  private fun trackSendFailures(state: org.owntracks.android.data.EndpointState) {
    when (state) {
      org.owntracks.android.data.EndpointState.ERROR,
      org.owntracks.android.data.EndpointState.DISCONNECTED ->
          if (failingSince == 0L) failingSince = System.currentTimeMillis()
      org.owntracks.android.data.EndpointState.IDLE,
      org.owntracks.android.data.EndpointState.CONNECTED -> failingSince = 0L
      else -> {} // CONNECTING: an attempt in progress, keep what we had
    }
  }

  /**
   * Positions really held back for want of signal. Every post sits in the queue for a moment while it
   * is sent (every 5 s in Move), and the phone may simply have had no fix for a while, so a non-empty
   * queue alone would flash "No signal"; it counts only once sends have been failing for
   * [NO_SIGNAL_AFTER_MS].
   */
  private fun backlog(): Int {
    val queued = viewModel.queueLength.value
    val failing = failingSince != 0L && System.currentTimeMillis() - failingSince > NO_SIGNAL_AFTER_MS
    return if (queued > 0 && failing) queued else 0
  }

  private fun render() {
    if (!::binding.isInitialized) return
    val b = currentBanner()
    state = b
    binding.banner.setBackgroundColor(b.color)
    // Only touch a view when its content changes: render() runs on every post (5 s in Move).
    if (binding.title.text.toString() != b.title) binding.title.text = b.title
    if (binding.sub.text.toString() != b.sub) binding.sub.text = b.sub
    binding.sub.visibility = if (b.sub.isBlank()) View.GONE else View.VISIBLE
    binding.action.visibility = if (b.button == null) View.GONE else View.VISIBLE
    binding.action.text = b.button ?: ""
    binding.action.setTextColor(b.color)

    val tid = viewModel.trackerId
    binding.who.text =
        when {
          b.kind == Kind.NOT_SET_UP -> getString(R.string.follo_brand)
          viewModel.displayName.isNotBlank() -> getString(R.string.follo_who, viewModel.displayName, tid)
          else -> getString(R.string.follo_tracker, tid)
        }
    val parts = mutableListOf<String>()
    val last = viewModel.lastSentAt.value
    parts +=
        when {
          last == 0L -> getString(R.string.follo_last_none)
          System.currentTimeMillis() - last < DateUtils.MINUTE_IN_MILLIS ->
              getString(R.string.follo_last_sent_just_now)
          else ->
              getString(
                  R.string.follo_last_sent,
                  DateUtils.getRelativeTimeSpanString(
                      last,
                      System.currentTimeMillis(),
                      DateUtils.MINUTE_IN_MILLIS,
                  ),
              )
        }
    val queued = backlog()
    if (queued > 0) parts += getString(R.string.follo_waiting, queued)
    viewModel.batteryLevel?.let { parts += getString(R.string.follo_battery, it) }
    val text = parts.joinToString(" · ")
    if (binding.last.text.toString() != text) binding.last.text = text
    val note =
        if (b.kind != Kind.NOT_SET_UP && !promptInFlight && !notificationPermissionRequester.hasPermission())
            getString(R.string.follo_notif_off_note)
        else ""
    if (binding.note.text.toString() != note) binding.note.text = note
    binding.note.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
  }

  // ── Map ───────────────────────────────────────────────────────────────────────────────

  private fun setUpMap() {
    Configuration.getInstance().apply {
      load(this@MainActivity, PreferenceManager.getDefaultSharedPreferences(this@MainActivity))
      userAgentValue = packageName
      osmdroidTileCache = noBackupFilesDir.resolve("osmdroid/tiles")
    }
    map = binding.map
    viewModel.tileState.value.let { (url, maxZoom) ->
      map.setTileProvider(FolloTileProvider(this, FolloTileSource.of(url, maxZoom))) // enlarges past the imagery's depth
    }
    map.overlayManager.tilesOverlay = ShiftedTilesOverlay(map.tileProvider, this) // Esri sits 1.5 m north
    map.setMultiTouchControls(true)
    // A one-finger drag means the volunteer is looking somewhere else: stop following until the card is
    // tapped. A pinch (any second finger during the touch) keeps following and keeps its zoom; the decision
    // is made when the fingers lift, so a pinch that starts one-fingered does not pause (review 21 Oct 2026).
    // A two-finger twist (Alex 24 Oct 2026: "rotate the map using 2 fingers, just like Google Maps") turns the
    // map once it passes ROTATE_START_DEG, so a pinch is not turned by accident, and holds it turned like a drag
    // does. It turns about the fingers: osmdroid's pinch keeps the point under them fixed whatever the turn.
    // While any finger is down the camera leaves the map alone.
    var downX = 0f
    var downY = 0f
    var dragged = false
    var twoFingers = false
    var lastAngle = 0f
    var twist = 0f
    var rotating = false
    var rotated = false
    var rebase = false
    fun fingerAngle(e: android.view.MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    map.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          downX = e.x
          downY = e.y
          dragged = false
          twoFingers = false
          rotating = false
          rotated = false
          twist = 0f
          gestureActive = true
        }
        android.view.MotionEvent.ACTION_POINTER_DOWN -> {
          twoFingers = true
          rebase = true
        }
        // the finger pair changes: measure the twist afresh from the next move
        android.view.MotionEvent.ACTION_POINTER_UP -> rebase = true
        android.view.MotionEvent.ACTION_MOVE ->
            if (e.pointerCount == 1) {
              if (hypot(e.x - downX, e.y - downY) > slop) dragged = true
            } else {
              val a = fingerAngle(e)
              if (rebase) {
                lastAngle = a
                rebase = false
              }
              val d = ((a - lastAngle + 540f) % 360f) - 180f
              lastAngle = a
              if (rotating) {
                map.mapOrientation = (map.mapOrientation + d + 360f) % 360f
                requestNavFrame() // the held arrow keeps pointing the way the phone faces
              } else {
                twist += d
                if (abs(twist) > ROTATE_START_DEG) {
                  rotating = true
                  rotated = true
                }
              }
            }
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
          gestureActive = false
          if ((dragged && !twoFingers) || rotated) followPaused = true
          if (camera == Camera.NAV) requestNavFrame() // arrow back on its spot at the new zoom, or turned on the held map
        }
      }
      false
    }
    // A pinch zooms about the fingers; put the arrow back on its spot for the new scale.
    map.addMapListener(
        object : org.osmdroid.events.MapListener {
          override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = false

          override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
            if (camera == Camera.NAV && !followPaused) requestNavFrame()
            return false
          }
        }
    )
    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    map.isTilesScaledToDpi = true
    map.minZoomLevel = 3.0
    map.maxZoomLevel = MAP_MAX_ZOOM.toDouble()
    map.controller.setZoom(START_ZOOM) // walking scale; the NAV look zooms out to make up for its tilt
    map.controller.setCenter(GeoPoint(-41.1, 146.83)) // until the first fix arrives

    val provider =
        object : IMyLocationProvider {
          private var job: kotlinx.coroutines.Job? = null

          override fun startLocationProvider(consumer: IMyLocationConsumer?): Boolean {
            val me = this
            job =
                lifecycleScope.launch {
                  repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.currentLocation.collect { it?.let { l -> consumer?.onLocationChanged(l, me) } }
                  }
                }
            return true
          }

          override fun stopLocationProvider() {
            job?.cancel()
            job = null
          }

          override fun getLastKnownLocation(): Location? = viewModel.currentLocation.value

          override fun destroy() = stopLocationProvider()
        }
    val px = (resources.displayMetrics.density * 24).roundToInt()
    myLocationOverlay =
        MyLocationNewOverlay(provider, map).apply {
          ResourcesCompat.getDrawable(resources, R.drawable.location_dot, null)?.toBitmap(px, px)?.let { setPersonIcon(it) }
          ResourcesCompat.getDrawable(resources, R.drawable.location_dot_arrow, null)?.toBitmap(px, px)?.let { setDirectionIcon(it) }
          setPersonAnchor(0.5f, 0.5f)
          setDirectionAnchor(0.5f, 0.5f)
        }
    map.overlays.add(myLocationOverlay)
    scaleBar = ScaleBarOverlay(map).apply { setAlignBottom(true); setScaleBarOffset(40, 260) }
    map.overlays.add(scaleBar)
    map.overlays.add(CopyrightOverlay(this).apply { setAlignBottom(true) })
  }

  private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

  /**
   * Ways the map can follow the dot. [Camera.NAV] is the app's (Alex chose it from the side-by-side
   * recordings, 20 Oct 2026); the rest stay for comparison, selectable in debug builds only.
   */
  private enum class Camera {
    LEASH, // north up: invisible circle, map still until the dot reaches it, then glides
    LOOKAHEAD, // north up: dot kept a quarter-screen behind the middle along the travel direction
    PAGE, // north up: map still until the dot nears an edge, then one glide putting it at the back
    HEADING, // travel direction up, dot 3/4 down, map rotates smoothly with every turn
    HEADING_CALM, // as HEADING, but the map only turns once the direction has changed by 30 degrees
    NAV, // like Google Maps after Start: tilted map, arrow 80 % down, arrow and map glide, map turns
  }

  private var camera = Camera.NAV
  /** The last fix the camera accepted as real movement (GPS wobble while standing is ignored). */
  private var camRef: Location? = null
  private var camRefAt = 0L
  private var bearing = 0f
  private var hasBearing = false
  /** When [bearing] last changed (elapsedRealtime). */
  private var bearingAt = 0L
  /** The direction shown at the top of the screen in the heading-up styles. */
  private var upBearing = 0f
  private var camAnim: android.animation.ValueAnimator? = null

  private fun followCamera(l: Location) {
    if (!::map.isInitialized || map.width == 0 || map.height == 0) return
    if (followPaused || gestureActive) {
      // The map stays where the volunteer dragged it, but the arrow keeps showing where they are
      if (camera == Camera.NAV) {
        navTo = l.toGeoPoint()
        navGlideMs = 0L
        navArrow?.let { it.position = l.toGeoPoint() }
        requestNavFrame()
      }
      return
    }
    // Standing still: the dot may wobble by a few metres, the map must not. Only movement beyond the
    // fix's own accuracy (at least JITTER_M) moves the camera or changes the travel direction.
    val ref = camRef
    val now = System.currentTimeMillis()
    if (ref != null) {
      val gate = maxOf(JITTER_M, minOf(l.accuracy, 15f))
      if (ref.distanceTo(l) < gate) return
      val b = ref.bearingTo(l)
      bearing = if (hasBearing) blendAngle(bearing, b, 0.5f) else b
      hasBearing = true
      bearingAt = SystemClock.elapsedRealtime()
    }
    // Glide over the time it took to get here, so the map moves continuously, not in hops.
    val ms = if (ref == null) 0L else (now - camRefAt).coerceIn(500L, 3000L)
    camRef = l
    camRefAt = now
    when (camera) {
      Camera.LEASH -> keepInView(l)
      Camera.LOOKAHEAD -> glide(centreFor(l, lookAheadOffset()), 0f, ms)
      Camera.PAGE -> {
        val pt = map.projection.toPixels(l.toGeoPoint(), null)
        val v = visible()
        val mx = v.width() * EDGE_FRACTION
        val my = v.height() * EDGE_FRACTION
        val near = ref == null || pt.x < v.left + mx || pt.x > v.right - mx || pt.y < v.top + my || pt.y > v.bottom - my
        if (near) glide(centreFor(l, lookAheadOffset(1.4f)), 0f, if (ref == null) 0L else 1200L)
      }
      Camera.NAV -> {
        // Position glides from where the arrow is now; heading (compass) and position are applied together
        // every frame in navFrame, so the two never fight.
        val p = l.toGeoPoint()
        val nowR = SystemClock.elapsedRealtime()
        navFrom = if (ref == null) p else navPosition(nowR) ?: p
        navTo = p
        navGlideStart = nowR
        navGlideMs = if (ref == null) 0L else ms
        requestNavFrame()
      }
      Camera.HEADING, Camera.HEADING_CALM -> {
        if (hasBearing) {
          val turn = abs(((bearing - upBearing + 540f) % 360f) - 180f)
          if (camera != Camera.HEADING_CALM || turn >= CALM_TURN_DEG) upBearing = bearing
        }
        val centre = headingUpCentre(l.toGeoPoint(), HEADING_DOWN)
        val glideMs = if (ref == null) 0L else maxOf(ms, if (camera == Camera.HEADING_CALM) 1500L else 0L)
        glide(centre, -upBearing, glideMs)
      }
    }
  }

  /**
   * Map centre for a heading-up map with the fix at [down] of the way down the visible map (and in
   * its middle sideways): the centre lies ahead of the fix along [upBearing], and to one side of it
   * when the visible map is off-centre (landscape, where the status column is on the left).
   */
  private fun headingUpCentre(p: GeoPoint, down: Float, up: Float = upBearing): GeoPoint {
    val v = visible()
    val ox = v.centerX() - map.width / 2f // dot right of the view centre by ox
    val oy = v.top + down * v.height() - map.height / 2f // dot below the view centre by oy
    val pxPerM = map.projection.metersToPixels(1f)
    return p.destinationPoint((oy / pxPerM).toDouble(), up.toDouble())
        .destinationPoint((ox / pxPerM).toDouble(), (up - 90f).toDouble())
  }

  /** The part of the map not covered by the status banner and the card, in map-view pixels. */
  private fun visible(): android.graphics.RectF {
    val sideways = binding.banner.width < map.width * 0.8f // landscape: status column on the left
    return if (sideways)
        android.graphics.RectF((binding.banner.right - map.left).toFloat(), 0f, map.width.toFloat(), map.height.toFloat())
    else {
      val top = (binding.banner.bottom - map.top).coerceAtLeast(0).toFloat()
      val bottom = (binding.card.top - map.top).toFloat().takeIf { it > top } ?: map.height.toFloat()
      android.graphics.RectF(0f, top, map.width.toFloat(), bottom)
    }
  }

  // ── Navigation look (Camera.NAV): tilted map + arrow, for the comparison recordings ─────────
  private var navArrow: Marker? = null
  private var scaleBar: ScaleBarOverlay? = null
  private var navZoomShift = 0.0

  private var navOn = false

  private fun setNavLook(on: Boolean) {
    navOn = on
    if (on) {
      myLocationOverlay?.let { map.overlays.remove(it) }
      if (navArrow == null)
          navArrow =
              Marker(map).apply {
                id = "nav-arrow"
                icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_follo_nav_arrow, null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
              }
      navArrow?.let { if (it !in map.overlays) map.overlays.add(it) }
      scaleBar?.let { map.overlays.remove(it) }
      // Alex 21 Oct 2026: no tilt. A flat, top-down map that turns with you; the arrow's spot is
      // 80 % of the way down the visible map (headingUpCentre), the same on iOS.
      map.rotationX = 0f
      map.scaleX = 1f
      map.scaleY = 1f
    } else {
      navArrow?.let { map.overlays.remove(it) }
      myLocationOverlay?.let { if (it !in map.overlays) map.overlays.add(it) }
      scaleBar?.let { if (it !in map.overlays) map.overlays.add(it) }
      if (navZoomShift != 0.0) map.controller.setZoom(map.zoomLevelDouble + navZoomShift)
      navZoomShift = 0.0
      map.rotationX = 0f
      map.scaleX = 1f
      map.scaleY = 1f
    }
    map.invalidate()
  }

  private fun blendAngle(from: Float, to: Float, w: Float): Float {
    val d = ((to - from + 540f) % 360f) - 180f
    return (from + d * w + 360f) % 360f
  }

  /**
   * Screen offset (px, from the visible middle) that puts the dot behind the middle along the travel
   * direction, north up: heading north = 3/4 of the way down.
   */
  private fun lookAheadOffset(scale: Float = 1f): Pair<Float, Float> {
    if (!hasBearing) return 0f to 0f
    val v = visible()
    val off = minOf(0.25f * v.height(), 0.33f * v.width()) * scale
    val r = Math.toRadians(bearing.toDouble())
    return (-kotlin.math.sin(r) * off).toFloat() to (kotlin.math.cos(r) * off).toFloat()
  }

  /** The map centre (north up) that puts this fix at the visible middle plus [offset]. */
  private fun centreFor(l: Location, offset: Pair<Float, Float> = 0f to 0f): GeoPoint {
    val pt = map.projection.toPixels(l.toGeoPoint(), null)
    val (ax, ay, _) = anchor()
    val tx = ax + offset.first
    val ty = ay + offset.second
    return map.projection.fromPixels((map.width / 2f + pt.x - tx).roundToInt(), (map.height / 2f + pt.y - ty).roundToInt()) as GeoPoint
  }

  /**
   * Linear glide of the centre (and optionally the rotation, and the nav arrow), so consecutive glides
   * join up into continuous movement.
   */
  private fun glide(to: GeoPoint, orientation: Float?, ms: Long, arrowTo: GeoPoint? = null) {
    camAnim?.cancel()
    val from = map.mapCenter
    val fromO = map.mapOrientation
    val arrowFrom = navArrow?.position?.takeIf { it.latitude != 0.0 } ?: arrowTo
    val dO = orientation?.let { ((it - fromO + 540f) % 360f) - 180f } ?: 0f
    camAnim =
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
          duration = ms
          interpolator = android.view.animation.LinearInterpolator()
          addUpdateListener { a ->
            val f = a.animatedValue as Float
            map.controller.setCenter(
                GeoPoint(
                    from.latitude + (to.latitude - from.latitude) * f,
                    from.longitude + (to.longitude - from.longitude) * f,
                )
            )
            if (orientation != null) map.mapOrientation = fromO + dO * f
            if (arrowTo != null && arrowFrom != null)
                navArrow?.position =
                    GeoPoint(
                        arrowFrom.latitude + (arrowTo.latitude - arrowFrom.latitude) * f,
                        arrowFrom.longitude + (arrowTo.longitude - arrowFrom.longitude) * f,
                    )
          }
          start()
        }
  }

  // ── NAV camera: one frame loop for position and heading ─────────────────────────────────
  // Alex 21 Oct 2026: the map turns with the COMPASS (the way the phone points), the arrow stays pointing up at 80 %
  // down the visible map, centred sideways. Without a compass reading the walking direction turns it instead.
  private var navFrom: GeoPoint? = null
  private var navTo: GeoPoint? = null
  private var navGlideStart = 0L
  private var navGlideMs = 0L
  /** The bearing shown at the top of the screen, eased toward the compass. */
  private var shownHeading = 0f
  private var headingInit = false
  private var lastFrameAt = 0L
  private var framePending = false
  private val navFrameCallback = Choreographer.FrameCallback { navFrame() }

  private fun requestNavFrame() {
    if (framePending || !::map.isInitialized) return
    framePending = true
    Choreographer.getInstance().postFrameCallback(navFrameCallback)
  }

  /** Where the arrow is along the current glide. */
  private fun navPosition(now: Long): GeoPoint? {
    val to = navTo ?: return null
    val from = navFrom ?: return to
    if (navGlideMs <= 0L) return to
    val f = ((now - navGlideStart).toFloat() / navGlideMs).coerceIn(0f, 1f)
    return GeoPoint(from.latitude + (to.latitude - from.latitude) * f, from.longitude + (to.longitude - from.longitude) * f)
  }

  private fun navFrame() {
    framePending = false
    if (isFinishing || camera != Camera.NAV || map.width == 0 || map.height == 0) return
    val now = SystemClock.elapsedRealtime()
    if (lastFrameAt != 0L && now - lastFrameAt < FRAME_MIN_MS) {
      requestNavFrame() // at most ~30 redraws a second
      return
    }
    val dt = if (lastFrameAt == 0L) FRAME_MIN_MS / 1000f else ((now - lastFrameAt) / 1000f).coerceAtMost(0.5f)
    lastFrameAt = now
    // The compass turns the map; without it, the walking direction does — but only a walking direction found
    // AFTER the compass went quiet, so a phone standing still never flips between the two.
    val target = compass.heading() ?: bearing.takeIf { hasBearing && bearingAt > compass.lastReadingAt() }
    var turning = false
    if (target != null) {
      if (!headingInit) {
        shownHeading = target
        headingInit = true
      } else {
        val d = ((target - shownHeading + 540f) % 360f) - 180f
        if (abs(d) > HEADING_SETTLED_DEG) {
          shownHeading = (shownHeading + d * (1f - exp(-dt / HEADING_TAU_S)) + 360f) % 360f
          turning = true
        } else shownHeading = target
      }
    }
    val gliding = navGlideMs > 0L && now < navGlideStart + navGlideMs
    val arrow = navArrow
    if (!followPaused && !gestureActive) {
      val pos = navPosition(now)
      if (pos != null) {
        upBearing = shownHeading
        map.mapOrientation = -shownHeading
        map.controller.setCenter(headingUpCentre(pos, NAV_DOWN, shownHeading))
        arrow?.position = pos
        arrow?.rotation = 0f
        if (gliding) drawPair()
      }
    } else {
      // The map is held where it was dragged, turned as it was; the arrow shows which way the phone points on it.
      arrow?.rotation = -map.mapOrientation - shownHeading
    }
    map.invalidate()
    if (gliding || turning) requestNavFrame() else lastFrameAt = 0L
  }

  private var centredOnce = false
  /** True once the volunteer drags or turns the map themselves; tapping the card or banner brings following back. */
  private var followPaused = false
  /** True while a finger is on the map: the camera does not move it under the finger. */
  private var gestureActive = false

  /**
   * The visible middle of the map (between the banner and the card), where the dot is kept, and the
   * radius of the invisible circle around it, in map-view pixels.
   */
  private fun anchor(): Triple<Float, Float, Float> {
    val v = visible()
    return Triple(v.centerX(), v.centerY(), LEASH_FRACTION * minOf(v.width(), v.height()))
  }

  /**
   * Alex's "invisible circle" (19 Oct): the dot moves freely inside a circle around the visible middle
   * of the map and the map stays still; once the dot reaches the circle the map glides along with it,
   * keeping it on the circle's edge. Stepping back inside stops the map until the far side is reached.
   */
  private fun keepInView(l: Location) {
    if (!::map.isInitialized || map.width == 0 || map.height == 0 || followPaused) return
    val pt = map.projection.toPixels(l.toGeoPoint(), null)
    val (ax, ay, r) = anchor()
    val dx = pt.x - ax
    val dy = pt.y - ay
    if (!centredOnce) {
      // First fix: put the dot in the middle at once (no animation from wherever the map was).
      map.controller.setCenter(map.projection.fromPixels((map.width / 2f + dx).roundToInt(), (map.height / 2f + dy).roundToInt()))
      centredOnce = true
      return
    }
    val dist = hypot(dx, dy)
    if (dist <= r) return
    val k = (dist - r) / dist // move the map just enough to put the dot back on the circle
    val c = map.projection.fromPixels((map.width / 2f + dx * k).roundToInt(), (map.height / 2f + dy * k).roundToInt())
    map.controller.animateTo(c, null, PAN_MS)
  }

  /** Card or banner tap: follow again, heading-up, with the arrow back on its spot. */
  private fun recentre() {
    followPaused = false
    if (camera == Camera.NAV) requestNavFrame() // the compass turns the map back even before the next fix
    val l = viewModel.currentLocation.value ?: return
    if (!::map.isInitialized || map.width == 0) return
    // Forget the last accepted fix, so the current style places the dot afresh (heading-up styles
    // included, which a plain north-up re-centre would undo).
    camRef = null
    centredOnce = false
    followCamera(l)
  }

  /**
   * Markers (radius 0) draw as a pin with its label. Fence regions (radius > 0) are not drawn: they
   * are the server's wake-up circle around the area, bigger than it; the real boundary is the
   * area box ([drawAreaBox]).
   */
  private fun addRegion(w: WaypointModel) {
    val point = GeoPoint(w.geofenceLatitude.value, w.geofenceLongitude.value)
    if (w.geofenceRadius <= 0) {
      map.overlays.add(
          Marker(map).apply {
            id = REGION_PREFIX + w.id
            position = point
            icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_follo_pin, null)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
          },
      )
      if (w.description.isNotBlank())
          map.overlays.add(
              Marker(map).apply {
                id = REGION_PREFIX + w.id + LABEL_SUFFIX
                position = point
                setTextIcon(w.description)
                setTextLabelBackgroundColor(Color.argb(184, 0, 0, 0))
                setTextLabelForegroundColor(Color.WHITE)
                setTextLabelFontSize((12 * resources.displayMetrics.scaledDensity).roundToInt())
                setAnchor(Marker.ANCHOR_CENTER, -0.15f)
                infoWindow = null
              },
          )
    }
  }

  /** The search area's storage box from the server: the line where sharing starts and stops. */
  private var drawnBox: String? = null

  private fun drawAreaBox(status: org.owntracks.android.model.messages.MessageFollo?) {
    if (!::map.isInitialized) return
    val key = (status?.poly?.toString() ?: status?.box)?.takeIf { status?.ends?.let { System.currentTimeMillis() / 1000 >= it } != true }
    if (key == drawnBox) return // the same box arrives with every reply; redrawing it each time flickers
    drawnBox = key
    map.overlays.removeAll { (it is Polygon && it.id == AREA_ID) || (it is Marker && it.id == AREA_ID + LABEL_SUFFIX) }
    drawnAreaPts = null
    val b = status?.boxBounds()
    val ring = status?.polyPoints()
    if (status != null && (b != null || ring != null) && status.ends?.let { System.currentTimeMillis() / 1000 >= it } != true) {
      // The drawn boundary when the area has one (21 Oct 2026), else the box. Label at the top edge's middle.
      val pts = ring?.map { (la, lo) -> GeoPoint(la, lo) }?.let { it + it.first() }
          ?: listOf(GeoPoint(b!![2], b[1]), GeoPoint(b[2], b[3]), GeoPoint(b[0], b[3]), GeoPoint(b[0], b[1]), GeoPoint(b[2], b[1]))
      drawnAreaPts = pts
      val n = pts.maxOf { it.latitude }
      val w = pts.minOf { it.longitude }
      val e = pts.maxOf { it.longitude }
      map.overlays.add(
          0,
          Polygon(map).apply {
            id = AREA_ID
            points = pts
            fillPaint.color = getColor(R.color.follo_area_fill)
            outlinePaint.color = getColor(R.color.follo_area)
            outlinePaint.strokeWidth = resources.displayMetrics.density * 4
            infoWindow = null
          },
      )
      map.overlays.add(
          Marker(map).apply {
            id = AREA_ID + LABEL_SUFFIX
            position = GeoPoint(n, (w + e) / 2)
            setTextIcon(getString(R.string.follo_area_label))
            setTextLabelBackgroundColor(Color.argb(184, 0, 0, 0))
            setTextLabelForegroundColor(Color.WHITE)
            setTextLabelFontSize((14 * resources.displayMetrics.scaledDensity).roundToInt())
            setAnchor(Marker.ANCHOR_CENTER, -0.2f) // just inside the north edge
            infoWindow = null
          },
      )
    }
    keepOwnOnTop()
    map.invalidate()
    drawGeofences(lastWaypoints)   // the area whose edge is now drawn (or no longer drawn) changes which outlines show
  }

  private fun insideBox(b: DoubleArray, lat: Double, lon: Double) =
      lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3]

  /** Ray casting on (lat, lon) pairs; same rule as the server's insideFence. */
  private fun insidePoly(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
    var c = false
    var j = ring.size - 1
    for (i in ring.indices) {
      val (yi, xi) = ring[i]
      val (yj, xj) = ring[j]
      if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
      j = i
    }
    return c
  }

  /** Index just above the search area and the walking track (the bottom map layers). */
  /**
   * Your own arrow (or dot) is always drawn last, so teammates, pins and labels never hide it (Alex 25 Oct 2026: "the
   * arrow showing me where I am is always visible"). osmdroid draws overlays in list order; call after adding any.
   */
  private fun keepOwnOnTop() {
    val own = (if (navOn) navArrow else myLocationOverlay) ?: return
    val i = map.overlays.indexOf(own)
    if (i < 0) return
    // Only the screen-fixed scale bar and credit may sit above it
    if (map.overlays.drop(i + 1).all { it is ScaleBarOverlay || it is CopyrightOverlay }) return
    map.overlays.removeAt(i)
    map.overlays.add(map.overlays.indexOfFirst { it is ScaleBarOverlay || it is CopyrightOverlay }.takeIf { it >= 0 } ?: map.overlays.size, own)
  }

  private fun aboveAreaAndTrack(): Int =
      map.overlays.indexOfLast { (it is Polygon && it.id == AREA_ID) || (it is Polyline && it.id == TRACK_ID) } + 1

  private var drawnTrackSerial = -1
  /** The segment being walked right now; new points are appended to it. */
  private var trackTail: Polyline? = null

  private fun trackLine(points: List<GeoPoint>) =
      Polyline(map).apply {
        id = TRACK_ID
        infoWindow = null
        setPoints(points)
        outlinePaint.color = getColor(R.color.follo_track)
        outlinePaint.strokeWidth = resources.displayMetrics.density * 4
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
      }

  /**
   * The phone's own walking track: magenta like QField tracklogs, above the area, below the pair line. Each fix is
   * appended to the current line; the whole track is rebuilt only on load, clear, trim or after missed updates
   * (review 21 Oct 2026: a full rebuild per fix stuttered after hours of walking).
   */
  private fun drawTrack(u: org.owntracks.android.data.repos.FolloTrackRepo.Update) {
    if (!::map.isInitialized || u.serial == drawnTrackSerial) return
    val incremental = !u.reset && u.last != null && u.serial == drawnTrackSerial + 1 && trackTail != null
    drawnTrackSerial = u.serial
    if (incremental) {
      val lp = u.last!!
      val p = GeoPoint(lp.lat, lp.lon)
      if (u.newSegment) {
        val at = map.overlays.indexOfLast { it is Polygon && it.id == AREA_ID } + 1
        trackTail = trackLine(listOf(p)).also { map.overlays.add(at, it) }
      } else trackTail!!.addPoint(p)
      map.invalidate()
      return
    }
    map.overlays.removeAll { it is Polyline && it.id == TRACK_ID }
    trackTail = null
    val at = map.overlays.indexOfLast { it is Polygon && it.id == AREA_ID } + 1
    val points = viewModel.trackSnapshot()
    val segments = viewModel.trackSegments(points)
    segments.reversed().forEach { seg -> map.overlays.add(at, trackLine(seg.map { GeoPoint(it.lat, it.lon) })) }
    // The tail is the last segment, or a fresh one-point line when the newest point stands alone
    val lastSeg = segments.lastOrNull()
    trackTail =
        if (lastSeg != null && lastSeg.last() == points.lastOrNull()) map.overlays.filterIsInstance<Polyline>().last { it.id == TRACK_ID }
        else points.lastOrNull()?.let { trackLine(listOf(GeoPoint(it.lat, it.lon))).also { l -> map.overlays.add(at, l) } }
    map.invalidate()
  }

  /** This phone's team's line leaders from the server (21 Oct 2026); the map shows only these people. */
  private fun leaders(): List<org.owntracks.android.model.messages.FolloLeader> =
      viewModel.folloStatus.value?.message?.leaders.orEmpty().filter { !it.topic.isNullOrBlank() }

  private fun leaderTopics(): Set<String> = leaders().filter { !it.isSelf }.mapNotNull { it.topic }.toSet()

  /** Topics the map draws: the server's `show` list (leader = whole team, member = the leaders), else the leaders. */
  private fun shownTopics(): Set<String> =
      viewModel.folloStatus.value?.message?.show?.filter { !it.isSelf && !it.topic.isNullOrBlank() }?.mapNotNull { it.topic }?.toSet()
          ?: leaderTopics()

  /** A leader whose last fix is older than [LEADER_STALE_MS] is taken off the map rather than shown where they were. */
  private fun isFresh(contact: Contact): Boolean =
      contact.latLng != null && FolloRules.leaderFresh(contact.locationTimestamp, System.currentTimeMillis() / 1000)

  private fun showIfLeader(contact: Contact) {
    if (contact.id in shownTopics()) updateContactMarker(contact)
  }

  /** Markers for the team's leaders only; anyone else (other teammates, other teams) is taken off the map. */
  private fun syncContactMarkers() {
    if (!::map.isInitialized) return
    val keep = shownTopics().filter { viewModel.allContacts[it]?.let(::isFresh) == true }.toSet()
    val removed =
        map.overlays.removeAll { it is Marker && it.id.startsWith(CONTACT_PREFIX) && it.id.removePrefix(CONTACT_PREFIX) !in keep }
    viewModel.allContacts.values.filter { it.id in keep }.forEach(::updateContactMarker)
    if (removed) map.invalidate()
  }

  /** Where this phone is drawn: the arrow while it is on the map (NAV), else the latest fix. */
  private fun ownDrawnPosition(): GeoPoint? =
      navArrow?.position?.takeIf { navOn && it.latitude != 0.0 }
          ?: viewModel.currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }

  private var pairLabelText: String? = null

  /**
   * The line between the team's two line leaders (Alex, 21 Oct 2026), seen by everyone in the team: light grey, CYAN
   * when they are PAIR_M ± PAIR_TOL_M apart (the 20 m strip), distance at its middle. A leader's own end is drawn at
   * its arrow, but the distance (and so the colour) is measured from the latest fix: the arrow eases and can lag a
   * few metres, which matters inside a ± 1 m band. Not exactly two leaders, no position for one of them, or a
   * partner whose fix is stale, = no line.
   */
  private fun drawPair() {
    if (!::map.isInitialized) return
    val two = leaders().takeIf { it.size == 2 }
    fun partner(ld: org.owntracks.android.model.messages.FolloLeader): GeoPoint? =
        viewModel.allContacts[ld.topic!!]?.takeIf(::isFresh)?.latLng?.let { GeoPoint(it.latitude.value, it.longitude.value) }
    val ends = two?.map { ld -> if (ld.isSelf) ownDrawnPosition() else partner(ld) }
    val fix = (viewModel.currentLocation.value ?: viewModel.lastPublished.value)?.let { GeoPoint(it.latitude, it.longitude) }
    val measured = two?.map { ld -> if (ld.isSelf) fix ?: ownDrawnPosition() else partner(ld) }
    val me = ends?.getOrNull(0)
    val other = ends?.getOrNull(1)
    val line = map.overlays.firstOrNull { it is Polyline && it.id == PAIR_ID } as Polyline?
    val label = map.overlays.firstOrNull { it is Marker && it.id == PAIR_ID + LABEL_SUFFIX } as Marker?
    if (me == null || other == null) {
      if (line != null || label != null) {
        map.overlays.removeAll { (it is Polyline && it.id == PAIR_ID) || (it is Marker && it.id == PAIR_ID + LABEL_SUFFIX) }
        pairLabelText = null
        map.invalidate()
      }
      return
    }
    val a = me
    val b = other
    val d = measured!![0]!!.distanceToAsDouble(measured[1]!!)
    val ok = kotlin.math.abs(d - PAIR_M) <= PAIR_TOL_M
    val colour = if (ok) Color.rgb(0, 229, 255) else Color.rgb(154, 163, 160)
    val l =
        line
            ?: Polyline(map).apply {
              id = PAIR_ID
              infoWindow = null
              outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }.also { map.overlays.add(aboveAreaAndTrack(), it) }
    l.setPoints(listOf(a, b))
    l.outlinePaint.color = colour
    l.outlinePaint.strokeWidth = resources.displayMetrics.density * (if (ok) 5 else 4)
    val m =
        label
            ?: Marker(map).apply {
              id = PAIR_ID + LABEL_SUFFIX
              infoWindow = null
              setTextLabelForegroundColor(Color.WHITE)
              setTextLabelFontSize((12 * resources.displayMetrics.scaledDensity).roundToInt())
              setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }.also { map.overlays.add(it); keepOwnOnTop() }
    m.position = GeoPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2)
    // A new label bitmap only when the text or colour changes (this runs every frame while the arrow glides).
    val text = String.format(java.util.Locale.ROOT, "%.1f m", d)
    if (label == null || pairLabelText != text + ok) {
      pairLabelText = text + ok
      m.setTextLabelBackgroundColor(if (ok) Color.argb(220, 0, 140, 160) else Color.argb(184, 0, 0, 0))
      m.setTextIcon(text)
    }
    map.invalidate()
  }

  private fun removeRegion(w: WaypointModel) {
    val ids = setOf(REGION_PREFIX + w.id, REGION_PREFIX + w.id + LABEL_SUFFIX)
    map.overlays.removeAll { (it is Marker && it.id in ids) || (it is Polygon && it.id in ids) }
  }

  private fun redrawRegions(all: List<WaypointModel>) {
    map.overlays.removeAll {
      (it is Marker && it.id.startsWith(REGION_PREFIX)) || (it is Polygon && it.id.startsWith(REGION_PREFIX))
    }
    all.forEach(::addRegion)
    keepOwnOnTop()
    map.invalidate()
  }

  private fun updateContactMarker(contact: Contact) {
    val latLng = contact.latLng ?: return
    if (!isFresh(contact)) return
    val point = GeoPoint(latLng.latitude.value, latLng.longitude.value)
    lifecycleScope.launch {
      val bitmap = contactImageBindingAdapter.getBitmapFromCache(contact)
      val id = CONTACT_PREFIX + contact.id
      if (contact.id !in shownTopics() || !isFresh(contact)) return@launch // the status changed while the picture loaded
      val existing = map.overlays.firstOrNull { it is Marker && it.id == id } as Marker?
      if (existing != null) {
        // Every reply repeats the teammates (5 s in Move): touch the marker only when it really moved or
        // its picture changed, or it blinks.
        val moved = existing.position.latitude != point.latitude || existing.position.longitude != point.longitude
        val newPicture = existing.relatedObject !== bitmap
        if (!moved && !newPicture) return@launch
        if (moved) existing.position = point
        if (newPicture) {
          existing.icon = bitmap.toDrawable(resources)
          existing.relatedObject = bitmap
        }
      } else if (!isDestroyed) {
        map.overlays.add(
            Marker(map).apply {
              this.id = id
              position = point
              icon = bitmap.toDrawable(resources)
              relatedObject = bitmap
              title = contact.displayName
              setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
              infoWindow = null
            },
        )
        keepOwnOnTop()
      }
      map.invalidate()
    }
  }

  companion object {
    private const val REGION_PREFIX = "region-"
    private const val AREA_ID = "follo-area"
    private const val GEOFENCE_ID = "follo-geofence"
    private const val PAIR_ID = "follo-pair"
    private const val TRACK_ID = "follo-track"
    // A check-in goes with a fix better than this (the app's own ignoreInaccurateLocations is 100) and no older than this
    private const val CHECK_IN_MAX_ACC_M = 100f
    private const val CHECK_IN_MAX_AGE_MS = 60_000L
    private const val PAIR_M = 20.0
    private const val PAIR_TOL_M = 1.0
    private const val NO_SIGNAL_AFTER_MS = 10_000L
    /** Radius of the invisible circle, as a fraction of the visible map's shorter side. */
    private const val LEASH_FRACTION = 0.25f
    /** How long one glide takes; about the time between fixes, so the map moves continuously. */
    private const val PAN_MS = 900L
    private const val EDGE_FRACTION = 0.15f
    /** Movement below this (or below the fix's accuracy) is GPS wobble, not walking. */
    private const val JITTER_M = 3f
    private const val CALM_TURN_DEG = 30f
    /** Where the dot / arrow sits, as a fraction of the way down the visible map. */
    private const val HEADING_DOWN = 0.75f
    private const val NAV_DOWN = 0.80f
    /** The map's turn eases toward the compass with this time constant (seconds). */
    private const val HEADING_TAU_S = 0.25f
    /** Closer than this to the compass = done turning (no more redraws). */
    private const val HEADING_SETTLED_DEG = 0.3f
    /** A two-finger twist turns the map only past this, so pinching to zoom never turns it by accident. */
    private const val ROTATE_START_DEG = 12f
    /** ≤ 30 camera redraws a second. */
    private const val FRAME_MIN_MS = 33L
    private const val START_ZOOM = 18.5
    private const val LABEL_SUFFIX = "-label"
    private const val CONTACT_PREFIX = "contact-"
    /** A Significant-mode phone may not post for hours; keep the last server word that long. */
    private const val SERVER_STATUS_TTL_MS = 12 * 60 * 60 * 1000L
  }
}
