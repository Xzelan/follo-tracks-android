package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test
import org.owntracks.android.R

/** The words a setup link that gave no setup ends in (review B9 add-on, 29 Oct 2026). */
class SetupLinkTest {
  @Test
  fun `a turned-off link says so`() {
    assertEquals(R.string.follo_setup_off, MainViewModel.SetupLink.Unfetched(410).messageRes)
  }

  @Test
  fun `a link that no longer signs asks for a new one`() {
    assertEquals(R.string.follo_setup_stale, MainViewModel.SetupLink.Unfetched(403).messageRes)
  }

  @Test
  fun `no answer or a server error says to check the signal`() {
    for (status in listOf(null, 500, 502, 503, 404, 429)) {
      assertEquals(
          "status $status", R.string.follo_setup_unreachable, MainViewModel.SetupLink.Unfetched(status).messageRes)
    }
  }
}
