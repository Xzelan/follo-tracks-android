package org.owntracks.android.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.messages.MessageFollo
import org.owntracks.android.model.messages.MessageHello
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.support.FolloEverywhere

/**
 * 22 Nov 2026 (FOLLO_tracks/docs/NEXT_BUILD_SPEC_20261122.md §0): what the server now sends with a team-mate and in
 * the status element, and the one new thing a post may carry (`ev: 1`, only under "Share everywhere I go").
 */
class FolloEverywhereMessagesTest {
  private val parser = Parser()

  @After
  fun reset() {
    FolloEverywhere.set(false)
    FolloEverywhere.noticeDone()
  }

  private fun location() =
      MessageLocation().apply {
        latitude = -41.1
        longitude = 146.8
        timestamp = 1_795_000_000
        trackerId = "AB"
      }

  @Test
  fun `a post has no ev key at all unless it was made under the mode`() {
    val plain = parser.toJsonPlain(location())
    assertFalse(plain, plain.contains("\"ev\""))
    assertFalse(plain, plain.contains("\"team\""))
    assertFalse(plain, plain.contains("\"tc\""))
    assertFalse(plain, plain.contains("\"vest\""))
    val under = parser.toJsonPlain(location().apply { ev = 1 })
    assertTrue(under, under.contains("\"ev\":1"))
  }

  @Test
  fun `a post made under the mode keeps its ev through the outgoing queue's save and load`() {
    val back = parser.fromJson(parser.toJsonPlain(location().apply { ev = 1 }).toByteArray()) as MessageLocation
    assertEquals(1, back.ev)
    assertNull((parser.fromJson(parser.toJsonPlain(location()).toByteArray()) as MessageLocation).ev)
  }

  @Test
  fun `a hello has no ev key unless it was made under the mode`() {
    assertEquals("""{"_type":"hello","src":"open","m":0,"tid":"L"}""", parser.toJsonPlain(MessageHello(src = "open", m = 0, tid = "L")))
    assertEquals(
        """{"_type":"hello","src":"open","m":2,"tid":"L","ev":1}""",
        parser.toJsonPlain(MessageHello(src = "open", m = 2, tid = "L", ev = 1)),
    )
  }

  @Test
  fun `a team-mate comes with their team, dot colour and vest`() {
    val reply =
        """[{"_type":"location","tid":"SB1","lat":-41.1,"lon":146.8,"tst":1795000000,"acc":5,"batt":80,
             "topic":"owntracks/0400000001/phone","team":"Blue","tc":"#5cc8ff","vest":"P"},
            {"_type":"location","tid":"LC1","lat":-41.2,"lon":146.9,"tst":1795000001,"topic":"owntracks/0400000002/phone"}]"""
    val two = parser.fromJson(reply.byteInputStream()).filterIsInstance<MessageLocation>()
    assertEquals("Blue", two[0].team)
    assertEquals("#5cc8ff", two[0].teamColour)
    assertEquals("P", two[0].vest)
    assertNull(two[1].teamColour) // an older server: nothing sent
    val kj = Contact("owntracks/0400000001/phone").apply { setLocationFromMessageLocation(two[0]) }
    assertEquals("SB1", kj.trackerId)
    assertEquals("#5cc8ff", kj.teamColour)
    assertEquals("P", kj.vest)
    // the next reply, vest handed back: the server still sends tc, so the vest goes
    val later =
        parser.fromJson("""[{"_type":"location","tid":"SB1","lat":-41.1,"lon":146.8,"tst":1795000010,"topic":"owntracks/0400000001/phone","team":"Blue","tc":"#5cc8ff"}]""".byteInputStream())
            .filterIsInstance<MessageLocation>().single()
    kj.setLocationFromMessageLocation(later)
    assertNull(kj.vest)
    assertEquals("Blue", kj.team)
    // a reply with none of it (an older server) leaves what is held
    val bare =
        parser.fromJson("""[{"_type":"location","tid":"SB1","lat":-41.1,"lon":146.8,"tst":1795000020,"topic":"owntracks/0400000001/phone"}]""".byteInputStream())
            .filterIsInstance<MessageLocation>().single()
    kj.setLocationFromMessageLocation(bare)
    assertEquals("#5cc8ff", kj.teamColour)
  }

  @Test
  fun `the status element says whether the server takes share everywhere and since when the newest builds are out`() {
    val follo =
        parser.fromJson("""[{"_type":"follo","state":"nosearch","ev":1,"latest":{"ios":64,"android":400148000,"androidv":"0.1.48","iat":1795300000,"aat":1795310000}}]""".byteInputStream())
            .filterIsInstance<MessageFollo>().single()
    assertEquals(1, follo.ev)
    assertEquals(1795310000L, follo.latest!!.aat)
    assertEquals(1795300000L, follo.latest!!.iat)
    val old = parser.fromJson("""[{"_type":"follo","state":"nosearch","latest":{"android":400147000}}]""".byteInputStream()).filterIsInstance<MessageFollo>().single()
    assertNull(old.ev)
    assertNull(old.latest!!.aat)
    // and it survives the save and load of the last status
    assertEquals(1, (parser.fromJson(parser.toJson(follo)!!) as MessageFollo).ev)
  }

  @Test
  fun `the flag is off until set, and the server's off-switch leaves a notice`() {
    assertFalse(FolloEverywhere.isOn)
    FolloEverywhere.set(true, nowMs = 123_456L)
    assertTrue(FolloEverywhere.isOn)
    assertEquals(123_456L, FolloEverywhere.state.since) // the moment of the OK is kept with the flag
    assertFalse(FolloEverywhere.serverOffNotice.value)
    FolloEverywhere.set(false, byServer = true)
    assertFalse(FolloEverywhere.isOn)
    assertEquals(0L, FolloEverywhere.state.since)
    assertTrue(FolloEverywhere.serverOffNotice.value)
    FolloEverywhere.noticeDone()
    assertFalse(FolloEverywhere.serverOffNotice.value)
    FolloEverywhere.set(true)
    FolloEverywhere.set(false) // the person's own choice: no notice
    assertFalse(FolloEverywhere.serverOffNotice.value)
  }
}
