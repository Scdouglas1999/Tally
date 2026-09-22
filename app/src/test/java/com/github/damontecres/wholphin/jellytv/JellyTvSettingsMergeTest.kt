package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.data.SettingsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyTvSettingsMergeTest {
    private fun raw(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*pairs))

    @Test
    fun `toggle preserves unknown keys`() {
        val before =
            JsonObject(
                mapOf(
                    "alerts" to JsonPrimitive(false),
                    "defaultView" to JsonPrimitive("multi"),
                    "favorites" to JsonArray(listOf(JsonPrimitive("ch1"))),
                ),
            )
        val after = SettingsJson.toggleFavorite(before, "ch2")
        assertEquals(JsonPrimitive(false), after["alerts"])
        assertEquals(JsonPrimitive("multi"), after["defaultView"])
        assertEquals(listOf("ch1", "ch2"), SettingsJson.parse(after).favorites)
    }

    @Test
    fun `toggling twice restores the original favorites`() {
        val before =
            JsonObject(
                mapOf(
                    "favorites" to JsonArray(listOf(JsonPrimitive("ch1"))),
                    "alerts" to JsonPrimitive(false),
                ),
            )
        val once = SettingsJson.toggleFavorite(before, "ch2")
        val twice = SettingsJson.toggleFavorite(once, "ch2")
        assertEquals(listOf("ch1"), SettingsJson.parse(twice).favorites)
        assertEquals(before["favorites"], twice["favorites"])
        assertEquals(JsonPrimitive(false), twice["alerts"])
    }

    @Test
    fun `missing favorites key is handled`() {
        val before = raw("alerts" to JsonPrimitive(false))
        val after = SettingsJson.toggleFavorite(before, "ch1")
        assertEquals(listOf("ch1"), SettingsJson.parse(after).favorites)
        assertEquals(JsonPrimitive(false), after["alerts"])
    }

    @Test
    fun `hideScores and lastChannel preserve unknown keys`() {
        val before =
            JsonObject(
                mapOf(
                    "defaultView" to JsonPrimitive("multi"),
                    "alerts" to JsonPrimitive(false),
                ),
            )
        val after = SettingsJson.setLastChannel(SettingsJson.setHideScores(before, true), "ch9")
        val parsed = SettingsJson.parse(after)
        assertTrue(parsed.hideScores)
        assertEquals("ch9", parsed.lastChannel)
        assertEquals(JsonPrimitive("multi"), after["defaultView"])
        assertFalse(after["alerts"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `parse of empty object yields defaults`() {
        val parsed = SettingsJson.parse(JsonObject(emptyMap()))
        assertTrue(parsed.favorites.isEmpty())
        assertFalse(parsed.hideScores)
        assertEquals(null, parsed.lastChannel)
    }

    @Test
    fun `my channels only is stored and read back, and absent means undecided`() {
        val stored = SettingsJson.setOnlyWatchable(raw("hideScores" to JsonPrimitive(true), "custom" to JsonPrimitive("kept")), true)

        assertTrue(stored["onlyWatchable"]!!.jsonPrimitive.boolean)
        assertEquals("kept", stored["custom"]!!.jsonPrimitive.content)
        assertEquals(true, SettingsJson.parse(stored).onlyWatchable)
        assertEquals(null, SettingsJson.parse(raw("hideScores" to JsonPrimitive(false))).onlyWatchable)
        assertEquals(false, SettingsJson.parse(SettingsJson.setOnlyWatchable(stored, false)).onlyWatchable)
    }
}
