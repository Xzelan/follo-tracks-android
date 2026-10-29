package org.owntracks.android.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.owntracks.android.R
import org.owntracks.android.services.BackgroundService.Companion.UPDATE_CURRENT_INTENT_FLAGS
import org.owntracks.android.ui.main.MainActivity
import timber.log.Timber

/**
 * FOLLO push "check in" (29 Oct 2026): the fallback when base camp asked this phone to check in and
 * the app could not post a position by itself (no location permission, no fix within 30 s, or the
 * tracking service could not be started from the background): one notification asking the person to
 * open FOLLO Tracks, whose check-in on open does the rest. Channel "FOLLO messages".
 */
object FolloCheckInNotification {
  const val CHANNEL_ID = "follo_msgs"
  const val NOTIFICATION_ID = 7

  @SuppressLint("MissingPermission") // areNotificationsEnabled() is the check
  fun show(context: Context, title: String?) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) {
      Timber.w("check-in asked, cannot post by itself and notifications are off")
      return
    }
    val open =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val text = context.getString(R.string.follo_checkin_notif_text)
    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_follo_notif)
            .setColor(context.getColor(R.color.follo_green))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, open, UPDATE_CURRENT_INTENT_FLAGS)
            )
            .build()
    manager.notify(NOTIFICATION_ID, notification)
  }

  /** The check-in got through after all (a late fix): take the request down. */
  fun cancel(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }
}
