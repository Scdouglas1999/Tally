package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.ui.settings.ChoiceDisplay
import io.github.scdouglas1999.tally.ui.settings.ConfirmText
import io.github.scdouglas1999.tally.ui.settings.ItemAction
import io.github.scdouglas1999.tally.ui.settings.choiceDisplay
import io.github.scdouglas1999.tally.ui.settings.confirmText
import io.github.scdouglas1999.tally.ui.settings.initialListIndex
import io.github.scdouglas1999.tally.ui.settings.itemMenuActions
import io.github.scdouglas1999.tally.ui.settings.itemMenuFacts
import io.github.scdouglas1999.tally.ui.settings.sliderStep
import io.github.scdouglas1999.tally.ui.settings.switchSummary
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings rows' and dialogs' pure helpers. The context-menu cases use payloads captured from the dev server:
 * `series-episodes-bb-s1.json` (Breaking Bad S1: E1–E2 played, E3 42% in) and `played-items.json`.
 */
class SettingsFormatTest {
    @Test
    fun `a choice row puts the current choice at the right`() {
        assertEquals(ChoiceDisplay("1080p", null), choiceDisplay("1080p", "1080p"))
        assertEquals(ChoiceDisplay("Medium", null), choiceDisplay(null, "Medium"))
    }

    @Test
    fun `a richer form of the choice replaces it`() {
        assertEquals(
            ChoiceDisplay("Use user profile - Default", null),
            choiceDisplay("Use user profile - Default", "Use user profile"),
        )
    }

    @Test
    fun `a description stays under the title`() {
        assertEquals(
            ChoiceDisplay("Tally", "Colors used across the app"),
            choiceDisplay("Colors used across the app", "Tally"),
        )
    }

    @Test
    fun `a non text choice shows the summary as its value`() {
        assertEquals(ChoiceDisplay("VLC", null), choiceDisplay("VLC", null))
        assertEquals(ChoiceDisplay(null, null), choiceDisplay(" ", null))
    }

    @Test
    fun `a switch summary that only restates ON or OFF is left out`() {
        val restatements = setOf("Enabled", "Disabled", "Show", "Hide")
        assertNull(switchSummary("Enabled", restatements))
        assertNull(switchSummary("Hide", restatements))
        assertNull(switchSummary(null, restatements))
        assertEquals(
            "Choose rows and images on the home page",
            switchSummary("Choose rows and images on the home page", restatements),
        )
    }

    @Test
    fun `a slider steps by its interval and stops at its ends`() {
        assertEquals(35L, sliderStep(30L, 5L, 60L, 5, forward = true))
        assertEquals(25L, sliderStep(30L, 5L, 60L, 5, forward = false))
        assertEquals(60L, sliderStep(58L, 5L, 60L, 5, forward = true))
        assertEquals(5L, sliderStep(5L, 5L, 60L, 5, forward = false))
    }

    @Test
    fun `a short confirm title is the kicker`() {
        assertEquals(ConfirmText("Discard changes?", null, null), confirmText("Discard changes?", null))
        assertEquals(
            ConfirmText("Remove Seerr Server", "http://seerr.local", null),
            confirmText("Remove Seerr Server", "http://seerr.local"),
        )
    }

    @Test
    fun `stray spaces at the start of body lines are trimmed`() {
        // Upstream's confirm_enable_experimental_body.
        assertEquals(
            "Experimental settings may be unstable!\n\nThese may removed or changed in any release.",
            confirmText(
                "Enable experimental settings?",
                "Experimental settings may be unstable!\n\n These may removed or changed in any release.",
            ).message,
        )
    }

    @Test
    fun `a sentence title becomes the message under the generic kicker`() {
        assertEquals(
            ConfirmText(null, "Are you sure you want to delete this item?", "Toy Story"),
            confirmText("Are you sure you want to delete this item?", "Toy Story"),
        )
    }

    @Test
    fun `focus starts on the marked row else the first one that can take it`() {
        assertEquals(2, initialListIndex(listOf(true, true, true), listOf(false, false, true)))
        assertEquals(1, initialListIndex(listOf(false, true, true), listOf(false, false, false)))
        assertEquals(1, initialListIndex(listOf(false, true), listOf(true, false)))
        assertEquals(-1, initialListIndex(emptyList(), emptyList()))
    }

    @Test
    fun `a half watched episode offers resume and play from start`() {
        val episode = episodes("series-episodes-bb-s1.json").first { it.name == "...And the Bag's in the River" }
        val actions = itemMenuActions(facts(episode, canRemoveContinueWatching = true, canRemoveNextUp = true))
        assertEquals(
            listOf(
                ItemAction.GO_TO,
                ItemAction.RESUME,
                ItemAction.PLAY_FROM_START,
                ItemAction.ADD_TO_PLAYLIST,
                ItemAction.REMOVE_CONTINUE_WATCHING,
                ItemAction.REMOVE_NEXT_UP,
                ItemAction.MARK_WATCHED,
                ItemAction.FAVORITE,
                ItemAction.GO_TO_SERIES,
                ItemAction.PLAY_WITH,
            ),
            actions,
        )
    }

    @Test
    fun `a watched episode offers mark unwatched and cannot leave continue watching`() {
        val pilot = episodes("series-episodes-bb-s1.json").first { it.name == "Pilot" }
        val actions = itemMenuActions(facts(pilot, canRemoveContinueWatching = true))
        assertTrue(ItemAction.MARK_UNWATCHED in actions)
        assertFalse(ItemAction.MARK_WATCHED in actions)
        assertFalse(ItemAction.REMOVE_CONTINUE_WATCHING in actions)
        assertTrue(ItemAction.PLAY in actions)
        assertFalse(ItemAction.RESUME in actions)
    }

    @Test
    fun `a movie has no series entries and delete only when allowed`() {
        val movie = episodes("played-items.json").first { it.type == BaseItemKind.MOVIE }
        val plain = itemMenuActions(facts(movie))
        assertFalse(ItemAction.GO_TO_SERIES in plain)
        assertFalse(ItemAction.REMOVE_NEXT_UP in plain)
        assertFalse(ItemAction.DELETE in plain)
        assertTrue(ItemAction.DELETE in itemMenuActions(facts(movie, canDelete = true)))
        // Upstream order: Delete sits after Add to playlist.
        val withDelete = itemMenuActions(facts(movie, canDelete = true))
        assertEquals(withDelete.indexOf(ItemAction.ADD_TO_PLAYLIST) + 1, withDelete.indexOf(ItemAction.DELETE))
    }

    private fun facts(
        dto: BaseItemDto,
        canDelete: Boolean = false,
        canRemoveContinueWatching: Boolean = false,
        canRemoveNextUp: Boolean = false,
    ) = itemMenuFacts(
        dto = dto,
        sourceId = null,
        showGoTo = true,
        showStreamChoices = true,
        canDelete = canDelete,
        canRemoveContinueWatching = canRemoveContinueWatching,
        canRemoveNextUp = canRemoveNextUp,
        showRemoveFromPlaylist = false,
        canClearChosenStreams = false,
    )

    private fun episodes(name: String): List<BaseItemDto> {
        val text =
            requireNotNull(javaClass.classLoader?.getResource("tally/$name")) { "missing fixture $name" }.readText()
        return ApiSerializer.json.decodeFromString(BaseItemDtoQueryResult.serializer(), text).items
    }
}
