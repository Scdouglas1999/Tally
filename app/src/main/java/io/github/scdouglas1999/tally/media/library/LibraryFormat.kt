package io.github.scdouglas1999.tally.media.library

import com.github.damontecres.wholphin.data.filter.CommunityRatingFilter
import com.github.damontecres.wholphin.data.filter.DecadeFilter
import com.github.damontecres.wholphin.data.filter.FavoriteFilter
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.FilterVideoType
import com.github.damontecres.wholphin.data.filter.GenreFilter
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.filter.OfficialRatingFilter
import com.github.damontecres.wholphin.data.filter.PlayedFilter
import com.github.damontecres.wholphin.data.filter.StudioFilter
import com.github.damontecres.wholphin.data.filter.VideoTypeFilter
import com.github.damontecres.wholphin.data.filter.YearFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

/** The letters of the jump bar, in order: `#` (digits and the rest), then A to Z. Same as upstream's `jump_letters`. */
const val JUMP_LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * The jump-bar letter an item files under, from its sort name: digits under `#`, A–Z under themselves, anything
 * else (and no name) under `#`. Upstream's `CardGrid` rule.
 */
fun jumpLetterFor(sortName: String?): Char {
    val first = sortName?.firstOrNull()?.uppercaseChar() ?: return JUMP_LETTERS[0]
    return when (first) {
        in '0'..'9' -> '#'
        in 'A'..'Z' -> first
        else -> JUMP_LETTERS[0]
    }
}

/** The bar shows only while the list is sorted by name (upstream's `showLetterButtons`) and has items. */
fun jumpBarShown(
    sort: ItemSortBy,
    itemCount: Int,
): Boolean = sort == ItemSortBy.SORT_NAME && itemCount > 0

/**
 * Choosing [chosen] in the sort dialog: the current sort flips its direction, another sort keeps the current
 * direction. Upstream's `SortByButton` rule.
 */
fun nextSort(
    current: SortAndDirection,
    chosen: ItemSortBy,
): SortAndDirection =
    if (chosen == current.sort) {
        current.flip()
    } else {
        SortAndDirection(chosen, current.direction)
    }

/** What a library's count names. */
enum class LibraryNoun {
    FILMS,
    SHOWS,
    COLLECTIONS,
    EPISODES,
    VIDEOS,
    ITEMS,
}

/**
 * The noun for a grid's count (`22 FILMS`, `5 SHOWS`): from the item types the grid asks for, else the library's
 * collection type (the types upstream's view model adds for it), else plain items.
 */
fun libraryNoun(
    includeItemTypes: List<BaseItemKind>?,
    collectionType: CollectionType?,
): LibraryNoun {
    val single = includeItemTypes?.singleOrNull()
    if (single != null) return nounFor(single)
    if (!includeItemTypes.isNullOrEmpty()) return LibraryNoun.ITEMS
    return when (collectionType) {
        CollectionType.MOVIES -> LibraryNoun.FILMS
        CollectionType.TVSHOWS -> LibraryNoun.SHOWS
        CollectionType.BOXSETS -> LibraryNoun.COLLECTIONS
        CollectionType.HOMEVIDEOS, CollectionType.MUSICVIDEOS -> LibraryNoun.VIDEOS
        else -> LibraryNoun.ITEMS
    }
}

private fun nounFor(kind: BaseItemKind): LibraryNoun =
    when (kind) {
        BaseItemKind.MOVIE -> LibraryNoun.FILMS
        BaseItemKind.SERIES -> LibraryNoun.SHOWS
        BaseItemKind.BOX_SET -> LibraryNoun.COLLECTIONS
        BaseItemKind.EPISODE -> LibraryNoun.EPISODES
        BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> LibraryNoun.VIDEOS
        else -> LibraryNoun.ITEMS
    }

/**
 * Whether the value [option] of the filter [filterOption] is on in [current]. Upstream's `FilterByButton` rule:
 * list filters contain it (by id, name or value), played/favorite compare `True`/`False`, the rating is a minimum.
 */
@Suppress("UNCHECKED_CAST")
fun isFilterValueOn(
    filterOption: ItemFilterBy<*>,
    current: GetItemsFilter,
    option: FilterValueOption,
): Boolean {
    val value = filterOption.get(current)
    return when (filterOption) {
        GenreFilter, StudioFilter -> (value as? List<UUID>).orEmpty().contains(option.value)
        FavoriteFilter, PlayedFilter -> (value as? Boolean) == option.name.toBoolean()
        OfficialRatingFilter -> (value as? List<String>).orEmpty().contains(option.name)
        VideoTypeFilter -> (value as? List<FilterVideoType>).orEmpty().contains(option.value)
        YearFilter, DecadeFilter -> (value as? List<Int>).orEmpty().contains(option.value)
        CommunityRatingFilter -> (value as? Int) == option.value
    }
}

/**
 * [current] with the value [option] of [filterOption] switched: added to or removed from a list filter (an empty
 * list clears the filter), set for played/favorite and the minimum rating. Upstream's `FilterByButton` rule.
 */
@Suppress("UNCHECKED_CAST")
fun toggleFilterValue(
    filterOption: ItemFilterBy<*>,
    current: GetItemsFilter,
    option: FilterValueOption,
): GetItemsFilter {
    val on = isFilterValueOn(filterOption, current, option)
    val value = filterOption.get(current)
    return when (filterOption) {
        GenreFilter, StudioFilter -> {
            val list = (value as? List<UUID>).orEmpty().toggled(option.value as UUID, on)
            (filterOption as ItemFilterBy<List<UUID>>).set(list, current)
        }

        FavoriteFilter, PlayedFilter -> {
            (filterOption as ItemFilterBy<Boolean>).set(option.name.toBoolean(), current)
        }

        OfficialRatingFilter -> {
            val list = (value as? List<String>).orEmpty().toggled(option.name, on)
            OfficialRatingFilter.set(list, current)
        }

        VideoTypeFilter -> {
            val list = (value as? List<FilterVideoType>).orEmpty().toggled(option.value as FilterVideoType, on)
            VideoTypeFilter.set(list, current)
        }

        YearFilter, DecadeFilter -> {
            val list = (value as? List<Int>).orEmpty().toggled(option.value as Int, on)
            (filterOption as ItemFilterBy<List<Int>>).set(list, current)
        }

        CommunityRatingFilter -> {
            CommunityRatingFilter.set(option.value as? Int, current)
        }
    }
}

private fun <T> List<T>.toggled(
    value: T,
    on: Boolean,
): List<T>? = (if (on) this - value else this + value).takeIf { it.isNotEmpty() }
