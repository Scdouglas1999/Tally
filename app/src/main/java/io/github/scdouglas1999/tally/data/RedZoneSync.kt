package io.github.scdouglas1999.tally.data

import io.github.scdouglas1999.tally.api.TallyRedZone
import java.time.OffsetDateTime

/**
 * Keeps what the RedZone overlay says (and the score bug and box score that follow the RedZone game) in step with the
 * picture. The server reports a cut the moment its playlist carries it, but a player sits behind the live edge (its own
 * buffer, plus Jellyfin's remux when it plays the channel through Live TV), so its picture cuts that much later.
 *
 * Every status answer is [offer]ed; [at] then says which of the cuts the player has reached: the last one that entered
 * the playlist at least `latency` ago. The first answer shows at once (nothing earlier is known to show), several cuts
 * inside one latency window each get their turn, and the overlay never steps back to an earlier cut.
 *
 * Times are read on this device's clock: the server's `since` times are shifted by the difference between its
 * `serverTime` and the device's clock when the answer arrived. Pure logic (no Android), one instance per player.
 */
class RedZoneSync {
    private class Cut(
        val status: TallyRedZone,
        /** When the playlist carried it, on this device's clock; [Long.MIN_VALUE]: on before anything we know of. */
        val sinceMs: Long,
    )

    private val cuts = ArrayList<Cut>()
    private var shown: Cut? = null

    /** What the overlay shows now: the last result of [at]. */
    val current: TallyRedZone? get() = shown?.status

    /** Takes a status answer that arrived at [nowMs] (device clock); null (unreadable) changes nothing. */
    fun offer(
        status: TallyRedZone?,
        nowMs: Long,
    ) {
        if (status == null) return
        // what the overlay keeps: the answer without its history and clock (the same cut stays the same value)
        val kept = status.copy(recent = emptyList(), serverTime = null)
        val skewMs = parse(status.serverTime)?.let { it - nowMs } ?: 0L

        fun local(since: String?): Long? = parse(since)?.let { it - skewMs }

        val fresh = ArrayList<Cut>()
        for (cut in status.recent) {
            val since = local(cut.since) ?: continue
            fresh +=
                Cut(
                    TallyRedZone(
                        active = cut.active,
                        gameId = cut.gameId,
                        title = cut.title,
                        reason = cut.reason,
                        since = cut.since,
                        next = status.next,
                    ),
                    since,
                )
        }
        val last = fresh.lastOrNull()
        if (last != null && last.status.sameCut(status)) {
            // what is on now: the latest answer's words (its reason, its next) at the time the playlist got it
            fresh[fresh.lastIndex] = Cut(kept, last.sinceMs)
        } else {
            // an older server (no recent), or a cut decided that has not reached the playlist yet: at its own time,
            // or as on already when it has none (the channel is not running yet)
            val since = local(status.since)?.coerceAtLeast(last?.sinceMs ?: Long.MIN_VALUE) ?: (last?.sinceMs ?: Long.MIN_VALUE)
            fresh += Cut(kept, since)
        }
        fresh.sortBy { it.sinceMs }

        // the answer is the truth from its first cut on; what came before it stays as known
        val from = fresh.first().sinceMs
        cuts.removeAll { it.sinceMs >= from }
        cuts += fresh
        cuts.sortBy { it.sinceMs }
        // the cut on screen, as the latest answer words it (a new reason shows at once: it is the same picture)
        shown?.let { on -> shown = cuts.firstOrNull { it.sinceMs == on.sinceMs && it.status.sameCut(on.status) } ?: on }
        while (cuts.size > MAX_CUTS) cuts.removeAt(0)
    }

    /**
     * What to show at [nowMs] (device clock) for a player [latencyMs] behind the live edge: the last cut that entered
     * the playlist at or before `nowMs - latencyMs`; before any has, the first one known (a player that just tuned in).
     */
    fun at(
        nowMs: Long,
        latencyMs: Long,
    ): TallyRedZone? {
        val reached = nowMs - latencyMs
        val on = shown
        val candidate = cuts.lastOrNull { it.sinceMs <= reached }
        when {
            candidate != null && (on == null || candidate.sinceMs >= on.sinceMs) -> shown = candidate
            on == null -> shown = cuts.firstOrNull()
        }
        // what came before the cut on screen is never shown again
        shown?.let { s -> cuts.removeAll { it.sinceMs < s.sinceMs } }
        return shown?.status
    }

    /** Forgets everything (another channel, or RedZone tuned in again). */
    fun reset() {
        cuts.clear()
        shown = null
    }

    companion object {
        /** A player whose distance from the live edge cannot be read is taken to sit this far behind (ms). */
        const val UNKNOWN_LATENCY_MS = 20_000L

        private const val MAX_CUTS = 16

        private fun TallyRedZone.sameCut(other: TallyRedZone): Boolean = active == other.active && gameId == other.gameId

        /** An ISO-8601 time in epoch ms, or null. */
        fun parse(iso: String?): Long? =
            iso?.takeIf { it.isNotBlank() }?.let {
                runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            }
    }
}

/**
 * How far behind the RedZone channel's live edge a player's picture is, for [RedZoneSync.at]. Pure: the player page
 * reads the numbers off ExoPlayer.
 */
object PlayerLatency {
    /**
     * Played through Jellyfin's Live TV, the channel goes through Jellyfin's remux first (ffmpeg reads the channel's
     * playlist whenever its reload comes round, then lists what it wrote as its own segments): the picture is this much
     * further behind the channel than the player's own distance from the edge of Jellyfin's playlist says (ms): measured
     * at 0.2 to 3.7 s over six cuts on the Android TV emulator through the dev server's Live TV (3-second segments),
     * so the middle of that.
     */
    const val REMUX_MS = 2_000L

    /** The time since the playlist's end last moved counts only up to this (a stalled playlist is not a live edge). */
    const val MAX_EDGE_AGE_MS = 6_000L

    /**
     * [liveOffsetMs]: ExoPlayer's `currentLiveOffset` when it knows it (a playlist with PROGRAM-DATE-TIME), else null.
     * Otherwise the distance to the end of the live window ([windowMs] - [positionMs]) plus the time since that end last
     * moved ([edgeAgeMs]: segments are listed whole, so the real edge runs ahead of the listed one in between). [remux]:
     * the stream is Jellyfin's remux of the channel, not the channel's own playlist. Null when nothing is known.
     */
    fun of(
        liveOffsetMs: Long?,
        windowMs: Long?,
        positionMs: Long,
        edgeAgeMs: Long,
        remux: Boolean,
    ): Long? {
        val player =
            liveOffsetMs?.takeIf { it >= 0 }
                ?: windowMs?.takeIf { it > 0 }?.let { (it - positionMs).coerceAtLeast(0) + edgeAgeMs.coerceIn(0, MAX_EDGE_AGE_MS) }
                ?: return null
        return player + if (remux) REMUX_MS else 0L
    }
}
