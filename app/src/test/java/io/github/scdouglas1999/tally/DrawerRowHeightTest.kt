package io.github.scdouglas1999.tally

import androidx.compose.ui.unit.dp
import io.github.scdouglas1999.tally.media.drawer.drawerRowHeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** The drawer's row pitch: every entry of the collapsed rail fits a 1080p screen (540dp at density 2). */
class DrawerRowHeightTest {
    @Test
    fun twelveEntriesWithBothDividersFitAt1080p() {
        // Search, Home, Movies, Shows, Sports, three libraries, Surprise me, Your <year>, Favorites, Settings
        val pitch = drawerRowHeight(540.dp, rows = 12, libraryDivider = true, sectionsDivider = true, nowPlaying = false)
        assertEquals(31.dp, pitch)
        // header 88 + library divider 27 + sections rule 13 + Settings rule and gap 21 + list padding 8
        assertEquals(true, 157.dp + pitch * 12 <= 540.dp)
    }

    @Test
    fun fewEntriesKeepTheFullPitch() {
        assertEquals(40.dp, drawerRowHeight(540.dp, rows = 5, libraryDivider = false, sectionsDivider = false, nowPlaying = false))
    }

    @Test
    fun manyEntriesStopAtTheMinimumAndScroll() {
        assertEquals(30.dp, drawerRowHeight(540.dp, rows = 20, libraryDivider = true, sectionsDivider = true, nowPlaying = true))
    }

    @Test
    fun nowPlayingTakesItsRowFromTheOthers() {
        val without = drawerRowHeight(540.dp, rows = 9, libraryDivider = true, sectionsDivider = true, nowPlaying = false)
        val with = drawerRowHeight(540.dp, rows = 9, libraryDivider = true, sectionsDivider = true, nowPlaying = true)
        assertEquals(40.dp, without)
        assertEquals(36.dp, with)
    }
}
