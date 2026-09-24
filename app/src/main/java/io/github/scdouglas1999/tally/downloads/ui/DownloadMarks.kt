package io.github.scdouglas1999.tally.downloads.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.scdouglas1999.tally.downloads.DownloadEntry
import io.github.scdouglas1999.tally.downloads.TallyDownloads
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A card's download mark: [done] (the down-arrow square) or under way at [progress] (the accent line). */
data class DownloadMark(
    val done: Boolean,
    val progress: Float,
)

/**
 * Every item's download mark, keyed by the item's id and, for a show, season or album, by that id too (downloaded
 * when at least one of its items is, under way while one is). One shared flow for all the cards on screen.
 */
@Singleton
class DownloadMarkIndex
    @Inject
    constructor(
        downloads: TallyDownloads,
        @param:DefaultCoroutineScope scope: CoroutineScope,
    ) {
        val marks: StateFlow<Map<UUID, DownloadMark>> =
            downloads.all
                .map(::marksOf)
                .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        companion object {
            fun marksOf(entries: List<DownloadEntry>): Map<UUID, DownloadMark> {
                val result = HashMap<UUID, DownloadMark>()
                entries.forEach { result[it.itemId] = DownloadMark(it.isDone, it.fraction()) }
                val groups =
                    entries.flatMap { entry ->
                        listOfNotNull(entry.seriesId, entry.seasonId, entry.albumId).map { it to entry }
                    }
                groups.groupBy({ it.first }, { it.second }).forEach { (id, members) ->
                    when (val status = statusOf(members)) {
                        is DownloadStatus.Active -> result[id] = DownloadMark(false, status.progress)
                        is DownloadStatus.Done -> result[id] = DownloadMark(true, 1f)
                        DownloadStatus.None -> Unit
                    }
                }
                return result
            }
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DownloadUiEntryPoint {
    fun markIndex(): DownloadMarkIndex

    fun downloads(): TallyDownloads
}

private fun entryPointOrNull(context: Context): DownloadUiEntryPoint? =
    try {
        EntryPointAccessors.fromApplication(context.applicationContext, DownloadUiEntryPoint::class.java)
    } catch (e: RuntimeException) {
        // not the app's Hilt graph (previews, tests of upstream screens): no downloads UI
        null
    }

private fun markIndexOrNull(context: Context): DownloadMarkIndex? = entryPointOrNull(context)?.markIndex()

/** [TallyDownloads] for code that has no view model (the phone shell), or null outside the app. */
internal fun downloadsOrNull(context: Context): TallyDownloads? = entryPointOrNull(context)?.downloads()

/** All marks, live; empty on a TV (downloads are a phone feature) and outside the app. */
@Composable
private fun rememberMarks(): State<Map<UUID, DownloadMark>>? {
    if (LocalTallyFormFactor.current != TallyFormFactor.PHONE) return null
    val context = LocalContext.current
    val index = remember(context) { markIndexOrNull(context) } ?: return null
    return index.marks.collectAsState()
}

/** [itemId]'s download mark, or null (not downloaded, a TV). Recomposes only when this item's mark changes. */
@Composable
fun downloadMark(itemId: UUID?): DownloadMark? {
    if (itemId == null) return null
    val marks = rememberMarks() ?: return null
    val mark by remember(marks, itemId) { derivedStateOf { marks.value[itemId] } }
    return mark
}

/** The downloaded mark: an 8dp `labelBar` square with a down arrow in `text`. */
@Composable
fun DownloadedSquare(
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Canvas(modifier = modifier.size(size).background(TallyColors.labelBar)) {
        val w = this.size.width
        val stroke = (w / 8f).coerceAtLeast(1f)
        val cx = w / 2f
        val top = w * 0.18f
        val bottom = w * 0.80f
        val arm = w * 0.26f
        drawLine(TallyColors.text, Offset(cx, top), Offset(cx, bottom), stroke, StrokeCap.Square)
        drawLine(TallyColors.text, Offset(cx - arm, bottom - arm), Offset(cx, bottom), stroke, StrokeCap.Square)
        drawLine(TallyColors.text, Offset(cx + arm, bottom - arm), Offset(cx, bottom), stroke, StrokeCap.Square)
    }
}

/** A row or card whose item downloads: a 2dp accent line along its bottom edge, the share done of its width. */
fun Modifier.downloadEdge(mark: DownloadMark?): Modifier =
    if (mark == null || mark.done) {
        this
    } else {
        drawWithContent {
            drawContent()
            val line = 2.dp.toPx()
            drawRect(
                color = TallyColors.accent,
                topLeft = Offset(0f, size.height - line),
                size = Size(size.width * mark.progress.coerceIn(0.02f, 1f), line),
            )
        }
    }
