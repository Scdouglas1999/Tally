package io.github.scdouglas1999.tally.dvr

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words and numbers for the DVR: sizes, times, a job's state. */
object DvrFormat {
    private const val KB = 1024.0
    private const val TICKS_PER_SECOND = 10_000_000L

    /** `350 MB`, `4.2 GB`, `12 GB`, `1.2 TB` (binary units, as the server counts). */
    fun size(bytes: Long): String {
        val gb = bytes / (KB * KB * KB)
        val tb = gb / KB
        return when {
            tb >= 1 -> "%.1f TB".format(Locale.US, tb).replace(".0 ", " ")
            gb >= 10 -> "%.0f GB".format(Locale.US, gb)
            gb >= 1 -> "%.1f GB".format(Locale.US, gb).replace(".0 ", " ")
            else -> "%.0f MB".format(Locale.US, (bytes / (KB * KB)).coerceAtLeast(1.0))
        }
    }

    /** `2h 58m`, `42m`: a recording's length. */
    fun length(seconds: Double): String = formatRuntime((seconds * TICKS_PER_SECOND).toLong())

    fun instant(iso: String?): Instant? =
        try {
            iso?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (_: DateTimeException) {
            null
        }

    /** `42:10`, `1:02:03`: how long a recording has been running. */
    fun elapsed(
        since: Instant,
        now: Instant = Instant.now(),
    ): String {
        val total = Duration.between(since, now).seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }

    /** `Sep 24`, month first. */
    fun date(instant: Instant): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.US, "MMMd")
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(instant.atZone(ZoneId.systemDefault()))
    }

    /** `7:12 PM` (or `19:12` on a 24-hour device). */
    @Composable
    fun time(instant: Instant): String {
        val is24 = DateFormat.is24HourFormat(LocalContext.current)
        val pattern = if (is24) "H:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(instant.atZone(ZoneId.systemDefault()))
    }

    /** `Sep 24 · 7:12 PM`, or only the time for today. */
    @Composable
    fun dayAndTime(instant: Instant): String {
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        return if (instant.atZone(zone).toLocalDate() == today) {
            stringResource(R.string.tally_dvr_today_at, time(instant))
        } else {
            date(instant) + " · " + time(instant)
        }
    }
}

/**
 * A job's state in words: "Recording since 7:12 PM", "Waiting for a stream", "Scheduled to record", "Failed: No
 * stream found for this game", "Finishing the recording", "Recorded", "Canceled".
 */
@Composable
fun recordingStateText(view: GameRecordingView): String =
    when (view.state) {
        DvrState.RECORDING -> {
            DvrFormat.instant(view.startedAt)?.let { stringResource(R.string.tally_dvr_state_recording_since, DvrFormat.time(it)) }
                ?: stringResource(R.string.tally_dvr_state_recording)
        }

        DvrState.WAITING -> {
            // The server says why: no channel carries the game yet, or every recording slot is taken.
            view.reason?.takeIf { it.startsWith(FREE_SLOT_PREFIX) } ?: stringResource(R.string.tally_dvr_state_waiting)
        }

        DvrState.SCHEDULED -> {
            stringResource(R.string.tally_dvr_state_scheduled)
        }

        DvrState.FINISHING -> {
            stringResource(R.string.tally_dvr_state_finishing)
        }

        DvrState.DONE -> {
            stringResource(R.string.tally_dvr_state_done)
        }

        DvrState.FAILED -> {
            stringResource(R.string.tally_dvr_state_failed, view.reason ?: stringResource(R.string.tally_dvr_error_generic))
        }

        DvrState.CANCELED -> {
            stringResource(R.string.tally_dvr_state_canceled)
        }

        else -> {
            view.state
        }
    }

private const val FREE_SLOT_PREFIX = "Waiting for a free slot"

/** "Keep all" / "Keep the last 5". */
@Composable
fun keepLastText(keepLast: Int): String =
    if (keepLast <= 0) {
        stringResource(R.string.tally_dvr_keep_all)
    } else {
        stringResource(R.string.tally_dvr_keep_last, keepLast)
    }

/** The choices of the keep-last sheet: all, 5, 10, 20. */
val KeepLastChoices = listOf(0, 5, 10, 20)
