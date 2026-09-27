package io.github.scdouglas1999.tally.watch

import io.github.scdouglas1999.tally.api.TallyFeed
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyLanguage

/*
 * A game's commentaries (`feeds`). The server already picked `watch` in the viewer's language, so WATCH never looks at
 * these; they only add "Watch in Español" / "Watch in English" and the player's Commentary choice. A game with one
 * commentary, or from a server that predates feeds, has none and every function here answers as if the game had only
 * its `watch`.
 */

/** A feed's language, English when the server leaves it out. */
val TallyFeed.commentary: String get() = language.ifBlank { TallyLanguage.ENGLISH }

/** The feeds that can be played (with a stream), when there is more than one; empty otherwise. */
fun TallyGame.commentaryFeeds(): List<TallyFeed> = feeds.filter { it.watch != null }.takeIf { it.size > 1 }.orEmpty()

/**
 * The commentary to offer beside WATCH: the first playable feed in another language than [TallyGame.watch]. Null
 * when the game has no stream or no feeds.
 */
fun TallyGame.otherFeed(): TallyFeed? {
    val current = watch ?: return null
    return commentaryFeeds().firstOrNull { it.commentary != current.commentary && it.watch?.channelId != current.channelId }
}

/** The game with [feed] as its WATCH: what every watch action plays for "Watch in Español". */
fun TallyGame.onFeed(feed: TallyFeed): TallyGame = feed.watch?.let { copy(watch = it) } ?: this

/** True when [channelId] carries this game: its WATCH or one of its commentaries. */
fun TallyGame.carries(channelId: String): Boolean =
    watch?.channelId == channelId || feeds.any { it.watch?.channelId == channelId }

/** The commentary [channelId] carries for this game; null when that channel does not carry it. */
fun TallyGame.commentaryOn(channelId: String): String? =
    feeds.firstOrNull { it.watch?.channelId == channelId }?.commentary
        ?: watch?.takeIf { it.channelId == channelId }?.commentary

/** True when WATCH plays this game with Spanish commentary: the cards show the ES chip. */
val TallyGame.spanishWatch: Boolean get() = watch?.commentary == TallyLanguage.SPANISH
