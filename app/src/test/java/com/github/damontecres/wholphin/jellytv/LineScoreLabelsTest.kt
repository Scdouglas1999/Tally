package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.api.JellyTvJson
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.ui.components.isLeading
import com.github.damontecres.wholphin.jellytv.ui.components.periodColumnCount
import com.github.damontecres.wholphin.jellytv.ui.components.periodLabels
import com.github.damontecres.wholphin.jellytv.ui.components.periodValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineScoreLabelsTest {
    @Test
    fun `football is quarters then overtime`() {
        assertEquals(listOf("1", "2", "3", "4"), periodLabels("football", 4))
        assertEquals(listOf("1", "2", "3", "4", "OT"), periodLabels("football", 5))
        assertEquals(listOf("1", "2", "3", "4", "OT", "2OT"), periodLabels("football", 6))
        assertEquals(listOf("1", "2", "3", "4", "OT", "2OT", "3OT"), periodLabels("football", 7))
    }

    @Test
    fun `football labels ignore case`() {
        assertEquals(listOf("1", "2", "3", "4", "OT"), periodLabels("Football", 5))
    }

    @Test
    fun `baseball is inning numbers including extras`() {
        assertEquals((1..9).map { it.toString() }, periodLabels("baseball", 9))
        assertEquals((1..11).map { it.toString() }, periodLabels("baseball", 11))
    }

    @Test
    fun `hockey is periods then overtime then a shootout`() {
        assertEquals(listOf("1", "2", "3"), periodLabels("hockey", 3))
        assertEquals(listOf("1", "2", "3", "OT"), periodLabels("hockey", 4))
        assertEquals(listOf("1", "2", "3", "OT", "SO"), periodLabels("hockey", 5))
        assertEquals(listOf("1", "2", "3", "OT", "SO", "6"), periodLabels("hockey", 6))
    }

    @Test
    fun `other sports are numbered`() {
        assertEquals(listOf("1", "2", "3", "4"), periodLabels("basketball", 4))
        assertEquals(listOf("1", "2"), periodLabels("soccer", 2))
        assertEquals(listOf("1", "2", "3"), periodLabels("", 3))
    }

    @Test
    fun `a non positive count has no labels`() {
        assertEquals(emptyList<String>(), periodLabels("football", 0))
        assertEquals(emptyList<String>(), periodLabels("baseball", -1))
    }

    @Test
    fun `regulation columns stay reserved so the table does not jump`() {
        assertEquals(4, periodColumnCount("football", 1))
        assertEquals(4, periodColumnCount("football", 4))
        assertEquals(5, periodColumnCount("football", 5))
        assertEquals(9, periodColumnCount("baseball", 3))
        assertEquals(9, periodColumnCount("baseball", 9))
        assertEquals(12, periodColumnCount("baseball", 12))
        assertEquals(3, periodColumnCount("hockey", 1))
        assertEquals(5, periodColumnCount("hockey", 5))
        assertEquals(2, periodColumnCount("basketball", 2))
        assertEquals(2, periodColumnCount("soccer", 2))
        assertEquals(0, periodColumnCount("soccer", 0))
    }

    @Test
    fun `an unplayed period is a dash and a played zero is a zero`() {
        assertEquals("–", periodValue(listOf(7), 1))
        assertEquals("0", periodValue(listOf(0, 10), 0))
        assertEquals("10", periodValue(listOf(0, 10), 1))
        assertEquals("-", periodValue(emptyList(), 0, unplayed = "-"))
    }

    @Test
    fun `only a strictly higher score is leading`() {
        assertTrue(isLeading(41, 31))
        assertFalse(isLeading(17, 17))
        assertFalse(isLeading(3, 4))
        assertFalse(isLeading(null, 4))
        assertFalse(isLeading(4, null))
    }

    @Test
    fun `sample board football and baseball labels`() {
        val text =
            javaClass
                .getResource("/jellytv/board-sample-periods.json")!!
                .readText()
        val board = JellyTvJson.decodeFromString<JtvBoard>(text)
        assertTrue(board.games.isNotEmpty())
        for (game in board.games) {
            val played = maxOf(game.away.periods.size, game.home.periods.size)
            assertTrue(game.name, played > 0)
            val labels = periodLabels(game.sport, periodColumnCount(game.sport, played))
            when (game.sport) {
                "football" -> {
                    assertEquals(game.name, listOf("1", "2", "3", "4"), labels.take(4))
                    if (played >= 5) assertEquals(game.name, "OT", labels[4])
                    if (played >= 6) assertEquals(game.name, "2OT", labels[5])
                    assertEquals(game.name, maxOf(4, played), labels.size)
                }

                "baseball" -> {
                    assertEquals(game.name, (1..labels.size).map { it.toString() }, labels)
                    assertTrue(game.name, labels.size >= 9)
                }

                else -> {
                    error("unexpected sport ${game.sport}")
                }
            }
        }
        val ot =
            board.games.filter {
                it.sport == "football" && maxOf(it.away.periods.size, it.home.periods.size) > 4
            }
        assertEquals(2, ot.size)
        val shortHome =
            board.games.filter { it.sport == "baseball" && it.home.periods.size < it.away.periods.size }
        assertEquals(3, shortHome.size)
        shortHome.forEach { game ->
            assertEquals("–", periodValue(game.home.periods, game.away.periods.lastIndex))
        }
    }
}
