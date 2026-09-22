package com.github.damontecres.wholphin.jellytv.quality

import com.github.damontecres.wholphin.preferences.AppPreference

/** One choice in the quality dialog. [bitsPerSecond] null = Original. [label] like "1080P · 10 MBPS". */
data class QualityOption(
    val bitsPerSecond: Int?,
    val label: String,
)

/**
 * Choices offered for a source of [sourceHeight] pixels high at [sourceBitrate] bits/s (either may be
 * unknown). Original first, then ladder entries whose height is at most the source and whose bitrate is
 * strictly under the source — same resolution at a lower bitrate is a real choice, a higher one is not.
 * An unknown height or bitrate never excludes an entry on that axis. Ladder, best first:
 * 4K 60 Mbps, 4K 40, 1080p 20, 1080p 10, 720p 6, 720p 4, 480p 2, 360p 1.
 */
object QualityLadder {
    private const val BPS_PER_MBPS = 1_000_000

    private data class Rung(
        val height: Int,
        val megabits: Int,
    ) {
        val bitsPerSecond: Int = megabits * BPS_PER_MBPS
        val label: String = if (height >= 2000) "4K · $megabits MBPS" else "${height}P · $megabits MBPS"
    }

    private val rungs =
        listOf(
            Rung(2160, 60),
            Rung(2160, 40),
            Rung(1080, 20),
            Rung(1080, 10),
            Rung(720, 6),
            Rung(720, 4),
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
                }.map { QualityOption(it.bitsPerSecond, it.label) }
        return listOf(QualityOption(null, "ORIGINAL")) + offered
    }
}

/**
 * Slider index for [AppPreference.MaxBitrate]. The settings control only has the steps upstream lists,
 * so 4 Mbps and 6 Mbps land on the nearest step (the lower one when both are equally close). Null is
 * Original, which is upstream's default of 100 Mbps.
 */
internal fun maxBitratePreferenceIndex(megabits: Int?): Long {
    val preference = AppPreference.MaxBitrate
    if (megabits == null) return preference.defaultValue
    var bestIndex = preference.defaultValue
    var bestDistance = Int.MAX_VALUE
    var bestMbps = Int.MAX_VALUE
    for (index in preference.min..preference.max) {
        val label = preference.summarizer?.invoke(index) ?: continue
        if (!label.endsWith("Mbps")) continue
        val mbps = label.substringBefore(' ').toIntOrNull() ?: continue
        val distance = kotlin.math.abs(mbps - megabits)
        if (distance < bestDistance || (distance == bestDistance && mbps < bestMbps)) {
            bestDistance = distance
            bestMbps = mbps
            bestIndex = index
        }
    }
    return bestIndex
}
