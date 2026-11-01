package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.location.geofencing.Geofence
import org.owntracks.android.location.toLatLng
import org.owntracks.android.model.messages.MessageTransition
import org.owntracks.android.services.folloSendsFixAfter
import org.owntracks.android.services.folloTransitionMessage

/** Circle crossings (Alex, 1 Nov 2026): no position in a transition, no location post after a leave. */
class MessageTransitionFolloTest {
  private val parser = Parser()

  private fun own(transition: Int) =
      folloTransitionMessage(transition, MessageTransition.TRIGGER_CIRCULAR, "JE", 1700000001, "FOLLO MV wake 3", 1793500000)

  @Test
  fun `this phone's transition never carries a position`() {
    for (t in listOf(Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_EXIT)) {
      val json = parser.toJsonPlain(own(t))
      for (key in listOf("lat", "lon", "acc", "alt", "vel")) assertFalse(json, json.contains("\"$key\""))
      assertTrue(json, json.contains("\"tst\":1793500000"))
      assertTrue(json, json.contains("\"event\":\"" + (if (t == Geofence.GEOFENCE_TRANSITION_EXIT) "leave" else "enter") + "\""))
      assertTrue(json, json.contains("\"desc\":\"FOLLO MV wake 3\""))
      assertTrue(json, json.contains("\"wtst\":1700000001"))
    }
  }

  @Test
  fun `no location post after a leave`() {
    assertTrue(folloSendsFixAfter(Geofence.GEOFENCE_TRANSITION_ENTER))
    assertTrue(folloSendsFixAfter(Geofence.GEOFENCE_TRANSITION_DWELL))
    assertFalse(folloSendsFixAfter(Geofence.GEOFENCE_TRANSITION_EXIT))
  }

  @Test
  fun `an incoming transition still reads its position, and one without moves no one`() {
    val withPos =
        parser.fromJson(
            """{"_type":"transition","acc":3,"event":"leave","lat":52.7,"lon":-1.6,"t":"l","tid":"ce","tst":1603209966,"wtst":1}"""
                .toByteArray()) as MessageTransition
    assertEquals(52.7, withPos.latitude!!, 0.0)
    assertEquals(3, withPos.accuracy)
    val without =
        parser.fromJson(
            """{"_type":"transition","event":"leave","t":"c","tid":"ce","tst":1603209966,"wtst":1}""".toByteArray())
            as MessageTransition
    assertNull(without.toLatLng())
    assertNull(without.accuracy)
  }
}
