package com.github.damontecres.wholphin.jellytv.quality

/** One choice in the quality dialog. [bitsPerSecond] null = Original. [label] like "1080P · 10 MBPS". */
data class QualityOption(
    val bitsPerSecond: Int?,
    val label: String,
)

/**
 * STUB: task `quality` implements it. The choices offered for a source of [sourceHeight] pixels high at
 * [sourceBitrate] bits/s (either may be unknown): Original first, then the ladder entries that are below the
 * source in BOTH resolution and bitrate (an unknown value never excludes an entry). Ladder, best first:
 * 4K 60 Mbps, 4K 40, 1080p 20, 1080p 10, 720p 6, 720p 4, 480p 2, 360p 1.
 */
object QualityLadder {
    fun options(
        sourceHeight: Int?,
        sourceBitrate: Int?,
    ): List<QualityOption> = listOf(QualityOption(null, "ORIGINAL"))
}
