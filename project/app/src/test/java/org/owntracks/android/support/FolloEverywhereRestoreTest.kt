package org.owntracks.android.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 24 Nov 2026: "Share everywhere" restored from a Google backup onto a reinstall must not count. */
class FolloEverywhereRestoreTest {
  private val installed = 1_795_468_200_000L

  @Test
  fun onFromBeforeThisInstallIsARestore() =
      assertTrue(FolloEverywhere.restoredOn(true, installed - 3_600_000L, installed))

  @Test
  fun onAfterThisInstallStands() =
      assertFalse(FolloEverywhere.restoredOn(true, installed + 60_000L, installed))

  @Test
  fun offIsNeverARestore() = assertFalse(FolloEverywhere.restoredOn(false, 0L, installed))

  @Test
  fun unknownInstallTimeLeavesItAlone() = assertFalse(FolloEverywhere.restoredOn(true, 5L, 0L))
}
