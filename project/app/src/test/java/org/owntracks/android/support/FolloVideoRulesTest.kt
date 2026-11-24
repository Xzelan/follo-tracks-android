package org.owntracks.android.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolloVideoRulesTest {
  @Test
  fun `the error codes are CameraX's own`() {
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_NONE, FolloVideoRules.ERROR_NONE)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE, FolloVideoRules.ERROR_SOURCE_INACTIVE)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA, FolloVideoRules.ERROR_NO_VALID_DATA)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, FolloVideoRules.ERROR_FILE_SIZE_LIMIT_REACHED)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE, FolloVideoRules.ERROR_INSUFFICIENT_STORAGE)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, FolloVideoRules.ERROR_DURATION_LIMIT_REACHED)
    assertEquals(androidx.camera.video.VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED, FolloVideoRules.ERROR_ENCODING_FAILED)
  }

  @Test
  fun `a stopped, interrupted or full recording is kept`() {
    assertTrue(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_NONE, 1000))
    assertTrue(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_SOURCE_INACTIVE, 1000))
    assertTrue(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_FILE_SIZE_LIMIT_REACHED, 1000))
    assertTrue(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_INSUFFICIENT_STORAGE, 1000))
    assertTrue(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_DURATION_LIMIT_REACHED, 1000))
  }

  @Test
  fun `a failed or empty recording is not kept`() {
    assertFalse(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_NONE, 0))
    assertFalse(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_ENCODING_FAILED, 1000))
    assertFalse(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_NO_VALID_DATA, 1000))
    assertFalse(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_RECORDER_ERROR, 1000))
    assertFalse(FolloVideoRules.keepAfter(FolloVideoRules.ERROR_UNKNOWN, 1000))
  }

  @Test
  fun `the phone's angle becomes a surface rotation`() {
    assertNull(FolloVideoRules.surfaceRotation(-1))
    assertEquals(0, FolloVideoRules.surfaceRotation(0))
    assertEquals(0, FolloVideoRules.surfaceRotation(44))
    assertEquals(3, FolloVideoRules.surfaceRotation(90))
    assertEquals(2, FolloVideoRules.surfaceRotation(180))
    assertEquals(1, FolloVideoRules.surfaceRotation(270))
    assertEquals(0, FolloVideoRules.surfaceRotation(359))
  }

  @Test
  fun `elapsed time reads like a clock`() {
    assertEquals("0:00", FolloVideoRules.elapsed(0))
    assertEquals("0:07", FolloVideoRules.elapsed(7_400_000_000L))
    assertEquals("12:30", FolloVideoRules.elapsed(750_000_000_000L))
    assertEquals("1:02:03", FolloVideoRules.elapsed(3_723_000_000_000L))
  }

  @Test
  fun `originals are H264 and stay under the server's 4 GiB`() {
    assertEquals("video/avc", FolloVideoRules.VIDEO_MIME)
    assertEquals("audio/mp4a-latm", FolloVideoRules.AUDIO_MIME)
    assertTrue(FolloVideoRules.MAX_BYTES < 4L * 1024 * 1024 * 1024)
  }
}
