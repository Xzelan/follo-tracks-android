package org.owntracks.android.data.repos

import android.content.Context
import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.owntracks.android.di.ApplicationScope

/**
 * The phone's own walking track (Alex, 21 Oct 2026), drawn magenta like QField tracklogs. Fed by every fix the
 * phone publishes (also in the background) and by the screen's own fixes. Kept 12 h and across
 * restarts; fixes worse than 30 m are skipped, a point is kept every 3 m, and the line is broken
 * after a 2 min gap or a 50 m jump. Only on the phone: nothing extra is sent. Same rules as iOS.
 */
@Singleton
class FolloTrackRepo
@Inject
constructor(
    @ApplicationContext context: Context,
    locationRepo: LocationRepo,
    @ApplicationScope scope: CoroutineScope,
) {
  data class Point(val lat: Double, val lon: Double, val t: Long)

  private val prefs = context.getSharedPreferences("follo_track", Context.MODE_PRIVATE)
  private val mutablePoints = MutableStateFlow(trim(load(), System.currentTimeMillis()))
  val points: StateFlow<List<Point>> = mutablePoints
  private var unsaved = 0

  init {
    scope.launch { locationRepo.currentPublishedLocation.collect { it?.let(::add) } }
  }

  @Synchronized
  fun add(l: Location) {
    if (!l.hasAccuracy() || l.accuracy > WORST_ACCURACY_M) return
    val current = mutablePoints.value
    current.lastOrNull()?.let { last ->
      if (l.time <= last.t) return
      val d = FloatArray(1)
      Location.distanceBetween(last.lat, last.lon, l.latitude, l.longitude, d)
      if (d[0] < STEP_M) return
    }
    mutablePoints.value = trim(current + Point(l.latitude, l.longitude, l.time), System.currentTimeMillis())
    if (++unsaved >= 20) save()
  }

  /** The track split wherever there is a long gap or a jump. */
  fun segments(points: List<Point> = mutablePoints.value): List<List<Point>> {
    val segments = mutableListOf<List<Point>>()
    var current = mutableListOf<Point>()
    var previous: Point? = null
    val d = FloatArray(1)
    for (p in points) {
      previous?.let { q ->
        Location.distanceBetween(q.lat, q.lon, p.lat, p.lon, d)
        if (p.t - q.t > BREAK_GAP_MS || d[0] > BREAK_JUMP_M) {
          if (current.size >= 2) segments.add(current)
          current = mutableListOf()
        }
      }
      current.add(p)
      previous = p
    }
    if (current.size >= 2) segments.add(current)
    return segments
  }

  /** A new setup starts a clean track. */
  @Synchronized
  fun clear() {
    mutablePoints.value = emptyList()
    save()
  }

  @Synchronized
  fun save() {
    unsaved = 0
    prefs
        .edit()
        .putString(KEY, mutablePoints.value.joinToString(";") { "${it.lat},${it.lon},${it.t}" })
        .apply()
  }

  private fun load(): List<Point> =
      prefs.getString(KEY, null)?.split(";")?.mapNotNull { row ->
        row.split(",").takeIf { it.size == 3 }?.let { (la, lo, t) ->
          val lat = la.toDoubleOrNull()
          val lon = lo.toDoubleOrNull()
          val time = t.toLongOrNull()
          if (lat != null && lon != null && time != null) Point(lat, lon, time) else null
        }
      } ?: emptyList()

  private fun trim(points: List<Point>, now: Long): List<Point> {
    val oldest = now - KEEP_MS
    return if (points.firstOrNull()?.let { it.t < oldest } == true) points.filter { it.t >= oldest } else points
  }

  companion object {
    private const val KEY = "track"
    private const val KEEP_MS = 12 * 60 * 60 * 1000L
    private const val WORST_ACCURACY_M = 30f
    private const val STEP_M = 3f
    private const val BREAK_GAP_MS = 2 * 60 * 1000L
    private const val BREAK_JUMP_M = 50f
  }
}
