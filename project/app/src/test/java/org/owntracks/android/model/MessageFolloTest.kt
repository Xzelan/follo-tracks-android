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

  @Test
  fun `area box and window end are parsed`() {
    val reply =
        """[{"_type":"follo","state":"outside","box":"-26.631421,153.027650,-26.622437,153.037699","ends":1792421940}]"""
    val follo = parser.fromJson(reply.byteInputStream()).filterIsInstance<MessageFollo>().single()
    val b = follo.boxBounds()!!
    assertEquals(-26.631421, b[0], 1e-9)
    assertEquals(153.037699, b[3], 1e-9)
    assertEquals(1792421940L, follo.ends)
    assertEquals(null, MessageFollo().apply { box = "1,2,3" }.boxBounds())
  }
}
