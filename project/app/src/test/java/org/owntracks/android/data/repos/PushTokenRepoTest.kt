package org.owntracks.android.data.repos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FOLLO push "check in" (29 Oct 2026): when the push token rides along in the fd diagnostics. */
class PushTokenRepoTest {
  private class MapStore : PushTokenRepo.Store {
    val map = mutableMapOf<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String) {
      map[key] = value
    }
  }

  private var clock = 1_000_000_000_000L
  private var notifications = true

  private fun repo(store: PushTokenRepo.Store = MapStore()) =
      PushTokenRepo(store, { clock }, { notifications })

  @Test
  fun `no token means no fields`() {
    val r = repo()
    assertNull(r.fdFields())
    assertNull(r.token)
  }

  @Test
  fun `the token goes on the first three posts after start, then once an hour`() {
    val r = repo()
    r.token = "tok1"
    assertEquals("tok1" to "fcm", r.fdFields())
    clock += 1_000
    assertEquals("tok1" to "fcm", r.fdFields())
    clock += 1_000
    assertEquals("tok1" to "fcm", r.fdFields())
    clock += 1_000
    assertNull(r.fdFields()) // fourth post, nothing new
    clock += 30 * 60_000
    assertNull(r.fdFields()) // half an hour: still not due
    clock += 31 * 60_000
    assertEquals("tok1" to "fcm", r.fdFields()) // over an hour since it was last included
    clock += 1_000
    assertNull(r.fdFields())
  }

  @Test
  fun `a changed token goes at once`() {
    val r = repo()
    r.token = "tok1"
    repeat(3) { r.fdFields() }
    assertNull(r.fdFields())
    r.token = "tok2"
    assertEquals("tok2" to "fcm", r.fdFields())
    assertNull(r.fdFields())
  }

  @Test
  fun `a new process repeats the stored token three times and keeps what was sent`() {
    val store = MapStore()
    val r1 = repo(store)
    r1.token = "tok1"
    repeat(4) { r1.fdFields() }
    val r2 = repo(store) // process restart: same store, counters reset
    assertEquals("tok1", r2.token)
    repeat(3) { assertEquals("tok1" to "fcm", r2.fdFields()) }
    assertNull(r2.fdFields())
    assertEquals("tok1", store.map[PushTokenRepo.KEY_SENT_TOKEN])
  }

  @Test
  fun `a token new to the phone says a post is needed, the same one does not`() {
    val r = repo()
    assertTrue(r.update("tok1")) // first ever
    assertFalse(r.update("tok1")) // Firebase repeats it at every start
    assertTrue(r.update("tok2")) // rotated
    assertFalse(r.update(""))
    assertFalse(r.update(null))
    assertEquals("tok2" to "fcm", r.fdFields()) // and the post that follows carries it
  }

  @Test
  fun `an empty or same token is ignored`() {
    val r = repo()
    r.token = "tok1"
    r.token = ""
    r.token = null
    assertEquals("tok1", r.token)
  }

  @Test
  fun `notifications allowed follows the phone`() {
    val r = repo()
    assertTrue(r.notificationsAllowed())
    notifications = false
    assertFalse(r.notificationsAllowed())
  }
}
