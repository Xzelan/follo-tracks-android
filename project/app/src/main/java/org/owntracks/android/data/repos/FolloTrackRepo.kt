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

  /**
   * What changed, for the map: [serial] counts every change; [reset] = start again from [snapshot] (load, clear, trim);
   * otherwise [last] was appended, on a new segment when [newSegment]. A map that missed a serial rebuilds from the
   * snapshot. This is cheap on purpose: the whole list is not copied per fix (review 21 Oct 2026).
   */
  data class Update(val serial: Int, val reset: Boolean, val last: Point?, val newSegment: Boolean)

  private val prefs = context.getSharedPreferences("follo_track", Context.MODE_PRIVATE)
  private val list = trim(load(), System.currentTimeMillis()).toMutableList()
  private val mutableUpdates = MutableStateFlow(Update(0, true, list.lastOrNull(), false))
  val updates: StateFlow<Update> = mutableUpdates
  private var unsaved = 0
  private var lastTrimAt = System.currentTimeMillis()

  /** A copy of the track, oldest first. */
  @Synchronized fun snapshot(): List<Point> = list.toList()

  init {
    scope.launch { locationRepo.currentPublishedLocation.collect { it?.let(::add) } }
  }

  @Synchronized
  fun add(l: Location) {
    if (!l.hasAccuracy() || l.accuracy > WORST_ACCURACY_M) return
    val last = list.lastOrNull()
    var newSegment = false
    if (last != null) {
      if (l.time <= last.t) return
      val d = FloatArray(1)
      Location.distanceBetween(last.lat, last.lon, l.latitude, l.longitude, d)
      if (d[0] < STEP_M) return
      newSegment = l.time - last.t > BREAK_GAP_MS || d[0] > BREAK_JUMP_M
    }
    val p = Point(l.latitude, l.longitude, l.time)
    list.add(p)
    val now = System.currentTimeMillis()
    var reset = false
    if (now - lastTrimAt > TRIM_EVERY_MS) {
      lastTrimAt = now
      val kept = trim(list, now)
      if (kept.size != list.size) {
        list.clear()
        list.addAll(kept)
        reset = true
      }
    }
    mutableUpdates.value = Update(mutableUpdates.value.serial + 1, reset, p, newSegment)
    if (++unsaved >= 20) save()
  }

  /** The track split wherever there is a long gap or a jump. */
  fun segments(points: List<Point> = snapshot()): List<List<Point>> {
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
    list.clear()
    mutableUpdates.value = Update(mutableUpdates.value.serial + 1, true, null, false)
    save()
  }

  @Synchronized
  fun save() {
    unsaved = 0
    prefs
        .edit()
        .putString(KEY, list.joinToString(";") { "${it.lat},${it.lon},${it.t}" })
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
    /** Old points are dropped this often, not on every fix. */
    private const val TRIM_EVERY_MS = 5 * 60 * 1000L
  }
}
