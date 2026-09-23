package io.github.scdouglas1999.tally.ui.player.controls

import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.shortLanguage
import io.github.scdouglas1999.tally.media.series.airDate
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.media.series.episodeNumber
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * Text for the Tally player controls. Pure functions, so the labels can be checked against payloads captured from
 * the dev server.
 */
object PlayerFormat {
    private const val TICKS_PER_MS = 10_000L

    /** What the title block's kicker says about the item. */
    enum class Kind { FILM, EPISODE, LIVE, OTHER }

    fun kind(item: BaseItemDto?): Kind =
        when (item?.type) {
            BaseItemKind.MOVIE -> Kind.FILM

            BaseItemKind.EPISODE -> Kind.EPISODE

            BaseItemKind.TV_CHANNEL,
            BaseItemKind.LIVE_TV_CHANNEL,
            BaseItemKind.CHANNEL,
            BaseItemKind.PROGRAM,
            BaseItemKind.LIVE_TV_PROGRAM,
            BaseItemKind.TV_PROGRAM,
            -> Kind.LIVE

            else -> Kind.OTHER
        }

    /**
     * The kicker over the title: [film] for a film, `BREAKING BAD · S1 E3` for an episode, [live] for a channel,
     * nothing for anything else.
     */
    fun kicker(
        item: BaseItemDto?,
        film: String,
        live: String,
        isLive: Boolean = false,
    ): String? {
        if (isLive) return live.tallyUppercase()
        return when (kind(item)) {
            Kind.FILM -> {
                film.tallyUppercase()
            }

            Kind.LIVE -> {
                live.tallyUppercase()
            }

            Kind.EPISODE -> {
                val code = episodeCode(item?.parentIndexNumber, item?.indexNumber, item?.indexNumberEnd)
                listOfNotNull(item?.seriesName?.takeIf { it.isNotBlank() }, code)
                    .joinToString(" · ")
                    .ifBlank { null }
                    ?.tallyUppercase()
            }

            Kind.OTHER -> {
                null
            }
        }
    }

    /** The title under the kicker: the episode's own name for an episode, the program's for a channel, else the name. */
    fun title(item: BaseItemDto?): String? =
        when (kind(item)) {
            Kind.LIVE -> item?.currentProgram?.name?.takeIf { it.isNotBlank() } ?: item?.name
            else -> item?.name
        }?.takeIf { it.isNotBlank() }

    /** One mono line: `2008 · G` for a film, the air date (`FEB 10, 2008`) for an episode. Null when unknown. */
    fun meta(
        item: BaseItemDto?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        item ?: return null
        return when (kind(item)) {
            Kind.EPISODE -> {
                airDate(item.premiereDate, zone)
            }

            Kind.LIVE -> {
                null
            }

            else -> {
                listOfNotNull(
                    item.productionYear?.toString(),
                    item.officialRating?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { null }?.tallyUppercase()
            }
        }
    }

    /** A playback position as a clock: `0:45`, `12:34`, `1:02:03`. */
    fun clock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** Time left, with a minus sign: `-34:28`. */
    fun remaining(
        positionMs: Long,
        durationMs: Long,
    ): String = "-" + clock((durationMs - positionMs).coerceAtLeast(0L))

    /** Real time left at the current speed, for the end time. */
    fun remainingRealMs(
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): Long {
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        return if (speed > 0f) (left / speed).roundToLong() else left
    }

    /** `1×`, `0.25×`, `1.5×`. */
    fun speed(value: Float): String {
        val text =
            if (value == value.toLong().toFloat()) {
                value.toLong().toString()
            } else {
                value.toBigDecimal().stripTrailingZeros().toPlainString()
            }
        return "$text×"
    }

    /** An audio track in one line: `EN · AC3 5.1`, `JA · OPUS STEREO`. */
    fun audioTrack(stream: MediaStream): String? {
        val language = shortLanguage(stream.language)
        val codec = stream.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
        val channels =
            stream.channelLayout?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
                ?: stream.channels?.takeIf { it > 0 }?.let { "${it}CH" }
        val codecAndChannels = listOfNotNull(codec, channels).joinToString(" ").ifBlank { null }
        return listOfNotNull(language, codecAndChannels).joinToString(" · ").ifBlank { null }
    }

    /** A subtitle track in one line: `EN`, `EN · FORCED`, `ES · EXTERNAL`. */
    fun subtitleTrack(stream: MediaStream): String? {
        val language = shortLanguage(stream.language)
        val flags =
            buildList {
                if (stream.isForced) add("FORCED")
                if (stream.isHearingImpaired == true) add("SDH")
                if (stream.isExternal) add("EXTERNAL")
            }
        val parts = listOfNotNull(language) + flags
        if (parts.isEmpty()) return stream.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
        return parts.joinToString(" · ")
    }

    /** A subtitle offset: `0s`, `+0.25s`, `-1s`. */
    fun subtitleDelay(delay: Duration): String {
        val ms = delay.inWholeMilliseconds
        if (ms == 0L) return "0s"
        val sign = if (ms > 0) "+" else "-"
        val seconds =
            (abs(ms) / 1000.0)
                .toBigDecimal()
                .stripTrailingZeros()
                .toPlainString()
        return "$sign${seconds}s"
    }

    /** Kicker of a chapter card: `CHAPTER 3 · 12:30` (numbered from 1). */
    fun chapterKicker(
        index: Int,
        positionMs: Long,
        chapter: String,
    ): String = "${chapter.tallyUppercase()} ${index + 1} · ${formatPosition(positionMs * TICKS_PER_MS)}"

    /**
     * Index of the chapter playing at [positionMs] given chapter start times sorted ascending, or null before the
     * first one (or with none).
     */
    fun chapterAt(
        startsMs: List<Long>,
        positionMs: Long,
    ): Int? {
        val index = startsMs.indexOfLast { it <= positionMs }
        return index.takeIf { it >= 0 }
    }

    /** Kicker of a queue card: [next] for the first, then `E04 · 47m` (episode number and runtime). */
    fun queueKicker(
        index: Int,
        item: BaseItemDto,
        next: String,
    ): String? {
        if (index == 0) return next.tallyUppercase()
        val number = if (item.type == BaseItemKind.EPISODE) episodeNumber(item.indexNumber) else null
        val runtime = item.runTimeTicks?.takeIf { it > 0 }?.let { formatRuntime(it) }
        return listOfNotNull(number, runtime).joinToString(" · ").ifBlank { null }?.tallyUppercase()
    }

    /** The next-up line: `S1 E4 · Cancer Man`, or the name alone. */
    fun nextUpLine(item: BaseItemDto): String? {
        val code = episodeCode(item.parentIndexNumber, item.indexNumber, item.indexNumberEnd)
        return listOfNotNull(code, item.name?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
    }

    /** How full the countdown bar is with [secondsLeft] of [totalSeconds] to go (0 when none are left). */
    fun countdownFraction(
        secondsLeft: Long,
        totalSeconds: Long,
    ): Float {
        if (totalSeconds <= 0L || secondsLeft <= 0L) return 0f
        return (secondsLeft.toFloat() / totalSeconds).coerceIn(0f, 1f)
    }
}
