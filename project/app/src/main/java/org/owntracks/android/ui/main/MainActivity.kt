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
import android.view.Gravity
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
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
import org.owntracks.android.BuildConfig
import org.owntracks.android.R
import org.owntracks.android.data.repos.ContactsRepoChange
import org.owntracks.android.data.repos.FolloDiagRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.databinding.ActivityMainBinding
import org.owntracks.android.model.Contact
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.services.LocationProcessor
import org.owntracks.android.support.ContactImageBindingAdapter
import org.owntracks.android.support.FolloEverywhere
import org.owntracks.android.support.FolloPower
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
 * sharing, standing by or needs something fixed with a two-way symbol for the link to the server,
 * and (19 Nov 2026) one "…" button that shows or hides the layer and function buttons. There are no settings: the phone is set up by a FOLLO link and the server drives the
 * rest.
 */
@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    ServiceStarter by ServiceStarter.Impl(),
    WorkManagerInitExceptionNotifier by WorkManagerInitExceptionNotifier.Impl() {

  @Inject lateinit var preferences: Preferences
  @Inject lateinit var folloDiagRepo: FolloDiagRepo
  @Inject lateinit var folloGridRepo: org.owntracks.android.data.repos.FolloGridRepo
  @Inject lateinit var folloDrawRepo: org.owntracks.android.data.repos.FolloDrawRepo
  @Inject lateinit var folloOfflineRepo: org.owntracks.android.data.repos.FolloOfflineRepo
  @Inject lateinit var folloWatchRepo: org.owntracks.android.data.repos.FolloWatchRepo
  @Inject lateinit var folloScopeRepo: org.owntracks.android.data.repos.FolloScopeRepo
  @Inject lateinit var folloLayRepo: org.owntracks.android.data.repos.FolloLayRepo
  @Inject lateinit var folloLinkRepo: org.owntracks.android.data.repos.FolloLinkRepo
  @Inject lateinit var requirementsChecker: RequirementsChecker
  @Inject lateinit var notificationsStash: NotificationsStash
  @Inject lateinit var contactImageBindingAdapter: ContactImageBindingAdapter
  @Inject lateinit var locationProcessor: LocationProcessor

  private val viewModel: MainViewModel by viewModels()
  private lateinit var binding: ActivityMainBinding
  private lateinit var map: MapView
  private var myLocationOverlay: MyLocationNewOverlay? = null
  private var service: BackgroundService? = null
  /** The system bars (and any camera cut-out) round the screen, for where the panels and buttons go */
  private var barsBottom = 0
  private var barsTop = 0
  private var barsRight = 0
  private var barsLeft = 0
  /** The navigation bar alone at the sides (sideways): the bottom band stops at it, but runs on under a camera cut-out */
  private var navLeft = 0
  private var navRight = 0
  private var state: Banner? = null
  /** True while a system permission prompt is up; the banner then says "Setting up" instead of red. */
  private var promptInFlight = false
  /** The app's own explanation dialog, if one is up (it does not pause the activity). */
  private var ownDialog: androidx.appcompat.app.AlertDialog? = null
  /** Which way the phone points: turns the map (NAV). */
  private lateinit var compass: FolloCompass
  /** Which way the screen is turned: upright on opening, a quarter turn per tap of the rotate button (14 Nov 2026). */
  private lateinit var screenTurner: FolloScreenTurner

  /** What the banner is showing; [kind] decides what the one button does. */
  private data class Banner(
      val kind: Kind,
      val color: Int,
      val title: String,
      val sub: String,
      val button: String? = null,
      /** The second line already says how many positions wait, so the card does not repeat it (13 Nov 2026) */
      val saysBacklog: Boolean = false,
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
    /** The server answered 401/403: blocked, or the setup link was re-issued (B2, 28 Oct 2026). */
    REJECTED,
    /** Inside the area in search hours, but not tracking yet: amber, no button (B10, 28 Oct 2026). */
    WAITING,
    SHARING,
    STANDBY,
    SETTING_UP,
    /** Turned Off with the power button (13 Nov 2026): no location use at all; red, no button but the power one. */
    OFF,
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
            openCheckIn()
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
    if (FolloPower.isOff) {
      render()
      return
    }
    if (requirementsChecker.hasLocationPermissions()) {
      viewModel.requestLocationUpdates()
      myLocationOverlay?.enableMyLocation()
      service?.reInitializeLocationRequests()
      // First chance to tell the server this phone exists: the post queued at setup time was
      // dropped because there was no location permission yet.
      if (preferences.setupCompleted) openCheckIn()
    }
    render()
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────────────────────

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    folloDiagRepo.noteLaunch("user")
    FolloTileCache.configure(this) // before the layout: the MapView opens the tile cache as it is created
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    // The version is no longer on the screen (14 Nov 2026): the third tick says whether it is the latest, and the
    // ticks' shared check names it.
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
      val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
      barsBottom = bars.bottom
      barsTop = bars.top
      barsRight = bars.right
      barsLeft = bars.left
      navLeft = nav.left
      navRight = nav.right
      binding.alwaysPage.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      applyOrientation(resources.configuration.orientation)
      windowInsets
    }
    // The full-screen Always step (15 Nov 2026): its column at most 420 dp wide, centred (sideways too)
    binding.alwaysPage.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
      if (r - l != or - ol) {
        val avail = r - l - v.paddingLeft - v.paddingRight
        val w = minOf(avail, (ALWAYS_MAX_DP * resources.displayMetrics.density).toInt())
        val lp = binding.alwaysColumn.layoutParams
        if (lp.width != w) {
          lp.width = w
          binding.root.post { binding.alwaysColumn.layoutParams = lp }
        }
      }
    }
    binding.alwaysOpen.setOnClickListener { requestBackgroundLocation() }

    compass =
        FolloCompass(this) {
          if (camera == Camera.NAV) requestNavFrame()
          schedulePill()
        }
    // The compass heading pill (Cam Allport 15 Nov 2026): a small dial at its left; a tap while calibration is poor
    // says how to calibrate
    headingDial = FolloCompassDial(dp(16f), dp(1.5f).toFloat())
    binding.heading.setCompoundDrawablesRelativeWithIntrinsicBounds(headingDial, null, null, null)
    binding.heading.setOnClickListener { if (compass.poor()) showCalibrate() }
    // Upright every time the app is opened; a turn chosen with the rotate button is kept only while the screen is
    // being made again (savedInstanceState), not over a fresh open.
    screenTurner = FolloScreenTurner(this)
    screenTurner.open(savedInstanceState?.takeIf { it.containsKey(TURN_KEY) }?.getInt(TURN_KEY))
    binding.recentre.setOnClickListener { recentre() }
    // The buttons hang under the banner and the position sits on the card, both of which grow and shrink with what
    // they say: place them again after any change (posted: a layout change asked for inside a layout pass is lost)
    val replace = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
      if (l != ol || t != ot || r != or || b != ob) binding.root.post { placeOverlays() }
    }
    binding.banner.addOnLayoutChangeListener(replace)
    binding.bottom.addOnLayoutChangeListener(replace)
    binding.notice.addOnLayoutChangeListener(replace)
    binding.note.addOnLayoutChangeListener(replace)
    binding.coords.addOnLayoutChangeListener(replace)
    binding.creditBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
      if (b - t != ob - ot) binding.root.post { applyFoot() }
    }
    // The three ticks: one tap shares the phone check (Alex, 14 Nov 2026)
    binding.ticks.setOnClickListener { onTicksClicked() }
    // The phone's own position: press and hold shares it (Alex, 13 Nov 2026)
    binding.coords.setOnLongClickListener {
      shareCoords()
      true
    }
    applyOrientation(resources.configuration.orientation)
    setUpMap()
    if (camera == Camera.NAV) setNavLook(true)
    buildMenu()
    buildWatch()
    buildScope()
    binding.note.setOnClickListener { onNoteClicked() }

    binding.action.setOnClickListener { onActionClicked() }
    // The recentre button brings the arrow back (14 Nov 2026: a tap on the card no longer does); a tap on the banner
    // still does (Alex 24 Oct 2026). The offline map's sign on the card is gone: the second tick says it.
    binding.banner.setOnClickListener {
      // A tap while the view is clean (18 Nov 2026) only brings everything back
      if (clean) clean = false else recentre()
    }
    // Clean view (Alex 18 Nov 2026, only on a phone that may see every phone): a long press on the banner hides this
    // phone's own arrow, the magenta lines, the rotate and recentre buttons, the card and the two pills; a tap shows them
    binding.banner.setOnLongClickListener {
      if (!watchAllowed || clean || FolloPower.isOff) return@setOnLongClickListener false
      clean = true
      true
    }
    // The two-way symbol (19 Nov 2026): a tap says who this phone is and when it last sent; a long press sends a
    // position now (the card's long press; the way out of standing still inside a wake-up circle, review 21 Oct 2026).
    binding.link.setOnClickListener { showLinkBubble() }
    binding.link.setOnLongClickListener {
      if (!FolloPower.isOff && requirementsChecker.hasLocationPermissions() && preferences.setupCompleted) {
        Toast.makeText(this, R.string.follo_send_now, Toast.LENGTH_SHORT).show()
        pendingCheckIn = PendingCheckIn.USER
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
          // 22 Nov 2026 (FP078): team-mates are dots drawn by the top overlay, worked out afresh on every change
          viewModel.contactEvents.collect {
            syncContactMarkers()
            if (it is ContactsRepoChange.ContactAdded || it is ContactsRepoChange.ContactLocationUpdated) drawPair()
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
                scopeUpdate() // set up meanwhile, or the fix has gone stale (FP055)
                drawAreaBox(viewModel.folloStatus.value?.message) // hides the area once its window ends
                syncContactMarkers() // and takes a leader off the map once their fix is stale
                drawPair()
                render()
              }
        }
        launch { viewModel.lastSentAt.collect { render() } }
        launch { FolloPower.offState.collect { showPower(it) } }
        launch { viewModel.rejected.collect { render() } }
        launch {
          viewModel.folloStatus.collect {
            it?.message?.team.let { team ->
              if (gridOverlay?.ownTeam != team) {
                gridOverlay?.ownTeam = team
                map.invalidate()
              }
            }
            drawAreaBox(it?.message)
            syncContactMarkers()
            drawPair()
            render()
            watchStatusChanged()
            // FP055: the areas the server already holds this phone's fixes for count as been to (kept on the phone)
            it?.message?.beenRows()?.let(folloScopeRepo::merge)
            scopeUpdate()
          }
        }
        launch { folloScopeRepo.been.collect { scopeUpdate() } }
        launch { folloWatchRepo.snapshot.collect { watchChanged() } }
        // The phone decides green/grey at the area edge itself, so every new fix may change it.
        launch {
          viewModel.currentLocation.collect {
            if (FolloPower.isOff) return@collect // a fix already on its way when the app was turned Off: forgotten
            scopeUpdate() // FP055: which view this fix means, before anything is drawn from it
            // FP055: the screen's own fixes join the walking track only inside a search area (an exempt phone: anywhere)
            it?.takeIf { l -> !scopeActive || FolloScope.areasAt(l.latitude, l.longitude, scopeAreas).isNotEmpty() }?.let(viewModel::addTrack)
            it?.let { l -> folloOfflineRepo.notePosition(l.latitude, l.longitude) }
            it?.let(compass::updateDeclination)
            it?.let(::followCamera)
            checkInIfReady()
            drawPair()
            render()
          }
        }
        launch { viewModel.lastPublished.collect { render() } }
        // A fix ages without any event: look every 15 s whether it has just gone stale (15 Nov 2026)
        launch {
          while (true) {
            delay(15_000L)
            if (FolloRules.fixIsStale(fixAgeMs()) != shownStale) render()
            scopeUpdate() // a fix gone stale ends LIVE (FP055)
            if (syncTrackTail()) map.invalidate() // a short piece of the track that has gone quiet comes off (FP079)
          }
        }
        launch { folloOfflineRepo.state.collect { renderOffline(it) } }
        launch {
          folloGridRepo.grid.collect {
            gridOverlay?.grid = it
            map.invalidate()
            renderMenu() // the Grids and Labels buttons exist where the grids have lines / names
          }
        }
        // The layers (19 Nov 2026): the list says which buttons exist, the files what can be drawn
        launch { folloLayRepo.list.collect { renderMenu() } }
        launch { folloLayRepo.held.collect { renderMenu() } }
        // The two-way symbol: looked at again at least every 10 s while the screen is on
        launch {
          while (true) {
            delay(10_000L)
            renderLink()
          }
        }
        launch { folloDrawRepo.draw.collect(::showDrawings) }
        launch { viewModel.trackUpdates.collect(::drawTrack) }
        launch { viewModel.displayNameState.collect { render() } }
        // "Share everywhere I go" (22 Nov 2026): the switch, wherever it was thrown (here, or by the server's status)
        launch { FolloEverywhere.onState.collect { everywhereChanged(it) } }
        launch { FolloEverywhere.serverOffNotice.collect { render() } }
        launch {
          viewModel.tileState.collect { (url, maxZoom) ->
            if (::map.isInitialized && (map.tileProvider.tileSource as? FolloTileSource)?.matches(url, maxZoom) != true) {
              map.setTileSource(FolloTileSource.of(url, maxZoom))
              showCredit()
              map.invalidate()
            }
          }
        }
      }
    }

    // Phones in circles (FolloWatch): asked every 10 s while the screen is in front, only on a phone the server names
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          if (watchAllowed && !FolloPower.isOff) folloWatchRepo.fetch()
          delay(org.owntracks.android.data.repos.FolloWatchRepo.PERIOD_MS)
        }
      }
    }

    startService(this)
    NotificationManagerCompat.from(this)
        .cancel(BackgroundService.BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0)
    notifyOnWorkManagerInitFailure(this)
    handleIntent(intent)
  }

  // The activity keeps running through a rotation (configChanges), so the map and its state survive; the layout is
  // switched here instead: upright = banner across the top, card above the bottom band; sideways (14 Nov 2026) = the
  // status panel in the top right corner and the card in the bottom right corner on the band, over a full-screen map.
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    applyOrientation(newConfig.orientation)
    // Let the camera place the dot afresh for the new shape of the visible map.
    centredOnce = false
    camRef = null
    // After the layout pass, so visible() sees the panels where they now are (not where they were).
    if (camera == Camera.NAV) binding.root.doOnLayout {
      setNavLook(true)
      requestNavFrame() // arrow back on its spot in the new shape at once
    }
  }

  private fun isLand() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

  private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

  /** Sideways, the status panel and the card are this wide (plus the navigation bar's width when it is on the right). */
  private fun panelWidth() = dp(SIDE_PANEL_DP)

  private fun applyOrientation(orientation: Int) {
    if (!::binding.isInitialized) return
    val land = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    // Sideways the status bar stays across the top: a strip in the banner's colour behind it
    binding.statusStrip.visibility = if (land && barsTop > 0) View.VISIBLE else View.GONE
    binding.statusStrip.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { height = barsTop }
    // The banner: across the top upright (under the status bar); the status panel sideways, hard into the top right
    // corner under the status bar, its colour running on under a navigation bar on the right (its words stop before it)
    binding.banner.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      width = if (land) panelWidth() + barsRight else match
      height = wrap
      topMargin = if (land) barsTop else 0
      gravity = android.view.Gravity.TOP or android.view.Gravity.END
    }
    binding.banner.updatePadding(
        left = dp(if (land) 16f else 20f) + if (land) 0 else barsLeft,
        top = dp(6f) + if (land) 0 else barsTop,
        right = dp(14f) + barsRight,
    )
    bannerBg = null // the panel's shape changes: draw it again
    state?.let { setBannerColor(it.color) }
    // The bottom band: the whole width (sideways it stops at a navigation bar on either side), down to the foot of the
    // screen; its row sits just above the navigation bar
    binding.creditBar.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      marginStart = if (land) navLeft else 0
      marginEnd = if (land) navRight else 0
    }
    binding.creditBar.updatePadding(
        left = dp(12f) + barsLeft - if (land) navLeft else 0,
        right = dp(8f) + barsRight - if (land) navRight else 0,
        bottom = barsBottom + dp(if (land) 5f else 4f),
    )
    // the Off words sit in the part of the screen the map would show: left of the panels when the phone is sideways
    binding.offCover.updatePadding(
        right = if (land) panelWidth() + barsRight else 0,
        top = if (land) barsTop else 0,
    )
    binding.waitCover.updatePadding(
        right = if (land) panelWidth() + barsRight else 0,
        top = if (land) barsTop else 0,
    )
    applyFoot()
    placeOverlays()
  }

  private var bannerBg: Pair<Int, Boolean>? = null

  /** The banner's colour; sideways a panel with its bottom left corner rounded, and the strip behind the status bar. */
  private fun setBannerColor(color: Int) {
    val land = isLand()
    if (bannerBg == color to land) return
    bannerBg = color to land
    val r = dp(16f).toFloat()
    binding.banner.background =
        if (land)
            android.graphics.drawable.GradientDrawable().apply {
              setColor(color)
              cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r)
            }
        else android.graphics.drawable.ColorDrawable(color)
    binding.statusStrip.setBackgroundColor(color)
  }

  /** The recentre button's distance from the left edge and the "…" button's from the right edge (px). */
  private fun recentreStart(): Int = dp(12f) + if (isLand()) barsLeft else 0

  private fun moreEnd(): Int =
      if (isLand()) (binding.banner.width.takeIf { it > 0 } ?: (panelWidth() + barsRight)) + dp(10f) else dp(12f)

  /**
   * The things over the map (14 Nov 2026; 19 Nov the simple screen). Upright: the recentre button top left and the "…"
   * button top right, 10 dp under the banner. Sideways: the recentre button top left under the status bar, the "…"
   * button level with it just left of the status panel. The position pill just above the bottom band on the right
   * (above the notices when one shows), the heading pill level with it on the left; the column under "…".
   */
  private fun placeOverlays() {
    if (!::binding.isInitialized) return
    val land = isLand()
    val top = if (land) barsTop + dp(12f) else binding.banner.height + dp(10f)
    val start = recentreStart()
    val end = moreEnd()
    binding.recentre.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginStart != start) {
        topMargin = top
        marginStart = start
      }
    }
    binding.more.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginEnd != end) {
        topMargin = top
        marginEnd = end
      }
    }
    placeWatch(top)
    scopeLaidOut()
    placeLink()
    if (binding.root.height > 0) {
      val first = firstNotice()
      val above =
          if (first != null && binding.bottom.height > 0) binding.root.height - (binding.bottom.top + first.top) + dp(8f)
          else bandHeight() + dp(8f)
      val right = if (land) dp(14f) + barsRight else dp(16f)
      binding.coords.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        if (bottomMargin != above || marginEnd != right) {
          bottomMargin = above
          marginEnd = right
        }
      }
      // The heading pill: on the left, level with the position pill. If the two would come closer than 8 dp, it sits
      // straight above the position pill instead (spec §5 of 15 Nov).
      val left = dp(16f) + if (land) barsLeft else 0
      val coordsW = if (binding.coords.visibility == View.VISIBLE) binding.coords.width.toFloat() else 0f
      val stack = FolloRules.pillsStack((binding.root.width - left - right).toFloat(), widestHeadingPx(), coordsW, dp(8f).toFloat())
      val hGravity = if (stack) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.START
      val hBottom = if (stack) above + binding.coords.height + dp(8f) else above
      binding.heading.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        if (gravity != hGravity || bottomMargin != hBottom || marginStart != left || marginEnd != right) {
          gravity = hGravity
          bottomMargin = hBottom
          marginStart = left
          marginEnd = right
        }
      }
      placeMenu(top, end, above)
    }
  }

  /** The first notice showing above the bottom band, if any. */
  private fun firstNotice(): View? = listOf(binding.notice, binding.note).firstOrNull { it.visibility == View.VISIBLE }

  /** The bottom band's height: its row plus the navigation bar under it. */
  private fun bandHeight(): Int =
      binding.creditBar.height.takeIf { it > 0 } ?: (dp(4f + 18f) + barsBottom + dp(if (isLand()) 5f else 4f))

  /** The heading pill at its widest ("359°  WNW"), so it never flips between beside and above as the text changes. */
  private fun widestHeadingPx(): Float {
    val v = binding.heading
    return v.paint.measureText("359°  WNW") + v.paddingStart + v.paddingEnd + v.compoundDrawablePadding + dp(16f)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    if (::screenTurner.isInitialized) outState.putInt(TURN_KEY, screenTurner.degrees)
  }

  /** The notices sit 8 dp above the bottom band, the whole width less 16 dp at each side (19 Nov 2026). */
  private fun applyFoot() {
    if (!::binding.isInitialized) return
    val land = isLand()
    val band = bandHeight()
    binding.bottom.updatePadding(
        left = dp(16f) + if (land) barsLeft else 0,
        right = dp(16f) + if (land) barsRight else 0,
        bottom = band + dp(8f),
    )
    binding.offCover.updatePadding(bottom = if (land) band else 0)
    binding.waitCover.updatePadding(bottom = if (land) band else 0)
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
    // Power button (13 Nov 2026): Off = the screen comes forward without asking for a position or checking in
    if (!FolloPower.isOff(this)) comeForward()
    render()
  }

  /** What coming to the front starts: the screen's own fixes, the permission questions, the fetches and the check-in. */
  private fun comeForward() {
    compass.start()
    if (requirementsChecker.hasLocationPermissions()) {
      viewModel.requestLocationUpdates()
      myLocationOverlay?.enableMyLocation()
    }
    if (preferences.setupCompleted) runPermissionChain()
    // Offline map + search grids (10 Nov 2026): look again whenever the app comes forward
    folloOfflineRepo.kick()
    folloGridRepo.refresh()
    folloDrawRepo.refresh() // base camp's drawings for this phone (12 Nov 2026)
    folloLayRepo.refresh() // the map layers (19 Nov 2026)
    // Check in every time the app comes to the front (Alex 25 Oct 2026, as on iOS): the reply brings the wake-up
    // circles for any search in the next 14 days. Since 15 Nov 2026 the service takes the fix itself and sends the
    // location-free hello anyway when none comes (or location can't be used): no waiting for the screen's fix here.
    if (preferences.setupCompleted) openCheckIn()
    everywhereRemind()
  }

  // ── Power button (Alex, 13 Nov 2026; support/FolloPower) ──────────────────────────────────────────

  /** The button turned the app Off (held 2 s) or back on (one tap). */
  private fun setPower(off: Boolean) {
    if (!preferences.setupCompleted || FolloPower.isOff == off) return
    FolloPower.set(this, off) // BackgroundService and showPower follow the flag
    if (!off) {
      startService(this) // the tracking service again, as when the app is opened
      lastOpenCheckInAt = -OPEN_CHECK_IN_DEDUPE_MS
      if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) comeForward()
    }
    showPower(off)
  }

  /** What the screen last showed for the power state (null = nothing yet). */
  private var shownOff: Boolean? = null

  /** Off: no map and no location for the screen, only the words; on: the map back. */
  private fun showPower(off: Boolean) {
    if (!::map.isInitialized || shownOff == off) return
    shownOff = off
    if (off) clean = false // Off ends the clean view
    binding.offCover.visibility = if (off) View.VISIBLE else View.GONE
    showRecentre()
    map.visibility = if (off) View.INVISIBLE else View.VISIBLE
    if (off) {
      viewModel.stopLocationUpdates()
      myLocationOverlay?.disableMyLocation()
      compass.stop()
      camAnim?.cancel()
      pendingCheckIn = null
      viewModel.saveTrack()
      binding.credit.visibility = View.INVISIBLE // no map, no credit; the ticks stay
    } else {
      followPaused = false
      centredOnce = false
      camRef = null
      showCredit()
    }
    render()
    watchStatusChanged()
    scopeUpdate()
    renderMenu()
  }

  /** A check-in waiting for a good enough fix: USER = the person's long-press (sent as is). The automatic (open)
   *  check-in no longer waits here (15 Nov 2026): BackgroundService.folloCheckIn takes its own fix or goes without. */
  private enum class PendingCheckIn {
    USER,
  }

  private var pendingCheckIn: PendingCheckIn? = null

  private fun checkInIfReady() {
    if (FolloPower.isOff) return
    val kind = pendingCheckIn ?: return
    val l = viewModel.currentLocation.value ?: return
    if (l.accuracy >= CHECK_IN_MAX_ACC_M || System.currentTimeMillis() - l.time > CHECK_IN_MAX_AGE_MS) return
    pendingCheckIn = null
    when (kind) {
      PendingCheckIn.USER -> startService(this, BackgroundService.INTENT_ACTION_SEND_LOCATION_USER)
    }
  }

  /**
   * "No fix on open" (Alex, 1 Nov 2026): every check-in the app makes by itself (coming to the front, set up, a
   * permission granted) goes through LocationProcessor's judgement: the position is posted only inside a held
   * wake-up circle or boundary, a location-free hello otherwise (BackgroundService.folloCheckIn, src "open").
   */
  private fun openCheckIn() {
    if (FolloPower.isOff) return
    // Coming to the front and the permission chain's "granted" callback both land here within a second of each
    // other (Samsung test 1 Nov 2026: two hellos per open); one automatic check-in per 10 s is plenty, except that one
    // made before location was allowed (a hello without a fix) doesn't hold back the first one after.
    val now = SystemClock.elapsedRealtime()
    val located = requirementsChecker.hasLocationPermissions()
    if (!FolloRules.openCheckInDue(now - lastOpenCheckInAt, lastOpenCheckInLocated, located)) return
    lastOpenCheckInAt = now
    lastOpenCheckInLocated = located
    startService(
        this,
        BackgroundService.INTENT_ACTION_FOLLO_CHECKIN,
        Intent().putExtra(BackgroundService.EXTRA_CHECKIN_SRC, LocationProcessor.CheckInSource.OPEN.wire),
    )
  }

  private var lastOpenCheckInAt = -OPEN_CHECK_IN_DEDUPE_MS
  private var lastOpenCheckInLocated = false

  /** The searches whose wake-up circles this phone holds ("FOLLO MV wake 3" -> MV), for "Ready for MV" (Alex 25 Oct 2026). */
  private var wakeAreas: List<String> = emptyList()
  /** The newest circles' date the held wake-up circles carry (Edit Circles, 1 Nov 2026), "1-11-2026", or null. */
  private var wakeUpdate: String? = null

  private fun wakeAreaOf(w: WaypointModel): String? =
      if (w.geofenceRadius > 0) FolloRules.wakeArea(w.description) else null

  private fun refreshWakeAreas(held: List<WaypointModel>) {
    val all = held + scopeDemo // debug builds: made-up circles for the screen alone (FP055), else nothing added
    drawGeofences(all)
    scopeCircles(all)
    val areas = all.mapNotNull(::wakeAreaOf).distinct()
    val update = FolloRules.circlesDay(all.filter { wakeAreaOf(it) != null }.map { it.description })
    if (areas != wakeAreas || update != wakeUpdate) {
      wakeAreas = areas
      wakeUpdate = update
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
        // FP055 (19 Nov 2026): the circle on the sphere, the very points the grey mask is cut from (FolloScope.ring), so
        // the line lies where the mask ends and where sharing starts; it was a flat approximation up to 0.6 % out
        val r = c.geofenceRadius.toDouble()
        val n = FolloScope.ringPoints(r)
        val pts =
            FolloScope.ring(FolloRules.Circle(c.geofenceLatitude.value, c.geofenceLongitude.value, r), n).map { (la, lo) ->
              val p = GeoPoint(la, lo)
              val covered =
                  group.any { o ->
                    o !== c &&
                        FolloRules.distanceM(la, lo, o.geofenceLatitude.value, o.geofenceLongitude.value) < o.geofenceRadius - 0.5
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

  override fun onPause() {
    compass.stop()
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
    // Debug builds only (15 Nov 2026): hold the map at "lat,lon" [north up] as if moved by hand, to look at the grids
    // somewhere the emulator's own position is not (no position inside a search circle is ever made up for a check).
    if (org.owntracks.android.BuildConfig.DEBUG)
        intent?.getStringExtra("follo_centre")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let {
          followPaused = true
          showRecentre()
          intent.getStringExtra("follo_zoom")?.toDoubleOrNull()?.let { z -> map.controller.setZoom(z) }
          // `follo_turn` (19 Nov 2026): the map turned that many degrees, as by a two-finger twist
          map.mapOrientation = intent.getStringExtra("follo_turn")?.toFloatOrNull() ?: 0f
          map.controller.setCenter(org.osmdroid.util.GeoPoint(it[0], it[1]))
          map.invalidate()
        }
    // Debug builds only (18 Nov 2026): the phones-in-circles drop-down with made-up phones and searches (FolloWatchRepo)
    if (org.owntracks.android.BuildConfig.DEBUG && intent?.getBooleanExtra("follo_watch_demo", false) == true) {
      folloWatchRepo.demo = true
      watchAllowed = false
      watchStatusChanged()
      scopeUpdate() // the demo phone is one the server names (exempt from FP055 unless Volunteer view is on)
    }
    // Debug builds only (FP055, 19 Nov 2026): made-up search circles for the SCREEN alone, "AA:lat,lon,r;AA:lat,lon,r;
    // Basecamp:lat,lon,r", so the three views can be checked on the emulator away from every real search area. The
    // sharing gate never sees them (it reads the real circles), so no position is ever sent because of them.
    // `follo_scope_been` = "AA,BB" marks areas as been to; "-" forgets every area.
    if (org.owntracks.android.BuildConfig.DEBUG) {
      intent?.getStringExtra("follo_scope_been")?.let { text ->
        if (text == "-") folloScopeRepo.clear()
        else folloScopeRepo.merge(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it to System.currentTimeMillis() / 1000 })
      }
      intent?.getStringExtra("follo_scope_demo")?.let { text ->
        scopeDemo =
            text.split(';').mapNotNull { row ->
              val area = row.substringBefore(':').trim()
              val n = row.substringAfter(':', "").split(',').mapNotNull { it.trim().toDoubleOrNull() }
              if (area.isEmpty() || n.size != 3) null
              else
                  WaypointModel(
                      description = "FOLLO $area wake 1",
                      geofenceLatitude = org.owntracks.android.location.geofencing.Latitude(n[0]),
                      geofenceLongitude = org.owntracks.android.location.geofencing.Longitude(n[1]),
                      geofenceRadius = n[2].toInt(),
                  )
            }
        lifecycleScope.launch { refreshWakeAreas(viewModel.waypoints()) }
      }
    }
    // Debug builds only (19 Nov 2026, the simple screen): `follo_lay_demo` = "AA:lat,lon,r" makes up every layer for
    // that (made-up) search on the phone itself, nothing fetched; `follo_menu_set` sets the buttons; `follo_link_demo`
    // = green | orange | red shows the two-way symbol that way.
    if (org.owntracks.android.BuildConfig.DEBUG) {
      intent?.getStringExtra("follo_lay_demo")?.let { text ->
        val area = text.substringBefore(':').trim()
        val n = text.substringAfter(':', "").split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (area.isNotEmpty() && n.size == 3)
            lifecycleScope.launch {
              kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FolloLayDemo.make(folloLayRepo, folloGridRepo, area, n[0], n[1], n[2]) }
              renderMenu()
            }
      }
      intent?.getStringExtra("follo_menu_set")?.let(::menuSet)
      // 22 Nov 2026: `follo_tick_demo` = "ok,part,warn" (app, circles, map) shows the ticks that way; `follo_ghost_demo`
      // = keep | drop shows the made-up phones with or without the one that has left; `follo_ev_demo` = offer (the row
      // with no server; keep the emulator OFFLINE) | remind (the 12-hour pop-up now) | serveroff (as if a status
      // element had arrived without `ev`).
      intent?.getStringExtra("follo_tick_demo")?.let { text ->
        tickDemo =
            text.split(',').mapNotNull { w -> FolloRules.Tick.entries.firstOrNull { it.name.equals(w.trim(), true) } }.takeIf { it.size == 3 }
        renderTicks()
      }
      intent?.getStringExtra("follo_ghost_demo")?.let {
        ghostDemoKeep = it == "keep"
        watchChanged()
      }
      when (intent?.getStringExtra("follo_ev_demo")) {
        "offer" -> everywhereDemo = true
        "remind" -> everywhereRemind(force = true)
        "serveroff" -> lifecycleScope.launch { locationProcessor.onEverywhereOff(byServer = true) }
      }
      intent?.getStringExtra("follo_link_demo")?.let { name ->
        folloLinkRepo.demo = FolloMenu.Link.entries.firstOrNull { it.name.equals(name, true) }
        renderLink()
      }
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
        is MainViewModel.SetupLink.Unfetched -> {
          // A FOLLO link that gave no setup: no signal, turned off (410) or no longer valid (403)
          Timber.w("Setup link gave no setup: HTTP ${link.status ?: "no response"}")
          Toast.makeText(this@MainActivity, link.messageRes, Toast.LENGTH_LONG).show()
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
                  folloScopeRepo.clear() // FP055: the places whoever this phone was set up as before had been to are not this person's
                  // Let the server see this phone straight away (a hello: no circles held yet), then walk the permissions.
                  openCheckIn()
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
    if (FolloPower.isOff) return // Off asks for nothing; the questions come back when the app is turned on
    if (promptInFlight) return // one Android prompt at a time; a second launch cancels the first
    if (!requirementsChecker.hasLocationPermissions()) {
      if (!preferences.userDeclinedEnableLocationPermissions) requestLocation()
      return
    }
    if (
        !requirementsChecker.hasBackgroundLocationPermission() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    ) {
      if (!preferences.userDeclinedEnableBackgroundLocationPermissions) {
        requestBackgroundLocation()
        return
      }
      // Still While Using after the one Android prompt (field review 9 Nov 2026: Kellie tracked only with the screen
      // on): the full-screen Always step stays up until it is fixed (15 Nov 2026; it replaced the once-per-open
      // reminder). Same on iOS.
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
          preferences.locationPromptShown = true
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
          // Settings only once Android itself has refused to ask again: the rationale flag is also false when
          // the system prompt has never been shown ("Not now" on our disclosure; B3, 28 Oct 2026).
          if (preferences.userDeclinedEnableLocationPermissions &&
              preferences.locationPromptShown &&
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
    val amber = getColor(R.color.follo_amber)
    fun s(id: Int) = getString(id)
    if (!viewModel.setupCompleted.value)
        return Banner(Kind.NOT_SET_UP, grey, s(R.string.follo_not_setup_title), s(R.string.follo_not_setup_sub))
    // Power button (13 Nov 2026): red, like a setting that needs attention, because nothing is recorded
    if (FolloPower.isOff) return Banner(Kind.OFF, red, s(R.string.follo_off_title), s(R.string.follo_off_sub))
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
    // The server turned the last send away (401/403): blocked, or the link was re-issued (B2/B14, 28 Oct 2026). Red,
    // with the remedy, instead of "No signal. N positions saved" for ever; the queue is kept and retried as on iOS.
    if (viewModel.rejected.value)
        return Banner(Kind.REJECTED, red, s(R.string.follo_rejected_title), s(R.string.follo_rejected_sub))
    // "Share everywhere I go" (Alex, 22 Nov 2026): said for as long as the mode is on, in the sharing style
    if (FolloEverywhere.isOn) {
      val queued = backlog()
      val sub =
          when {
            queued == 1 -> s(R.string.follo_offline_sub_one)
            queued > 1 -> getString(R.string.follo_offline_sub, queued)
            else -> ""
          }
      return Banner(Kind.SHARING, green, s(R.string.follo_everywhere_banner), sub, saysBacklog = queued > 0)
    }
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
      // Green only while the phone really is tracking (review 28 Oct 2026, B10). Inside the area but still in Manual
      // (the server's Move never arrived, or the check-in is queued behind no signal) the phone records nothing, so the
      // old green "positions saved" line was false; and in Move with nothing accepted for a minute and nothing queued,
      // it has no usable fix. Same on iOS.
      if (!viewModel.isMoving)
          return Banner(Kind.WAITING, amber, s(R.string.follo_waiting_title),
              s(if (queued > 0) R.string.follo_waiting_nosignal_sub else R.string.follo_waiting_sub))
      if (queued == 0 && System.currentTimeMillis() - viewModel.lastSentAt.value > SENT_STALE_MS)
          return Banner(Kind.WAITING, amber, s(R.string.follo_waiting_fix_title), s(R.string.follo_waiting_fix_sub))
      // Words from Alex's list of 13 Nov 2026: one line with when sharing ends, with or without signal; with no signal
      // the second line counts what waits (the card no longer repeats it); the server's own line still goes under
      val until = FolloRules.shortClock(server?.until)
      val title = if (until.isEmpty()) s(R.string.follo_sharing_now) else getString(R.string.follo_sharing_until, until)
      val sub =
          when {
            queued == 1 -> s(R.string.follo_offline_sub_one)
            queued > 1 -> getString(R.string.follo_offline_sub, queued)
            !server?.line.isNullOrBlank() -> server!!.line!!
            else -> ""
          }
      return Banner(Kind.SHARING, green, title, sub, saysBacklog = queued > 0)
    }
    // Standing by (Alex 14 Nov 2026, wording page): every search area is open 6am-6pm daily, so one banner says it all,
    // with the hours from this phone's windows; the old "Standing by: no search right now" and plain "Standing by"
    // banners are gone. The server's own line, when it sends one, still goes under.
    val hours = FolloRules.hoursText(server?.windows(), nowS, java.time.ZoneId.systemDefault(), nearestArea())
    return Banner(
        Kind.STANDBY,
        grey,
        if (hours == null) s(R.string.follo_standby_outside_title) else getString(R.string.follo_standby_outside_hours, hours),
        server?.line?.takeIf { it.isNotBlank() } ?: "",
    )
  }

  /** The search area of the held wake-up circle nearest the phone (worked out on the phone; nothing is sent). */
  private fun nearestArea(): String? {
    val here = viewModel.currentLocation.value ?: viewModel.lastPublished.value ?: return null
    return lastWaypoints
        .mapNotNull { w -> wakeAreaOf(w)?.takeIf { it != "Basecamp" }?.let { it to w } }
        .minByOrNull { (_, w) -> FolloRules.distanceM(here.latitude, here.longitude, w.geofenceLatitude.value, w.geofenceLongitude.value) }
        ?.first
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
    setBannerColor(b.color)
    // Only touch a view when its content changes: render() runs on every post (5 s in Move).
    if (binding.title.text.toString() != b.title) binding.title.text = b.title
    if (binding.sub.text.toString() != b.sub) binding.sub.text = b.sub
    binding.sub.visibility = if (b.sub.isBlank()) View.GONE else View.VISIBLE
    binding.action.visibility = if (b.button == null) View.GONE else View.VISIBLE
    binding.action.text = b.button ?: ""
    binding.action.setTextColor(b.color)
    // The full-screen Always step (15 Nov 2026): exactly while the banner would say "Set location to Always"
    val alwaysVisibility = if (b.kind == Kind.NEED_BACKGROUND) View.VISIBLE else View.GONE
    if (binding.alwaysPage.visibility != alwaysVisibility) {
      binding.alwaysPage.visibility = alwaysVisibility
      if (alwaysVisibility == View.VISIBLE) binding.alwaysTitle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
    // "No search area loaded yet…" above the bottom band: in the standing-by states, until the circles arrive
    val noticeVisibility = if (b.kind == Kind.STANDBY && wakeAreas.isEmpty()) View.VISIBLE else View.GONE
    if (binding.notice.visibility != noticeVisibility) binding.notice.visibility = noticeVisibility
    // Battery saver (field review 9 Nov 2026: Leonie Ch 55 fixes in 5 h, Heiner, Matt C, Lissa, Mitchell R, Paul, Tyrone,
    // David S, Marie — fd.lp 1 ⇒ thin, gappy tracks even with the app exempt from battery optimisation). It is the phone's
    // own setting, so the note says what to do and a tap opens the Battery saver page; it comes first, over the
    // notifications note. Since 19 Nov 2026 (no card) these are notices above the bottom band.
    batterySaverNote = b.kind != Kind.NOT_SET_UP && b.kind != Kind.OFF && isPowerSaveMode()
    everywhereNote = FolloEverywhere.serverOffNotice.value && b.kind != Kind.NOT_SET_UP
    if (everywhereNote) everywhereNoteShown()
    val note =
        when {
          everywhereNote -> getString(R.string.follo_everywhere_server_off)
          batterySaverNote -> getString(R.string.follo_battery_saver_note)
          b.kind != Kind.NOT_SET_UP && b.kind != Kind.OFF && !promptInFlight && !notificationPermissionRequester.hasPermission() ->
              getString(R.string.follo_notif_off_note)
          else -> ""
        }
    if (binding.note.text.toString() != note) binding.note.text = note
    val noteVisibility = if (note.isBlank()) View.GONE else View.VISIBLE
    if (binding.note.visibility != noteVisibility) binding.note.visibility = noteVisibility
    renderLink()
    renderTicks()
    renderCoords(b)
    pillKind = b.kind
    renderHeading()
    renderMenu()
  }

  /** Who this phone is, as the card said it (now behind a tap on the two-way symbol, and in the shared check). */
  private fun whoText(): String {
    val tid = viewModel.trackerId
    return when {
      !viewModel.setupCompleted.value -> getString(R.string.follo_brand)
      viewModel.displayName.isNotBlank() -> getString(R.string.follo_who, viewModel.displayName, tid)
      else -> getString(R.string.follo_tracker, tid)
    }
  }

  /** When it last sent, and what waits, as the card's second line said it. */
  private fun lastText(): String {
    val parts = mutableListOf<String>()
    val last = viewModel.lastSentAt.value
    parts +=
        when {
          last == 0L -> getString(R.string.follo_last_none)
          System.currentTimeMillis() - last < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.follo_last_sent_just_now)
          else ->
              getString(
                  R.string.follo_last_sent,
                  DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
              )
        }
    val queued = backlog()
    if (queued > 0) parts += getString(R.string.follo_waiting, queued)
    return parts.joinToString(" · ")
  }

  // ── The compass heading pill (Cam Allport 15 Nov 2026; spec §5 of NEXT_VERSION_SPEC_20261115) ─────────────
  private lateinit var headingDial: FolloCompassDial
  /** The banner's kind at the last render: no pill where no position may be used (or while Off / the Always step). */
  private var pillKind: Kind? = null
  private var pillQueued = false
  private var pillDrawnAt = 0L

  /** At most every [PILL_MIN_MS] (~5 a second); the compass calls on every turn past its 2° dead-band. */
  private fun schedulePill() {
    if (!::binding.isInitialized || pillQueued) return
    val wait = (pillDrawnAt + PILL_MIN_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    pillQueued = true
    binding.heading.postDelayed(
        {
          pillQueued = false
          renderHeading()
        },
        wait,
    )
  }

  /**
   * "112°  ESE": the way the top of the screen as drawn points, true north (magnetic until the first fix gives the
   * declination). Shown whenever the compass has a reading and a position may be used; none on a phone without a
   * compass. Views are touched only when the text, the dial's degree or the calibration changes.
   */
  private fun renderHeading() {
    if (!::binding.isInitialized || !::headingDial.isInitialized) return
    pillDrawnAt = SystemClock.elapsedRealtime()
    val h = compass.pillHeading()
    val kind = pillKind
    // only while the Compass button under "…" is on (19 Nov 2026)
    val show = compassOn && h != null && kind != null && kind !in NO_POSITION_KINDS && kind != Kind.NEED_BACKGROUND && !FolloPower.isOff && !clean && scopeOwn
    if (show && h != null) {
      val text = FolloRules.headingText(h)
      if (binding.heading.text.toString() != text) {
        binding.heading.text = text
        // the map may report from inside a layout pass, where a request for a new width is lost (13 Nov 2026)
        binding.heading.post { binding.heading.requestLayout() }
      }
      headingDial.heading = FolloRules.headingDegrees(h).toFloat()
      val poor = compass.poor()
      val color = android.graphics.Color.argb(if (poor) POOR_ALPHA else 255, 255, 255, 255)
      if (binding.heading.currentTextColor != color) {
        binding.heading.setTextColor(color)
        headingDial.alpha = if (poor) POOR_ALPHA else 255
        binding.heading.isClickable = poor
      }
      val cd = getString(if (poor) R.string.follo_heading_poor_cd else R.string.follo_heading_cd, text.replace("  ", " "))
      if (binding.heading.contentDescription != cd) binding.heading.contentDescription = cd
    }
    val visibility = if (show) View.VISIBLE else View.GONE
    if (binding.heading.visibility != visibility) {
      binding.heading.visibility = visibility
      binding.root.post { placeOverlays() }
    }
  }

  private fun showCalibrate() {
    MaterialAlertDialogBuilder(this).setMessage(R.string.follo_compass_calibrate).setPositiveButton(android.R.string.ok, null).show()
  }

  // ── The ticks at the bottom right and the phone's own position (Alex, 14 Nov 2026) ─────────────────

  /** The three ticks (Alex 15 Nov 2026 order): latest app, checked in with FOLLO today, offline map saved; a warning sign for any that isn't. */
  private fun renderTicks() {
    if (!::binding.isInitialized) return
    val now = System.currentTimeMillis()
    val st = viewModel.folloStatus.value
    // FP083 (Alex 22 Nov 2026): each tick has three states, good / partial (in progress) / something wrong
    val circles =
        FolloRules.circlesTick(
            st != null && FolloRules.checkedInToday(st.receivedAt, now, java.time.ZoneId.systemDefault()), heldWakeCircles())
    val off = folloOfflineRepo.state.value
    val saved =
        FolloRules.mapTick(
            off is org.owntracks.android.data.repos.FolloOfflineRepo.State.Saved,
            off is org.owntracks.android.data.repos.FolloOfflineRepo.State.Saving,
        )
    val latest =
        FolloRules.appTick(BuildConfig.VERSION_CODE.toLong(), st?.message?.latest?.android, st?.message?.latest?.aat, now / 1000)
    val shown = tickDemo ?: listOf(latest, circles, saved) // debug builds: `follo_tick_demo`
    fun tick(v: android.widget.TextView, t: FolloRules.Tick) {
      if (v.text.toString() != t.sign) v.text = t.sign
    }
    tick(binding.tickApp, shown[0])
    tick(binding.tickCircles, shown[1])
    tick(binding.tickMap, shown[2])
    fun word(t: FolloRules.Tick, ok: Int, part: Int, bad: Int) =
        getString(when (t) { FolloRules.Tick.OK -> ok; FolloRules.Tick.PART -> part; FolloRules.Tick.WARN -> bad })
    val words =
        listOf(
                word(shown[0], R.string.follo_tick_app_ok, R.string.follo_tick_app_part, R.string.follo_tick_app_bad),
                word(shown[1], R.string.follo_tick_circles_ok, R.string.follo_tick_circles_part, R.string.follo_tick_circles_bad),
                word(shown[2], R.string.follo_tick_map_ok, R.string.follo_tick_map_part, R.string.follo_tick_map_bad),
            )
            .joinToString(", ")
    binding.ticks.contentDescription = getString(R.string.follo_ticks_cd, words)
  }

  /** Debug builds only (`follo_tick_demo` = "ok,part,warn": app, circles, map): the ticks as told, for screenshots. */
  private var tickDemo: List<FolloRules.Tick>? = null

  /** How many wake-up circles the phone holds (the circles tick's "partial": circles on the phone, not checked in today). */
  private fun heldWakeCircles(): Int = lastWaypoints.count { FolloRules.isWakeCircle(it.description, it.geofenceRadius) }

  /** How old the screen's newest fix is (null = none). */
  private fun fixAgeMs(): Long? = viewModel.currentLocation.value?.let { System.currentTimeMillis() - it.time }

  /** What the arrow last showed: true = grey (the fix is stale), null = nothing yet. */
  private var shownStale: Boolean? = null

  private fun navArrowIcon(stale: Boolean) = if (stale) R.drawable.ic_follo_nav_arrow_stale else R.drawable.ic_follo_nav_arrow

  /**
   * A fix more than 5 minutes old is where the phone last was, not where it is (15 Nov 2026: FOLLO-L, indoors, showed a
   * 7-day-old Tasmanian fix as "your position", and a long press would have shared it): the arrow turns grey and the
   * position pill goes (renderCoords), both back as soon as a fresh fix comes.
   */
  private fun renderStale(): Boolean {
    val stale = FolloRules.fixIsStale(fixAgeMs())
    if (shownStale != stale) {
      shownStale = stale
      navArrow?.let {
        it.icon = ResourcesCompat.getDrawable(resources, navArrowIcon(stale), null)
        map.invalidate()
      }
    }
    return stale
  }

  /** The position pill: whenever the app has a fresh fix of its own, except while Off or while location can't be used. */
  private fun renderCoords(b: Banner) {
    val l = viewModel.currentLocation.value
    val stale = renderStale()
    val show = l != null && !stale && !FolloPower.isOff && b.kind !in NO_POSITION_KINDS && !clean && scopeOwn
    val visibility = if (show) View.VISIBLE else View.GONE
    if (show && l != null) {
      val text = FolloRules.coordsText(l.latitude, l.longitude)
      if (binding.coords.text.toString() != text) {
        binding.coords.text = text
        binding.coords.contentDescription = getString(R.string.follo_coords_cd, text)
        // the map may report from inside a layout pass, where a request for a new width is lost (13 Nov 2026)
        binding.coords.post { binding.coords.requestLayout() }
      }
    }
    if (binding.coords.visibility != visibility) {
      binding.coords.visibility = visibility
      binding.root.post { placeOverlays() }
    }
  }

  /** Press and hold the position: the phone's share sheet with "MGA55 526599 E, 5252393 N (GDA2020 lat, lon)". */
  private fun shareCoords() {
    val l = viewModel.currentLocation.value ?: return
    if (FolloRules.fixIsStale(fixAgeMs())) return // never share where the phone last was as where it is
    if (!scopeOwn) return // FP055: nor any position from outside the search area
    share(FolloRules.coordsShare(l.latitude, l.longitude), getString(R.string.follo_coords_share))
  }

  /** A tap on the ticks: the phone check to share; while the offline map waits for Wi-Fi, its question comes first. */
  private fun onTicksClicked() {
    val s = folloOfflineRepo.state.value as? org.owntracks.android.data.repos.FolloOfflineRepo.State.Waiting
    if (s == null || FolloPower.isOff) {
      shareCheck()
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.follo_offline_ask_title)
        .setMessage(getString(R.string.follo_offline_ask_msg, s.megabytes))
        .setPositiveButton(R.string.follo_offline_ask_yes) { _, _ -> folloOfflineRepo.allowMetered() }
        .setNegativeButton(R.string.follo_offline_ask_no, null)
        .setNeutralButton(R.string.follo_offline_ask_share) { _, _ -> shareCheck() }
        .show()
  }

  private fun shareCheck() {
    val st = viewModel.folloStatus.value
    val off = folloOfflineRepo.state.value
    val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val device = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    val version =
        if (BuildConfig.FOLLO_BUILD > 0) "${BuildConfig.VERSION_NAME} (${BuildConfig.FOLLO_BUILD})" else BuildConfig.VERSION_NAME
    val text =
        FolloRules.checkText(
            phone = whoText(),
            device = device,
            os = "Android ${Build.VERSION.RELEASE}",
            statusAtMs = st?.receivedAt ?: 0L,
            nowMs = System.currentTimeMillis(),
            zone = java.time.ZoneId.systemDefault(),
            map =
                FolloRules.MapCheck(
                    saved = off is org.owntracks.android.data.repos.FolloOfflineRepo.State.Saved,
                    savingPercent = (off as? org.owntracks.android.data.repos.FolloOfflineRepo.State.Saving)?.percent,
                    waitingForWifi = off is org.owntracks.android.data.repos.FolloOfflineRepo.State.Waiting,
                ),
            version = version,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            latestCode = st?.message?.latest?.android,
            latestVersion = st?.message?.latest?.androidv,
            circlesDay = wakeUpdate?.replace('-', '.'),
            compass = FolloRules.compassCheckLine(compass.pillHeading(), compass.isTrueNorth(), compass.accuracyDegrees()),
            circlesHeld = heldWakeCircles(),
            latestAtS = st?.message?.latest?.aat,
        )
    share(text, getString(R.string.follo_check_share))
  }

  private fun share(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
      startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
      Timber.w(e, "Nothing to share with")
    }
  }

  /** The recentre button: while the map is held where it was moved by hand, and there is a map. */
  private fun showRecentre() {
    if (!::binding.isInitialized) return
    val visibility = if (followPaused && !FolloPower.isOff && !clean && !scopeWaiting()) View.VISIBLE else View.GONE
    if (binding.recentre.visibility != visibility) {
      binding.recentre.visibility = visibility
      binding.root.post { placeOverlays() } // the drop-down sits right of it
    }
  }

  private var batterySaverNote = false

  // ── Offline map + search grids (10 Nov 2026) ─────────────────────────────────────────────

  /** The search grids, under everything else the app draws on the map. */
  private var gridOverlay: FolloGridOverlay? = null
  /** Base camp's drawings for this phone (12 Nov 2026): just above the search grids, under everything else. */
  private var drawOverlay: FolloDrawOverlay? = null

  /** The offline map's state changed: the second tick (13 Nov 2026 a sign on the card; 14 Nov the tick). */
  private fun renderOffline(@Suppress("UNUSED_PARAMETER") s: org.owntracks.android.data.repos.FolloOfflineRepo.State) {
    renderTicks()
  }

  private fun isPowerSaveMode(): Boolean =
      (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

  /** The note under the card: while it says Battery saver is on, a tap opens that setting. */
  private fun onNoteClicked() {
    if (everywhereNote) {
      FolloEverywhere.noticeDone() // dismissed
      return
    }
    if (!batterySaverNote) return
    try {
      settingsLauncher.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    } catch (e: Exception) {
      Toast.makeText(this, R.string.follo_battery_saver_howto, Toast.LENGTH_LONG).show()
    }
  }

  // ── Map ───────────────────────────────────────────────────────────────────────────────

  private fun setUpMap() {
    map = binding.map
    viewModel.tileState.value.let { (url, maxZoom) ->
      map.setTileProvider(FolloTileProvider(this, FolloTileSource.of(url, maxZoom))) // enlarges past the imagery's depth
    }
    map.overlayManager.tilesOverlay = ShiftedTilesOverlay(map.tileProvider, this) // Esri sits 1.5 m north
    map.setMultiTouchControls(true)
    // Any move, zoom or turn made by hand means the volunteer is looking somewhere else: stop following, and
    // leave the map there at that zoom, until the card is tapped (Alex 13 Nov 2026; until then a pinch kept
    // following, so zooming in on another spot threw the map back to the arrow). That is a one-finger drag, a
    // pinch that moved or zoomed the map, a twist, or the double tap that zooms in; a plain tap holds nothing.
    // The decision is made when the fingers lift (FolloRules.holdsMap).
    // A two-finger twist (Alex 24 Oct 2026: "rotate the map using 2 fingers, just like Google Maps") turns the
    // map once it passes ROTATE_START_DEG, so a pinch is not turned by accident, and holds it turned like a drag
    // does. It turns about the fingers: osmdroid's pinch keeps the point under them fixed whatever the turn.
    // While any finger is down the camera leaves the map alone.
    var downX = 0f
    var downY = 0f
    var dragged = false
    var lastAngle = 0f
    var twist = 0f
    var rotating = false
    var rotated = false
    var rebase = false
    var pinched = false
    var pinchStart = false
    var span0 = 0f
    var focusX0 = 0f
    var focusY0 = 0f
    var doubleTap = false
    var lastUpAt = 0L
    var lastUpX = 0f
    var lastUpY = 0f
    val doubleTapMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
    val doubleTapSlop = android.view.ViewConfiguration.get(this).scaledDoubleTapSlop
    fun fingerAngle(e: android.view.MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    map.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          downX = e.x
          downY = e.y
          dragged = false
          rotating = false
          rotated = false
          pinched = false
          twist = 0f
          // the second tap of a double tap (osmdroid zooms in on it): same test as the system's gesture detector
          doubleTap = e.eventTime - lastUpAt <= doubleTapMs && hypot(e.x - lastUpX, e.y - lastUpY) <= doubleTapSlop
          gestureActive = true
        }
        android.view.MotionEvent.ACTION_POINTER_DOWN -> {
          rebase = true
          pinchStart = true
        }
        // the finger pair changes: measure the twist afresh from the next move
        android.view.MotionEvent.ACTION_POINTER_UP -> rebase = true
        android.view.MotionEvent.ACTION_MOVE ->
            if (e.pointerCount == 1) {
              if (hypot(e.x - downX, e.y - downY) > slop) dragged = true
            } else {
              // two fingers that only rest on the map change nothing; past the slop they have moved or zoomed it
              val span = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
              val focusX = (e.getX(0) + e.getX(1)) / 2f
              val focusY = (e.getY(0) + e.getY(1)) / 2f
              if (pinchStart) {
                span0 = span
                focusX0 = focusX
                focusY0 = focusY
                pinchStart = false
              } else if (abs(span - span0) > slop || hypot(focusX - focusX0, focusY - focusY0) > slop) pinched = true
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
          lastUpAt = e.eventTime
          lastUpX = e.x
          lastUpY = e.y
          if (FolloRules.holdsMap(dragged, pinched, rotated, doubleTap)) {
            followPaused = true
            watchFollowing = false // the map stays where it was moved, also when it was on another phone
          }
          if (camera == Camera.NAV) requestNavFrame() // the arrow turned to suit the held map, or back on its spot
        }
      }
      false
    }
    // While following, a zoom the app makes puts the arrow back on its spot for the new scale. The credit follows
    // the imagery at the middle of the map (5 Nov 2026).
    map.addMapListener(
        object : org.osmdroid.events.MapListener {
          override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
            scopeClamp()
            showCredit()
            if (!scopeActive && viewArea() != menuArea) binding.root.post { renderMenu() } // an exempt phone looks anywhere
            return false
          }

          override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
            if (camera == Camera.NAV && !followPaused) requestNavFrame()
            scopeClamp()
            showCredit()
            return false
          }
        }
    )
    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    map.isTilesScaledToDpi = true
    map.minZoomLevel = 3.0
    map.maxZoomLevel = MAP_MAX_ZOOM.toDouble()
    // The search grids (10 Nov 2026): first in the list, so the track, the circles, teammates and the phone's own
    // arrow are all drawn over them (the track and the circle lines are placed after it, see aboveAreaAndTrack)
    gridOverlay =
        FolloGridOverlay(resources.displayMetrics.density, resources.displayMetrics.density * resources.configuration.fontScale).also {
          it.grid = folloGridRepo.grid.value
          it.ownTeam = viewModel.folloStatus.value?.message?.team
          map.overlays.add(0, it)
        }
    // Base camp's drawings (12 Nov 2026): straight after the grids, so they lie over them and under the track, the
    // circles, teammates, markers and the phone's own arrow
    drawOverlay =
        FolloDrawOverlay(resources.displayMetrics.density, resources.displayMetrics.density * resources.configuration.fontScale).also {
          map.overlays.add(1, it)
        }
    showDrawings(folloDrawRepo.draw.value)
    // Until the first fix: the phone's own fix if there is one, else the last position Android already holds (read from
    // its cache, no new fix taken, never sent: only the map uses it) at walking scale, else the Australia overview
    // (9 Nov 2026, Greg G: the app opened on a George Town street, grey where the imagery stops, before his first fix)
    val known = viewModel.currentLocation.value ?: if (FolloPower.isOff(this)) null else lastKnownForMap()
    known?.let { folloOfflineRepo.notePosition(it.latitude, it.longitude) } // which areas are near enough to save (phone only)
    // FP055: where search areas only are shown, the map never opens on the phone's own position (scopeUpdate places it)
    if (known != null && !scopeOn()) {
      map.controller.setZoom(START_ZOOM) // walking scale
      map.controller.setCenter(known.toGeoPoint())
    } else {
      overviewUntilFix = true
      map.controller.setZoom(OVERVIEW_ZOOM)
      map.controller.setCenter(GeoPoint(-28.0, 134.0))
    }

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
    showCredit()
  }

  /**
   * The current imagery's credit under the card (was osmdroid's CopyrightOverlay in the map's corner). With imagery
   * chosen per tile it is the imagery at the middle of the map (Alex, 5 Nov 2026), so it is checked on every scroll and
   * zoom; the text is only touched when it changes.
   */
  private fun showCredit() {
    if (FolloPower.isOff || scopeCovered) return // no map, no credit
    val source = map.tileProvider.tileSource
    val text =
        if (hillshadeShown()) getString(R.string.follo_credit_hillshade)
        else if (source is FolloTileSource) {
          val c = map.mapCenter
          source.creditAt(c.latitude, c.longitude, org.osmdroid.util.TileSystem.getInputTileZoomLevel(map.zoomLevelDouble))
        } else source.copyrightNotice.orEmpty()
    if (binding.credit.text.toString() != text) {
      binding.credit.text = text
      // The map reports its first scroll from inside a layout pass, where the pill's request for a new width is lost
      // (seen on the Samsung, 13 Nov 2026: the Tasmanian credit cut short at the width of the Esri one): ask again after
      binding.credit.post { binding.credit.requestLayout() }
    }
    val visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    if (binding.credit.visibility != visibility) binding.credit.visibility = visibility
  }

  private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

  /** The map shows the Australia overview (no position known yet); the first fix zooms in to walking scale. */
  private var overviewUntilFix = false

  /** The newest position Android already has from any provider, without asking for a new one; null without permission. */
  @android.annotation.SuppressLint("MissingPermission")
  private fun lastKnownForMap(): Location? =
      runCatching {
            val lm = getSystemService(android.location.LocationManager::class.java)
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
          }
          .getOrNull()

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
    NAV, // like Google Maps after Start: arrow at the middle of the whole screen (FP033, 16 Nov 2026), arrow and map glide, map turns
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
    if (!scopeFollows()) return // FP055: outside a search area the map is on an area, never on the phone
    if (overviewUntilFix) {
      // First fix after the overview: down to walking scale over it, following
      overviewUntilFix = false
      followPaused = false
      camRef = null
      map.controller.setZoom(START_ZOOM)
      map.controller.setCenter(l.toGeoPoint())
    }
    if (followPaused || gestureActive) {
      // The map stays where the volunteer dragged it, but the arrow keeps showing where they are
      if (camera == Camera.NAV) {
        navTo = l.toGeoPoint()
        navToT = l.time
        navGlideMs = 0L
        navArrow?.let { it.position = l.toGeoPoint() }
        shownT = l.time
        releaseTrack()
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
      if (ref.distanceTo(l) < gate) {
        // The arrow stays put (wobble, or back where it was after a walk with the screen off): it is as good as at this
        // fix, so the track points up to now are drawn, not held for the next move (5 Nov 2026, as iOS). A glide still
        // running draws them as it ends.
        if (navGlideMs <= 0L || SystemClock.elapsedRealtime() >= navGlideStart + navGlideMs) {
          shownT = maxOf(shownT, l.time)
          navToT = shownT
          releaseTrack()
          map.invalidate()
        }
        return
      }
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
        navFromT = if (ref == null) l.time else navTimeAt(nowR)
        navToT = l.time
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
    return headingUpCentreAt(p, v.centerX(), v.top + down * v.height(), up)
  }

  /**
   * Where the navigation arrow sits, in map-view pixels (Alex 15 Nov 2026, FP033): across, as before (upright the
   * middle, sideways halfway between the recentre and rotate buttons); down, the middle of the WHOLE screen, whatever
   * covers the map (banner, card, footer, status bar).
   */
  private fun navSpot(): android.graphics.PointF {
    val v = visible()
    val at = IntArray(2)
    map.getLocationInWindow(at)
    val screenH = window.decorView.height.toFloat().takeIf { it > 0f } ?: binding.root.height.toFloat()
    return android.graphics.PointF(v.centerX(), FolloRules.arrowSpotY(screenH, at[1].toFloat(), map.height.toFloat()))
  }

  /** Map centre for a heading-up map with the fix at ([sx], [sy]) in map-view pixels. */
  private fun headingUpCentreAt(p: GeoPoint, sx: Float, sy: Float, up: Float): GeoPoint {
    val ox = sx - map.width / 2f // dot right of the view centre by ox
    val oy = sy - map.height / 2f // dot below the view centre by oy
    val pxPerM = map.projection.metersToPixels(1f)
    return p.destinationPoint((oy / pxPerM).toDouble(), up.toDouble())
        .destinationPoint((ox / pxPerM).toDouble(), (up - 90f).toDouble())
  }

  /**
   * The part of the map the arrow is kept in, in map-view pixels. Upright: between the banner and the card, across the
   * whole width. Sideways (14 Nov 2026): from under the status bar down to the bottom band, its middle halfway between
   * the recentre button and the rotate button (both centres), so the arrow sits in the open map left of the panels.
   */
  private fun visible(): android.graphics.RectF {
    return if (isLand()) {
      val top = (barsTop - map.top).toFloat().coerceAtLeast(0f)
      val bottom = (binding.creditBar.top - map.top).toFloat().takeIf { it > top + 1 } ?: map.height.toFloat()
      val half = dp(20f)
      val recentreX = recentreStart() + half - map.left
      val rotateX = map.width - moreEnd() - half
      val mid = ((recentreX + rotateX) / 2f).coerceIn(1f, map.width.toFloat())
      android.graphics.RectF(0f, top, 2 * mid, bottom)
    } else {
      val top = (binding.banner.bottom - map.top).coerceAtLeast(0).toFloat()
      // down to the first notice when one shows, else to the bottom band (19 Nov 2026: no card)
      val band = (binding.creditBar.top - map.top).toFloat().takeIf { it > top + 1 } ?: map.height.toFloat()
      val first = if (clean) null else firstNotice()
      val bottom = first?.let { (binding.bottom.top + it.top - map.top).toFloat().takeIf { y -> y > top } } ?: band
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
                isEnabled = ownShown()
                icon = ResourcesCompat.getDrawable(resources, navArrowIcon(shownStale == true), null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
              }
      navArrow?.let { if (it !in map.overlays) map.overlays.add(it) }
      scaleBar?.let { map.overlays.remove(it) }
      // Alex 21 Oct 2026: no tilt. A flat, top-down map that turns with you; the arrow's spot is
      // the middle of the whole screen since 16 Nov 2026 (FP033, navSpot), the same on iOS.
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
    releaseTrack() // without the arrow the whole track is drawn
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
  // Alex 21 Oct 2026: the map turns with the COMPASS (the way the phone points), the arrow stays pointing up (since
  // 16 Nov 2026 at the middle of the whole screen, navSpot; was 80 % down the visible map), centred sideways. Without a compass reading the walking direction turns it instead.
  private var navFrom: GeoPoint? = null
  private var navTo: GeoPoint? = null
  private var navGlideStart = 0L
  private var navGlideMs = 0L
  /**
   * Fix times (ms) at the two ends of the glide, and the time the arrow is at — in between while it glides; 0 = no fix
   * yet. The walking track is drawn only up to [shownT], then on to the arrow (Alex, 5 Nov 2026: "it draws the tracklog
   * in front of the arrow and the arrow catches up"). The same as iOS.
   */
  private var navFromT = 0L
  private var navToT = 0L
  private var shownT = 0L
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

  /** The fix time at the arrow's place along the current glide (see [navPosition]). */
  private fun navTimeAt(now: Long): Long {
    if (navGlideMs <= 0L || navFromT == 0L) return navToT
    val f = ((now - navGlideStart).toFloat() / navGlideMs).coerceIn(0f, 1f)
    return navFromT + ((navToT - navFromT) * f).toLong()
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
    if (!followPaused && !gestureActive && scopeFollows()) {
      val pos = navPosition(now)
      if (pos != null) {
        upBearing = shownHeading
        map.mapOrientation = -shownHeading
        val spot = navSpot() // FP033: the middle of the whole screen, top to bottom
        map.controller.setCenter(headingUpCentreAt(pos, spot.x, spot.y, shownHeading))
        arrow?.position = pos
        arrow?.rotation = 0f
        shownT = navTimeAt(now)
        releaseTrack() // the track points the arrow has passed, and the line on to it
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
  /**
   * True once the volunteer moves, zooms or turns the map themselves; the recentre button (or a tap on the banner)
   * brings following back. While it is true the recentre button shows at the top left (14 Nov 2026).
   */
  private var followPaused = false
    set(value) {
      if (field == value) return
      field = value
      showRecentre()
    }
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

  /** The recentre button or a banner tap: follow again, heading-up, at walking scale, with the arrow back on its spot. */
  private fun recentre() {
    if (FolloPower.isOff) return // no map to recentre
    if (watchRecentre()) return // the map is on another phone or a search (FolloWatch): back to that
    if (scopeRecentre()) return // FP055: not in a search area = back to the framed view of the last one
    followPaused = false
    if (camera == Camera.NAV) requestNavFrame() // the compass turns the map back even before the next fix
    val l = viewModel.currentLocation.value ?: return
    if (!::map.isInitialized || map.width == 0) return
    // the zoom goes back too: whatever was pinched while looking elsewhere is not the walking view (13 Nov 2026)
    if (map.zoomLevelDouble != START_ZOOM) map.controller.setZoom(START_ZOOM)
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

  /** The area's fill polygon and its edge lines (tracks go on top of both). */
  private fun isAreaLayer(o: org.osmdroid.views.overlay.Overlay) =
      (o is Polygon && o.id == AREA_ID) || (o is Polyline && o.id == AREA_EDGE_ID)

  private fun drawAreaBox(status: org.owntracks.android.model.messages.MessageFollo?) {
    if (!::map.isInitialized) return
    val key = (status?.poly?.toString() ?: status?.box)?.takeIf { status?.ends?.let { System.currentTimeMillis() / 1000 >= it } != true }
    if (key == drawnBox) return // the same box arrives with every reply; redrawing it each time flickers
    drawnBox = key
    map.overlays.removeAll { isAreaLayer(it) || (it is Marker && it.id == AREA_ID + LABEL_SUFFIX) }
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
            // 3 Nov 2026 (Alex): the edge is drawn by the lines below, not the polygon's own outline, which drew the
            // zero-width links between separate groups of circles as stray lines (FolloRingEdges)
            outlinePaint.color = Color.TRANSPARENT
            outlinePaint.strokeWidth = 0f
            infoWindow = null
          },
      )
      val edges = ring?.let { org.owntracks.android.model.FolloRingEdges.pieces(it) }?.map { run -> run.map { (la, lo) -> GeoPoint(la, lo) } } ?: listOf(pts)
      edges.forEachIndexed { k, run ->
        map.overlays.add(
            1 + k,
            Polyline(map).apply {
              id = AREA_EDGE_ID
              setPoints(run)
              outlinePaint.color = getColor(R.color.follo_area)
              outlinePaint.strokeWidth = resources.displayMetrics.density * 4
              outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND   // runs meet end to end where a link was
              outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
              infoWindow = null
            },
        )
      }
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
      map.overlays.indexOfLast { isAreaLayer(it) || it === gridOverlay || it === drawOverlay || (it is Polyline && it.id == TRACK_ID) } + 1

  private var drawnTrackSerial = -1
  /** The segment being walked right now; new points are appended to it. */
  private var trackTail: Polyline? = null
  /** The newest track point drawn. */
  private var trackLastDrawn: org.owntracks.android.data.repos.FolloTrackRepo.Point? = null
  /**
   * Track points the arrow has not reached yet, oldest first, each with whether it starts a new segment; drawn as the
   * arrow passes them ([releaseTrack]). Meanwhile [trackToArrow] runs from the newest drawn point to the arrow.
   */
  private val trackHeld = ArrayDeque<Pair<org.owntracks.android.data.repos.FolloTrackRepo.Point, Boolean>>()
  /** The track's last stretch, from its newest drawn point to the arrow, moved every frame of the glide (5 Nov 2026). */
  private var trackToArrow: Polyline? = null

  private fun trackLine(points: List<GeoPoint>) =
      Polyline(map).apply {
        id = TRACK_ID
        infoWindow = null
        isEnabled = ownShown() // hidden in the clean view (18 Nov 2026), and wherever FP055 shows nothing of this phone
        setPoints(points)
        outlinePaint.color = getColor(R.color.follo_track)
        outlinePaint.strokeWidth = resources.displayMetrics.density * 4
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
      }

  /**
   * Walking-only (FP079, 22 Nov 2026; rules FolloWalk, the live map's of 11 Nov): the track's points go through this one
   * at a time; only pieces that are walking are drawn (driving, GPS spikes and the straight line after a silent gap are
   * not). Display only: every point stays in FolloTrackRepo.
   */
  private var trackWalker = FolloWalk.Walker()

  private fun trackFix(p: org.owntracks.android.data.repos.FolloTrackRepo.Point) = FolloWalk.Fix(p.t / 1000.0, p.lat, p.lon)

  private fun trackAt(): Int = map.overlays.indexOfLast { isAreaLayer(it) || it === gridOverlay || it === drawOverlay } + 1

  private fun trackLineOf(points: List<FolloWalk.Fix>) = trackLine(points.map { GeoPoint(it.lat, it.lon) })

  /**
   * The newest piece of the track on the map exactly while the rule says it is drawn: there once it has proved itself
   * (3 fixes over 15 s), gone again when it is under a minute long and its last fix is over a minute old. True when
   * the map changed.
   */
  private fun syncTrackTail(): Boolean {
    if (!::map.isInitialized) return false
    val open = trackWalker.open
    val want = open != null && open.drawnOpen(System.currentTimeMillis() / 1000.0)
    val tail = trackTail
    if (want && tail == null) {
      trackTail = trackLineOf(open!!.points).also { map.overlays.add(trackAt(), it) }
      return true
    }
    if (!want && tail != null) {
      map.overlays.remove(tail)
      trackTail = null
      drawTrackToArrow()
      return true
    }
    return false
  }

  /**
   * The phone's own walking track: magenta like QField tracklogs, above the area, below the pair line. Each fix is
   * appended to the current line; the whole track is rebuilt only on load, clear, trim or after missed updates
   * (review 21 Oct 2026: a full rebuild per fix stuttered after hours of walking). A point newer than where the arrow
   * has glided to waits in [trackHeld] until the arrow passes it (5 Nov 2026), so the line never runs ahead of it.
   * Since 22 Nov 2026 only the walking is drawn ([trackWalker]).
   */
  private fun drawTrack(u: org.owntracks.android.data.repos.FolloTrackRepo.Update) {
    if (!::map.isInitialized || u.serial == drawnTrackSerial) return
    val incremental =
        !u.reset && u.last != null && u.serial == drawnTrackSerial + 1 && (trackLastDrawn != null || trackHeld.isNotEmpty())
    drawnTrackSerial = u.serial
    if (incremental) {
      trackHeld.addLast(u.last!! to u.newSegment)
      releaseTrack()
      map.invalidate()
      return
    }
    map.overlays.removeAll { it is Polyline && it.id == TRACK_ID }
    trackTail = null
    trackToArrow = null
    trackHeld.clear()
    trackLastDrawn = null
    trackWalker = FolloWalk.Walker()
    val at = trackAt()
    val all = viewModel.trackSnapshot()
    // Only up to where the arrow is; the rest waits for it
    val reached = trackReachedT()
    val ended = mutableListOf<List<FolloWalk.Fix>>()
    val d = FloatArray(1)
    var held = false
    for (i in all.indices) {
      val q = all.getOrNull(i - 1)
      val p = all[i]
      val newSegment =
          q != null &&
              org.owntracks.android.data.repos.FolloTrackRepo.isBreak(
                  p.t - q.t, d.also { Location.distanceBetween(q.lat, q.lon, p.lat, p.lon, it) }[0])
      if (!held && p.t <= reached) {
        trackWalker.add(trackFix(p), newSegment).ended?.let { if (it.drawnEnded) ended += it.points }
        trackLastDrawn = p
      } else {
        held = true
        trackHeld.addLast(p to newSegment)
      }
    }
    ended.reversed().forEach { piece -> map.overlays.add(at, trackLineOf(piece)) }
    syncTrackTail()
    drawTrackToArrow()
    map.invalidate()
  }

  /** The fix time the drawn track may reach: where the arrow is while it is on the map, else everything. */
  private fun trackReachedT(): Long = if (navOn && shownT > 0L) shownT else Long.MAX_VALUE

  /** Draws the held track points the arrow has reached, then moves the line from the track's end to the arrow. */
  private fun releaseTrack() {
    if (!::map.isInitialized) return
    val reached = trackReachedT()
    while (trackHeld.isNotEmpty() && trackHeld.first().first.t <= reached) {
      val (lp, newSegment) = trackHeld.removeFirst()
      val step = trackWalker.add(trackFix(lp), newSegment)
      if (step.skipped) continue
      if (step.joined) {
        trackTail?.addPoint(GeoPoint(lp.lat, lp.lon)) // not on the map yet: syncTrackTail puts the whole piece there
      } else {
        // the piece before this fix is over: it stays if it was a walk of a minute or more, else it comes off again
        val was = step.ended
        val tail = trackTail
        if (was != null && was.drawnEnded) {
          if (tail == null) map.overlays.add(trackAt(), trackLineOf(was.points))
        } else if (tail != null) map.overlays.remove(tail)
        trackTail = null
      }
      trackLastDrawn = lp
    }
    syncTrackTail()
    drawTrackToArrow()
  }

  /**
   * The line from the newest drawn track point to the arrow, while the arrow heads for a point not drawn yet. None
   * across a break in the track (the held point starts a new segment, or would not join the walk) or when there is no
   * arrow, or while the newest piece is not on the map.
   */
  private fun drawTrackToArrow() {
    val last = trackLastDrawn
    val held = trackHeld.firstOrNull()
    val arrow = navArrow?.position?.takeIf { navOn && shownT > 0L && it.latitude != 0.0 }
    if (last == null || held == null || held.second || arrow == null || trackTail == null || !trackWalker.wouldJoin(trackFix(held.first))) {
      trackToArrow?.let { map.overlays.remove(it) }
      trackToArrow = null
      return
    }
    val ends = listOf(GeoPoint(last.lat, last.lon), GeoPoint(arrow.latitude, arrow.longitude))
    val line = trackToArrow
    if (line != null) line.setPoints(ends)
    else {
      trackToArrow = trackLine(ends).also { map.overlays.add(trackAt(), it) }
    }
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

  /**
   * Ghost dots (FP079, a coordinator 21 Nov 2026): a team-mate whose newest fix is 30 s old or more and lies within 30 m of the
   * edge of the held circles has LEFT the search (phones post only inside, so nothing replaces that fix): not drawn,
   * no pair line to them, until their next fix.
   */
  private fun hasLeft(contact: Contact): Boolean {
    val at = contact.latLng ?: return false
    return FolloRules.hasLeft(
        System.currentTimeMillis() / 1000 - contact.locationTimestamp,
        FolloRules.dotDepthM(at.latitude.value, at.longitude.value, null, scopeAreas),
    )
  }

  /** On the map: heard from in the last 10 minutes and not left. */
  private fun isShown(contact: Contact): Boolean = isFresh(contact) && !hasLeft(contact)

  /** This phone's team-mates as dots (FP078), and the phones in search circles on a phone that may see them. */
  private var contactDots: List<FolloDots.Dot> = emptyList()
  private var contactUsers: List<String> = emptyList()
  private var watchDots: List<FolloDots.Dot> = emptyList()
  private var watchUsers: Set<String> = emptySet()

  /** Both kinds of dot to the top overlay; a person in both lists is drawn once (as the phones-in-circles list has them). */
  private fun pushDots() {
    val top = watchTopOverlay ?: return
    top.dots = watchDots + contactDots.filterIndexed { i, _ -> contactUsers.getOrNull(i) !in watchUsers }
    map.invalidate()
  }

  /**
   * The team's dots (the server's `show` list: a leader sees the whole team, a member the leaders); anyone else, anyone
   * not heard from for 10 minutes and anyone who has left is not on the map. Called on every change and every 15 s.
   */
  private fun syncContactMarkers() {
    if (!::map.isInitialized) return
    val shown = shownTopics().mapNotNull { viewModel.allContacts[it] }.filter(::isShown)
    val dots =
        shown.map { c ->
          FolloDots.Dot(c.latLng!!.latitude.value, c.latLng!!.longitude.value, c.trackerId, FolloRules.dotColour(c.teamColour), c.vest)
        }
    if (dots == contactDots) return
    contactDots = dots
    // "owntracks/<user>/phone": the user, to tell a team-mate who is also in the phones-in-circles list
    contactUsers = shown.map { c -> c.id.split('/').getOrElse(1) { c.id } }
    pushDots()
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
        viewModel.allContacts[ld.topic!!]?.takeIf(::isShown)?.latLng?.let { GeoPoint(it.latitude.value, it.longitude.value) }
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

  /**
   * Base camp's drawings for this phone (12 Nov 2026, docs/DRAW_20261112.md §3): lines, areas, circles and labels in
   * their own layer ([FolloDrawOverlay]); a pin is a marker exactly like a live-map marker ([addRegion]). Display only.
   */
  private fun showDrawings(draw: FolloDraw?) {
    if (!::map.isInitialized) return
    val layer = drawOverlay ?: return
    layer.show(draw, map)
    map.overlays.removeAll { it is Marker && it.id.startsWith(DRAW_PREFIX) }
    for (d in draw?.items.orEmpty()) {
      if (d.kind != FolloDraw.Kind.PIN) continue
      val point = GeoPoint(d.pts[0], d.pts[1])
      map.overlays.add(
          Marker(map).apply {
            id = DRAW_PREFIX + d.id
            position = point
            icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_follo_pin, null)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
          },
      )
      if (d.text != null)
          map.overlays.add(
              Marker(map).apply {
                id = DRAW_PREFIX + d.id + LABEL_SUFFIX
                position = point
                setTextIcon(d.text)
                setTextLabelBackgroundColor(Color.argb(184, 0, 0, 0))
                setTextLabelForegroundColor(Color.WHITE)
                setTextLabelFontSize((12 * resources.displayMetrics.scaledDensity).roundToInt())
                setAnchor(Marker.ANCHOR_CENTER, -0.15f)
                infoWindow = null
              },
          )
    }
    keepOwnOnTop()
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

  // ── Search areas only (FP055, Alex 19 Nov 2026; rules in FolloScope, contract docs/FP055_SPEC_20261119.md) ────────────
  // "It needs to only work within the search area, and must not give the false impression that it does anything
  // outside. Loophole to fix: 'you can see my house', also in screenshots people send to show it's working."
  // LIVE (inside a held search area): as before, under the grey mask and within the area's limits. LAST (not in one,
  // but has been to one): that area's map, north up, nothing of this phone on it. WAITING (been to none): no map.
  // An exempt phone (status `all: 1`) is untouched unless its "Volunteer view" is on. No location request is added.

  private var maskOverlay: FolloMaskOverlay? = null
  /** The held circles by search area (a Basecamp circle with its nearest area). */
  private var scopeAreas: Map<String, List<FolloRules.Circle>> = emptyMap()
  /** The held circles have been read once (until then the map stays covered). */
  private var scopeLoaded = false
  /** FP055 applies to this phone right now (set up, not Off, not exempt or in Volunteer view). */
  private var scopeActive = false
  /** What the screen is on; null while FP055 does not apply, or before the circles are read. */
  private var scopeState: FolloScope.State? = null
  /** Whether anything of this phone's own position may be on the screen (arrow, track, coordinates, heading). */
  private var scopeOwn = true
  /** The map is hidden behind the grey panel: WAITING, or not yet known what to show. */
  private var scopeCovered = false
  /** What the mask, the frame and the limits were last set from. */
  private var scopeKey = "-"
  /** Frame(V): the box the map is kept to; null = no limits. */
  private var scopeFrame: FolloScope.Box? = null
  private var scopeClamping = false
  /** The visible map the limits were last worked out for. */
  private var scopeLaidOutFor = ""
  /** Debug builds only: made-up circles for the screen alone (handleIntent `follo_scope_demo`). */
  private var scopeDemo: List<WaypointModel> = emptyList()
  private val scopeRecheck = Runnable { scopeUpdate() }

  /** Whether FP055 applies: set up, not Off, and not an exempt phone with Volunteer view off. */
  private fun scopeOn(): Boolean {
    if (!preferences.setupCompleted || FolloPower.isOff(this)) return false
    // "Share everywhere I go" (Alex 22 Nov 2026: "This turns off the greying out"): as an exempt phone's "My location"
    if (FolloEverywhere.isOn) return false
    val all = if (folloWatchRepo.demo) 1 else viewModel.folloStatus.value?.message?.all
    return FolloScope.applies(all, folloScopeRepo.volunteerView)
  }

  /** The camera may follow this phone: FP055 does not apply, or the phone is in a search area. */
  private fun scopeFollows(): Boolean = !scopeActive || scopeState?.view?.mode == FolloScope.Mode.LIVE

  /** No map at all (WAITING, or still working out what to show). */
  private fun scopeWaiting(): Boolean = scopeCovered

  /** This phone's own arrow and track: hidden in the clean view, and wherever FP055 shows nothing of this phone. */
  private fun ownShown(): Boolean = !clean && scopeOwn

  private fun showOwn() {
    if (!::map.isInitialized) return
    val shown = ownShown()
    navArrow?.isEnabled = shown
    myLocationOverlay?.isEnabled = shown
    map.overlays.forEach { if (it is Polyline && it.id == TRACK_ID) it.isEnabled = shown }
    map.invalidate()
  }

  /** onCreate: the mask goes above every line layer (they are all added below it by index) and under the pins. */
  private fun buildScope() {
    maskOverlay =
        FolloMaskOverlay(resources.displayMetrics.density, getColor(R.color.follo_area)).also { m ->
          val at = map.overlays.indexOfFirst { it is Marker || it === watchTopOverlay || it === myLocationOverlay || it is ScaleBarOverlay }
          map.overlays.add(if (at >= 0) at else map.overlays.size, m)
        }
    scopeUpdate()
  }

  /** The held circles changed (or were read for the first time). */
  private fun scopeCircles(all: List<WaypointModel>) {
    scopeAreas =
        FolloScope.areas(
            all.filter { FolloRules.isWakeCircle(it.description, it.geofenceRadius) }.map {
              FolloRules.Circle(it.geofenceLatitude.value, it.geofenceLongitude.value, it.geofenceRadius.toDouble(), FolloRules.wakeArea(it.description).orEmpty())
            }
        )
    scopeLoaded = true
    scopeUpdate()
  }

  /**
   * Which view (spec §3): on every fix, every change of the circles, every status element, coming to the front, the
   * power button, and when the flap guard's 10 s are up. Uses only the fix the screen already has.
   */
  private fun scopeUpdate() {
    if (!::map.isInitialized || !::binding.isInitialized || maskOverlay == null) return
    binding.root.removeCallbacks(scopeRecheck)
    val active = scopeOn()
    var state: FolloScope.State? = null
    var own = !active
    if (active && scopeLoaded) {
      val l = viewModel.currentLocation.value?.takeIf { !FolloRules.fixIsStale(System.currentTimeMillis() - it.time) }
      // a fresh fix too rough to say where the phone is (the app's own 100 m rule) decides nothing either way
      val rough = l != null && !(l.hasAccuracy() && l.accuracy < CHECK_IN_MAX_ACC_M)
      val prev = scopeState.takeIf { scopeActive }
      if (rough && prev?.view?.mode == FolloScope.Mode.LIVE && prev.view.area in scopeAreas) {
        state = prev
        own = scopeOwn
      } else {
        val fix = l?.takeIf { !rough }?.let { FolloScope.Fix(it.latitude, it.longitude) }
        fix?.let { folloScopeRepo.note(it.lat, it.lon, scopeAreas) } // been to: kept on the phone, never sent
        val now = SystemClock.elapsedRealtime()
        var st = FolloScope.step(prev, scopeAreas, folloScopeRepo.been.value, fix, now)
        // A search chosen in the drop-down (19 Nov 2026, spec §7): its LAST view, until a fix inside a held area
        if (st.view.mode == FolloScope.Mode.LIVE) scopeChosen = null
        else scopeChosen?.let { ch -> if (ch in scopeAreas) st = FolloScope.State(FolloScope.View(FolloScope.Mode.LAST, ch)) else scopeChosen = null }
        state = st
        own = FolloScope.showsOwn(st, scopeAreas, fix)
        st.outsideSinceMs?.let { binding.root.postDelayed(scopeRecheck, (it + FolloScope.LEAVE_AFTER_MS - now).coerceAtLeast(50L)) }
      }
    }
    scopeApply(active, state, own)
  }

  private fun scopeApply(active: Boolean, state: FolloScope.State?, own: Boolean) {
    val was = scopeState?.view.takeIf { scopeActive }
    val wasActive = scopeActive
    scopeActive = active
    scopeState = state
    val view = state?.view
    val circles = view?.area?.let { scopeAreas[it] }.orEmpty()
    val covered = active && (view == null || view.mode == FolloScope.Mode.WAITING || circles.isEmpty())
    val key = if (!active) "" else "${view?.mode}:${view?.area}:" + circles.joinToString(";") { "${it.lat},${it.lon},${it.r}" }
    if (key != scopeKey) {
      scopeKey = key
      scopeCovered = covered
      maskOverlay?.rings =
          if (!active || covered) emptyList()
          else circles.map { c -> FolloScope.ring(c, FolloScope.ringPoints(c.r)).flatMap { listOf(it.first, it.second) }.toDoubleArray() }
      scopeFrame = if (!active || covered) null else FolloScope.bbox(circles)?.let(FolloScope::frame)
      // WAITING: no map at all (hidden, so no tiles are asked for), only the words; they come once it is known that
      // the phone has been to none of its areas (before the circles are read the panel is bare)
      binding.waitCover.visibility = if (covered) View.VISIBLE else View.GONE
      binding.waitText.visibility = if (covered && view != null) View.VISIBLE else View.INVISIBLE
      if (!FolloPower.isOff) map.visibility = if (covered) View.INVISIBLE else View.VISIBLE
      if (covered) binding.credit.visibility = View.INVISIBLE else showCredit()
      renderMenu()
      showRecentre()
      scopeLaidOutFor = ""
      scopeLimits()
      when {
        !active -> {
          // FP055 no longer applies (Volunteer view switched off, or the server now names this phone): as before
          if (wasActive && !FolloPower.isOff && watchTarget == null) {
            followPaused = false
            camRef = null
            centredOnce = false
            viewModel.currentLocation.value?.let {
              if (map.zoomLevelDouble != START_ZOOM) map.controller.setZoom(START_ZOOM)
              map.controller.setCenter(it.toGeoPoint())
              followCamera(it)
            }
          }
        }
        covered -> camAnim?.cancel()
        view?.mode == FolloScope.Mode.LAST -> scopeHome() // the area's home view, also when its circles changed
        view?.mode == FolloScope.Mode.LIVE ->
            if (was?.mode != FolloScope.Mode.LIVE) {
              // arrived (or opened inside): follow at walking scale, as the app always has inside a search
              overviewUntilFix = false
              followPaused = false
              camRef = null
              centredOnce = false
              if (map.zoomLevelDouble != START_ZOOM) map.controller.setZoom(START_ZOOM)
              viewModel.currentLocation.value?.let {
                map.controller.setCenter(it.toGeoPoint())
                followCamera(it)
              }
            }
      }
      map.invalidate()
    }
    if (own != scopeOwn) {
      scopeOwn = own
      showOwn()
      render() // the two pills
    }
    if (viewArea() != menuArea) renderMenu() // other layers, another title on the drop-down
  }

  /** The zoom at which Frame(V) just fits the visible map, north up; never deeper than walking scale. */
  private fun scopeFitZoom(f: FolloScope.Box): Double? {
    if (map.width == 0 || map.height == 0) return null
    val p = map.projection
    val w = abs(p.getLongPixelXFromLongitude(f.east) - p.getLongPixelXFromLongitude(f.west)).toDouble()
    val h = abs(p.getLongPixelYFromLatitude(f.south) - p.getLongPixelYFromLatitude(f.north)).toDouble()
    val v = visible()
    if (v.width() <= 0f || v.height() <= 0f) return null
    return FolloWatch.fitZoom(map.zoomLevelDouble, w, h, v.width().toDouble(), v.height().toDouble(), 1.0, SCOPE_FREE_MIN_ZOOM, START_ZOOM)
  }

  /** §6: zooming out stops where Frame(V) just fits the visible map (the LAST home view); no area = as it always was. */
  private fun scopeLimits() {
    val min = scopeFrame?.let(::scopeFitZoom) ?: if (scopeFrame == null) SCOPE_FREE_MIN_ZOOM else return
    if (abs(map.minZoomLevel - min) > 0.001) map.minZoomLevel = min
    if (scopeFrame != null && map.zoomLevelDouble < min - 0.001) map.controller.setZoom(min)
  }

  /** The LAST home view: V north up, Frame(V) fitted to the visible map; not held, so the recentre button is away. */
  private fun scopeHome() {
    val f = scopeFrame ?: return
    val zoom = scopeFitZoom(f)
    if (zoom == null) {
      map.post { if (scopeState?.view?.mode == FolloScope.Mode.LAST && !followPaused) scopeHome() }
      return
    }
    camAnim?.cancel()
    map.scroller.forceFinished(true)
    watchPlace(f.midLat, f.midLon, zoom, animate = false, hold = false)
  }

  /** placeOverlays: the visible map changed size (the banner's words, the notice, the screen turned): limits again. */
  private fun scopeLaidOut() {
    if (!::map.isInitialized || scopeFrame == null || map.width == 0) return
    val v = visible()
    val now = "${map.width}x${map.height}:${v.left},${v.top},${v.right},${v.bottom}"
    if (now == scopeLaidOutFor) return
    scopeLaidOutFor = now
    scopeLimits()
    if (scopeState?.view?.mode == FolloScope.Mode.LAST && !followPaused && !gestureActive) scopeHome()
  }

  /**
   * §6: the middle of the visible map stays inside Frame(V), however the map is turned. The LIVE follow camera is left
   * alone (the phone is inside the area anyway): there the limit holds only while the map is held by hand.
   */
  private fun scopeClamp() {
    val f = scopeFrame ?: return
    if (scopeClamping || !scopeActive || map.width == 0) return
    if (scopeState?.view?.mode == FolloScope.Mode.LIVE && !followPaused) return
    val pxPerM = map.projection.metersToPixels(1f).toDouble()
    if (!(pxPerM > 0) || !pxPerM.isFinite()) return
    val v = visible()
    val ox = v.centerX() - map.width / 2f
    val oy = v.centerY() - map.height / 2f
    val up = -map.mapOrientation.toDouble() // the bearing at the top of the screen
    val c = map.mapCenter
    // the place at the middle of the visible map (headingUpCentreAt the other way round)
    val mid = GeoPoint(c.latitude, c.longitude).destinationPoint(oy / pxPerM, up + 180.0).destinationPoint(ox / pxPerM, up + 90.0)
    if (f.contains(mid.latitude, mid.longitude)) return
    val (la, lo) = f.clamp(mid.latitude, mid.longitude)
    scopeClamping = true
    map.scroller.forceFinished(true) // a fling ends at the edge
    map.controller.setCenter(GeoPoint(c.latitude + la - mid.latitude, c.longitude + lo - mid.longitude))
    scopeClamping = false
  }

  /** recentre(): not in a search area = back to the framed view of the last one (true = done). */
  private fun scopeRecentre(): Boolean {
    if (!scopeActive) return false
    return when (scopeState?.view?.mode) {
      FolloScope.Mode.LIVE -> false // follow again, as always
      FolloScope.Mode.LAST -> {
        followPaused = false
        scopeHome()
        true
      }
      else -> true // no map
    }
  }

  /** The drop-down's "Volunteer view" row (exempt phones, §7): this phone as any volunteer's phone shows it. */
  private fun setVolunteerView(on: Boolean) {
    if (folloScopeRepo.volunteerView == on) return
    folloScopeRepo.volunteerView = on
    if (on) {
      clean = false
      watchTarget = null
      watchFollowing = false
      watchFramed = null
    }
    scopeUpdate()
    watchChanged() // the other phones' dots and lines go (or come back, and the map goes to what was chosen)
    watchTitle()
  }

  // ── Every phone in a search circle + every live-map search (FolloWatch; iOS 13 Nov, Android 18 Nov 2026) ───────────
  // Alex: "make it so I can always see any phone in a search circle anywhere in Australia … a drop down of all phones in
  // the search circles at the moment that if I select someone, it centres on them … defaults to most recent if I haven't
  // selected someone"; 18 Nov: "On my iphone and my 'FOLLO Tester' Samsung only, update the drop down to include all
  // the live map searches, even if no one is in them". Shown only on a phone whose status carries `all: 1`.

  /** The server's word that this phone may see every phone (status `all`), and it is set up. */
  private var watchAllowed = false
  private var watchChoice: FolloWatch.Choice = FolloWatch.Choice.Auto
  /** What the map is on (null = this phone's own position) */
  private var watchTarget: FolloWatch.Target? = null
  /** The map keeps [watchTarget] in view until it is moved by hand */
  private var watchFollowing = false
  /** The search the map has been framed on since it was chosen (framed once its box is known) */
  private var watchFramed: String? = null
  private lateinit var watchButton: android.widget.TextView
  private var watchLogOverlay: FolloWatchOverlay? = null
  private var watchTopOverlay: FolloWatchOverlay? = null
  /** Per phone: what its line was drawn from, and the pieces */
  private val watchPieces = mutableMapOf<String, Pair<String, List<DoubleArray>>>()

  /**
   * The clean view (Alex 18 Nov 2026): "if I long press the top banner [it] hides the display of: location arrow,
   * magenta tracks, rotation button, re-centre, card, coordinates, compass. Tapping the top banner shows the above
   * again." Since 19 Nov 2026 it hides the "…" button and its column too. Then: "I need to see other people's tracklogs, just not mine" ⇒ only this phone's own track hides.
   * Not kept over a restart; Off ends it.
   */
  private var clean = false
    set(value) {
      if (field == value) return
      field = value
      applyClean()
    }

  private fun applyClean() {
    if (!::binding.isInitialized) return
    val shown = !clean
    binding.bottom.visibility = if (clean) View.INVISIBLE else View.VISIBLE
    renderMenu()
    showOwn()
    // other phones' tracklogs (watchLogOverlay) STAY: Alex 18 Nov 2026 "I need to see other people's tracklogs, just not mine"
    showRecentre()
    render() // the two pills
    binding.root.post { placeOverlays() }
    if (camera == Camera.NAV && !followPaused) requestNavFrame() // the visible map is taller without the card
    map.invalidate()
  }

  /** onCreate: the drop-down (hidden until the server says this phone may) and the two layers it draws in. */
  private fun buildWatch() {
    val d = resources.displayMetrics.density
    watchButton =
        android.widget.TextView(this).apply {
          background =
              android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(184, 0, 0, 0))
                cornerRadius = 20f * d
              }
          setTextColor(Color.WHITE)
          textSize = 15f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
          gravity = Gravity.CENTER_VERTICAL
          minHeight = dp(40f)
          setPadding(dp(14f), 0, dp(12f), 0)
          elevation = 4f * d
          visibility = View.GONE
          contentDescription = "Phones in search circles"
          setOnClickListener { showWatchMenu() }
        }
    binding.root.addView(
        watchButton,
        binding.root.indexOfChild(binding.more) + 1,
        android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
    )
    val sd = d * resources.configuration.fontScale
    watchLogOverlay =
        FolloWatchOverlay(FolloWatchOverlay.Kind.LOGS, d, sd).also {
          // straight above the search grids, under the drawings
          val g = map.overlays.indexOf(gridOverlay)
          map.overlays.add(if (g >= 0) g + 1 else 0, it)
        }
    watchTopOverlay = FolloWatchOverlay(FolloWatchOverlay.Kind.TOP, d, sd).also { map.overlays.add(it) }
    keepOwnOnTop()
    folloWatchRepo.chosenArea?.let { watchChoice = FolloWatch.Choice.OnArea(it) }
    watchStatusChanged()
  }

  /** The status element, the set-up or the power button changed: is this phone (still) one that may look? */
  private fun watchStatusChanged() {
    if (!::watchButton.isInitialized) return
    val allowed =
        (preferences.setupCompleted && viewModel.folloStatus.value?.message?.all == 1 || folloWatchRepo.demo) && !FolloPower.isOff
    if (allowed == watchAllowed) return
    watchAllowed = allowed
    if (allowed) {
      lifecycleScope.launch { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) folloWatchRepo.fetch() }
    } else {
      clean = false
      folloWatchRepo.clear()
    }
    watchChanged()
  }

  /** The phones in search circles that have not left (FP079): what is drawn, counted and listed. */
  private fun watchPresent(snap: org.owntracks.android.data.repos.FolloWatchRepo.Snapshot = folloWatchRepo.snapshot.value): List<FolloWatch.Phone> =
      if (BuildConfig.DEBUG && ghostDemoKeep) snap.phones
      else FolloWatch.present(snap.phones, scopeAreas, System.currentTimeMillis() / 1000.0)

  /** Debug builds only (`follo_ghost_demo` = "keep"): the made-up phones as 0.1.47 showed them, for a before / after. */
  private var ghostDemoKeep = false

  /** A new list (every 10 s while the app is in front): the dots, what the map is on, the lines, the drop-down. */
  private fun watchChanged() {
    if (!::watchButton.isInitialized || !::map.isInitialized) return
    val on = watchAllowed && !FolloPower.isOff
    // 19 Nov 2026: the drop-down shows only while the Searches button under "…" is on, then on every phone
    val vis = if (searchPillShown()) View.VISIBLE else View.GONE
    if (watchButton.visibility != vis) {
      watchButton.visibility = vis
      binding.root.post { placeOverlays() }
    }
    val snap = folloWatchRepo.snapshot.value
    // "Volunteer view" (FP055 §7): the phone is like any volunteer's, so nothing of the other phones is on the map
    val volunteer = folloScopeRepo.volunteerView
    val phones = if (on && !volunteer) watchPresent(snap) else emptyList()
    if (on && snap.fetchedAt != 0L) {
      val settled = FolloWatch.settle(watchChoice, phones, snap.areas)
      if (settled != watchChoice) {
        if (watchChoice is FolloWatch.Choice.OnArea) folloWatchRepo.chosenArea = null // that search is gone
        watchChoice = settled
      }
    }
    val sharing = viewModel.folloStatus.value?.message?.state == "sharing"
    val heldUser = (watchTarget as? FolloWatch.Target.OnPhone)?.user
    // "Share everywhere I go": the map stays on this phone's own position, as with "My location"
    val target = if (on && !volunteer && !FolloEverywhere.isOn) FolloWatch.target(watchChoice, heldUser, phones, sharing) else null
    if (target != watchTarget) {
      // something else to look at (or nothing any more): the map goes to it
      watchTarget = target
      watchFollowing = target != null
      watchFramed = null
      when (target) {
        is FolloWatch.Target.OnPhone -> phones.firstOrNull { it.user == target.user }?.let { watchCentre(it.lat, it.lon, resetZoom = true) }
        is FolloWatch.Target.OnArea -> watchFrameArea()
        null -> {}
      }
    } else if (watchFollowing) {
      when (target) {
        is FolloWatch.Target.OnPhone -> phones.firstOrNull { it.user == target.user }?.let { watchCentre(it.lat, it.lon, resetZoom = false) }
        is FolloWatch.Target.OnArea -> if (watchFramed != target.code) watchFrameArea() // its box had not come yet
        null -> {}
      }
    }
    drawWatch(phones, if (on && !volunteer) snap else org.owntracks.android.data.repos.FolloWatchRepo.Snapshot())
    watchTitle()
  }

  private fun watchArea(code: String?): FolloWatch.Area? =
      code?.let { c -> FolloWatch.counted(folloWatchRepo.snapshot.value.areas, watchPresent())?.firstOrNull { it.code == c } }

  private fun watchTitle() {
    if (!::watchButton.isInitialized) return
    val present = watchPresent()
    val t = watchTarget
    val title =
        (if (FolloEverywhere.isOn) getString(R.string.follo_everywhere_pill) // "Share everywhere I go": said on every phone
        else if (!watchAllowed) viewArea() ?: getString(R.string.follo_search_pill_none) // an ordinary phone: the search in view
        else if (folloScopeRepo.volunteerView) VOLUNTEER_VIEW
        else
            FolloWatch.title(
                (t as? FolloWatch.Target.OnPhone)?.let { p -> present.firstOrNull { it.user == p.user } },
                watchArea((t as? FolloWatch.Target.OnArea)?.code),
                present.size,
                System.currentTimeMillis() / 1000.0,
            )) + "  ▾"
    if (watchButton.text.toString() != title) {
      watchButton.text = title
      watchButton.post { watchButton.requestLayout() }
    }
  }

  /** placeOverlays: on the round buttons' row, centred on the screen (19 Nov 2026), clear of recentre and "…". */
  private fun placeWatch(top: Int) {
    if (!::watchButton.isInitialized) return
    val w = binding.root.width
    val leftEdge = recentreStart() + if (binding.recentre.visibility == View.VISIBLE) dp(40f + 8f) else 0
    val rightEdge = w - moreEnd() - dp(40f + 8f)
    val room = (2 * minOf(w / 2 - leftEdge, rightEdge - w / 2)).coerceAtLeast(dp(80f))
    if (watchButton.maxWidth != room) watchButton.maxWidth = room
    watchButton.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginStart != 0 || gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        topMargin = top
        marginStart = 0
      }
    }
  }

  /** The drop-down: the searches, then the phones in circles, then "My location". */
  private fun showWatchMenu() {
    if (!watchAllowed) return showSearchMenu() // an ordinary phone: the searches it is linked to
    folloWatchRepo.snapshot.value.let { snap ->
      val now = System.currentTimeMillis() / 1000.0
      val menu = android.widget.PopupMenu(this, watchButton)
      val actions = mutableListOf<() -> Unit>()
      fun item(text: String, on: Boolean, group: Int = 0, action: (() -> Unit)?) {
        val mi = menu.menu.add(group, actions.size, actions.size, if (on) "✓  $text" else text)
        if (action == null) mi.isEnabled = false
        actions += action ?: {}
      }
      val t = watchTarget
      val everywhere = FolloEverywhere.isOn
      val present = watchPresent(snap) // those who have left are not listed or counted (FP079)
      FolloWatch.counted(snap.areas, present)?.let { areas ->
        item("Searches", false, action = null)
        for (a in areas) item(FolloWatch.areaLine(a), !everywhere && t is FolloWatch.Target.OnArea && t.code == a.code) {
          watchChoose(FolloWatch.Choice.OnArea(a.code))
        }
      }
      item(if (present.isEmpty()) "No phones in search circles" else "Phones in search circles", false, action = null)
      for (p in present) item(FolloWatch.line(p, now), !everywhere && t is FolloWatch.Target.OnPhone && t.user == p.user) {
        watchChoose(FolloWatch.Choice.OnPhone(p.user))
      }
      val volunteer = folloScopeRepo.volunteerView
      item("My location", t == null && !volunteer && !everywhere) { watchChoose(FolloWatch.Choice.Me) }
      // FP055 §7: this phone as any volunteer's phone shows it (search areas only); any other row switches it off
      item(VOLUNTEER_VIEW, volunteer && !everywhere) {
        everywhereLeave()
        setVolunteerView(!volunteer)
      }
      // "Share everywhere I go" (Alex 22 Nov 2026): the last row, under a divider, while the server offers it
      if (everywhereOffered()) {
        androidx.core.view.MenuCompat.setGroupDividerEnabled(menu.menu, true)
        item(getString(R.string.follo_everywhere_row), everywhere, group = 1) { everywhereTapped() }
      }
      menu.setOnMenuItemClickListener {
        actions.getOrNull(it.itemId)?.invoke()
        true
      }
      menu.show()
    }
    lifecycleScope.launch { folloWatchRepo.fetch() } // and ask now, as iOS does when the list is opened
  }

  /** A line of the drop-down was tapped. */
  private fun watchChoose(choice: FolloWatch.Choice) {
    everywhereLeave() // any other row = back to a normal search
    if (folloScopeRepo.volunteerView) {
      folloScopeRepo.volunteerView = false
      scopeUpdate() // the mask and the limits go before the map moves to what was chosen
    }
    watchChoice = choice
    folloWatchRepo.chosenArea = (choice as? FolloWatch.Choice.OnArea)?.code
    watchTarget = null // taken as new by watchChanged: the map goes there
    watchFollowing = false
    if (choice == FolloWatch.Choice.Me) {
      drawWatch(watchPresent(), folloWatchRepo.snapshot.value)
      watchTitle()
      recentre()
      return
    }
    watchChanged()
  }

  /** recentre(): with the map on another phone or a search, the recentre button goes back to THAT. */
  private fun watchRecentre(): Boolean {
    when (val t = watchTarget) {
      is FolloWatch.Target.OnPhone -> {
        val p = watchPresent().firstOrNull { it.user == t.user } ?: return false
        watchFollowing = true
        watchCentre(p.lat, p.lon, resetZoom = true)
        return true
      }
      is FolloWatch.Target.OnArea -> {
        watchFollowing = true
        watchFramed = null
        watchFrameArea()
        return true
      }
      null -> return false
    }
  }

  /** The map on a phone that is not this one: north up, walking scale (or as it is), the phone in the visible middle. */
  private fun watchCentre(lat: Double, lon: Double, resetZoom: Boolean) {
    val zoom = if (resetZoom) START_ZOOM else map.zoomLevelDouble
    watchPlace(lat, lon, zoom, animate = !resetZoom)
  }

  /** The chosen search: north up, its circles' box filling most of the visible map. Once its box is known. */
  private fun watchFrameArea() {
    val code = (watchTarget as? FolloWatch.Target.OnArea)?.code ?: return
    val a = watchArea(code) ?: return
    if (map.width == 0 || map.height == 0) {
      map.post { if (watchFramed == null) watchFrameArea() }
      return
    }
    val p = map.projection
    val w = abs(p.getLongPixelXFromLongitude(a.east) - p.getLongPixelXFromLongitude(a.west)).toDouble()
    val h = abs(p.getLongPixelYFromLatitude(a.south) - p.getLongPixelYFromLatitude(a.north)).toDouble()
    val v = visible()
    val zoom = FolloWatch.fitZoom(map.zoomLevelDouble, w, h, v.width().toDouble(), v.height().toDouble(), 0.85, map.minZoomLevel, START_ZOOM)
    watchFramed = code
    watchPlace((a.south + a.north) / 2, (a.west + a.east) / 2, zoom, animate = false)
  }

  /** North up at [zoom] with (lat, lon) in the middle of the visible map. The map counts as held (recentre shows). */
  private fun watchPlace(lat: Double, lon: Double, zoom: Double, animate: Boolean, hold: Boolean = true) {
    if (map.width == 0 || map.height == 0) {
      map.post { watchPlace(lat, lon, zoom, animate, hold) }
      return
    }
    overviewUntilFix = false
    followPaused = hold // FP055's framed view of a search area is its home view: not held, so no recentre button
    map.mapOrientation = 0f
    val z0 = map.zoomLevelDouble
    val lat0 = map.mapCenter.latitude
    val pxPerM =
        map.projection.metersToPixels(1f) * Math.pow(2.0, zoom - z0) * cos(Math.toRadians(lat0)) / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
    val v = visible()
    val ox = v.centerX() - map.width / 2f // the point right of the view's centre by ox
    val oy = v.centerY() - map.height / 2f // and below it by oy
    var c = GeoPoint(lat, lon)
    if (pxPerM > 0 && pxPerM.isFinite()) c = c.destinationPoint(oy / pxPerM, 0.0).destinationPoint(ox / pxPerM, -90.0)
    if (zoom != z0) map.controller.setZoom(zoom)
    // a short step glides (the phone walking); anything further is a jump
    val step = map.mapCenter.let { FolloWatch.metres(it.latitude, it.longitude, c.latitude, c.longitude) }
    if (animate && zoom == z0 && step < 300) map.controller.animateTo(c, null, PAN_MS) else map.controller.setCenter(c)
    if (camera == Camera.NAV) requestNavFrame() // this phone's own arrow turns to suit the north-up map
    map.invalidate()
  }

  /**
   * Every phone's line of the last day: the one the map is on lime up to its dot, every other one a magenta tracklog;
   * the chosen search's outline; a dot per phone in a circle. Only the lines that changed are worked out again.
   */
  private fun drawWatch(phones: List<FolloWatch.Phone>, snap: org.owntracks.android.data.repos.FolloWatchRepo.Snapshot) {
    val logOverlay = watchLogOverlay ?: return
    val top = watchTopOverlay ?: return
    val liveUser = (watchTarget as? FolloWatch.Target.OnPhone)?.user
    val logs = mutableListOf<DoubleArray>()
    var live: List<DoubleArray> = emptyList()
    watchPieces.keys.retainAll(snap.logs.keys)
    val now = System.currentTimeMillis() / 1000.0
    for ((user, points) in snap.logs) {
      val isLive = user == liveUser
      val dot = if (isLive) phones.firstOrNull { it.user == user } else null
      // FP079 (22 Nov 2026): walking only, as the live map draws it (FolloWalk); worked out again only when the line
      // changed, or when its newest piece has just gone quiet for a minute (a short one then comes off)
      val quiet = points.lastOrNull()?.let { now - it.t > FolloWalk.TAIL } ?: true
      val key = "${if (isLive) "live" else "log"}:${snap.logVersion[user] ?: 0}:${dot?.at ?: 0}:$quiet"
      val pieces =
          watchPieces[user]?.takeIf { it.first == key }?.second
              ?: FolloWalk.lines(points.map { FolloWalk.Fix(it.t, it.lat, it.lon) }, now, dot?.let { it.lat to it.lon }, FolloWatch.CUT_M)
                  .also { watchPieces[user] = key to it }
      if (isLive) live = pieces else logs += pieces
    }
    logOverlay.logs = logs
    logOverlay.isEnabled = true // other phones' tracklogs stay in the clean view (Alex 18 Nov 2026: "just not mine")
    top.live = live
    // FP078: the same dot as a team-mate's, the fill and the vest as the server sends them
    watchDots = phones.map { FolloDots.Dot(it.lat, it.lon, it.tid, FolloRules.dotColour(it.tc), it.vest) }
    watchUsers = phones.map { it.user }.toSet()
    pushDots()
    val area = (watchTarget as? FolloWatch.Target.OnArea)?.code?.let { c -> snap.areas?.firstOrNull { it.code == c } }
    top.outline = area?.let { a -> snap.outlines[a.code]?.takeIf { it.size >= 6 } }
    top.outlineColor = area?.color ?: Color.WHITE
    map.invalidate()
  }

  // ── The simple screen (Alex, 19 Nov 2026; FOLLO_tracks/docs/SIMPLE_MODE_SPEC_20261119.md) ─────────────────────────
  // The card is gone. One "…" button at the top right shows or hides a column: the layer buttons that exist for the
  // search area in view (Tracklogs, Grids, Labels, Hillshade, Slope, Contours, POI), a half-button gap, then Rotate,
  // Power, Compass and Searches. What each is set to is remembered on the phone and keeps showing while "…" is closed.
  // The rules are FolloMenu (pure, unit-tested); the layer files come from FolloLayRepo, whole and in advance.

  private val menuPrefs by lazy { getSharedPreferences("follo_menu", Context.MODE_PRIVATE) }

  /** The column under "…" is showing (remembered; a new install starts closed). */
  private var menuOpen: Boolean
    get() = menuPrefs.getBoolean("more", false)
    set(value) = menuPrefs.edit().putBoolean("more", value).apply()

  /** The Compass button: the heading pill at the bottom left. Off until switched on. */
  private var compassOn: Boolean
    get() = menuPrefs.getBoolean("compass", false)
    set(value) = menuPrefs.edit().putBoolean("compass", value).apply()

  /** The Searches button: the drop-down at the top. Off until switched on. */
  private var searchesOn: Boolean
    get() = menuPrefs.getBoolean("searches", false)
    set(value) = menuPrefs.edit().putBoolean("searches", value).apply()

  private fun layerOn(l: FolloMenu.Layer): Boolean = menuPrefs.getBoolean("layer_" + l.key, l.defaultOn)

  private val layerButtons = java.util.EnumMap<FolloMenu.Layer, FolloMenuButton>(FolloMenu.Layer::class.java)
  private val functionButtons = java.util.EnumMap<FolloMenu.Function, View>(FolloMenu.Function::class.java)
  private lateinit var powerButton: FolloPowerButton
  /** The column as it shows now, top to bottom, and how many of them are layer buttons */
  private var menuShown: List<View> = emptyList()
  private var menuLayerCount = 0
  /** Off: the power button stands alone where "…" is */
  private var menuPowerAlone = false
  private var menuKey = "-"
  /** The search area the layer buttons and the layers were last worked out for */
  private var menuArea: String? = null
  private var hsOverlay: FolloPackOverlay? = null
  private var slOverlay: FolloPackOverlay? = null
  private var ctOverlay: FolloLayOverlay? = null
  private var tlOverlay: FolloLayOverlay? = null
  private var poiOverlay: FolloLayOverlay? = null
  /** What each layer on the map was last loaded from (its file's path; null = nothing) */
  private val layerShown = java.util.EnumMap<FolloMenu.Layer, String>(FolloMenu.Layer::class.java)
  /** A search chosen in an ordinary phone's drop-down (spec §7): its LAST view, until a fix inside a held area. */
  private var scopeChosen: String? = null

  /** onCreate, after the map: the buttons of the column and the layers' overlays (under the grids, under the mask). */
  private fun buildMenu() {
    val d = resources.displayMetrics.density
    val sd = d * resources.configuration.fontScale
    // the layers, bottom to top: hillshade (a base) → slope → contours → tracklogs → [grids, labels] → POI
    val redraw: () -> Unit = { map.postInvalidate() }
    hsOverlay = FolloPackOverlay(opaque = true, redraw).also { map.overlays.add(0, it) }
    slOverlay = FolloPackOverlay(opaque = false, redraw).also { map.overlays.add(1, it) }
    ctOverlay = FolloLayOverlay(FolloLayOverlay.Kind.CONTOURS, d, sd).also { map.overlays.add(2, it) }
    tlOverlay = FolloLayOverlay(FolloLayOverlay.Kind.TRACKLOGS, d, sd).also { map.overlays.add(3, it) }
    poiOverlay = FolloLayOverlay(FolloLayOverlay.Kind.POI, d, sd).also { map.overlays.add(map.overlays.indexOf(gridOverlay) + 1, it) }

    binding.more.shapes = FolloMenuButton.dots()
    binding.more.setOnClickListener {
      menuOpen = !menuOpen
      renderMenu()
    }
    val at = binding.root.indexOfChild(binding.more) + 1
    fun add(v: View, cd: String) {
      v.visibility = View.GONE
      v.contentDescription = cd
      binding.root.addView(v, at, android.widget.FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.TOP or Gravity.END))
    }
    fun photo(id: Int) = android.graphics.BitmapFactory.decodeResource(resources, id)
    for (l in FolloMenu.Layer.entries) {
      val b = FolloMenuButton(this)
      b.look = FolloMenuButton.Look.LAYER
      when (l) {
        FolloMenu.Layer.TRACKLOGS -> b.shapes = FolloMenuButton.tracklogs()
        FolloMenu.Layer.GRIDS -> b.shapes = FolloMenuButton.grids()
        FolloMenu.Layer.LABELS -> b.tag = "A1"
        FolloMenu.Layer.HILLSHADE -> b.photo = photo(R.drawable.follo_layer_hill)
        FolloMenu.Layer.SLOPE -> b.photo = photo(R.drawable.follo_layer_slope)
        FolloMenu.Layer.CONTOURS -> b.photo = photo(R.drawable.follo_layer_contours)
        FolloMenu.Layer.POI -> b.shapes = FolloMenuButton.poi()
      }
      b.setOnClickListener { onLayerTapped(l) }
      layerButtons[l] = b
      add(b, layerName(l))
    }
    val rotate = FolloMenuButton(this)
    rotate.symbol = ResourcesCompat.getDrawable(resources, R.drawable.ic_follo_rotate, null)?.mutate()
    rotate.setOnClickListener { screenTurner.turn() }
    functionButtons[FolloMenu.Function.ROTATE] = rotate
    add(rotate, getString(R.string.follo_rotate))
    // Power button (13 Nov 2026): hold 2 s = Off, one tap = on again. Under "…" since 19 Nov 2026; Off, it stands alone
    // where "…" is, so the phone can always be turned back on.
    powerButton = FolloPowerButton(this)
    powerButton.elevation = 4f * d
    powerButton.outlineProvider =
        object : android.view.ViewOutlineProvider() {
          override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
        }
    powerButton.onTurnOff = { setPower(off = true) }
    powerButton.onTurnOn = { setPower(off = false) }
    powerButton.onHint = { Toast.makeText(this, R.string.follo_power_hold_hint, Toast.LENGTH_SHORT).show() }
    functionButtons[FolloMenu.Function.POWER] = powerButton
    add(powerButton, getString(R.string.follo_power_cd_on))
    val compassButton = FolloMenuButton(this)
    compassButton.shapes = FolloMenuButton.compass()
    compassButton.setOnClickListener {
      compassOn = !compassOn
      renderMenu()
    }
    functionButtons[FolloMenu.Function.COMPASS] = compassButton
    add(compassButton, getString(R.string.follo_fn_compass))
    val searchesButton = FolloMenuButton(this)
    searchesButton.shapes = FolloMenuButton.australia()
    searchesButton.pictureScale = 27f / 24f
    searchesButton.setOnClickListener {
      searchesOn = !searchesOn
      renderMenu()
      watchChanged()
    }
    functionButtons[FolloMenu.Function.SEARCHES] = searchesButton
    add(searchesButton, getString(R.string.follo_fn_searches))
  }

  private fun layerName(l: FolloMenu.Layer): String =
      getString(
          when (l) {
            FolloMenu.Layer.TRACKLOGS -> R.string.follo_layer_tl
            FolloMenu.Layer.GRIDS -> R.string.follo_layer_grids
            FolloMenu.Layer.LABELS -> R.string.follo_layer_labels
            FolloMenu.Layer.HILLSHADE -> R.string.follo_layer_hs
            FolloMenu.Layer.SLOPE -> R.string.follo_layer_sl
            FolloMenu.Layer.CONTOURS -> R.string.follo_layer_ct
            FolloMenu.Layer.POI -> R.string.follo_layer_poi
          })

  /**
   * The search area "in view": where FP055 applies, the area the screen is on (V); on an exempt phone, the search
   * chosen in the drop-down, else the held area under the middle of the map, else the one the phone is in.
   */
  private fun viewArea(): String? {
    if (!::map.isInitialized) return null
    if (scopeActive) return scopeState?.view?.area
    (watchTarget as? FolloWatch.Target.OnArea)?.code?.let { if (it in scopeAreas) return it }
    val c = map.mapCenter
    FolloScope.areasAt(c.latitude, c.longitude, scopeAreas).firstOrNull()?.let { return it }
    return viewModel.currentLocation.value?.let { FolloScope.areasAt(it.latitude, it.longitude, scopeAreas).firstOrNull() }
  }

  /** The hillshade is the base map right now (its pack is on the phone and drawn in place of the imagery). */
  private fun hillshadeShown(): Boolean = hsOverlay?.pack != null

  /**
   * Which buttons show and what they say (spec §2): on every render, and when the view, the grids, the layer list or
   * the layer files change. Views are touched only when something changes.
   */
  private fun renderMenu() {
    if (!::binding.isInitialized || !::powerButton.isInitialized) return
    val kind = state?.kind
    val off = FolloPower.isOff
    val has = kind != null && kind !in NO_MENU_KINDS && !clean
    val showMore = has && !off
    val open = showMore && menuOpen
    val area = viewArea()
    menuArea = area
    val gridArea = area?.let { a -> folloGridRepo.grid.value?.areas?.firstOrNull { it.code == a } }
    val layers =
        if (open && !scopeCovered)
            FolloMenu.layerButtons(
                folloLayRepo.list.value?.listed.orEmpty(),
                gridArea?.teams?.any { it.lines.isNotEmpty() } == true,
                gridArea?.names?.isNotEmpty() == true,
                area,
            )
        else emptyList()
    val functions =
        when {
          off && kind == Kind.OFF && !clean -> listOf(FolloMenu.Function.POWER)
          !open -> emptyList()
          scopeCovered -> listOf(FolloMenu.Function.POWER, FolloMenu.Function.SEARCHES) // WAITING: no map
          else -> FolloMenu.Function.entries
        }
    fun vis(v: View, show: Boolean) {
      val want = if (show) View.VISIBLE else View.GONE
      if (v.visibility != want) v.visibility = want
    }
    vis(binding.more, showMore)
    binding.more.on = open
    binding.more.contentDescription = getString(if (open) R.string.follo_more_open else R.string.follo_more)
    for ((l, b) in layerButtons) {
      vis(b, l in layers)
      val on = layerOn(l)
      b.on = on
      b.contentDescription = getString(if (on) R.string.follo_layer_on else R.string.follo_layer_off, layerName(l))
    }
    for ((f, v) in functionButtons) vis(v, f in functions)
    (functionButtons[FolloMenu.Function.COMPASS] as? FolloMenuButton)?.on = compassOn
    (functionButtons[FolloMenu.Function.SEARCHES] as? FolloMenuButton)?.on = searchesOn
    powerButton.off = off
    menuShown = layers.mapNotNull { layerButtons[it] } + functions.mapNotNull { functionButtons[it] }
    menuLayerCount = layers.size
    menuPowerAlone = off && functions.size == 1
    val key = "$showMore|$open|$layers|$functions|$menuPowerAlone"
    if (key != menuKey) {
      menuKey = key
      binding.root.post { placeOverlays() }
    }
    applyLayers()
    renderHeading()
    if (::watchButton.isInitialized) {
      val pill = if (searchPillShown()) View.VISIBLE else View.GONE
      if (watchButton.visibility != pill) {
        watchButton.visibility = pill
        binding.root.post { placeOverlays() }
      }
      watchTitle()
    }
  }

  /** placeOverlays: the column under "…" (spec §2), further columns to the left when it does not fit. */
  private fun placeMenu(top: Int, end: Int, pillAbove: Int) {
    if (!::powerButton.isInitialized || menuShown.isEmpty()) return
    val d = resources.displayMetrics.density
    if (menuPowerAlone) {
      powerButton.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        if (topMargin != top || marginEnd != end) {
          topMargin = top
          marginEnd = end
        }
      }
      return
    }
    val first = top + dp(40f + 10f)
    val pillH = binding.coords.height.takeIf { it > 0 } ?: dp(29f)
    val avail = (binding.root.height - pillAbove - pillH - dp(8f) - first) / d
    val slots = FolloMenu.layout(menuLayerCount, menuShown.size - menuLayerCount, avail)
    for ((i, v) in menuShown.withIndex()) {
      val s = slots.getOrNull(i) ?: continue
      val t = first + (s.y * d).roundToInt()
      val e = end + s.column * dp(40f + 10f)
      v.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        if (topMargin != t || marginEnd != e) {
          topMargin = t
          marginEnd = e
        }
      }
    }
  }

  /** A layer button was tapped: on / off, remembered. A hillshade or slope that still waits for Wi-Fi asks first. */
  private fun onLayerTapped(l: FolloMenu.Layer) {
    val on = !layerOn(l)
    menuPrefs.edit().putBoolean("layer_" + l.key, on).apply()
    val area = viewArea()
    if (on && area != null && folloLayRepo.needsAsking(area, l)) {
      MaterialAlertDialogBuilder(this)
          .setTitle(if (l == FolloMenu.Layer.HILLSHADE) R.string.follo_lay_ask_hs else R.string.follo_lay_ask_sl)
          .setMessage(getString(R.string.follo_offline_ask_msg, FolloMenu.megabytes(folloLayRepo.kb(area, l.key))))
          .setPositiveButton(R.string.follo_offline_ask_yes) { _, _ -> folloLayRepo.allowMetered(area, l.key) }
          .setNegativeButton(R.string.follo_offline_ask_no, null)
          .show()
    }
    renderMenu()
  }

  /**
   * What the map draws of the layers (spec §6): each one that is switched on and on the phone for the area in view.
   * Files are opened and read off the main thread; a layer that is on but not on the phone yet appears when it lands.
   */
  private fun applyLayers() {
    if (!::map.isInitialized || hsOverlay == null) return
    gridOverlay?.let {
      val lines = layerOn(FolloMenu.Layer.GRIDS)
      val names = layerOn(FolloMenu.Layer.LABELS)
      if (it.showLines != lines || it.showNames != names) {
        it.showLines = lines
        it.showNames = names
        map.invalidate()
      }
    }
    val area = menuArea
    val none = scopeCovered || FolloPower.isOff
    // The layers switched on for the area in view are fetched now if they are not on the phone, however far away the
    // search is (spec §5, added 19 Nov 2026: FOLLO-L in Queensland looking at FG drew nothing)
    folloLayRepo.want(
        if (none || area == null) emptySet()
        else FolloMenu.Layer.entries.filter { it != FolloMenu.Layer.GRIDS && it != FolloMenu.Layer.LABELS && layerOn(it) }.map { "$area/${it.key}" }.toSet()
    )
    for (l in FolloMenu.Layer.entries) {
      if (l == FolloMenu.Layer.GRIDS || l == FolloMenu.Layer.LABELS) continue
      val file = if (!none && area != null && layerOn(l)) folloLayRepo.file(area, l.key) else null
      val path = file?.path
      if (layerShown[l] == path) continue
      if (path == null) layerShown.remove(l) else layerShown[l] = path
      if (file == null) {
        setLayer(l, null, null, null, null)
        continue
      }
      lifecycleScope.launch {
        var pack: FolloPack? = null
        var contours: List<FolloLay.Contour>? = null
        var lines: List<DoubleArray>? = null
        var pois: List<FolloLay.Poi>? = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          try {
            when (l) {
              FolloMenu.Layer.HILLSHADE, FolloMenu.Layer.SLOPE -> pack = FolloPack.open(file)
              FolloMenu.Layer.CONTOURS -> contours = FolloLay.parseContours(file.readText())
              FolloMenu.Layer.TRACKLOGS -> lines = FolloLay.parseLines(file.readText())
              FolloMenu.Layer.POI -> pois = FolloLay.parsePois(file.readText())
              else -> {}
            }
          } catch (e: Exception) {
            Timber.w(e, "Could not read layer ${l.key}")
          }
        }
        if (layerShown[l] == path) setLayer(l, pack, contours, lines, pois) else pack?.close()
      }
    }
  }

  private fun setLayer(l: FolloMenu.Layer, pack: FolloPack?, contours: List<FolloLay.Contour>?, lines: List<DoubleArray>?, pois: List<FolloLay.Poi>?) {
    when (l) {
      FolloMenu.Layer.HILLSHADE -> {
        hsOverlay?.let { o ->
          val old = o.pack
          o.pack = pack
          old?.close()
        }
        // a base map: the hillshade in place of the imagery (no imagery tile is drawn or asked for meanwhile)
        map.overlayManager.tilesOverlay.isEnabled = pack == null
        showCredit()
      }
      FolloMenu.Layer.SLOPE ->
          slOverlay?.let { o ->
            val old = o.pack
            o.pack = pack
            old?.close()
          }
      FolloMenu.Layer.CONTOURS -> ctOverlay?.contours = contours.orEmpty()
      FolloMenu.Layer.TRACKLOGS -> tlOverlay?.lines = lines.orEmpty()
      FolloMenu.Layer.POI -> poiOverlay?.pois = pois.orEmpty()
      else -> {}
    }
    map.invalidate()
  }

  // ── The two-way symbol (spec §3) ────────────────────────────────────────────────────────

  private var linkCap = 0f
  private val hideLinkBubble = Runnable { if (::binding.isInitialized) binding.linkBubble.visibility = View.GONE }

  /** Green / orange / red with a slash; hidden before set up, on the permission screens and while Off. */
  private fun renderLink() {
    if (!::binding.isInitialized) return
    val kind = state?.kind
    val show = kind != null && kind !in NO_MENU_KINDS && kind != Kind.OFF
    val visibility = if (show) View.VISIBLE else View.GONE
    if (binding.link.visibility != visibility) {
      binding.link.visibility = visibility
      if (!show) binding.linkBubble.visibility = View.GONE
      binding.root.post { placeOverlays() }
    }
    if (!show) return
    if (linkCap == 0f) {
      val r = android.graphics.Rect()
      binding.title.paint.getTextBounds("H", 0, 1, r)
      linkCap = r.height().toFloat()
      binding.link.capHeight = linkCap
    }
    val link = folloLinkRepo.link()
    binding.link.state = link
    val cd =
        getString(
            when (link) {
              FolloMenu.Link.GREEN -> R.string.follo_link_green
              FolloMenu.Link.ORANGE -> R.string.follo_link_orange
              FolloMenu.Link.RED -> R.string.follo_link_red
            })
    if (binding.link.contentDescription != cd) binding.link.contentDescription = cd
  }

  /** placeOverlays: the chip's middle on the middle of the title's first line of capitals; the bubble under it. */
  private fun placeLink() {
    val chip = binding.link
    if (chip.visibility != View.VISIBLE || chip.height == 0) return
    val layout = binding.title.layout ?: return
    val middle = binding.title.top + binding.title.paddingTop + layout.getLineBaseline(0) - linkCap / 2f
    val ty = middle - (chip.top + chip.height / 2f)
    if (kotlin.math.abs(chip.translationY - ty) > 0.5f) chip.translationY = ty
    val chipBottom = binding.banner.top + binding.titleRow.top + chip.top + chip.translationY + chip.height - chip.touchPx
    val chipRight = binding.banner.left + binding.titleRow.left + chip.right - chip.touchPx
    val top = chipBottom.roundToInt() + dp(4f)
    val end = (binding.root.width - chipRight).coerceAtLeast(dp(8f))
    binding.linkBubble.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginEnd != end) {
        topMargin = top
        marginEnd = end
      }
    }
  }

  /** A tap on the two-way symbol: the tracker's name and when it last sent, as the card said them, for 4 seconds. */
  private fun showLinkBubble() {
    binding.linkBubble.text = whoText() + "\n" + lastText()
    binding.linkBubble.visibility = View.VISIBLE
    binding.linkBubble.removeCallbacks(hideLinkBubble)
    binding.linkBubble.postDelayed(hideLinkBubble, LINK_BUBBLE_MS)
    binding.root.post { placeOverlays() }
  }

  // ── The Searches button's drop-down on an ordinary phone (spec §7) ──────────────────────

  /** The drop-down shows while the Searches button is on (every phone), not before set up, on a permission screen or Off. */
  private fun searchPillShown(): Boolean {
    val kind = state?.kind
    // "Share everywhere I go": the pill shows for as long as the mode is on, whatever the Searches button is set to
    return (searchesOn || FolloEverywhere.isOn) && preferences.setupCompleted && !FolloPower.isOff && kind != null && kind !in NO_MENU_KINDS
  }

  /** The searches this phone holds circles for, then "Automatic". Nothing about the choice is sent anywhere. */
  private fun showSearchMenu() {
    val menu = android.widget.PopupMenu(this, watchButton)
    val names = folloLayRepo.list.value?.names.orEmpty()
    val areas = scopeAreas.keys.sorted()
    val shown = scopeState?.view?.area
    val everywhere = FolloEverywhere.isOn
    areas.forEachIndexed { i, code ->
      val line = names[code]?.let { "$code  $it" } ?: code
      menu.menu.add(0, i, i, if (!everywhere && code == (scopeChosen ?: shown)) "✓  $line" else line)
    }
    menu.menu.add(0, areas.size, areas.size, getString(R.string.follo_search_auto))
    // "Share everywhere I go" (Alex 22 Nov 2026): the last row, under a divider, while the server offers it
    val everywhereId = areas.size + 1
    if (everywhereOffered()) {
      androidx.core.view.MenuCompat.setGroupDividerEnabled(menu.menu, true)
      val row = getString(R.string.follo_everywhere_row)
      menu.menu.add(1, everywhereId, everywhereId, if (everywhere) "✓  $row" else row)
    }
    menu.setOnMenuItemClickListener { item ->
      if (item.itemId == everywhereId) {
        everywhereTapped()
        return@setOnMenuItemClickListener true
      }
      everywhereLeave() // any other row = back to a normal search
      val code = areas.getOrNull(item.itemId)
      scopeChosen = code
      scopeUpdate()
      // already on that area's framed view (or back to automatic on the same one): frame it afresh
      if (scopeActive && scopeState?.view?.mode == FolloScope.Mode.LAST) {
        followPaused = false
        showRecentre()
        scopeHome()
      }
      renderMenu()
      true
    }
    menu.show()
  }

  // ── "⚠ Share everywhere I go ⚠" (Alex, 22 Nov 2026; FOLLO_tracks/docs/NEXT_BUILD_SPEC_20261122.md §D) ─────────────────
  // "in the menu of searches on the app (when they've tapped the Australia map), have a special 'search' … which ignores
  // all the rules about tracking (e.g. geofencing circles) and allows someone who is really keen to record something in a
  // random place to record it … I want them to have it but with plenty of warning. This turns off the greying out. It
  // doesn't try to download map as there's no defined area. There's no time restrictions on this mode either."
  // The flag is support/FolloEverywhere; what leaves the phone is decided in LocationProcessor, never here.

  private var everywhereDialog: androidx.appcompat.app.AlertDialog? = null
  /** Debug builds only (`follo_ev_demo`): the row is offered with no server saying so, for screenshots (offline). */
  private var everywhereDemo = false
  /** The note above the bottom band is the "turned off by FOLLO" one right now (a tap dismisses it). */
  private var everywhereNote = false
  private var everywhereNoteTimer = false

  /** The row exists only while the last status element says this server takes such posts. */
  private fun everywhereOffered(): Boolean =
      FolloRules.everywhereOffered(viewModel.folloStatus.value?.message?.ev) || (BuildConfig.DEBUG && everywhereDemo)

  /** The row was tapped: on = back to a normal search ("Automatic"); off = the pop-up, and the mode only on OK. */
  private fun everywhereTapped() {
    if (FolloEverywhere.isOn) {
      everywhereLeave()
      return
    }
    everywhereDialog?.dismiss()
    everywhereDialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.follo_everywhere_row)
            .setMessage(R.string.follo_everywhere_msg)
            .setPositiveButton(R.string.follo_everywhere_ok) { _, _ -> everywhereStart() }
            .setNegativeButton(R.string.follo_everywhere_cancel, null)
            .show()
  }

  /** OK on the pop-up: the mode is on from now. */
  private fun everywhereStart() {
    if (FolloPower.isOff || !preferences.setupCompleted) return
    FolloEverywhere.shownAt = System.currentTimeMillis()
    locationProcessor.onEverywhereOn() // the flag, Move; the screen follows the flag (everywhereChanged)
    // and a position now, rather than at the next fix the tracking takes
    lastOpenCheckInAt = -OPEN_CHECK_IN_DEDUPE_MS
    openCheckIn()
  }

  /** Back to a normal search, if the mode is on (any other row of the drop-down, the ticked row, "Back to normal"). */
  private fun everywhereLeave() {
    if (!FolloEverywhere.isOn) return
    // the flag is cleared before this returns (nothing in between can leave by the mode); the hello follows
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { locationProcessor.onEverywhereOff() }
  }

  /** The switch was thrown (here or by the server): the mask, the limits, what the map is on, the pill, the banner. */
  private fun everywhereChanged(on: Boolean) {
    if (!::binding.isInitialized || !::map.isInitialized) return
    if (on) {
      // whatever search or phone was chosen before is let go: leaving the mode is "Automatic" (spec §D4)
      scopeChosen = null
      watchChoice = FolloWatch.Choice.Auto
      if (folloWatchRepo.chosenArea != null) folloWatchRepo.chosenArea = null
      clean = false
      if (folloScopeRepo.volunteerView) folloScopeRepo.volunteerView = false
    } else {
      everywhereDialog?.dismiss()
      everywhereDialog = null
    }
    scopeUpdate()
    watchChanged()
    render()
    if (on && !FolloPower.isOff) {
      // this phone's own position, anywhere: follow it as the app always has outside FP055
      followPaused = false
      showRecentre()
      recentre()
    }
  }

  /** Coming to the front with the mode on, 12 hours or more after the pop-up was last shown: the same words again. */
  private fun everywhereRemind(force: Boolean = false) {
    if (!FolloEverywhere.isOn || FolloPower.isOff) return
    val now = System.currentTimeMillis()
    if (!force && !FolloRules.reminderDue(FolloEverywhere.shownAt, now)) return
    if (everywhereDialog?.isShowing == true) return
    FolloEverywhere.shownAt = now
    everywhereDialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.follo_everywhere_row)
            .setMessage(R.string.follo_everywhere_msg)
            .setPositiveButton(R.string.follo_everywhere_keep, null)
            .setNegativeButton(R.string.follo_everywhere_back) { _, _ -> everywhereLeave() }
            .show()
  }

  /** The "turned off by FOLLO" notice is on the screen: it goes by itself after 30 s (or at a tap). */
  private fun everywhereNoteShown() {
    if (everywhereNoteTimer) return
    everywhereNoteTimer = true
    binding.note.postDelayed(
        {
          everywhereNoteTimer = false
          FolloEverywhere.noticeDone()
        },
        EVERYWHERE_NOTE_MS,
    )
  }

  /** Debug builds only (`follo_menu_set` = "more=1,hs=1,compass=0,…"): what the buttons are set to, for screenshots. */
  private fun menuSet(text: String) {
    val e = menuPrefs.edit()
    for (pair in text.split(',')) {
      val k = pair.substringBefore('=').trim()
      val on = pair.substringAfter('=', "1").trim() == "1"
      when (k) {
        "more", "compass", "searches" -> e.putBoolean(k, on)
        else -> FolloMenu.Layer.entries.firstOrNull { it.key == k }?.let { e.putBoolean("layer_" + it.key, on) }
      }
    }
    e.apply()
    renderMenu()
    watchChanged()
  }


  companion object {
    private const val REGION_PREFIX = "region-"
    /** The drop-down's row and title while an exempt phone shows what a volunteer's does (FP055 §7) */
    private const val VOLUNTEER_VIEW = "Volunteer view"
    /** How far out the map zooms where FP055 sets no limit (as it always did) */
    private const val SCOPE_FREE_MIN_ZOOM = 3.0
    private const val DRAW_PREFIX = "draw-" // pins among base camp's drawings (12 Nov 2026)
    private const val AREA_ID = "follo-area"
    private const val AREA_EDGE_ID = "follo-area-edge"
    private const val GEOFENCE_ID = "follo-geofence"
    private const val PAIR_ID = "follo-pair"
    private const val TRACK_ID = "follo-track"
    // A check-in goes with a fix better than this (the app's own ignoreInaccurateLocations is 100) and no older than this
    private const val CHECK_IN_MAX_ACC_M = 100f
    private const val CHECK_IN_MAX_AGE_MS = 60_000L
    private const val OPEN_CHECK_IN_DEDUPE_MS = FolloRules.OPEN_CHECK_IN_DEDUPE_MS
    private const val PAIR_M = 20.0
    private const val PAIR_TOL_M = 1.0
    private const val NO_SIGNAL_AFTER_MS = 10_000L
    /** A sharing phone with nothing accepted for this long (and nothing queued) is waiting on a fix (B10). */
    private const val SENT_STALE_MS = 60_000L
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
    /** Sideways (Alex 14 Nov 2026): the status panel and the card are this wide; the card is this tall at least. */
    private const val SIDE_PANEL_DP = 280f
    /** States in which the position pill is not shown: no position may be used or none is coming. */
    /** The Always step's column at most this wide (spec 15 Nov 2026). */
    private const val ALWAYS_MAX_DP = 420
    private val NO_POSITION_KINDS = setOf(Kind.NOT_SET_UP, Kind.SETTING_UP, Kind.NEED_LOCATION, Kind.LOCATION_OFF, Kind.NEED_PRECISE, Kind.OFF)
    /** Not set up and the permission screens: no "…" button, no two-way symbol, no drop-down (19 Nov 2026). */
    private val NO_MENU_KINDS =
        setOf(
            Kind.NOT_SET_UP,
            Kind.SETTING_UP,
            Kind.NEED_LOCATION,
            Kind.LOCATION_OFF,
            Kind.NEED_PRECISE,
            Kind.NEED_BACKGROUND,
            Kind.NEED_NOTIFICATIONS,
            Kind.NEED_BATTERY,
        )
    /** The "Share everywhere has been turned off by FOLLO." notice stays this long unless tapped */
    private const val EVERYWHERE_NOTE_MS = 30_000L
    /** The bubble under the two-way symbol stays this long */
    private const val LINK_BUBBLE_MS = 4_000L
    /** The map's turn eases toward the compass with this time constant (seconds). */
    private const val HEADING_TAU_S = 0.25f
    /** Closer than this to the compass = done turning (no more redraws). */
    private const val HEADING_SETTLED_DEG = 0.3f
    /** A two-finger twist turns the map only past this, so pinching to zoom never turns it by accident. */
    private const val ROTATE_START_DEG = 12f
    /** ≤ 30 camera redraws a second. */
    private const val FRAME_MIN_MS = 33L
    private const val START_ZOOM = 18.5
    /** savedInstanceState key: how far the rotate button has turned the screen (0, 90, 180, 270) */
    private const val TURN_KEY = "follo_turn"
    /** The heading pill is redrawn at most this often (~5 a second, spec §5). */
    private const val PILL_MIN_MS = 200L
    /** Pill text and dial at 55 % white while the compass needs calibrating (spec §5). */
    private const val POOR_ALPHA = 140
    /** All of Australia, shown until the first fix when the phone has no position at all. */
    private const val OVERVIEW_ZOOM = 4.0
    private const val LABEL_SUFFIX = "-label"
    private const val CONTACT_PREFIX = "contact-"
    /** A Significant-mode phone may not post for hours; keep the last server word that long. */
    private const val SERVER_STATUS_TTL_MS = 12 * 60 * 60 * 1000L
  }
}
