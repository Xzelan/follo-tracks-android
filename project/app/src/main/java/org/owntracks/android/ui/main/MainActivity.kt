package org.owntracks.android.ui.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.DashPathEffect
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
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
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.owntracks.android.R
import org.owntracks.android.data.repos.ContactsRepoChange
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

    setUpMap()
    binding.action.setOnClickListener { onActionClicked() }
    binding.card.setOnClickListener {
      myLocationOverlay?.enableFollowLocation()
      viewModel.currentLocation.value?.let { map.controller.animateTo(it.toGeoPoint()) }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { redrawRegions(viewModel.waypoints()) }
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
            map.invalidate()
          }
        }
        launch {
          viewModel.allContacts.values.forEach(::updateContactMarker)
          viewModel.contactEvents.collect {
            when (it) {
              ContactsRepoChange.AllCleared -> {
                map.overlays.removeAll { o -> o is Marker && o.id.startsWith(CONTACT_PREFIX) }
                map.invalidate()
              }
              is ContactsRepoChange.ContactAdded -> updateContactMarker(it.contact)
              is ContactsRepoChange.ContactLocationUpdated -> updateContactMarker(it.contact)
              is ContactsRepoChange.ContactCardUpdated -> updateContactMarker(it.contact)
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
              ) { _, _, _, _, _ ->
                Unit
              }
              .collect { render() }
        }
        launch { viewModel.lastSentAt.collect { render() } }
        launch {
          viewModel.folloStatus.collect {
            drawAreaBox(it?.message)
            render()
          }
        }
        // The phone decides green/grey at the area edge itself, so every new fix may change it.
        launch { viewModel.currentLocation.collect { render() } }
        launch { viewModel.lastPublished.collect { render() } }
        launch { viewModel.displayNameState.collect { render() } }
        launch {
          viewModel.tileUrlState.collect {
            if (::map.isInitialized) {
              map.setTileSource(FolloTileSource(it))
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
    unbindService(serviceConnection)
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    // A system prompt pauses us, so being resumed means it has gone; our own dialogs do not pause us.
    promptInFlight = ownDialog?.isShowing == true
    map.onResume()
    if (requirementsChecker.hasLocationPermissions()) {
      viewModel.requestLocationUpdates()
      myLocationOverlay?.enableMyLocation()
    }
    if (preferences.setupCompleted) runPermissionChain()
    render()
  }

  override fun onPause() {
    map.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    map.onDetach()
    super.onDestroy()
  }

  // ── Setup link ─────────────────────────────────────────────────────────────────────────

  private fun handleIntent(intent: Intent?) {
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
              ))
              openAppSettings()
          else requestLocation()
      Kind.LOCATION_OFF -> settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      Kind.NEED_BACKGROUND -> requestBackgroundLocation()
      Kind.NEED_NOTIFICATIONS ->
          if (preferences.userDeclinedEnableNotificationPermissions) openAppSettings()
          else notificationPermissionRequester.requestNotificationPermission()
      Kind.NEED_BATTERY -> {
        Toast.makeText(this, R.string.follo_battery_howto, Toast.LENGTH_LONG).show()
        settingsLauncher.launch(BatteryOptimizingIntents(this).settingsIntent)
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
    if (!requirementsChecker.hasBackgroundLocationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        return Banner(Kind.NEED_BACKGROUND, red, s(R.string.follo_action_title), s(R.string.follo_bg_sub), s(R.string.follo_bg_btn))
    if (!notificationPermissionRequester.hasPermission())
        return Banner(Kind.NEED_NOTIFICATIONS, red, s(R.string.follo_action_title), s(R.string.follo_notif_sub), s(R.string.follo_notif_btn))
    if (!isIgnoringBatteryOptimizations())
        return Banner(Kind.NEED_BATTERY, red, s(R.string.follo_action_title), s(R.string.follo_battery_sub), s(R.string.follo_battery_btn))
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
    val here = viewModel.currentLocation.value ?: viewModel.lastPublished.value
    val sharing =
        when {
          server == null -> viewModel.isMoving
          windowOver -> false
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
    return when (if (area != null) "outside" else if (windowOver) null else server?.state) {
      "outside" ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_outside_title),
              server?.line?.takeIf { it.isNotBlank() } ?: s(R.string.follo_standby_outside_sub),
          )
      "nosearch" ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_nosearch_title),
              server?.line?.takeIf { it.isNotBlank() }
                  ?: ((server?.next?.let { getString(R.string.follo_next_search, it) } ?: "") +
                      s(R.string.follo_nosearch_tail)),
          )
      else ->
          Banner(
              Kind.STANDBY,
              grey,
              s(R.string.follo_standby_title),
              server?.line?.takeIf { it.isNotBlank() } ?: s(R.string.follo_standby_sub),
          )
    }
  }

  /**
   * Positions really held back for want of signal. Every post sits in the queue for a moment while it
   * is sent (every 5 s in Move), so a non-empty queue alone would flash "No signal" on each send; it
   * only counts once nothing has gone through for [NO_SIGNAL_AFTER_MS].
   */
  private fun backlog(): Int {
    val queued = viewModel.queueLength.value
    val last = viewModel.lastSentAt.value
    return if (queued > 0 && System.currentTimeMillis() - last > NO_SIGNAL_AFTER_MS) queued else 0
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
  }

  // ── Map ───────────────────────────────────────────────────────────────────────────────

  private fun setUpMap() {
    Configuration.getInstance().apply {
      load(this@MainActivity, PreferenceManager.getDefaultSharedPreferences(this@MainActivity))
      userAgentValue = packageName
      osmdroidTileCache = noBackupFilesDir.resolve("osmdroid/tiles")
    }
    map = binding.map
    map.setTileSource(FolloTileSource(viewModel.tileUrl))
    map.setMultiTouchControls(true)
    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    map.isTilesScaledToDpi = true
    map.minZoomLevel = 3.0
    map.maxZoomLevel = 20.0
    map.controller.setZoom(16.0)
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
          enableFollowLocation()
        }
    map.overlays.add(myLocationOverlay)
    map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(true); setScaleBarOffset(40, 260) })
    map.overlays.add(CopyrightOverlay(this).apply { setAlignBottom(true) })
  }

  private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

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
    val key = status?.box?.takeIf { status.ends?.let { System.currentTimeMillis() / 1000 >= it } != true }
    if (key == drawnBox) return // the same box arrives with every reply; redrawing it each time flickers
    drawnBox = key
    map.overlays.removeAll { (it is Polygon && it.id == AREA_ID) || (it is Marker && it.id == AREA_ID + LABEL_SUFFIX) }
    val b = status?.boxBounds()
    if (status != null && b != null && status.ends?.let { System.currentTimeMillis() / 1000 >= it } != true) {
      val (s, w, n, e) = listOf(b[0], b[1], b[2], b[3])
      map.overlays.add(
          0,
          Polygon(map).apply {
            id = AREA_ID
            points = listOf(GeoPoint(n, w), GeoPoint(n, e), GeoPoint(s, e), GeoPoint(s, w), GeoPoint(n, w))
            fillPaint.color = getColor(R.color.follo_area_fill)
            outlinePaint.color = getColor(R.color.follo_area)
            outlinePaint.strokeWidth = resources.displayMetrics.density * 4
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
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
    map.invalidate()
  }

  private fun insideBox(b: DoubleArray, lat: Double, lon: Double) =
      lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3]

  private fun removeRegion(w: WaypointModel) {
    val ids = setOf(REGION_PREFIX + w.id, REGION_PREFIX + w.id + LABEL_SUFFIX)
    map.overlays.removeAll { (it is Marker && it.id in ids) || (it is Polygon && it.id in ids) }
  }

  private fun redrawRegions(all: List<WaypointModel>) {
    map.overlays.removeAll {
      (it is Marker && it.id.startsWith(REGION_PREFIX)) || (it is Polygon && it.id.startsWith(REGION_PREFIX))
    }
    all.forEach(::addRegion)
    map.invalidate()
  }

  private fun updateContactMarker(contact: Contact) {
    val latLng = contact.latLng ?: return
    val point = GeoPoint(latLng.latitude.value, latLng.longitude.value)
    lifecycleScope.launch {
      val bitmap = contactImageBindingAdapter.getBitmapFromCache(contact)
      val id = CONTACT_PREFIX + contact.id
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
      }
      map.invalidate()
    }
  }

  companion object {
    private const val REGION_PREFIX = "region-"
    private const val AREA_ID = "follo-area"
    private const val NO_SIGNAL_AFTER_MS = 30_000L
    private const val LABEL_SUFFIX = "-label"
    private const val CONTACT_PREFIX = "contact-"
    /** A Significant-mode phone may not post for hours; keep the last server word that long. */
    private const val SERVER_STATUS_TTL_MS = 12 * 60 * 60 * 1000L
  }
}
