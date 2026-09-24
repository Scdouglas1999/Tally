package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.media.drawer.TallyGlyph
import io.github.scdouglas1999.tally.media.drawer.tallyGlyph
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The phone's bottom bar: [PhoneDimens.bottomBarHeight] of equal cells (Home, Movies, Shows, Sports, More; a cell is
 * left out when its library or the Tally plugin is not there) over the gesture-bar inset, on `ground` with a 1dp
 * `rule` line on top. The [current] tab is `text` with a 16x2dp accent bar at its top edge, the others `muted`.
 */
@Composable
internal fun PhoneBottomBar(
    nav: PhoneNavModel,
    current: PhoneTab,
    onTab: (PhoneTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<PhoneTab> = nav.tabs,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(TallyColors.ground)
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, stroke / 2f),
                        end = Offset(size.width, stroke / 2f),
                        strokeWidth = stroke,
                    )
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.bottomBarHeight)
                    .padding(top = PhoneDimens.hairline),
        ) {
            tabs.forEach { tab ->
                PhoneTabCell(
                    label = stringResource(tab.label()),
                    glyph = tab.glyph(nav),
                    selected = tab == current,
                    onClick = { onTab(tab) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private fun PhoneTab.label(): Int =
    when (this) {
        PhoneTab.HOME -> R.string.tally_phone_tab_home
        PhoneTab.MOVIES -> R.string.tally_phone_tab_movies
        PhoneTab.SHOWS -> R.string.tally_phone_tab_shows
        PhoneTab.SPORTS -> R.string.tally_phone_tab_sports
        PhoneTab.MORE -> R.string.tally_phone_tab_more
        PhoneTab.DOWNLOADS -> R.string.tally_dl_page_title
        PhoneTab.SETTINGS -> R.string.settings
    }

/** The drawer's glyph for the same entry. */
private fun PhoneTab.glyph(nav: PhoneNavModel): TallyGlyph =
    when (this) {
        PhoneTab.HOME -> TallyGlyph.Font(R.string.fa_house)
        PhoneTab.MOVIES -> nav.movies?.let { tallyGlyph(it.value) } ?: TallyGlyph.Font(R.string.fa_film)
        PhoneTab.SHOWS -> nav.shows?.let { tallyGlyph(it.value) } ?: TallyGlyph.Font(R.string.fa_tv)
        PhoneTab.SPORTS -> TallyGlyph.Font(R.string.tally_drawer_fa_trophy)
        PhoneTab.MORE -> TallyGlyph.Font(R.string.fa_ellipsis)
        PhoneTab.DOWNLOADS -> TallyGlyph.Font(R.string.fa_download)
        PhoneTab.SETTINGS -> TallyGlyph.Font(R.string.tally_drawer_fa_settings)
    }

@Composable
private fun PhoneTabCell(
    label: String,
    glyph: TallyGlyph,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) TallyColors.text else TallyColors.muted
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .semantics { this.selected = selected }
                .phoneClickable(role = Role.Tab, onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(width = 16.dp, height = 2.dp)
                        .background(TallyColors.accent),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhoneGlyph(glyph = glyph, size = 20.dp, color = color)
            Text(
                text = label.tallyUppercase(),
                style = PhoneType.label,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
