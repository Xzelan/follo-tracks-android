package org.owntracks.android.data.repos

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
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
    )
  }
}
