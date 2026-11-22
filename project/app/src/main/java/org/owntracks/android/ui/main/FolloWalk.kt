package org.owntracks.android.ui.main

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Walking-only lines (FP079; a coordinator, 21 Nov 2026: tell driving from walking so you can see an area was covered on foot;
 * Alex: "the live map already shows walking only; the apps don't … has to be done carefully so no real data is
 * discarded"). DISPLAY ONLY: nothing stored or sent changes; this only decides which fixes are joined into a drawn line.
 *
 * The live map's rule (site/index.html "Walking-only tracks", 11 Nov 2026) as one pass over a phone's fixes in time
 * order. Contract and shared test data: FOLLO_tracks/docs/NEXT_BUILD_SPEC_20261122.md §B2, docs/next_build/
 * walk_vectors.json (reference: walk_ref.py). The line is CUT between two fixes when
 *  - they are more than [GAP] seconds apart, or
 *  - the step is longer than [CAP], or than [V] × max(seconds between them, [W]) (a jump over 30 m at the usual 5 s), or
 *  - the phone is more than V × T from where it was T ≥ W seconds ago in the same piece, or more than V × W from the
 *    oldest fix of the last W seconds (driving on 1 s fixes: every step is short).
 * A piece is drawn once it has [MINN] fixes over [MINT] seconds; one that ENDS under [KEEP] seconds long is not drawn
 * (a vehicle crawling at walking pace); the newest piece shows while its last fix is under [TAIL] seconds old.
 */
object FolloWalk {
  const val V = 2.0
  const val W = 15.0
  const val CAP = 100.0
  const val MINN = 3
  const val MINT = 15.0
  const val KEEP = 60.0
  const val GAP = 1800.0
  const val TAIL = 60.0
  private const val K = 111195.0

  /** One fix: time in epoch seconds, latitude, longitude. */
  class Fix(val t: Double, val lat: Double, val lon: Double)

  fun dist(a: Fix, b: Fix): Double =
      hypot((b.lon - a.lon) * K * cos(Math.toRadians((a.lat + b.lat) / 2)), (b.lat - a.lat) * K)

  /** A piece: the fixes joined so far. */
  class Piece internal constructor(first: Fix) {
    val t0: Double = first.t
    var last: Fix = first
      internal set
    internal val win = ArrayList<Fix>().apply { add(first) }
    val points = ArrayList<Fix>().apply { add(first) }
    val n: Int
      get() = points.size

    /** Enough fixes over enough time to be a walk and not a spike. */
    val ready: Boolean
      get() = n >= MINN && last.t - t0 >= MINT

    /** Long enough to stay drawn once it has ended. */
    val keeps: Boolean
      get() = last.t - t0 >= KEEP

    /** Whether it is drawn while it is the newest piece at [now]. */
    fun drawnOpen(now: Double): Boolean = ready && (keeps || now - last.t <= TAIL)

    /** Whether it is drawn once another piece has started after it. */
    val drawnEnded: Boolean
      get() = ready && keeps

    internal fun joins(p: Fix): Boolean {
      val dt = p.t - last.t
      if (dt > GAP) return false
      val d = dist(last, p)
      if (d > CAP || d > V * maxOf(dt, W)) return false
      var k = -1
      for (i in win.indices.reversed()) {
        if (p.t - win[i].t >= W) {
          k = i
          break
        }
      }
      if (k > 0) win.subList(0, k).clear() // time only moves on: older fixes can never be the reference again
      // win[0] = the newest fix that is W seconds old or more (else the piece's first); win[1] = the oldest younger one
      if (dist(win[0], p) > V * maxOf(p.t - win[0].t, W)) return false
      return !(k >= 0 && win.size > 1 && dist(win[1], p) > V * W)
    }

    internal fun add(p: Fix) {
      last = p
      win.add(p)
      points.add(p)
    }
  }

  /**
   * Takes a phone's fixes one at a time, oldest first. [open] = the newest piece; [add] says what the fix did, so a
   * map can keep its lines in step without building them all again.
   */
  class Walker {
    var open: Piece? = null
      private set
    private var lastT = Double.NEGATIVE_INFINITY

    /** What a fix did. [ended] = the piece that was open before it and is now over (null when the fix joined). */
    class Step(val joined: Boolean, val skipped: Boolean, val ended: Piece?)

    /**
     * [breakBefore] = the caller's own break (the phone's track is also cut after a 2 min gap or a 50 m jump): the fix
     * starts a new piece whatever the rule says. A fix whose time is not later than the one before is skipped.
     */
    fun add(p: Fix, breakBefore: Boolean = false): Step {
      if (p.t <= lastT) return Step(joined = false, skipped = true, ended = null)
      lastT = p.t
      val c = open
      if (c != null && !breakBefore && c.joins(p)) {
        c.add(p)
        return Step(joined = true, skipped = false, ended = null)
      }
      open = Piece(p)
      return Step(joined = false, skipped = false, ended = c)
    }

    /**
     * Whether [p], the very next fix to be added, would join the open piece (for the line drawn towards a fix the
     * map's arrow has not reached yet). Looking does not change what [add] then does.
     */
    fun wouldJoin(p: Fix, breakBefore: Boolean = false): Boolean {
      val c = open ?: return false
      return !breakBefore && p.t > lastT && c.joins(p)
    }
  }

  /** The lines to draw for [fixes] (oldest first) at time [now] (epoch seconds), each a list of fixes. */
  fun pieces(fixes: List<Fix>, now: Double): List<List<Fix>> {
    val out = ArrayList<List<Fix>>()
    val w = Walker()
    for (p in fixes) w.add(p).ended?.let { if (it.drawnEnded) out.add(it.points) }
    w.open?.let { if (it.drawnOpen(now)) out.add(it.points) }
    return out
  }

  /**
   * The same as flat lat,lon,… arrays (what the overlays draw). [end] (the phone's dot) is joined to the newest line
   * when that line reaches the newest fix and the dot is a step under [endWithin] metres away.
   */
  fun lines(fixes: List<Fix>, now: Double, end: Pair<Double, Double>? = null, endWithin: Double = 50.0): List<DoubleArray> {
    val ps = pieces(fixes, now)
    val newest = fixes.lastOrNull()
    return ps.mapIndexed { i, piece ->
      val join =
          end != null && i == ps.lastIndex && newest != null && piece.last() === newest &&
              FolloWatch.metres(newest.lat, newest.lon, end.first, end.second).let { it > 0 && it < endWithin }
      val a = DoubleArray(piece.size * 2 + if (join) 2 else 0)
      for ((k, p) in piece.withIndex()) {
        a[2 * k] = p.lat
        a[2 * k + 1] = p.lon
      }
      if (join && end != null) {
        a[a.size - 2] = end.first
        a[a.size - 1] = end.second
      }
      a
    }
  }
}
