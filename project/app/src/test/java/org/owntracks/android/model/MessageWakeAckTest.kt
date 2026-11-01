package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.owntracks.android.model.messages.MessageCmd
import org.owntracks.android.model.messages.MessageFollo
import org.owntracks.android.model.messages.MessageWakeAck

/** Edit Circles (1 Nov 2026): the location-free answer to a `wake` push. */
class MessageWakeAckTest {
  private val parser = Parser()

  @Test
  fun `a wakeack serialises to exactly type, area, version and the held boundary version`() {
    val json = parser.toJsonPlain(MessageWakeAck.of("TEST2", "123", "abc"))
    assertEquals("""{"_type":"wakeack","area":"TEST2","v":"123","fd":{"pv":"abc"}}""", json)
  }

  @Test
  fun `a wakeack never carries a position`() {
    for (held in listOf("abc", null)) {
      val json = parser.toJsonPlain(MessageWakeAck.of("MV", "1761950000000", held))
      for (key in listOf("lat", "lon", "acc", "tst", "_id", "topic")) assertFalse(json, json.contains("\"$key\""))
    }
  }

  @Test
  fun `no held boundary means no fd at all`() {
    assertEquals("""{"_type":"wakeack","area":"MV","v":"7"}""", parser.toJsonPlain(MessageWakeAck.of("MV", "7", null)))
  }

  @Test
  fun `a wakeack survives the outgoing queue's save and load`() {
    val back = parser.fromJson(parser.toJsonPlain(MessageWakeAck.of("CB", "99", "v1")).toByteArray())
    val ack = back as MessageWakeAck
    assertEquals("CB", ack.area)
    assertEquals("99", ack.v)
    assertEquals("v1", ack.fd?.pv)
    assertNull(ack.fd?.pt)
  }

  @Test
  fun `the reply to a wakeack parses like the reply to a location post`() {
    val reply =
        """[{"_type":"cmd","action":"clearWaypoints"},
           {"_type":"cmd","action":"setWaypoints","waypoints":{"_type":"waypoints","waypoints":[
             {"_type":"waypoint","desc":"FOLLO TEST2 wake 1 2026-11-01","lat":-26.403467,"lon":153.101394,"rad":150,"tst":1700000000}]}},
           {"_type":"follo","state":"nosearch","polyv":"p2","poly":[[-26.40,153.10],[-26.41,153.10],[-26.41,153.11]]}]"""
    val messages = parser.fromJson(reply.byteInputStream())
    assertEquals(3, messages.size)
    assertEquals(listOf("clearWaypoints", "setWaypoints"), messages.filterIsInstance<MessageCmd>().map { it.action?.value })
    assertEquals("p2", messages.filterIsInstance<MessageFollo>().single().heldPolyVersion())
  }
}
