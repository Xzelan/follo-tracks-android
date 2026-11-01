package org.owntracks.android.services

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.owntracks.android.data.repos.FolloDiagRepo
import org.owntracks.android.data.repos.FolloStatusRepo
import org.owntracks.android.data.repos.PushTokenRegistrar
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.model.messages.MessageWakeAck
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.ui.mixins.hasLocationPermission
import timber.log.Timber

/**
 * FOLLO push "check in" (29 Oct 2026). Base camp's live map can ask every phone of a search to
 * check in now: the FOLLO server sends a high-priority FCM data message `{act: "checkin", …}`. The
 * phone takes a fresh fix and posts it (BackgroundService.folloCheckIn, report type "user"); the
 * server answers Move if it is inside the area or a Basecamp circle. The person sees nothing unless
 * they are started. When the app cannot post by itself it shows one notification asking them to
 * open the app instead. Any other `act` is ignored. A new token is posted at once
 * (PushTokenRegistrar).
 *
 * Edit Circles (1 Nov 2026): `{act: "wake", area, v}` means base camp changed the area's wake-up
 * circles. The phone answers with a location-free [MessageWakeAck] through its normal outgoing
 * path (the queue, then the HTTP endpoint, whose reply is processed like any other: the new circles
 * and boundary arrive by clearWaypoints/setWaypoints + the `follo` status). No position is taken
 * or sent, nothing is shown. When the tracking service is not running (swiped away while standing
 * by) it is started the way the check-in push starts it, as a foreground service, because Data
 * Saver cuts a plain background process off mobile data (FOLLO-L, 1 Nov 2026) while a foreground
 * service is exempt; the WAKEACK action only queues the ack. If the system refuses that start, the
 * sender loop is armed the way a reconnect job does (bind only), and if the process is ended
 * first the queued ack goes on the next post anyway, which gets the circles too.
 */
@AndroidEntryPoint
class FolloPushService : FirebaseMessagingService() {
  @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar
  @Inject lateinit var messageProcessor: MessageProcessor
  @Inject lateinit var folloStatusRepo: FolloStatusRepo
  @Inject lateinit var folloDiagRepo: FolloDiagRepo
  @Inject lateinit var preferences: Preferences
  @Inject @ApplicationScope lateinit var scope: CoroutineScope

  override fun onNewToken(token: String) {
    pushTokenRegistrar.onToken(token)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    when (data["act"]) {
      ACT_CHECKIN -> checkIn(data)
      ACT_WAKE -> wakeAck(data)
      else -> Timber.d("push ignored: act=${data["act"]}")
    }
  }

  private fun checkIn(data: Map<String, String>) {
    Timber.i("push: base camp asks for a check-in (area ${data["area"]}, by ${data["by"]})")
    if (hasLocationPermission(this)) {
      try {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BackgroundService::class.java)
                .setAction(BackgroundService.INTENT_ACTION_FOLLO_CHECKIN),
        )
        return
      } catch (e: Exception) {
        // IllegalStateException / ForegroundServiceStartNotAllowedException: the system would not
        // let the tracking service start from here; ask the person instead.
        Timber.e(e, "check-in: could not start the tracking service")
      }
    }
    FolloCheckInNotification.show(this, data["title"])
  }

  private fun wakeAck(data: Map<String, String>) {
    val area = data["area"].orEmpty()
    val v = data["v"].orEmpty()
    if (!preferences.setupCompleted) {
      Timber.d("push wake: not set up, ignored (area $area)")
      return
    }
    Timber.i("push: base camp changed the $area circles (v $v, by ${data["by"]}), acknowledging without a position")
    val pv = folloStatusRepo.heldPolyVersion()
    if (!folloDiagRepo.foregroundServiceRunning && hasLocationPermission(this)) {
      try {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BackgroundService::class.java)
                .setAction(BackgroundService.INTENT_ACTION_FOLLO_WAKEACK)
                .putExtra(BackgroundService.EXTRA_WAKE_AREA, area)
                .putExtra(BackgroundService.EXTRA_WAKE_V, v)
                .putExtra(BackgroundService.EXTRA_WAKE_PV, pv),
        )
        Timber.i("push wake: tracking service started to send the ack")
        return
      } catch (e: Exception) {
        Timber.w(e, "push wake: could not start the tracking service, sending from here")
      }
    }
    messageProcessor.queueMessageForSending(MessageWakeAck.of(area, v, pv))
    scope.launch {
      try {
        messageProcessor.initializeAndReconnect().exceptionOrNull()?.let { Timber.w(it, "push wake: reconnect failed, the ack waits for the next post") }
      } catch (e: Exception) {
        Timber.w(e, "push wake: could not arm the sender, the ack waits for the next post")
      }
    }
  }

  companion object {
    const val ACT_CHECKIN = "checkin"
    const val ACT_WAKE = "wake"
  }
}
