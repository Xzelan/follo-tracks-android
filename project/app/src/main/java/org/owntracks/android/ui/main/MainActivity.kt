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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
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
  @Inject lateinit var folloGridRepo: org.owntracks.android.data.repos.FolloGridRepo
  @Inject lateinit var folloDrawRepo: org.owntracks.android.data.repos.FolloDrawRepo
  @Inject lateinit var folloOfflineRepo: org.owntracks.android.data.repos.FolloOfflineRepo
  @Inject lateinit var requirementsChecker: RequirementsChecker
  @Inject lateinit var notificationsStash: NotificationsStash
  @Inject lateinit var contactImageBindingAdapter: ContactImageBindingAdapter

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
      applyOrientation(resources.configuration.orientation)
      windowInsets
    }

    compass = FolloCompass(this) { if (camera == Camera.NAV) requestNavFrame() }
    // Upright every time the app is opened; a turn chosen with the rotate button is kept only while the screen is
    // being made again (savedInstanceState), not over a fresh open.
    screenTurner = FolloScreenTurner(this)
    screenTurner.open(savedInstanceState?.takeIf { it.containsKey(TURN_KEY) }?.getInt(TURN_KEY))
    binding.rotate.setOnClickListener { screenTurner.turn() }
    binding.recentre.setOnClickListener { recentre() }
    // The buttons hang under the banner and the position sits on the card, both of which grow and shrink with what
    // they say: place them again after any change (posted: a layout change asked for inside a layout pass is lost)
    val replace = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
      if (l != ol || t != ot || r != or || b != ob) binding.root.post { placeOverlays() }
    }
    binding.banner.addOnLayoutChangeListener(replace)
    binding.bottom.addOnLayoutChangeListener(replace)
    binding.notice.addOnLayoutChangeListener(replace)
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
    binding.note.setOnClickListener { onNoteClicked() }

    binding.action.setOnClickListener { onActionClicked() }
    // Power button (13 Nov 2026): hold 2 s = Off, one tap = on again
    binding.power.onTurnOff = { setPower(off = true) }
    binding.power.onTurnOn = { setPower(off = false) }
    binding.power.onHint = { Toast.makeText(this, R.string.follo_power_hold_hint, Toast.LENGTH_SHORT).show() }
    // The recentre button brings the arrow back (14 Nov 2026: a tap on the card no longer does); a tap on the banner
    // still does (Alex 24 Oct 2026). The offline map's sign on the card is gone: the second tick says it.
    binding.banner.setOnClickListener { recentre() }
    // Long press: send a position now (the way out of standing still inside a wake-up circle, review 21 Oct 2026).
    binding.card.setOnLongClickListener {
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
          }
        }
        // The phone decides green/grey at the area edge itself, so every new fix may change it.
        launch {
          viewModel.currentLocation.collect {
            if (FolloPower.isOff) return@collect // a fix already on its way when the app was turned Off: forgotten
            it?.let(viewModel::addTrack)
            it?.let { l -> folloOfflineRepo.notePosition(l.latitude, l.longitude) }
            it?.let(compass::updateDeclination)
            it?.let(::followCamera)
            checkInIfReady()
            drawPair()
            render()
          }
        }
        launch { viewModel.lastPublished.collect { render() } }
        launch { folloOfflineRepo.state.collect { renderOffline(it) } }
        launch {
          folloGridRepo.grid.collect {
            gridOverlay?.grid = it
            map.invalidate()
          }
        }
        launch { folloDrawRepo.draw.collect(::showDrawings) }
        launch { viewModel.trackUpdates.collect(::drawTrack) }
        launch { viewModel.displayNameState.collect { render() } }
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
    // The notice and the card: sideways as wide as the status panel, hard into the right side, sitting on the band.
    // A little padding at the start and top keeps the card's shadow from being cut off.
    val shadow = dp(8f)
    binding.bottom.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      width = if (land) panelWidth() + barsRight + shadow else match
      gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
    }
    binding.card.minimumHeight = if (land) dp(LAND_CARD_DP) else 0
    binding.card.updatePadding(
        left = dp(16f),
        top = dp(if (land) 8f else 13f),
        right = dp(14f) + if (land) barsRight else 0,
        bottom = dp(if (land) 8f else 13f),
    )
    binding.notice.updatePadding(left = dp(if (land) 16f else 12f), right = dp(12f) + if (land) barsRight else 0)
    binding.notice.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { bottomMargin = if (land) 0 else dp(8f) }
    styleBottom()
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
    applyFoot()
    placeOverlays()
  }

  /** Sideways, only the corner that faces the map is rounded; upright the panels keep their usual look. */
  private fun styleBottom() {
    val land = isLand()
    val r = dp(16f).toFloat()
    val noticeShown = binding.notice.visibility == View.VISIBLE
    fun corner(color: Int, topLeft: Float) =
        android.graphics.drawable.GradientDrawable().apply {
          setColor(color)
          cornerRadii = floatArrayOf(topLeft, topLeft, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    if (land) {
      binding.card.background = corner(getColor(R.color.follo_card), if (noticeShown) 0f else r)
      binding.notice.background = corner(getColor(R.color.follo_amber), r)
    } else {
      binding.card.setBackgroundResource(R.drawable.bg_follo_card)
      binding.notice.setBackgroundResource(R.drawable.bg_follo_notice)
    }
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

  /** The recentre button's distance from the left edge and the rotate button's from the right edge (px). */
  private fun recentreStart(): Int = dp(12f) + if (isLand()) barsLeft else 0

  private fun rotateEnd(): Int =
      if (isLand()) (binding.banner.width.takeIf { it > 0 } ?: (panelWidth() + barsRight)) + dp(10f) else dp(12f)

  /**
   * The things over the map (14 Nov 2026). Upright: the recentre button top left and the rotate button top right,
   * 10 dp under the banner. Sideways: the recentre button top left under the status bar, the rotate button level with
   * it just left of the status panel. The position pill just above the right end of the card (or the notice).
   */
  private fun placeOverlays() {
    if (!::binding.isInitialized) return
    val land = isLand()
    val top = if (land) barsTop + dp(12f) else binding.banner.height + dp(10f)
    val start = recentreStart()
    val end = rotateEnd()
    binding.recentre.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginStart != start) {
        topMargin = top
        marginStart = start
      }
    }
    binding.rotate.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
      if (topMargin != top || marginEnd != end) {
        topMargin = top
        marginEnd = end
      }
    }
    val first = if (binding.notice.visibility == View.VISIBLE) binding.notice else binding.card
    if (binding.root.height > 0 && binding.bottom.height > 0) {
      val above = binding.root.height - (binding.bottom.top + first.top) + dp(8f)
      val right = if (land) dp(14f) + barsRight else dp(16f)
      binding.coords.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
        if (bottomMargin != above || marginEnd != right) {
          bottomMargin = above
          marginEnd = right
        }
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    if (::screenTurner.isInitialized) outState.putInt(TURN_KEY, screenTurner.degrees)
  }

  /**
   * The card sits straight on the bottom band: upright 8 dp above it, sideways on it. The band is as tall as its row
   * plus the navigation bar under it.
   */
  private fun applyFoot() {
    if (!::binding.isInitialized) return
    val land = isLand()
    val band = binding.creditBar.height.takeIf { it > 0 } ?: (dp(4f + 18f) + barsBottom + dp(if (land) 5f else 4f))
    val shadow = dp(8f)
    binding.bottom.updatePadding(
        left = if (land) shadow else dp(16f),
        top = if (land) shadow else dp(12f),
        right = if (land) 0 else dp(16f),
        bottom = band + if (land) 0 else dp(8f),
    )
    binding.offCover.updatePadding(bottom = if (land) band else 0)
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
    remindedBackground = false   // the While Using reminder is once per open
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
    // Check in every time the app comes to the front (Alex 25 Oct 2026, as on iOS): the reply brings the wake-up
    // circles for any search in the next 14 days. Sent once the screen has a fix better than 100 m.
    if (preferences.setupCompleted && requirementsChecker.hasLocationPermissions()) {
      pendingCheckIn = PendingCheckIn.OPEN
      checkInIfReady()
    }
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
  }

  /** A check-in (open or long-press) waiting for a usable fix; the app's own 100 m filter would drop a worse one. */
  /** A check-in waiting for a good enough fix: USER = the person's long-press (sent as is), OPEN = automatic (judged). */
  private enum class PendingCheckIn {
    USER,
    OPEN,
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
      PendingCheckIn.OPEN -> openCheckIn()
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
    // other (Samsung test 1 Nov 2026: two hellos per open); one automatic check-in per 10 s is plenty.
    val now = SystemClock.elapsedRealtime()
    if (now - lastOpenCheckInAt < OPEN_CHECK_IN_DEDUPE_MS) return
    lastOpenCheckInAt = now
    startService(
        this,
        BackgroundService.INTENT_ACTION_FOLLO_CHECKIN,
        Intent().putExtra(BackgroundService.EXTRA_CHECKIN_SRC, LocationProcessor.CheckInSource.OPEN.wire),
    )
  }

  private var lastOpenCheckInAt = -OPEN_CHECK_IN_DEDUPE_MS

  /** The searches whose wake-up circles this phone holds ("FOLLO MV wake 3" -> MV), for "Ready for MV" (Alex 25 Oct 2026). */
  private var wakeAreas: List<String> = emptyList()
  /** The newest circles' date the held wake-up circles carry (Edit Circles, 1 Nov 2026), "1-11-2026", or null. */
  private var wakeUpdate: String? = null

  private fun wakeAreaOf(w: WaypointModel): String? =
      if (w.geofenceRadius > 0) FolloRules.wakeArea(w.description) else null

  private fun refreshWakeAreas(all: List<WaypointModel>) {
    drawGeofences(all)
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
      // on): the reminder comes back once every time the app is opened, with the way to Settings. Same on iOS.
      if (!remindedBackground) {
        remindedBackground = true
        showBackgroundReminder()
        return
      }
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

  /** Once per app open while location stays While Using (Android shows its own prompt only once). */
  private var remindedBackground = false

  private fun showBackgroundReminder() {
    promptInFlight = true
    render()
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.follo_bg_reminder_title)
        .setMessage(R.string.follo_bg_reminder_msg)
        .setPositiveButton(R.string.follo_bg_reminder_open) { _, _ ->
          promptInFlight = false
          render()
          Toast.makeText(this, R.string.follo_location_howto, Toast.LENGTH_LONG).show()
          openAppSettings()
        }
        .setNegativeButton(R.string.follo_disclosure_not_now) { _, _ ->
          promptInFlight = false
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
    // Power button (13 Nov 2026; on the card since 14 Nov): there once the phone is set up
    val powerVisibility = if (b.kind == Kind.NOT_SET_UP) View.GONE else View.VISIBLE
    if (binding.power.visibility != powerVisibility) binding.power.visibility = powerVisibility
    binding.power.off = b.kind == Kind.OFF
    // "No search area loaded yet…" above the card: in the standing-by states, until the circles arrive
    val noticeVisibility = if (b.kind == Kind.STANDBY && wakeAreas.isEmpty()) View.VISIBLE else View.GONE
    if (binding.notice.visibility != noticeVisibility) {
      binding.notice.visibility = noticeVisibility
      styleBottom() // sideways the card's corner is square under the notice
    }

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
    if (queued > 0 && !b.saysBacklog) parts += getString(R.string.follo_waiting, queued)
    // No battery on the card (Alex 13 Nov 2026: "keep it on live maps"; the phone still sends it)
    val text = parts.joinToString(" · ")
    if (binding.last.text.toString() != text) binding.last.text = text
    // Battery saver (field review 9 Nov 2026: Leonie Ch 55 fixes in 5 h, Heiner, Matt C, Lissa, Mitchell R, Paul, Tyrone,
    // David S, Marie — fd.lp 1 ⇒ thin, gappy tracks even with the app exempt from battery optimisation). It is the phone's
    // own setting, so the note says what to do and a tap opens the Battery saver page; it comes first, over the
    // notifications note.
    batterySaverNote = b.kind != Kind.NOT_SET_UP && b.kind != Kind.OFF && isPowerSaveMode()
    val note =
        when {
          batterySaverNote -> getString(R.string.follo_battery_saver_note)
          b.kind != Kind.NOT_SET_UP && !promptInFlight && !notificationPermissionRequester.hasPermission() ->
              getString(R.string.follo_notif_off_note)
          else -> ""
        }
    if (binding.note.text.toString() != note) binding.note.text = note
    binding.note.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
    renderTicks()
    renderCoords(b)
  }

  // ── The ticks at the bottom right and the phone's own position (Alex, 14 Nov 2026) ─────────────────

  /** The three ticks: checked in with FOLLO today, offline map saved, latest app; a warning sign for any that isn't. */
  private fun renderTicks() {
    if (!::binding.isInitialized) return
    val now = System.currentTimeMillis()
    val st = viewModel.folloStatus.value
    val circles = st != null && FolloRules.checkedInToday(st.receivedAt, now, java.time.ZoneId.systemDefault())
    val saved = folloOfflineRepo.state.value is org.owntracks.android.data.repos.FolloOfflineRepo.State.Saved
    val latest = FolloRules.isLatest(BuildConfig.VERSION_CODE.toLong(), st?.message?.latest?.android)
    fun tick(v: android.widget.TextView, ok: Boolean) {
      val t = if (ok) FolloRules.TICK_OK else FolloRules.TICK_WARN
      if (v.text.toString() != t) v.text = t
    }
    tick(binding.tickCircles, circles)
    tick(binding.tickMap, saved)
    tick(binding.tickApp, latest)
    val words =
        listOf(
                getString(if (circles) R.string.follo_tick_circles_ok else R.string.follo_tick_circles_bad),
                getString(if (saved) R.string.follo_tick_map_ok else R.string.follo_tick_map_bad),
                getString(if (latest) R.string.follo_tick_app_ok else R.string.follo_tick_app_bad),
            )
            .joinToString(", ")
    binding.ticks.contentDescription = getString(R.string.follo_ticks_cd, words)
  }

  /** The position pill: whenever the app has a fix of its own, except while Off or while location can't be used. */
  private fun renderCoords(b: Banner) {
    val l = viewModel.currentLocation.value
    val show = l != null && !FolloPower.isOff && b.kind !in NO_POSITION_KINDS
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

  /** Press and hold the position: the phone's share sheet with "GDA2020 lat, lon (MGA55 E … N …)". */
  private fun shareCoords() {
    val l = viewModel.currentLocation.value ?: return
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
            phone = binding.who.text.toString(),
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
    val visibility = if (followPaused && !FolloPower.isOff) View.VISIBLE else View.GONE
    if (binding.recentre.visibility != visibility) binding.recentre.visibility = visibility
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
          if (FolloRules.holdsMap(dragged, pinched, rotated, doubleTap)) followPaused = true
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
            showCredit()
            return false
          }

          override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
            if (camera == Camera.NAV && !followPaused) requestNavFrame()
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
    if (known != null) {
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
    if (FolloPower.isOff) return // no map, no credit
    val source = map.tileProvider.tileSource
    val text =
        if (source is FolloTileSource) {
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
    val ox = v.centerX() - map.width / 2f // dot right of the view centre by ox
    val oy = v.top + down * v.height() - map.height / 2f // dot below the view centre by oy
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
      val rotateX = map.width - rotateEnd() - half
      val mid = ((recentreX + rotateX) / 2f).coerceIn(1f, map.width.toFloat())
      android.graphics.RectF(0f, top, 2 * mid, bottom)
    } else {
      val top = (binding.banner.bottom - map.top).coerceAtLeast(0).toFloat()
      // the top of the notice when it is shown, else of the card (both inside `bottom`, under its top padding)
      val bottom = (binding.bottom.top + binding.bottom.paddingTop - map.top).toFloat().takeIf { it > top } ?: map.height.toFloat()
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
  // Alex 21 Oct 2026: the map turns with the COMPASS (the way the phone points), the arrow stays pointing up at 80 %
  // down the visible map, centred sideways. Without a compass reading the walking direction turns it instead.
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
    if (!followPaused && !gestureActive) {
      val pos = navPosition(now)
      if (pos != null) {
        upBearing = shownHeading
        map.mapOrientation = -shownHeading
        map.controller.setCenter(headingUpCentre(pos, if (isLand()) LAND_DOWN else NAV_DOWN, shownHeading))
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
        setPoints(points)
        outlinePaint.color = getColor(R.color.follo_track)
        outlinePaint.strokeWidth = resources.displayMetrics.density * 4
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
      }

  /**
   * The phone's own walking track: magenta like QField tracklogs, above the area, below the pair line. Each fix is
   * appended to the current line; the whole track is rebuilt only on load, clear, trim or after missed updates
   * (review 21 Oct 2026: a full rebuild per fix stuttered after hours of walking). A point newer than where the arrow
   * has glided to waits in [trackHeld] until the arrow passes it (5 Nov 2026), so the line never runs ahead of it.
   */
  private fun drawTrack(u: org.owntracks.android.data.repos.FolloTrackRepo.Update) {
    if (!::map.isInitialized || u.serial == drawnTrackSerial) return
    val incremental =
        !u.reset && u.last != null && u.serial == drawnTrackSerial + 1 && (trackTail != null || trackHeld.isNotEmpty())
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
    val at = map.overlays.indexOfLast { isAreaLayer(it) || it === gridOverlay || it === drawOverlay } + 1
    val all = viewModel.trackSnapshot()
    // Only up to where the arrow is; the rest waits for it
    val reached = trackReachedT()
    val shown = all.takeWhile { it.t <= reached }
    val d = FloatArray(1)
    for (i in shown.size until all.size) {
      val q = all.getOrNull(i - 1)
      val p = all[i]
      val newSegment =
          q != null &&
              org.owntracks.android.data.repos.FolloTrackRepo.isBreak(
                  p.t - q.t, d.also { Location.distanceBetween(q.lat, q.lon, p.lat, p.lon, it) }[0])
      trackHeld.addLast(p to newSegment)
    }
    trackLastDrawn = shown.lastOrNull()
    val segments = viewModel.trackSegments(shown)
    segments.reversed().forEach { seg -> map.overlays.add(at, trackLine(seg.map { GeoPoint(it.lat, it.lon) })) }
    // The tail is the last segment, or a fresh one-point line when the newest point stands alone
    val lastSeg = segments.lastOrNull()
    trackTail =
        if (lastSeg != null && lastSeg.last() == shown.lastOrNull()) map.overlays.filterIsInstance<Polyline>().last { it.id == TRACK_ID }
        else shown.lastOrNull()?.let { trackLine(listOf(GeoPoint(it.lat, it.lon))).also { l -> map.overlays.add(at, l) } }
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
      val p = GeoPoint(lp.lat, lp.lon)
      val tail = trackTail
      if (newSegment || tail == null) {
        val at = map.overlays.indexOfLast { isAreaLayer(it) || it === gridOverlay || it === drawOverlay } + 1
        trackTail = trackLine(listOf(p)).also { map.overlays.add(at, it) }
      } else tail.addPoint(p)
      trackLastDrawn = lp
    }
    drawTrackToArrow()
  }

  /**
   * The line from the newest drawn track point to the arrow, while the arrow heads for a point not drawn yet. None
   * across a break in the track (the held point starts a new segment) or when there is no arrow.
   */
  private fun drawTrackToArrow() {
    val last = trackLastDrawn
    val held = trackHeld.firstOrNull()
    val arrow = navArrow?.position?.takeIf { navOn && shownT > 0L && it.latitude != 0.0 }
    if (last == null || held == null || held.second || arrow == null) {
      trackToArrow?.let { map.overlays.remove(it) }
      trackToArrow = null
      return
    }
    val ends = listOf(GeoPoint(last.lat, last.lon), GeoPoint(arrow.latitude, arrow.longitude))
    val line = trackToArrow
    if (line != null) line.setPoints(ends)
    else {
      val at = map.overlays.indexOfLast { isAreaLayer(it) || it === gridOverlay || it === drawOverlay } + 1
      trackToArrow = trackLine(ends).also { map.overlays.add(at, it) }
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

  /** Other people's dots are half the size of the cached 48 dp picture (Alex, 2 Nov 2026). */
  private fun contactIcon(bitmap: android.graphics.Bitmap) =
      bitmap.scale((bitmap.width / 2).coerceAtLeast(1), (bitmap.height / 2).coerceAtLeast(1)).toDrawable(resources)

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
          existing.icon = contactIcon(bitmap)
          existing.relatedObject = bitmap
        }
      } else if (!isDestroyed) {
        map.overlays.add(
            Marker(map).apply {
              this.id = id
              position = point
              icon = contactIcon(bitmap)
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
    private const val DRAW_PREFIX = "draw-" // pins among base camp's drawings (12 Nov 2026)
    private const val AREA_ID = "follo-area"
    private const val AREA_EDGE_ID = "follo-area-edge"
    private const val GEOFENCE_ID = "follo-geofence"
    private const val PAIR_ID = "follo-pair"
    private const val TRACK_ID = "follo-track"
    // A check-in goes with a fix better than this (the app's own ignoreInaccurateLocations is 100) and no older than this
    private const val CHECK_IN_MAX_ACC_M = 100f
    private const val CHECK_IN_MAX_AGE_MS = 60_000L
    private const val OPEN_CHECK_IN_DEDUPE_MS = 10_000L
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
    private const val NAV_DOWN = 0.80f
    /** Sideways (14 Nov 2026): the arrow in the middle of the open map, top to bottom. */
    private const val LAND_DOWN = 0.5f
    /** Sideways (Alex 14 Nov 2026): the status panel and the card are this wide; the card is this tall at least. */
    private const val SIDE_PANEL_DP = 280f
    private const val LAND_CARD_DP = 80f
    /** States in which the position pill is not shown: no position may be used or none is coming. */
    private val NO_POSITION_KINDS = setOf(Kind.NOT_SET_UP, Kind.SETTING_UP, Kind.NEED_LOCATION, Kind.LOCATION_OFF, Kind.NEED_PRECISE, Kind.OFF)
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
    /** All of Australia, shown until the first fix when the phone has no position at all. */
    private const val OVERVIEW_ZOOM = 4.0
    private const val LABEL_SUFFIX = "-label"
    private const val CONTACT_PREFIX = "contact-"
    /** A Significant-mode phone may not post for hours; keep the last server word that long. */
    private const val SERVER_STATUS_TTL_MS = 12 * 60 * 60 * 1000L
  }
}
