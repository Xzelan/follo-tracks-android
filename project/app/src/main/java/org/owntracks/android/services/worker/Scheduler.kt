package org.owntracks.android.services.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.owntracks.android.preferences.Preferences
import timber.log.Timber

@Singleton
class Scheduler
@Inject
constructor(
    private val preferences: Preferences,
    @param:ApplicationContext private val context: Context,
) : Preferences.OnPreferenceChangeListener {
  init {
    preferences.registerOnPreferenceChangedListener(this)
  }

  private val anyNetworkConstraint =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  private val workManager = WorkManager.getInstance(context)

  /** Used by the background service to periodically ping a location */
  fun scheduleLocationPing() {
    val pingWorkRequest: WorkRequest =
        PeriodicWorkRequest.Builder(
                SendLocationPingWorker::class.java,
                preferences.ping.toLong(),
                TimeUnit.MINUTES,
            )
            .addTag(PERIODIC_TASK_SEND_LOCATION_PING)
            .setConstraints(anyNetworkConstraint)
            .build()
    Timber.d(
        "WorkManager queue task $PERIODIC_TASK_SEND_LOCATION_PING as ${pingWorkRequest.id} " +
            "with interval ${preferences.ping} minutes"
    )
    workManager.cancelAllWorkByTag(PERIODIC_TASK_SEND_LOCATION_PING)
    workManager.enqueue(pingWorkRequest)
  }

  /** Cancels all WorkManager tasks. Called on app exit */
  fun cancelAllTasks() {
    workManager.cancelAllWorkByTag(PERIODIC_TASK_SEND_LOCATION_PING)
  }

  companion object {
    private const val PERIODIC_TASK_SEND_LOCATION_PING = "PERIODIC_TASK_SEND_LOCATION_PING"
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::ping.name)) {
      workManager.cancelAllWorkByTag(PERIODIC_TASK_SEND_LOCATION_PING)
      scheduleLocationPing()
    }
  }
}
