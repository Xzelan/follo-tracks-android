package org.owntracks.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.messages.FolloDiag
import org.owntracks.android.model.messages.MessageLocation

class FolloDiagTest {
  private val parser = Parser()

  @Test
  fun `diagnostics ride along on a location post with short keys and no empty fields`() {
    val message =
        MessageLocation().apply {
          latitude = -26.63
          longitude = 153.03
          timestamp = 1792576065
          trackerId = "IP"
          fd = FolloDiag(fg = 0, au = "always", pr = 1, m = 2, up = 345, lr = "boot", bt = 1, lp = 0, q = 3, src = "default")
        }
    val json = parser.toJsonPlain(message)
    assertTrue(json, json.contains("\"fd\":{"))
    assertTrue(json, json.contains("\"au\":\"always\""))
    assertTrue(json, json.contains("\"up\":345"))
    assertTrue(json, json.contains("\"src\":\"default\""))
    assertTrue("fd must stay small: $json", json.substringAfter("\"fd\":").length < 150)

    val sparse = MessageLocation().apply { timestamp = 1; trackerId = "IP"; fd = FolloDiag(fg = 1) }
    val sparseJson = parser.toJsonPlain(sparse)
    assertTrue(sparseJson, sparseJson.contains("\"fd\":{\"fg\":1}"))
    assertFalse(sparseJson, sparseJson.contains("null"))
  }

  @Test
  fun `a location without diagnostics parses and serialises as before`() {
    val message = parser.fromJson("""{"_type":"location","lat":-26.63,"lon":153.03,"tst":1792576065,"tid":"IP"}""")
    assertTrue(message is MessageLocation)
    assertNull((message as MessageLocation).fd)
    assertFalse(parser.toJsonPlain(message).contains("fd"))
    val withFd = parser.fromJson("""{"_type":"location","lat":1,"lon":2,"tst":3,"tid":"IP","fd":{"fg":1,"au":"inuse","src":"user"}}""") as MessageLocation
    assertEquals("inuse", withFd.fd?.au)
  }
}
