package org.owntracks.android.data.repos

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.owntracks.android.model.messages.FolloDiag
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.preferences.types.MonitoringMode

/**
 * Builds the [FolloDiag] object that rides along on every location post (21 Oct 2026). The launch
 * reason is whatever first noted it: the boot/update receiver, the main screen, or the service.
 */
@Singleton
class FolloDiagRepo
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val endpointStateRepo: EndpointStateRepo,
    private val folloStatusRepo: FolloStatusRepo,
    private val pushTokenRepo: PushTokenRepo,
    private val folloGridRepo: FolloGridRepo,
    private val folloOfflineRepo: FolloOfflineRepo,
    private val folloRingRepo: FolloRingRepo,
    private val folloDeviceRepo: FolloDeviceRepo,
) {
  @Volatile private var launchReason: String? = null

  /** True while BackgroundService holds its foreground (location) service. */
  @Volatile var foregroundServiceRunning: Boolean = false

  /** The first caller after process start wins; later calls say nothing new about the launch. */
  fun noteLaunch(reason: String) {
    if (launchReason == null) launchReason = reason
  }

  fun build(trigger: MessageLocation.ReportType, monitoring: MonitoringMode): FolloDiag {
    val granted = { p: String ->
      ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val background =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val au =
        when {
          (fine || coarse) && background -> "always"
          fine || coarse -> "inuse"
          else -> "denied"
        }
    val importance =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
    val front = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val push = pushTokenRepo.fdFields() // FOLLO push "check in" (29 Oct 2026)
    val device = folloDeviceRepo.fdFields() // FP122 (28 Nov 2026): model + dual-band GPS, on some posts only
    // 28 Nov 2026 (Alex, the Phones page's %Locked and °C columns): is the phone in someone's hand, and how hot is it.
    // Neither needs a permission; both are read from the system as it stands (the battery broadcast is sticky).
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val lk = lockedFlag(runCatching { powerManager?.isInteractive }.getOrNull(), runCatching { keyguard?.isKeyguardLocked }.getOrNull())
    val tc =
        batteryCelsius(
            runCatching {
                  context
                      .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                      ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                }
                .getOrNull())
    return FolloDiag(
        fg = if (front) 1 else 0,
        au = au,
        pr = if (fine) 1 else 0,
        m = monitoring.value,
        up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000,
        lr = launchReason ?: "?",
        bt = if (foregroundServiceRunning) 1 else 0,
        lp = if (powerManager?.isPowerSaveMode == true) 1 else 0,
        q = endpointStateRepo.endpointQueueLength.value,
        src = trigger.name.lowercase(),
        pv = folloStatusRepo.heldPolyVersion(),
        pn = if (pushTokenRepo.notificationsAllowed()) 1 else 0,
        pt = push?.first,
        pp = push?.second,
        v = appVersion(),
        gv = folloGridRepo.heldVersion(),
        of = folloOfflineRepo.percent,
        off = if (org.owntracks.android.support.FolloPower.isOff) 1 else null,
        ar = folloRingRepo.inside.value.size,
        lk = lk,
        tc = tc,
        md = device?.first,
        l5 = device?.second,
    )
  }

  companion object {
    /** "0.1.32", or "0.1.32 (1)" for a second build of the same version: what the heading shows (MainActivity). */
    fun appVersion(name: String = org.owntracks.android.BuildConfig.VERSION_NAME, build: Int = org.owntracks.android.BuildConfig.FOLLO_BUILD): String =
        if (build > 0) "$name ($build)" else name

    /**
     * fd.lk (28 Nov 2026): 1 = the screen is off or the lock screen is showing (nobody is looking at the phone), 0 = in
     * use, null = the system told us neither. [interactive] is PowerManager.isInteractive, [keyguard] is
     * KeyguardManager.isKeyguardLocked (true on a swipe-only lock screen too; a phone with no lock screen at all counts as
     * locked only while its screen is off).
     */
    fun lockedFlag(interactive: Boolean?, keyguard: Boolean?): Int? =
        when {
          interactive == null && keyguard == null -> null
          interactive == false || keyguard == true -> 1
          else -> 0
        }

    /**
     * fd.tc (28 Nov 2026): BatteryManager.EXTRA_TEMPERATURE is in tenths of a degree Celsius. Null when the extra is
     * missing or is not a temperature a working phone can have (some report a huge or very negative placeholder).
     */
    fun batteryCelsius(tenths: Int?): Double? =
        if (tenths == null || tenths <= -400 || tenths >= 1200) null else tenths / 10.0
  }
}
