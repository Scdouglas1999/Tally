package com.github.damontecres.wholphin.jellytv.quality

import com.github.damontecres.wholphin.preferences.AppPreference

/**
 * One choice in the quality dialog. [bitsPerSecond] null = Original. [megabits] is the step on upstream's Max
 * bitrate slider (null = Original). [label] like "1080P · 10 MBPS" or "ORIGINAL · 14.8 MBPS".
 */
data class QualityOption(
    val bitsPerSecond: Int?,
    val megabits: Int?,
    val label: String,
)

/**
 * Choices offered for a source of [sourceHeight] pixels high at [sourceBitrate] bits/s (either may be
 * unknown). Original first (labeled with the source bitrate when known), then ladder entries whose height is
 * at most the source and whose bitrate is strictly under the source — same resolution at a lower bitrate is a
 * real choice, a higher one is not. An unknown height or bitrate never excludes an entry on that axis.
 * Ladder, best first, on upstream's own Max bitrate steps (1 Mbps = [AppPreference.MEGA_BIT]) so a rung saved
 * as the default lands exactly on the settings slider: 4K 120, 4K 80, 4K 60, 4K 40, 1080p 30, 1080p 20,
 * 1080p 15, 1080p 10, 1080p 8, 720p 5, 720p 3, 480p 2, 360p 1.
 */
object QualityLadder {
    private data class Rung(
        val height: Int,
        val megabits: Int,
    ) {
        val bitsPerSecond: Int = (megabits * AppPreference.MEGA_BIT).toInt()
        val label: String = if (height >= 2000) "4K · $megabits MBPS" else "${height}P · $megabits MBPS"
    }

    private val rungs =
        listOf(
            Rung(2160, 120),
            Rung(2160, 80),
            Rung(2160, 60),
            Rung(2160, 40),
            Rung(1080, 30),
            Rung(1080, 20),
            Rung(1080, 15),
            Rung(1080, 10),
            Rung(1080, 8),
            Rung(720, 5),
            Rung(720, 3),
            Rung(480, 2),
            Rung(360, 1),
        )

    fun options(
        sourceHeight: Int?,
        sourceBitrate: Int?,
    ): List<QualityOption> {
        val height = sourceHeight?.takeIf { it > 0 }
        val bitrate = sourceBitrate?.takeIf { it > 0 }
        val offered =
            rungs
                .filter { rung ->
                    (height == null || rung.height <= height) &&
                        (bitrate == null || rung.bitsPerSecond < bitrate)
                }.map { QualityOption(it.bitsPerSecond, it.megabits, it.label) }
        return listOf(QualityOption(null, null, originalLabel(bitrate))) + offered
    }

    /** `ORIGINAL · 14.8 MBPS`, or `ORIGINAL` alone when the source bitrate is unknown (live streams). */
    fun originalLabel(sourceBitrate: Int?): String {
        val rate = QualityStatus.bitrateLabel(sourceBitrate) ?: return "ORIGINAL"
        return "ORIGINAL · $rate"
    }
}

/**
 * Slider index for [AppPreference.MaxBitrate]: the step whose label is exactly [megabits] Mbps (every rung is
 * one of upstream's steps). The nearest step (the lower one on a tie) is only a safety net. Null is Original,
 * which is upstream's default of 100 Mbps.
 */
internal fun maxBitratePreferenceIndex(megabits: Int?): Long {
    val preference = AppPreference.MaxBitrate
    if (megabits == null) return preference.defaultValue
    val steps =
        (preference.min..preference.max).mapNotNull { index ->
            val label = preference.summarizer?.invoke(index) ?: return@mapNotNull null
            if (!label.endsWith(" Mbps")) return@mapNotNull null
            label.substringBefore(' ').toIntOrNull()?.let { index to it }
        }
    steps.firstOrNull { it.second == megabits }?.let { return it.first }
    return steps
        .minWithOrNull(compareBy<Pair<Long, Int>> { kotlin.math.abs(it.second - megabits) }.thenBy { it.second })
        ?.first ?: preference.defaultValue
}
