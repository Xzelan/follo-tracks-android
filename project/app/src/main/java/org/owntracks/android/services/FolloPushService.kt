package org.owntracks.android.services

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.owntracks.android.data.repos.PushTokenRepo
import org.owntracks.android.ui.mixins.hasLocationPermission
import timber.log.Timber

/**
 * FOLLO push "check in" (29 Oct 2026). Base camp's live map can ask every phone of a search to
 * check in now: the FOLLO server sends a high-priority FCM data message `{act: "checkin", …}`. The
 * phone takes a fresh fix and posts it (BackgroundService, report type "response"); the server
 * answers Move if it is inside the area or a Basecamp circle. The person sees nothing unless they
 * are started. When the app cannot post by itself it shows one notification asking them to open the
 * app instead. Any other `act` is ignored. Token changes are kept for the next location post.
 */
@AndroidEntryPoint
class FolloPushService : FirebaseMessagingService() {
  @Inject lateinit var pushTokenRepo: PushTokenRepo

  override fun onNewToken(token: String) {
    pushTokenRepo.token = token
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    if (data["act"] != ACT_CHECKIN) {
      Timber.d("push ignored: act=${data["act"]}")
      return
    }
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

  companion object {
    const val ACT_CHECKIN = "checkin"
  }
}
