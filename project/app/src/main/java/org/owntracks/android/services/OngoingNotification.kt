package org.owntracks.android.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_CHANNEL_ONGOING
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_ID_ONGOING
import org.owntracks.android.R
import org.owntracks.android.data.EndpointState
import org.owntracks.android.data.EndpointStatus
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.ui.main.MainActivity
import timber.log.Timber

@OptIn(ExperimentalTime::class)
class OngoingNotification(private val context: Context, initialMode: MonitoringMode) {
  data class ServiceNotificationState(
      val title: String,
      val content: String,
      val subText: String,
      val notificationHigherPriority: Boolean,
  )

  private val notificationManagerCompat = NotificationManagerCompat.from(context)
  private val resultIntent by lazy {
    Intent(context, MainActivity::class.java)
        .setAction("android.intent.action.MAIN")
        .addCategory("android.intent.category.LAUNCHER")
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
  private val resultPendingIntent by lazy {
    PendingIntent.getActivity(
        context,
        0,
        resultIntent,
        BackgroundService.UPDATE_CURRENT_INTENT_FLAGS,
    )
  }
  private var serviceNotificationState =
      ServiceNotificationState(
          getMonitoringLabel(initialMode),
          "",
          "",
          false,
      )

  private val notificationBuilder =
      NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ONGOING)
          .setOngoing(true)
          .setContentIntent(resultPendingIntent)
          .setStyle(NotificationCompat.BigTextStyle())
          .setSmallIcon(R.drawable.ic_owntracks_80)
          .setSound(null, AudioManager.STREAM_NOTIFICATION)
          .setColor(context.getColor(R.color.follo_green))
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

  fun getNotification() =
      notificationBuilder
          .setContentTitle(serviceNotificationState.title)
          .setContentText(serviceNotificationState.content)
          .setWhen(Clock.System.now().toEpochMilliseconds())
          .setSubText(serviceNotificationState.subText)
          .setPriority(
              if (serviceNotificationState.notificationHigherPriority) {
                NotificationCompat.PRIORITY_DEFAULT
              } else {
                NotificationCompat.PRIORITY_MIN
              }
          )
          .build()

  private fun updateNotification() {
    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      notificationManagerCompat.notify(NOTIFICATION_ID_ONGOING, getNotification())
    } else {
      Timber.w(
          "Tried to update ongoing notification with $this but notification permissions were missing"
      )
    }
  }

  fun setEndpointState(endpointState: EndpointStatus, host: String) {
    // FOLLO: a plain line only while sending is not working; otherwise nothing to read.
    val notificationContent =
        when (endpointState.state) {
          EndpointState.ERROR,
          EndpointState.ERROR_CONFIGURATION,
          EndpointState.DISCONNECTED -> context.getString(R.string.follo_notif_trouble)
          else -> ""
        }
    serviceNotificationState = serviceNotificationState.copy(content = notificationContent)
    updateNotification()
  }

  // FOLLO: the notification says what the banner says. Move = the server has sharing on.
  private fun getMonitoringLabel(monitoringMode: MonitoringMode) = context.run {
    when (monitoringMode) {
      MonitoringMode.Move -> getString(R.string.follo_sharing_title)
      else -> getString(R.string.follo_notif_standby)
    }
  }

  fun setMonitoringMode(monitoringMode: MonitoringMode) {
    serviceNotificationState =
        serviceNotificationState.copy(title = getMonitoringLabel(monitoringMode), subText = "")
    updateNotification()
  }

  fun setTitle(title: String) {
    serviceNotificationState = serviceNotificationState.copy(title = title)
    updateNotification()
  }
}
