package org.owntracks.android.data.repos

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.ui.mixins.hasLocationPermission
import timber.log.Timber

/**
 * FOLLO push "check in" (29 Oct 2026): where a Firebase token lands (App.setUpPush,
 * FolloPushService.onNewToken). A token the server has not seen is sent at once: the phone posts
 * one position (the push check-in path, BackgroundService.folloCheckIn, report type "user") and the
 * fd inclusion rule's "token changed" branch puts `pt` in it. Only when set-up is complete, a
 * location permission exists, and the app is in front or its tracking service already runs: a
 * service started from the background would be refused by Android 12+ and must not crash the app
 * for a token; a rotated token then simply rides on the next post.
 */
@Singleton
class PushTokenRegistrar
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val pushTokenRepo: PushTokenRepo,
    private val preferences: Preferences,
    private val folloDiagRepo: FolloDiagRepo,
) {
  fun onToken(token: String?) {
    if (!pushTokenRepo.update(token)) return
    if (!preferences.setupCompleted || !hasLocationPermission(context)) {
      Timber.d("push token: not set up / no location permission, sent on the next post")
      return
    }
    val importance =
        ActivityManager.RunningAppProcessInfo()
            .also { ActivityManager.getMyMemoryState(it) }
            .importance
    val front = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    if (!front && !folloDiagRepo.foregroundServiceRunning) {
      Timber.i("push token: app in the background, sent on the next post")
      return
    }
    try {
      ContextCompat.startForegroundService(
          context,
          Intent(context, BackgroundService::class.java)
              .setAction(BackgroundService.INTENT_ACTION_FOLLO_CHECKIN),
      )
      Timber.i("push token: posting a position so the server gets it")
    } catch (e: Exception) {
      Timber.w(e, "push token: could not start the tracking service, sent on the next post")
    }
  }
}
