package io.github.scdouglas1999.tally.ui.phone

import com.github.damontecres.wholphin.services.NavDrawerItemState
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import io.github.scdouglas1999.tally.media.drawer.indexedDrawerItems
import io.github.scdouglas1999.tally.media.drawer.isTallyAppSection
import io.github.scdouglas1999.tally.media.drawer.tallyPrimaryRank

/**
 * The same indexes `NavDrawerViewModel` writes for its own entries (private upstream, repeated in the Tally drawer
 * too). The phone shell uses the drawer's view model, so it marks and opens pages exactly as the drawer does.
 */
internal const val HOME_INDEX = -1
internal const val SEARCH_INDEX = -2
internal const val NOW_PLAYING_INDEX = -3

/** The five places of the bottom bar; offline mode has only [DOWNLOADS] and [SETTINGS]. */
internal enum class PhoneTab { HOME, MOVIES, SHOWS, SPORTS, MORE, DOWNLOADS, SETTINGS }

/**
 * What the phone's navigation shows, from the drawer's items: the first Movies and TV Shows libraries and Sports for
 * the bar (picked the way the Tally drawer ranks them), and everything else the drawer lists for the More sheet, in the
 * drawer's order. Every entry keeps the drawer's index (items first, then More items after them), so a tap goes
 * through `NavDrawerViewModel.onClickDrawerItem` exactly as the drawer's own entry does.
 */
internal data class PhoneNavModel(
    val movies: IndexedValue<NavDrawerItem>?,
    val shows: IndexedValue<NavDrawerItem>?,
    val sports: IndexedValue<NavDrawerItem>?,
    val moreLibraries: List<IndexedValue<NavDrawerItem>>,
    val moreSections: List<IndexedValue<NavDrawerItem>>,
) {
    val tabs: List<PhoneTab>
        get() =
            buildList {
                add(PhoneTab.HOME)
                if (movies != null) add(PhoneTab.MOVIES)
                if (shows != null) add(PhoneTab.SHOWS)
                if (sports != null) add(PhoneTab.SPORTS)
                add(PhoneTab.MORE)
            }

    /** The bar's tabs: in offline mode (the server cannot be reached) only Downloads and Settings. */
    fun tabs(offline: Boolean): List<PhoneTab> = if (offline) listOf(PhoneTab.DOWNLOADS, PhoneTab.SETTINGS) else tabs

    /** The tab the drawer's [selectedIndex] belongs to: anything that is not a tab is under More. */
    fun currentTab(selectedIndex: Int): PhoneTab =
        when (selectedIndex) {
            HOME_INDEX -> PhoneTab.HOME
            movies?.index -> PhoneTab.MOVIES
            shows?.index -> PhoneTab.SHOWS
            sports?.index -> PhoneTab.SPORTS
            else -> PhoneTab.MORE
        }

    companion object {
        fun from(state: NavDrawerItemState): PhoneNavModel {
            // Unlike the TV drawer, which leaves Live TV out while Sports is one of its entries, the phone lists the
            // server's Live TV library in the More sheet (on a phone it opens the channel grid).
            val visible = indexedDrawerItems(state.items, hideLiveTv = false)
            val moreVisible =
                indexedDrawerItems(state.moreItems, hideLiveTv = false).map { IndexedValue(it.index + state.items.size, it.value) }
            val all = visible + moreVisible
            val movies = all.firstOrNull { it.value.tallyPrimaryRank() == 0 }
            val shows = all.firstOrNull { it.value.tallyPrimaryRank() == 1 }
            val sports = all.firstOrNull { it.value == NavDrawerItem.Sports }
            val onBar = setOfNotNull(movies?.index, shows?.index, sports?.index)
            // the drawer's order: Movies, TV Shows, Sports, the other libraries, its More items, then the app sections
            val primary =
                visible
                    .filter { it.value.tallyPrimaryRank() != null }
                    .sortedBy { it.value.tallyPrimaryRank() }
            val libraries = visible.filter { it.value.tallyPrimaryRank() == null && !it.value.isTallyAppSection() }
            val sections = visible.filter { it.value.tallyPrimaryRank() == null && it.value.isTallyAppSection() }
            return PhoneNavModel(
                movies = movies,
                shows = shows,
                sports = sports,
                moreLibraries = (primary + libraries + moreVisible).filter { it.index !in onBar },
                moreSections = sections.filter { it.index !in onBar },
            )
        }
    }
}
