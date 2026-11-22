package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageFollo
import org.owntracks.android.ui.main.FolloWatch.Choice
import org.owntracks.android.ui.main.FolloWatch.Target

class FolloWatchTest {
  private val body =
      """{"now":1000,
        "phones":[{"u":"a","n":"Jon","tid":"JE1","team":"Blue","area":"MV","lat":-41.1,"lon":146.8,"tst":900},
                  {"u":"b","n":"","team":"Red","area":"DW","lat":-27.6,"lon":153.1,"tst":990},
                  {"u":"bad","lat":"x","lon":1,"tst":1},{"u":"far","lat":-99,"lon":1,"tst":1}],
        "logs":{"seen":980,"more":1,"u":{"a":[800,-41.1,146.8,850,-41.1001,146.8001,"x"],"z":[]}},
        "areas":[{"c":"MV","n":"Search One, Sometown TAS","col":"#ffff00","b":[-41.14,146.83,-41.08,146.90],"k":1},
                 {"c":"DW","n":"Search Two, Othertown QLD","col":"#8000ff","b":[-27.65,153.05,-27.61,153.10],"k":0},
                 {"c":"BAD","n":"x","b":[1,2,3]}],
        "av":"51e4dc4a",
        "outlines":{"MV":[-41.1,146.8,-41.2,146.9,-41.15,146.85],"ODD":[1,2,3]}}"""

  private fun phone(u: String, at: Double) = FolloWatch.Phone(u, u, u.uppercase(), "", "MV", -41.0, 146.0, at)

  @Test
  fun `a reply is read, bad pieces skipped, phones newest first`() {
    val r = FolloWatch.parse(body)!!
    assertEquals(listOf("b", "a"), r.phones.map { it.user })
    assertEquals("b", r.phones[0].name) // no name: the user id
    assertEquals("B", r.phones[0].tid) // no tid: the last two letters
    assertEquals(980L, r.logs!!.seen)
    assertTrue(r.logs!!.more)
    assertEquals(listOf("a"), r.logs!!.users.keys.toList())
    assertEquals(2, r.logs!!.users["a"]!!.size)
    assertEquals(listOf("MV", "DW"), r.areas!!.map { it.code })
    assertEquals(0xFF8000FF.toInt(), r.areas!![1].color)
    assertEquals("51e4dc4a", r.av)
    assertEquals(setOf("MV"), r.outlines!!.keys)
  }

  @Test
  fun `an older server sends no searches`() {
    val r = FolloWatch.parse("""{"now":1,"phones":[]}""")!!
    assertNull(r.areas)
    assertNull(r.av)
    assertNull(r.outlines)
    assertNull(FolloWatch.parse("nonsense"))
    assertNull(FolloWatch.parse("""{"now":1}"""))
  }

  @Test
  fun `merge keeps time order and drops repeats and near points`() {
    val held = mutableListOf<FolloWatch.Point>()
    val a = FolloWatch.Point(10.0, -41.0, 146.0)
    val b = FolloWatch.Point(20.0, -41.0001, 146.0) // 11 m on
    val near = FolloWatch.Point(30.0, -41.00011, 146.0) // 1 m on
    assertTrue(FolloWatch.merge(listOf(a, b, near), held))
    assertEquals(listOf(a, b), held)
    assertFalse(FolloWatch.merge(listOf(a, b), held)) // the same fixes again change nothing
    val late = FolloWatch.Point(15.0, -41.00005, 146.0)
    assertTrue(FolloWatch.merge(listOf(late), held))
    assertEquals(listOf(10.0, 15.0, 20.0), held.map { it.t })
  }

  @Test
  fun `lines are cut at long steps and reach the dot`() {
    val pts =
        listOf(
            FolloWatch.Point(1.0, -41.0, 146.0),
            FolloWatch.Point(2.0, -41.0001, 146.0),
            FolloWatch.Point(3.0, -41.01, 146.0), // a 1 km jump: a lone point, left out
        )
    assertEquals(1, FolloWatch.lines(pts).size)
    assertEquals(4, FolloWatch.lines(pts)[0].size)
    val two = pts.take(2)
    assertEquals(6, FolloWatch.lines(two, -41.0002 to 146.0)[0].size) // the dot joined on
    assertEquals(4, FolloWatch.lines(two, -42.0 to 146.0)[0].size) // too far: not joined
  }

  @Test
  fun `a chosen search stays even with nobody in it`() {
    val r = FolloWatch.parse(body)!!
    val c = Choice.OnArea("DW")
    assertEquals(c, FolloWatch.settle(c, emptyList(), r.areas))
    assertEquals(Target.OnArea("DW"), FolloWatch.target(c, null, emptyList(), sharing = true))
    assertEquals(Target.OnArea("DW"), FolloWatch.target(c, "a", r.phones, sharing = false)) // never jumps to a phone
  }

  @Test
  fun `a search gone from the list, or no searches at all, goes back to auto`() {
    val r = FolloWatch.parse(body)!!
    assertEquals(Choice.Auto, FolloWatch.settle(Choice.OnArea("CB"), emptyList(), r.areas))
    assertEquals(Choice.Auto, FolloWatch.settle(Choice.OnArea("MV"), emptyList(), null))
  }

  @Test
  fun `phone choice and auto as on iOS`() {
    val phones = listOf(phone("new", 100.0), phone("old", 50.0))
    assertEquals(Target.OnPhone("new"), FolloWatch.target(Choice.Auto, null, phones, false))
    assertEquals(Target.OnPhone("old"), FolloWatch.target(Choice.Auto, "old", phones, false)) // stays on the one held
    assertNull(FolloWatch.target(Choice.Auto, null, phones, true)) // sharing: own position first
    assertEquals(Target.OnPhone("old"), FolloWatch.target(Choice.OnPhone("old"), null, phones, true))
    assertEquals(Target.OnPhone("new"), FolloWatch.target(Choice.OnPhone("gone"), null, phones, false))
    assertNull(FolloWatch.target(Choice.Me, "new", phones, false))
    assertEquals(Choice.Auto, FolloWatch.settle(Choice.OnPhone("gone"), phones, null))
    assertEquals(Choice.Auto, FolloWatch.settle(Choice.Me, emptyList(), null))
    assertEquals(Choice.Me, FolloWatch.settle(Choice.Me, phones, null))
  }

  @Test
  fun `words`() {
    val r = FolloWatch.parse(body)!!
    assertEquals("MV · Search One · 1 phone", FolloWatch.areaLine(r.areas!![0]))
    assertEquals("DW · Search Two · no phones", FolloWatch.areaLine(r.areas!![1]))
    assertEquals("DW · no phones", FolloWatch.title(null, r.areas!![1], 5, 1000.0))
    assertEquals("DW · 3 phones", FolloWatch.title(null, r.areas!![1].copy(count = 3), 5, 1000.0))
    assertEquals("Jon (JE1) · MV · 2 min", FolloWatch.title(r.phones[1], null, 2, 1000.0))
    assertEquals("No phones in circles", FolloWatch.title(null, null, 0, 0.0))
    assertEquals("1 phone in a circle", FolloWatch.title(null, null, 1, 0.0))
    assertEquals("2 phones in circles", FolloWatch.title(null, null, 2, 0.0))
    assertEquals("now", FolloWatch.age(3.0))
    assertEquals("40 s", FolloWatch.age(40.0))
    assertEquals("12 min", FolloWatch.age(720.0))
    assertEquals("2 h", FolloWatch.age(7200.0))
    assertEquals("Bench test", FolloWatch.shortName("Bench test, Bli Bli QLD"))
    assertEquals("TEST", FolloWatch.shortName("TEST"))
  }

  @Test
  fun `fit zoom`() {
    // a box 2000 px wide at zoom 15 into a 1000 px view at 85 %: one step and a bit out
    val z = FolloWatch.fitZoom(15.0, 2000.0, 100.0, 1000.0, 1000.0, 0.85, 3.0, 18.5)
    assertEquals(15.0 + Math.log(0.425) / Math.log(2.0), z, 1e-9)
    assertEquals(18.5, FolloWatch.fitZoom(15.0, 10.0, 10.0, 1000.0, 1000.0, 0.85, 3.0, 18.5), 0.0) // tiny: walking scale
    assertEquals(3.0, FolloWatch.fitZoom(15.0, 1e9, 1e9, 1000.0, 1000.0, 0.85, 3.0, 18.5), 0.0)
  }

  @Test
  fun `the status element carries all`() {
    val parser = Parser()
    val m = parser.fromJson("""{"_type":"follo","state":"nosearch","all":1}""") as MessageFollo
    assertEquals(1, m.all)
    val n = parser.fromJson("""{"_type":"follo","state":"nosearch"}""") as MessageFollo
    assertNull(n.all)
    assertNotNull(n.state)
  }

  // ── 22 Nov 2026: ghosts (FP079) and the dots' colours (FP078) ──────────────────────────────────────────────

  @Test
  fun `a phone comes with its dot colour and vest`() {
    val r =
        FolloWatch.parse(
            """{"now":1000,"phones":[{"u":"a","n":"Sam","tid":"SB1","team":"Blue","area":"FG","lat":-41.1,"lon":146.8,"tst":990,"tc":"#5cc8ff","vest":"P"},
               {"u":"b","n":"Lee","tid":"LC1","team":"Red","area":"FG","lat":-41.1,"lon":146.8,"tst":980}]}""")!!
    assertEquals("#5cc8ff", r.phones[0].tc)
    assertEquals("P", r.phones[0].vest)
    assertNull(r.phones[1].tc)
    assertNull(r.phones[1].vest)
  }

  @Test
  fun `a phone that went quiet on its circle's edge has left, and is not counted`() {
    val fg = listOf(FolloRules.Circle(-41.10, 146.80, 1000.0, "FG"))
    val edge = -41.10 + 990 / 111_194.93 // 10 m inside the edge
    fun phone(u: String, lat: Double, at: Double, area: String = "FG") = FolloWatch.Phone(u, u, u.uppercase(), "Blue", area, lat, 146.80, at)
    val now = 10_000.0
    val phones =
        listOf(
            phone("ghost", edge, now - 120), // drove out two minutes ago: its last fix is on the line
            phone("crossing", edge, now - 10), // on the edge but heard from 10 s ago: still there
            phone("quiet", -41.10, now - 600), // deep inside, no signal for ten minutes: still shown
            phone("other", edge, now - 120, area = "MV"), // an area this phone holds no circles for: the server's call
        )
    val present = FolloWatch.present(phones, mapOf("FG" to fg), now)
    assertEquals(listOf("crossing", "quiet", "other"), present.map { it.user })
    val areas = listOf(FolloWatch.Area("FG", "Search Three", 0, -41.2, 146.7, -41.0, 146.9, 3), FolloWatch.Area("MV", "Search One", 0, -41.2, 146.7, -41.0, 146.9, 1))
    assertEquals(listOf(2, 1), FolloWatch.counted(areas, present)!!.map { it.count })
    assertEquals("FG · 2 phones", FolloWatch.title(null, FolloWatch.counted(areas, present)!![0], present.size, now))
    assertNull(FolloWatch.counted(null, present))
    // a chosen phone that has left falls back to the automatic choice
    assertEquals(Choice.Auto, FolloWatch.settle(Choice.OnPhone("ghost"), present, areas))
  }
}
