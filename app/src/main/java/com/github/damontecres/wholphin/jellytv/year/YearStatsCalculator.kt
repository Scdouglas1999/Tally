package com.github.damontecres.wholphin.jellytv.year

import java.time.ZoneId

/** STUB: task `year` implements it (pure; unit-tested against a real captured payload). */
object YearStatsCalculator {
    fun compute(
        items: List<WatchedItem>,
        year: Int,
        zone: ZoneId,
    ): YearStats = YearStats(year, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), List(12) { 0L }, null, null, null, null)
}
