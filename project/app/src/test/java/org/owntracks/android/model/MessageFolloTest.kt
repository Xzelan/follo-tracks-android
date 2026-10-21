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
  fun `show and leaders name teammates by topic, self excluded by the app`() {
    val reply =
        """[{"_type":"follo","state":"outside","team":"Test",
            "leaders":[{"tid":"L","topic":"owntracks/samsungapp/phone","self":true},{"tid":"R","topic":"owntracks/follo-r/phone"}],
            "show":[{"tid":"R","topic":"owntracks/follo-r/phone"},{"tid":"IP","topic":"owntracks/iphoneapp/phone"}]}]"""
    val follo = parser.fromJson(reply.byteInputStream()).filterIsInstance<MessageFollo>().single()
    assertEquals(listOf("owntracks/follo-r/phone", "owntracks/iphoneapp/phone"), follo.show!!.map { it.topic })
    assertTrue(follo.leaders!!.single { it.tid == "L" }.isSelf)
    assertEquals(2, follo.leaders!!.size)
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
  fun `boundary version is parsed and the outline carried over only for the same version`() {
    val withPoly =
        parser.fromJson("""[{"_type":"follo","state":"outside","polyv":"a1b2c3d4","poly":[[-26.63,153.03],[-26.62,153.03],[-26.62,153.04]]}]""".byteInputStream())
            .filterIsInstance<MessageFollo>().single()
    assertEquals("a1b2c3d4", withPoly.polyv)
    assertEquals(3, withPoly.polyPoints()!!.size)
    assertEquals("a1b2c3d4", withPoly.heldPolyVersion())

    // same version, outline left out: keep the one we hold
    val sameVersion = MessageFollo().apply { state = "sharing"; polyv = "a1b2c3d4" }
    sameVersion.carryPolyFrom(withPoly)
    assertEquals(3, sameVersion.polyPoints()!!.size)
    assertEquals("a1b2c3d4", sameVersion.heldPolyVersion())

    // new version, outline left out: hold nothing until the server sends it
    val newVersion = MessageFollo().apply { state = "sharing"; polyv = "ffffffff" }
    newVersion.carryPolyFrom(withPoly)
    assertEquals(null, newVersion.poly)
    assertEquals(null, newVersion.heldPolyVersion())

    // no version at all (no window, or an area without an outline): nothing carried
    val noWindow = MessageFollo().apply { state = "nosearch" }
    noWindow.carryPolyFrom(withPoly)
    assertEquals(null, noWindow.poly)
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

  @Test
  fun `team leaders are parsed, own entry marked`() {
    val reply =
        """[{"_type":"follo","state":"sharing","leaders":[{"tid":"L","topic":"owntracks/samsungapp/phone","self":true},
           {"tid":"R","topic":"owntracks/follo-r/phone"}],"pair":{"tid":"R","topic":"owntracks/follo-r/phone"}}]"""
    val follo = parser.fromJson(reply.byteInputStream()).filterIsInstance<MessageFollo>().single()
    val leaders = follo.leaders!!
    assertEquals(2, leaders.size)
    assertEquals("owntracks/samsungapp/phone", leaders[0].topic)
    assertTrue(leaders[0].isSelf)
    assertEquals("R", leaders[1].tid)
    assertEquals(false, leaders[1].isSelf)
    val back = parser.fromJson(parser.toJson(follo)!!) as MessageFollo
    assertEquals(2, back.leaders!!.size)
    assertTrue(back.leaders!![0].isSelf)
  }

  @Test
  fun `a reply without leaders leaves them empty`() {
    val follo = parser.fromJson("""[{"_type":"follo","state":"outside"}]""".byteInputStream()).filterIsInstance<MessageFollo>().single()
    assertEquals(null, follo.leaders)
  }
}
