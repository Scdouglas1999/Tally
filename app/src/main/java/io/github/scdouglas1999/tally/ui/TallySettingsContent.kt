package io.github.scdouglas1999.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyInfo
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import io.github.scdouglas1999.tally.ui.upToTab

/**
 * The Tally settings list, shared by the Settings tab and [TallySettingsPage]:
 * focusable rows with square two-state switches, then a muted block with the
 * server's plugin build, API version, and enabled features.
 */
@Composable
fun TallySettingsContent(
    onlyWatchable: Boolean,
    hideScores: Boolean,
    info: TallyInfo?,
    onToggleOnlyWatchable: () -> Unit,
    onHideScoresChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        firstRowFocus.tryRequestFocus("jellytv-settings")
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = 24.dp),
    ) {
        TallyRow(
            label = stringResource(R.string.tally_settings_my_channels),
            description = stringResource(R.string.tally_settings_my_channels_desc),
            onClick = onToggleOnlyWatchable,
            trailing = { TallySwitch(checked = onlyWatchable) },
            modifier = Modifier.focusRequester(firstRowFocus).upToTab(),
        )
        TallyRow(
            label = stringResource(R.string.tally_settings_hide_scores),
            description = stringResource(R.string.tally_settings_hide_scores_desc),
            onClick = { onHideScoresChange(!hideScores) },
            trailing = { TallySwitch(checked = hideScores) },
        )
        ServerBlock(info = info, modifier = Modifier.padding(top = 24.dp))
    }
}

/**
 * A square two-state switch in the design language: a 1dp square outline with a
 * filled accent square inside when on. Not a Material switch.
 */
@Composable
private fun TallySwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(26.dp)
                .border(
                    TallyDimens.hairline,
                    if (checked) TallyColors.accent else TallyColors.ruleStrong,
                ),
    ) {
        if (checked) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(TallyColors.accent),
            )
        }
    }
}

@Composable
private fun ServerBlock(
    info: TallyInfo?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.tally_settings_plugin).uppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
        if (info == null) {
            Text(
                text = stringResource(R.string.tally_server_unavailable),
                style = TallyType.hint,
                color = TallyColors.muted,
            )
        } else {
            Text(
                text =
                    stringResource(
                        R.string.tally_server_version,
                        info.pluginBuild ?: "\u2014",
                        info.apiVersion,
                    ),
                style = TallyType.hint,
                color = TallyColors.muted,
            )
            Text(
                text =
                    stringResource(
                        R.string.tally_server_features,
                        info.features.joinToString(", ").ifBlank { "\u2014" },
                    ),
                style = TallyType.hint,
                color = TallyColors.muted,
            )
        }
    }
}
