package org.owntracks.android.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.support.FolloPhotoRules.Job

/** The photo queue's rules (23 Nov 2026; FOLLO_tracks/docs/PHOTO_BUILD_SPEC_20261123.md §Upload queue). */
class FolloPhotoRulesTest {
  private val now = 1_795_400_000_000L

  private fun item(pid: String, t: Long, id: String? = "s$pid", sentT: Boolean = id != null, sentP: Boolean = false, sentO: Boolean = false,
                   nextTry: Long = 0, del: Boolean = false, oDoneAt: Long? = null) =
      FolloPhoto(pid = pid, type = "photo", t = t, id = id, sentT = sentT, sentP = sentP, sentO = sentO, nextTry = nextTry, del = del, oDoneAt = oDoneAt, osz = 10)

  @Test
  fun `nothing goes without a network`() {
    assertNull(FolloPhotoRules.nextJob(listOf(item("a", 1, id = null)), emptyList(), online = false, unmetered = true, now = now))
  }

  @Test
  fun `details and thumb first, oldest first, before any asked preview or original`() {
    val items = listOf(item("new2", 300, id = null), item("old", 100), item("new1", 200, id = null))
    val j = FolloPhotoRules.nextJob(items, listOf("old" to "p", "old" to "o"), online = true, unmetered = true, now = now)
    assertEquals(Job.New("new1"), j)
  }

  @Test
  fun `a thumb the server did not keep is sent again`() {
    val j = FolloPhotoRules.nextJob(listOf(item("a", 1, id = "x", sentT = false)), emptyList(), true, false, now)
    assertEquals(Job.New("a"), j)
  }

  @Test
  fun `previews only when asked, newest ask first`() {
    val items = listOf(item("a", 100), item("b", 200), item("c", 300))
    assertNull(FolloPhotoRules.nextJob(items, emptyList(), online = true, unmetered = false, now = now))
    assertEquals(Job.Preview("c"), FolloPhotoRules.nextJob(items, listOf("c" to "p", "a" to "p"), true, false, now))
    // a preview already sent is not sent again
    val sent = items.map { if (it.pid == "c") it.copy(sentP = true) else it }
    assertEquals(Job.Preview("a"), FolloPhotoRules.nextJob(sent, listOf("c" to "p", "a" to "p"), true, false, now))
  }

  @Test
  fun `asked previews before asked originals`() {
    val items = listOf(item("a", 100), item("b", 200))
    assertEquals(Job.Preview("b"), FolloPhotoRules.nextJob(items, listOf("a" to "o", "b" to "p"), true, false, now))
  }

  @Test
  fun `originals wait for Wi-Fi unless asked for`() {
    val items = listOf(item("a", 100), item("b", 200))
    assertNull(FolloPhotoRules.nextJob(items, emptyList(), online = true, unmetered = false, now = now))
    assertEquals(Job.Original("a", asked = false), FolloPhotoRules.nextJob(items, emptyList(), online = true, unmetered = true, now = now))
    assertEquals(Job.Original("b", asked = true), FolloPhotoRules.nextJob(items, listOf("b" to "o"), online = true, unmetered = false, now = now))
  }

  @Test
  fun `nothing more once everything is sent`() {
    val items = listOf(item("a", 100, sentP = true, sentO = true))
    assertNull(FolloPhotoRules.nextJob(items, listOf("a" to "p", "a" to "o"), true, true, now))
  }

  @Test
  fun `an item waiting out its back-off is skipped, others go`() {
    val items = listOf(item("a", 100, id = null, nextTry = now + 5_000), item("b", 200, id = null))
    assertEquals(Job.New("b"), FolloPhotoRules.nextJob(items, emptyList(), true, false, now))
    assertEquals(now + 5_000, FolloPhotoRules.nextWake(items, now))
    assertEquals(Job.New("a"), FolloPhotoRules.nextJob(items, emptyList(), true, false, now + 5_000))
  }

  @Test
  fun `back-off doubles from 5 s to at most 5 min`() {
    assertEquals(0L, FolloPhotoRules.backoffMs(0))
    assertEquals(5_000L, FolloPhotoRules.backoffMs(1))
    assertEquals(10_000L, FolloPhotoRules.backoffMs(2))
    assertEquals(160_000L, FolloPhotoRules.backoffMs(6))
    assertEquals(300_000L, FolloPhotoRules.backoffMs(7))
    assertEquals(300_000L, FolloPhotoRules.backoffMs(40))
  }

  @Test
  fun `a deletion the server must hear of goes first, one it never knew needs nothing`() {
    val items = listOf(item("a", 100, id = null), item("d", 50, del = true))
    assertEquals(Job.Delete("d"), FolloPhotoRules.nextJob(items, emptyList(), true, false, now))
    // a deleted item is never sent
    val only = listOf(item("d", 50, id = null, del = true))
    assertNull(FolloPhotoRules.nextJob(only, emptyList(), true, true, now))
    assertEquals(0, FolloPhotoRules.waiting(listOf(item("d", 50, del = true))))
  }

  @Test
  fun `preview pieces are 64 KB and resume where the server is`() {
    assertEquals(0L until 65_536L, FolloPhotoRules.piece(0, 100_000))
    assertEquals(65_536L until 100_000L, FolloPhotoRules.piece(65_536, 100_000))
    assertNull(FolloPhotoRules.piece(100_000, 100_000))
    // the server answered 409 {got: 131072}: go on from there
    assertEquals(131_072L until 196_608L, FolloPhotoRules.piece(131_072, 300_000))
  }

  @Test
  fun `local copies go 7 days after the original was confirmed, never before`() {
    val items = listOf(item("a", 1, sentO = true, oDoneAt = now - 7 * 86_400_000L), item("b", 1, sentO = true, oDoneAt = now - 6 * 86_400_000L),
                       item("c", 1, sentO = false))
    assertEquals(listOf("a"), FolloPhotoRules.expired(items, now).map { it.pid })
  }

  @Test
  fun `the want list is polled only for a quiet phone holding recent unsent previews`() {
    val recent = listOf(item("a", now - 60_000))
    assertTrue(FolloPhotoRules.pollWant(recent, lastStatusAt = now - 61_000, lastPollAt = 0, now = now))
    assertFalse(FolloPhotoRules.pollWant(recent, lastStatusAt = now - 10_000, lastPollAt = 0, now = now)) // still posting
    assertFalse(FolloPhotoRules.pollWant(recent, lastStatusAt = 0, lastPollAt = now - 30_000, now = now)) // asked 30 s ago
    assertFalse(FolloPhotoRules.pollWant(listOf(item("a", now - 4 * 3_600_000L)), 0, 0, now)) // older than 3 h
    assertFalse(FolloPhotoRules.pollWant(listOf(item("a", now - 60_000, sentP = true)), 0, 0, now))
  }

  @Test
  fun `the camera button shows only in a search or Share everywhere, while the server takes photos`() {
    assertTrue(FolloPhotoRules.buttonShown(true, "sharing", false))
    assertTrue(FolloPhotoRules.buttonShown(true, "outside", false))
    assertFalse(FolloPhotoRules.buttonShown(true, "nosearch", false))
    assertTrue(FolloPhotoRules.buttonShown(true, "nosearch", true))
    assertFalse(FolloPhotoRules.buttonShown(false, "sharing", true))
  }

  @Test
  fun `fit keeps the aspect and never enlarges`() {
    assertEquals(211 to 158, FolloPhotoRules.fit(4032, 3024, 211))
    assertEquals(158 to 211, FolloPhotoRules.fit(3024, 4032, 211))
    assertEquals(1600 to 1200, FolloPhotoRules.fit(4032, 3024, 1600))
    assertEquals(100 to 50, FolloPhotoRules.fit(100, 50, 211))
  }

  @Test
  fun `camera files are recognised by their first bytes`() {
    fun b(vararg x: Int) = ByteArray(16).also { for ((i, v) in x.withIndex()) it[i] = v.toByte() }
    assertEquals("image/jpeg", FolloPhotoRules.sniff(b(0xff, 0xd8, 0xff, 0xe1)))
    fun ftyp(brand: String) = ByteArray(16).also { "\u0000\u0000\u0000\u0018ftyp$brand".toByteArray(Charsets.ISO_8859_1).copyInto(it) }
    assertEquals("image/heic", FolloPhotoRules.sniff(ftyp("heic")))
    assertEquals("video/quicktime", FolloPhotoRules.sniff(ftyp("qt  ")))
    assertEquals("video/mp4", FolloPhotoRules.sniff(ftyp("isom")))
    assertEquals("video/3gpp", FolloPhotoRules.sniff(ftyp("3gp4")))
    assertNull(FolloPhotoRules.sniff(b(1, 2, 3)))
  }

  @Test
  fun `fingerprint is SHA-256 in lowercase hex`() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FolloPhotoRules.sha256Hex(ByteArray(0).inputStream()))
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FolloPhotoRules.sha256Hex("abc".toByteArray().inputStream()))
  }

  @Test
  fun `fingerprint of a file bigger than one read piece`() {
    val f = java.io.File.createTempFile("pho", ".bin")
    try {
      val b = ByteArray(3 * (1 shl 20) + 17) { (it * 31 + 7).toByte() }
      f.writeBytes(b)
      val want = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
      assertEquals(want, FolloPhotoRules.sha256Hex(f))
      assertTrue(Regex("^[0-9a-f]{64}$").matches(want))
    } finally {
      f.delete()
    }
    assertNull(FolloPhotoRules.sha256Hex(java.io.File("/nonexistent/none.jpg")))
  }

  @Test
  fun `items kept before the fingerprint still load, and new ones keep it`() {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }
    val old = json.decodeFromString(FolloPhoto.serializer(), """{"pid":"0123456789abcdef0123","type":"photo","t":1,"osz":5,"oct":"image/jpeg"}""")
    assertNull(old.sha)
    val h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    val back = json.decodeFromString(FolloPhoto.serializer(), json.encodeToString(FolloPhoto.serializer(), old.copy(sha = h)))
    assertEquals(h, back.sha)
  }

  @Test
  fun `pids are 20 lowercase hex`() {
    repeat(50) { assertTrue(Regex("^[a-f0-9]{20}$").matches(FolloPhotoRules.newPid())) }
  }

  @Test
  fun `states in words`() {
    val p = item("a", 100)
    assertEquals("small copy sent · original waits for Wi-Fi", FolloPhotoRules.stateWords(p, emptyList(), true, false))
    assertEquals("waiting for signal", FolloPhotoRules.stateWords(item("b", 1, id = null), emptyList(), false, false))
    assertEquals("bigger copy asked for — sending 40 %",
                 FolloPhotoRules.stateWords(p.copy(pGot = 40, pTotal = 100), listOf("a" to "p"), true, false))
    assertEquals("all sent", FolloPhotoRules.stateWords(p.copy(sentO = true), emptyList(), true, true))
  }
}
