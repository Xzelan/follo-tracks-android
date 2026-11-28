package org.owntracks.android.data.repos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.FolloDiag
import org.owntracks.android.model.messages.MessageLocation

/** FP122 (28 Nov 2026): the phone's model and whether its GPS is dual-band, and when they ride along in fd. */
class FolloDeviceRepoTest {
  private class MapStore : FolloDeviceRepo.Store {
    val map = mutableMapOf<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String) {
      map[key] = value
    }
  }

  private class FakeSource(var chip: List<Double> = emptyList(), var canWatch: Boolean = true) : FolloDeviceRepo.Source {
    var listener: ((List<Float>) -> Unit)? = null
    var starts = 0
    var stops = 0
    var chipAsks = 0

    override fun supportedHz(): List<Double> {
      chipAsks++
      return chip
    }

    override fun startWatching(onStatus: (List<Float>) -> Unit): Boolean {
      if (!canWatch) return false
      starts++
      listener = onStatus
      return true
    }

    override fun stopWatching() {
      stops++
      listener = null
    }
  }

  private var clock = 1_000_000_000_000L
  private val l1 = 1_575_420_000f
  private val l5 = 1_176_450_000f
  private val plain = listOf(l1, l1, 1_561_098_000f, 1_602_000_000f, l1)

  private fun repo(store: FolloDeviceRepo.Store = MapStore(), source: FolloDeviceRepo.Source = FakeSource()) =
      FolloDeviceRepo(store, { clock }, "Samsung SM-S918B", source)

  @Test
  fun `the model goes on the first three posts after start, then every twelve hours`() {
    val r = repo()
    repeat(3) {
      assertEquals("Samsung SM-S918B" to null, r.fdFields())
      clock += 5_000
    }
    assertNull(r.fdFields()) // fourth post, nothing new
    clock += 11 * 3_600_000L
    assertNull(r.fdFields()) // eleven hours: not due
    clock += 2 * 3_600_000L
    assertEquals("Samsung SM-S918B" to null, r.fdFields()) // over twelve hours since it last went
    clock += 5_000
    assertNull(r.fdFields())
  }

  @Test
  fun `a new process sends it again, and a clock put back does not silence it`() {
    val store = MapStore()
    repeat(4) { repo(store).let { r -> repeat(4) { r.fdFields() } } }
    assertEquals("Samsung SM-S918B" to null, repo(store).fdFields())
    val r = repo(store)
    repeat(3) { r.fdFields() }
    clock -= 24 * 3_600_000L
    assertEquals("Samsung SM-S918B" to null, r.fdFields())
  }

  @Test
  fun `a chip that lists a second-band signal is dual-band and nothing is listened to`() {
    val source = FakeSource(chip = listOf(1_575_420_000.0, 1_176_450_000.0))
    val r = repo(source = source)
    assertEquals("Samsung SM-S918B" to 1, r.fdFields())
    assertEquals(1, r.l5)
    repeat(5) { r.fdFields() }
    assertEquals(0, source.starts)
    assertEquals(1, source.chipAsks) // asked once per process
  }

  @Test
  fun `a chip that lists only the first band is 0 until a second-band satellite says otherwise`() {
    val source = FakeSource(chip = listOf(1_575_420_000.0, 1_602_000_000.0))
    val r = repo(source = source)
    assertEquals("Samsung SM-S918B" to 0, r.fdFields())
    assertEquals(1, source.starts)
    repeat(3) { r.fdFields() }
    assertNull(r.fdFields())
    source.listener!!(plain + l5)
    assertEquals("Samsung SM-S918B" to 1, r.fdFields()) // changed, so it goes at once
    assertNull(r.fdFields())
    assertEquals(1, source.stops)
  }

  @Test
  fun `one second-band satellite is enough, kept for good and across restarts`() {
    val store = MapStore()
    val source = FakeSource()
    val r = repo(store, source)
    assertEquals("Samsung SM-S918B" to null, r.fdFields())
    assertEquals(1, source.starts)
    source.listener!!(plain)
    assertNull(r.l5)
    source.listener!!(listOf(l1, l5))
    assertEquals(1, r.l5)
    assertEquals(1, source.stops)
    assertEquals(FolloDeviceRepo.BY_SEEN, store.get(FolloDeviceRepo.KEY_L5_BY))
    r.onStatus(plain) // a late report after stopping changes nothing
    assertEquals(1, r.l5)

    val again = FakeSource()
    val r2 = repo(store, again)
    assertEquals("Samsung SM-S918B" to 1, r2.fdFields())
    assertEquals(0, again.starts) // already known: no listening
  }

  @Test
  fun `only first-band satellites for long enough is 0, and thin or silent reports do not count`() {
    val store = MapStore()
    val source = FakeSource()
    val r = repo(store, source)
    r.fdFields()
    repeat(1000) { source.listener!!(listOf(l1, l1, l1)) } // fewer than MIN_SATS with a frequency
    repeat(1000) { source.listener!!(emptyList()) } // a chip that reports no frequencies at all
    assertNull(r.l5)
    repeat(FolloDeviceRepo.PLAIN_NEEDED - 1) { source.listener!!(plain) }
    assertNull(r.l5)
    source.listener!!(plain)
    assertEquals(0, r.l5)
    assertEquals(1, source.stops)

    // the next process listens again, and a second-band signal then turns the 0 into 1
    val again = FakeSource()
    val r2 = repo(store, again)
    assertEquals("Samsung SM-S918B" to 0, r2.fdFields())
    assertEquals(1, again.starts)
    again.listener!!(listOf(l5))
    assertEquals(1, r2.l5)
  }

  @Test
  fun `without the precise-location permission it keeps trying and says nothing about L5`() {
    val source = FakeSource(canWatch = false)
    val r = repo(source = source)
    repeat(5) { r.fdFields() }
    assertNull(r.l5)
    assertEquals(0, source.starts)
    source.canWatch = true
    r.fdFields()
    assertEquals(1, source.starts)
    r.fdFields()
    assertEquals(1, source.starts) // already listening: not started twice
  }

  @Test
  fun `the second band is everything from 1100 to 1300 MHz`() {
    for (hz in listOf(1_176_450_000.0, 1_207_140_000.0, 1_227_600_000.0, 1_246_000_000.0, 1_278_750_000.0))
        assertTrue("$hz", FolloDeviceRepo.secondBand(hz))
    for (hz in listOf(1_575_420_000.0, 1_561_098_000.0, 1_602_000_000.0, 0.0, 2_492_028_000.0))
        assertFalse("$hz", FolloDeviceRepo.secondBand(hz))
  }

  @Test
  fun `the model reads as the share check words it`() {
    assertEquals("Samsung SM-S918B", FolloDeviceRepo.deviceName("samsung", "SM-S918B"))
    assertEquals("Google Pixel 8 Pro", FolloDeviceRepo.deviceName("Google", "Pixel 8 Pro"))
    assertEquals("motorola edge 50", FolloDeviceRepo.deviceName("motorola", "motorola edge 50"))
    assertEquals("SM-A346E", FolloDeviceRepo.deviceName("", "SM-A346E"))
    assertEquals("Samsung", FolloDeviceRepo.deviceName("samsung", null))
    assertEquals("", FolloDeviceRepo.deviceName(null, null))
    assertEquals(FolloDeviceRepo.MODEL_MAX, FolloDeviceRepo.deviceName("maker", "x".repeat(200)).length)
    assertEquals("Oppo CPH2 x", FolloDeviceRepo.deviceName("oppo", "CPH2\u0007 x\n"))
  }

  @Test
  fun `md and l5 are short keys in fd and absent when not due`() {
    val parser = Parser()
    val with = parser.toJsonPlain(MessageLocation().apply { timestamp = 1; trackerId = "IP"; fd = FolloDiag(fg = 0, md = "Samsung SM-S918B", l5 = 1) })
    assertTrue(with, with.contains("\"md\":\"Samsung SM-S918B\""))
    assertTrue(with, with.contains("\"l5\":1"))
    val unknown = parser.toJsonPlain(MessageLocation().apply { timestamp = 1; trackerId = "IP"; fd = FolloDiag(fg = 0, md = "Samsung SM-S918B") })
    assertTrue(unknown, unknown.contains("\"md\""))
    assertFalse(unknown, unknown.contains("\"l5\""))
    val without = parser.toJsonPlain(MessageLocation().apply { timestamp = 1; trackerId = "IP"; fd = FolloDiag(fg = 0) })
    assertFalse(without, without.contains("\"md\""))
    assertFalse(without, without.contains("\"l5\""))
    val back = parser.fromJson("""{"_type":"location","lat":1,"lon":2,"tst":3,"tid":"IP","fd":{"fg":0,"md":"Google Pixel 8 Pro","l5":0}}""") as MessageLocation
    assertEquals("Google Pixel 8 Pro", back.fd?.md)
    assertEquals(0, back.fd?.l5)
  }
}
