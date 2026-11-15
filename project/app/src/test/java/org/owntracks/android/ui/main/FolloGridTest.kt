package org.owntracks.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageFollo

class FolloGridTest {
  private val body =
      """{"v":"1a2b3c4d",
        "col":{"Blue":"#1A6DB5","Orange":"#D4711A"},
        "areas":{"MV":{"v":"x","teams":{
            "Blue":{"l":[[-41.1,146.8,-41.2,146.9,-41.3,147.0],[-41.15,146.85,-41.16,146.86]]},
            "Mauve":{"l":[[-41.1,146.8,-41.11,146.81]]},
            "Orange":{"l":[[-41.1,146.8],[1,2,3],["a",2,3,4],[-99,146.8,-41.1,146.8]]}},
          "names":[[-41.05,146.75,"Blue 1","Blue"],[-41.1,146.8,"","Blue"],[-41.1,146.8,"Odd 1","Mauve"],["x"]]},
         "FG":{"teams":{}}}}"""

  @Test
  fun `a grid reply is read, bad pieces skipped`() {
    val g = FolloGrid.parse(body)
    assertNotNull(g)
    assertEquals("1a2b3c4d", g!!.v)
    assertEquals(listOf("MV"), g.areas.map { it.code }) // an area with no lines is left out
    val mv = g.areas[0]
    assertEquals(listOf("Blue", "Mauve"), mv.teams.map { it.name }) // Orange had no usable line
    assertEquals(2, mv.teams[0].lines.size)
    assertEquals(6, mv.teams[0].lines[0].size)
    assertEquals(-41.3, mv.teams[0].lines[0][4], 0.0)
    assertEquals(0xFF1A6DB5.toInt(), mv.teams[0].color)
    assertEquals(FolloGrid.WHITE, mv.teams[1].color) // a team with no colour is white
    assertEquals(listOf("Blue 1", "Odd 1"), mv.names.map { it.text })
    assertEquals(0xFF1A6DB5.toInt(), mv.names[0].color)
    // the box holds every line and name
    assertEquals(-41.3, mv.south, 0.0)
    assertEquals(-41.05, mv.north, 0.0)
    assertEquals(146.75, mv.west, 0.0)
    assertEquals(147.0, mv.east, 0.0)
    assertEquals(3, g.lineCount)
  }

  @Test
  fun `grey cells past a U-turn are read beside the lines, a bad grey never costs the lines`() {
    // 15 Nov 2026 (FOLLO Planner `phones-greyed-cells`): `g` = the lattice of the cells the walk skips. Made-up points.
    val g =
        FolloGrid.parse(
            """{"v":"9","col":{"Red":"#C0392B","Blue":"#1A6DB5","Green":"#27774A"},"areas":{"ZZ":{"teams":{
              "Red":{"l":[[-41.5,146.5,-41.5,146.6]],"g":[[-41.6,146.5,-41.6,146.6],[-41.7,146.5],["x",1,2,3],[-41.6,146.4,-41.65,146.45]]},
              "Blue":{"l":[[-41.5,146.5,-41.4,146.5]],"g":"rubbish"},
              "Green":{"g":[[-41.8,146.5,-41.8,146.7]]},
              "Grey only bad":{"g":[[1,2]]}}}}}"""
        )!!
    val zz = g.areas.single()
    assertEquals(listOf("Red", "Blue", "Green"), zz.teams.map { it.name })
    val red = zz.teams[0]
    assertEquals(1, red.lines.size)
    assertEquals(2, red.grey.size) // two good grey lines, two bad ones skipped
    assertEquals(146.45, red.grey[1][3], 0.0)
    val blue = zz.teams[1]
    assertEquals(1, blue.lines.size) // a `g` that is not an array drops only itself
    assertTrue(blue.grey.isEmpty())
    val green = zz.teams[2]
    assertTrue(green.lines.isEmpty()) // a team with grey cells alone is still drawn
    assertEquals(1, green.grey.size)
    assertEquals(-41.8, zz.south, 0.0) // the box takes the grey lines in too
    assertEquals(146.4, zz.west, 0.0)
    assertEquals(146.7, zz.east, 0.0)
    assertEquals(2, g.lineCount) // the count is of the walked lines only
    // an old body without `g` still reads the same
    assertTrue(FolloGrid.parse(body)!!.areas[0].teams.all { it.grey.isEmpty() })
  }

  @Test
  fun `no grids and rubbish`() {
    val none = FolloGrid.parse("""{"v":"0","col":{},"areas":{}}""")
    assertEquals("0", none!!.v)
    assertTrue(none.areas.isEmpty())
    assertNull(FolloGrid.parse("<html>"))
    assertNull(FolloGrid.parse("""{"areas":{}}""")) // no version: not a grid reply
    assertNull(FolloGrid.parse("[]"))
  }

  @Test
  fun `colours and team names`() {
    assertEquals(0xFFD4711A.toInt(), FolloGrid.color("#D4711A"))
    assertEquals(0xFF27774A.toInt(), FolloGrid.color("27774a"))
    assertEquals(FolloGrid.WHITE, FolloGrid.color("red"))
    assertEquals(FolloGrid.WHITE, FolloGrid.color(null))
    assertTrue(FolloGrid.sameTeam("Blue", " blue "))
    assertFalse(FolloGrid.sameTeam("Blue", "Red"))
    assertFalse(FolloGrid.sameTeam(null, null))
    assertFalse(FolloGrid.sameTeam("", ""))
  }

  @Test
  fun `the status element carries team, gridv and off, and survives being saved`() {
    val parser = Parser()
    val json =
        """{"_type":"follo","state":"outside","team":"Blue","gridv":"1a2b3c4d",
           "off":{"z":{"MV":19,"AH":17},"km":300,"cap":20000,"free":2500,"later":1}}"""
    val m = parser.fromJson(json) as MessageFollo
    assertEquals("Blue", m.team)
    assertEquals("1a2b3c4d", m.gridv)
    assertEquals(mapOf("MV" to 19, "AH" to 17), m.off?.z)
    assertEquals(300.0, m.off?.km!!, 0.0)
    assertEquals(20000, m.off?.cap)
    assertEquals(2500, m.off?.free)
    val again = parser.fromJson(parser.toJson(m)) as MessageFollo // FolloStatusRepo persists it this way
    assertEquals(mapOf("MV" to 19, "AH" to 17), again.off?.z)
    assertEquals("1a2b3c4d", again.gridv)
    // a server without the feature
    val old = parser.fromJson("""{"_type":"follo","state":"nosearch"}""") as MessageFollo
    assertNull(old.gridv)
    assertNull(old.off)
  }
}
