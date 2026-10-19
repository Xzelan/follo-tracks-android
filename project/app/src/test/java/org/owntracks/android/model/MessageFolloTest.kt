package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.messages.MessageFollo

class MessageFolloTest {
  private val parser = Parser()

  @Test
  fun `server status element is parsed from a reply array`() {
    val reply =
        """[{"_type":"cmd","action":"clearWaypoints"},
           {"_type":"follo","state":"sharing","search":"Bench test","until":"11:59 pm",
            "name":"Samsung (FOLLO app)","team":"Unassigned","tile":"https://t/{z}/{y}/{x}"}]"""
    val messages = parser.fromJson(reply.byteInputStream())
    val follo = messages.filterIsInstance<MessageFollo>().single()
    assertEquals("sharing", follo.state)
    assertEquals("Bench test", follo.search)
    assertEquals("11:59 pm", follo.until)
    assertEquals("https://t/{z}/{y}/{x}", follo.tile)
  }

  @Test
  fun `status survives a save and load round trip`() {
    val original =
        MessageFollo().apply {
          state = "outside"
          search = "Michael Vrankovic search"
          next = "Fri 6 Nov, 7:00 am"
          name = "Alex"
        }
    val json = parser.toJson(original)!!
    assertTrue(json.contains("\"_type\":\"follo\""))
    val back = parser.fromJson(json) as MessageFollo
    assertEquals("outside", back.state)
    assertEquals("Michael Vrankovic search", back.search)
    assertEquals("Fri 6 Nov, 7:00 am", back.next)
    assertEquals("Alex", back.name)
  }
}
