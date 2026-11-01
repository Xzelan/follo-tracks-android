package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.owntracks.android.model.messages.FolloDiag
import org.owntracks.android.model.messages.MessageHello

/** "No fix on open" (1 Nov 2026): the location-free check-in. */
class MessageHelloTest {
  private val parser = Parser()

  @Test
  fun `a hello serialises to exactly type, src, m, tid and fd`() {
    val json = parser.toJsonPlain(MessageHello(src = "open", m = 0, tid = "L", fd = FolloDiag(fg = 1, up = 5, pv = "abc")))
    assertEquals("""{"_type":"hello","src":"open","m":0,"tid":"L","fd":{"fg":1,"up":5,"pv":"abc"}}""", json)
  }

  @Test
  fun `a hello never carries a position`() {
    val json = parser.toJsonPlain(MessageHello(src = "push", m = 1, tid = "R", fd = FolloDiag(fg = 0, pt = "tok", pp = "fcm")))
    for (key in listOf("lat", "lon", "acc", "alt", "vel", "tst", "_id", "topic")) assertFalse(json, json.contains("\"$key\""))
  }

  @Test
  fun `a hello survives the outgoing queue's save and load`() {
    val back = parser.fromJson(parser.toJsonPlain(MessageHello(src = "token", m = 2, tid = "AB")).toByteArray()) as MessageHello
    assertEquals("token", back.src)
    assertEquals(2, back.m)
    assertEquals("AB", back.tid)
  }
}
