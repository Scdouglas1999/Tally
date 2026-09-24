package io.github.scdouglas1999.tally.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.settings.TallyPanelFrame
import io.github.scdouglas1999.tally.ui.settings.TallyPanelWindow
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * "Support Tally": one quiet row at the bottom of Settings (About), nowhere else. On a phone it opens the Patreon page
 * in the browser; a TV has no browser, so it shows the address as a QR code ([TallySupportDialog]).
 */
object TallySupport {
    const val PATREON_URL = "https://www.patreon.com/SeanDouglas"

    /** What the TV dialog prints under the code. */
    const val SHORT_ADDRESS = "patreon.com/SeanDouglas"

    /** `tally/release.sh` ends every release's notes with this marker and the Patreon line after it. */
    const val NOTES_MARKER = "<!-- tally-support -->"

    /** Release notes as the app shows them: everything before [NOTES_MARKER] (the Patreon line is for GitHub only). */
    fun cutReleaseNotes(notes: String): String = if (notes.contains(NOTES_MARKER)) notes.substringBefore(NOTES_MARKER).trimEnd() else notes

    /** Opens the Patreon page in the browser (phones). */
    fun openInBrowser(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, PATREON_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser for the Patreon page")
        }
    }
}

/** A TV's Support Tally: the Patreon address as a QR code, the short address in mono under it, and CLOSE. */
@Composable
fun TallySupportDialog(onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (closeFocus.tryRequestFocus("tally-support-close")) return@LaunchedEffect
            delay(40)
        }
    }
    TallyPanelWindow(onDismissRequest = onDismiss) {
        TallyPanelFrame(
            kicker = stringResource(R.string.tally_support_title),
            onBack = onDismiss,
            width = 420.dp,
            trapHorizontal = true,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_support_summary),
                    style = TallyType.body,
                    color = TallyColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                QrCode(text = TallySupport.PATREON_URL, size = 200.dp)
                Text(
                    text = TallySupport.SHORT_ADDRESS,
                    style = TallyType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                )
                TallyButton(
                    label = stringResource(R.string.tally_support_close),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocus),
                )
            }
        }
    }
}

/**
 * [text] as a QR code [size] square: `ground` modules on a `text` square with the standard 4-module quiet zone, so any
 * phone camera reads it off a dark screen.
 */
@Composable
fun QrCode(
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
    dark: Color = TallyColors.ground,
    light: Color = TallyColors.text,
) {
    val modules = remember(text) { QrEncoder.encode(text) }
    Canvas(modifier = modifier.size(size).background(light)) {
        val count = modules.size + QUIET_ZONE * 2
        // whole pixels per module, centered, so every module is the same size on screen
        val cell = kotlin.math.floor(this.size.minDimension / count)
        val origin = (this.size.minDimension - cell * count) / 2f + cell * QUIET_ZONE
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, on ->
                if (on) {
                    drawRect(
                        color = dark,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

private const val QUIET_ZONE = 4
