package org.owntracks.android.data.repos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.ui.main.FolloRules
import org.owntracks.android.ui.main.FolloScope
import timber.log.Timber

/**
 * Which search areas this phone has been to, and when (FP055, 19 Nov 2026; rules in FolloScope): what the screen shows
 * when the phone is not in one. Kept on the phone only — it is NEVER sent, and nothing here asks for a position: it is
 * fed by the fixes the app takes anyway (the ones it publishes, also in the background, and the screen's own), and by
 * the server's `been` rows (the areas it already holds this phone's fixes for). Also the exempt phones' "Volunteer
 * view" switch.
 */
@Singleton
class FolloScopeRepo
@Inject
constructor(
    @ApplicationContext context: Context,
    locationRepo: LocationRepo,
    private val waypointsRepo: WaypointsRepo,
    @ApplicationScope scope: CoroutineScope,
    @param:CoroutineScopes.IoDispatcher private val io: CoroutineDispatcher,
) {
  private val prefs = context.getSharedPreferences("follo_scope", Context.MODE_PRIVATE)
  private val mutableBeen = MutableStateFlow(FolloScope.decodeBeen(prefs.getString(KEY_BEEN, null)))

  /** Area code → epoch seconds the phone was last known inside it. */
  val been: StateFlow<Map<String, Long>> = mutableBeen

  /** An exempt phone behaves like a volunteer's (spec §7); kept over restarts, off by default. */
  var volunteerView: Boolean
    get() = prefs.getBoolean(KEY_VOLUNTEER, false)
    set(value) {
      prefs.edit().putBoolean(KEY_VOLUNTEER, value).apply()
    }

  init {
    // every fix the phone publishes was inside a held circle or boundary: note the area, also with the screen off
    scope.launch {
      locationRepo.currentPublishedLocation.collect { l ->
        if (l == null) return@collect
        try {
          val circles =
              withContext(io) { waypointsRepo.getAll() }
                  .filter { FolloRules.isWakeCircle(it.description, it.geofenceRadius) }
                  .map {
                    FolloRules.Circle(
                        it.geofenceLatitude.value,
                        it.geofenceLongitude.value,
                        it.geofenceRadius.toDouble(),
                        FolloRules.wakeArea(it.description).orEmpty(),
                    )
                  }
          note(l.latitude, l.longitude, FolloScope.areas(circles))
        } catch (e: Exception) {
          Timber.w(e, "Could not note the search area of a published fix")
        }
      }
    }
  }

  /** A fix the app already has: the held areas it is inside count as been to now (written at most once a minute). */
  @Synchronized
  fun note(lat: Double, lon: Double, areas: Map<String, List<FolloRules.Circle>>, nowS: Long = System.currentTimeMillis() / 1000) {
    val at = FolloScope.areasAt(lat, lon, areas)
    if (at.isEmpty()) return
    val had = mutableBeen.value
    if (at.all { nowS - (had[it] ?: 0L) < FolloScope.BEEN_EVERY_S }) return
    store(had + at.associateWith { nowS })
  }

  /** The server's `been` rows of a status element. */
  @Synchronized
  fun merge(rows: List<Pair<String, Long>>) {
    if (rows.isEmpty()) return
    val merged = FolloScope.mergeBeen(mutableBeen.value, rows)
    if (merged != mutableBeen.value) store(merged)
  }

  /** A new setup: whoever this phone was set up as before, their places are not this person's. */
  @Synchronized
  fun clear() {
    store(emptyMap())
  }

  private fun store(been: Map<String, Long>) {
    mutableBeen.value = been
    prefs.edit().putString(KEY_BEEN, FolloScope.encodeBeen(been)).apply()
  }

  companion object {
    private const val KEY_BEEN = "been"
    private const val KEY_VOLUNTEER = "volunteer_view"
  }
}
