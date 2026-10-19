package org.owntracks.android.ui.mixins

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.owntracks.android.services.BackgroundService
import timber.log.Timber

/** Provides a mixin for Activities that want to be able to start the service */
interface ServiceStarter {
  fun startService(context: Context, action: String? = null, intent: Intent? = null)

  class Impl : ServiceStarter {
    override fun startService(context: Context, action: String?, intent: Intent?) {
      // FOLLO: the service runs as a "location" foreground service, which Android only allows
      // with a location permission; without one there is nothing for it to do yet anyway.
      if (!hasLocationPermission(context)) {
        Timber.d("not starting the service (no location permission yet), action $action")
        return
      }
      Timber.d("requesting service start with action $action")
      ContextCompat.startForegroundService(
          context,
          (intent ?: Intent()).setClass(context, BackgroundService::class.java).apply {
            action?.also { this.action = it }
          },
      )
    }
  }
}

internal fun hasLocationPermission(context: Context): Boolean =
    listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        .any {
          ContextCompat.checkSelfPermission(context, it) ==
              android.content.pm.PackageManager.PERMISSION_GRANTED
        }
