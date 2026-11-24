package org.owntracks.android.services.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.owntracks.android.data.repos.FolloPhotoRepo
import timber.log.Timber

/**
 * Wakes the photo queue (FolloPhotoRepo) when the app's process was gone (23 Nov 2026): one job on any network for the
 * details, thumbs and copies basecamp asked for, one on unmetered Wi-Fi for originals. Tries again (WorkManager
 * back-off) while that kind of work is still owed.
 */
@HiltWorker
class FolloPhotoWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: FolloPhotoRepo,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val wifi = inputData.getBoolean(WIFI, false)
    repo.pump()
    val owes = repo.stillOwes(wifi)
    Timber.d("FolloPhotoWorker wifi=$wifi still owes=$owes")
    return if (owes) Result.retry() else Result.success()
  }

  companion object {
    const val WIFI = "wifi"
  }
}
