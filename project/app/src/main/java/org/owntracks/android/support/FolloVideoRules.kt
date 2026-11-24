package org.owntracks.android.support

/**
 * Rules for the app's own video screen (24 Nov 2026, Alex: "get the original to record at 1080p H.264, then the preview
 * smaller, and the thumbnail smaller again"), kept apart so they can be tested.
 */
object FolloVideoRules {
  /** H.264 / AVC: every browser and computer at basecamp plays it (HEVC / AV1 do not). */
  const val VIDEO_MIME = "video/avc"

  /** AAC: plays everywhere (the camcorder profile can pick AMR-NB, 8 kHz telephone sound). */
  const val AUDIO_MIME = "audio/mp4a-latm"

  /** The server takes originals up to 4 GiB; stop a little short (≈ 30 min of 1080p at ~17 Mbit/s). */
  const val MAX_BYTES = 4L * 1024 * 1024 * 1024 - 64L * 1024 * 1024

  // VideoRecordEvent.Finalize error codes (CameraX 1.7), copied so the rule can be tested without CameraX.
  const val ERROR_NONE = 0
  const val ERROR_UNKNOWN = 1
  const val ERROR_FILE_SIZE_LIMIT_REACHED = 2
  const val ERROR_INSUFFICIENT_STORAGE = 3
  const val ERROR_SOURCE_INACTIVE = 4
  const val ERROR_INVALID_OUTPUT_OPTIONS = 5
  const val ERROR_ENCODING_FAILED = 6
  const val ERROR_RECORDER_ERROR = 7
  const val ERROR_NO_VALID_DATA = 8
  const val ERROR_DURATION_LIMIT_REACHED = 9
  const val ERROR_RECORDING_GARBAGE_COLLECTED = 10

  /**
   * Is the finished file a usable video? CameraX still writes a complete, playable file when the recording stopped
   * because a limit was reached, the phone ran out of space, or the camera was taken away (a call, the app going to the
   * background) — those are kept; the others are not.
   */
  fun keepAfter(error: Int, bytes: Long): Boolean =
      bytes > 0 &&
          error in setOf(ERROR_NONE, ERROR_FILE_SIZE_LIMIT_REACHED, ERROR_INSUFFICIENT_STORAGE, ERROR_SOURCE_INACTIVE, ERROR_DURATION_LIMIT_REACHED)

  /** Surface rotation (0 = ROTATION_0 … 3 = ROTATION_270) for an OrientationEventListener angle; null = flat / unknown. */
  fun surfaceRotation(degrees: Int): Int? =
      when {
        degrees < 0 -> null
        degrees % 360 in 45 until 135 -> 3 // phone turned right ⇒ ROTATION_270
        degrees % 360 in 135 until 225 -> 2
        degrees % 360 in 225 until 315 -> 1
        else -> 0
      }

  /** "0:07", "12:30", "1:02:03" */
  fun elapsed(nanos: Long): String {
    val s = (nanos / 1_000_000_000L).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
  }
}
