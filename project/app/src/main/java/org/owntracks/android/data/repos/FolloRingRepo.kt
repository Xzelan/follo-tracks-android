package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * FP066 (23 Nov 2026): which approach rings (FolloRings) the phone is in, per area, kept on disk so a restarted service
 * knows at once whether to watch. Set from the OS ring events and from fixes no worse than 500 m. Only the COUNT ever
 * leaves the phone (fd `ar`).
 */
@Singleton
class FolloRingRepo @Inject constructor(@ApplicationContext context: Context) {
  private val prefs = context.getSharedPreferences("follo_rings", Context.MODE_PRIVATE)
  val inside = MutableStateFlow(prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet())

  fun set(areas: Set<String>) {
    if (areas == inside.value) return
    Timber.i("approach rings: inside ${areas.size} (${if (areas.isEmpty()) "none" else areas.sorted().joinToString()})")
    inside.value = areas
    prefs.edit().putStringSet(KEY, areas).apply()
  }

  companion object {
    private const val KEY = "inside"
  }
}
