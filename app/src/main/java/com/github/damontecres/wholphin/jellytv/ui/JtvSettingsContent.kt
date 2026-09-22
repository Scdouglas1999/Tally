package com.github.damontecres.wholphin.jellytv.ui

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
import com.github.damontecres.wholphin.jellytv.api.JtvInfo
import com.github.damontecres.wholphin.jellytv.ui.components.JtvRow
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.jellytv.ui.upToTab
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The JellyTV settings list, shared by the Settings tab and [JellyTvSettingsPage]:
 * focusable rows with square two-state switches, then a muted block with the
 * server's plugin build, API version, and enabled features.
 */
@Composable
fun JtvSettingsContent(
    onlyWatchable: Boolean,
    hideScores: Boolean,
    info: JtvInfo?,
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
                .padding(horizontal = JtvDimens.marginHorizontal)
                .padding(top = 24.dp),
    ) {
        JtvRow(
            label = stringResource(R.string.jtv_settings_my_channels),
            description = stringResource(R.string.jtv_settings_my_channels_desc),
            onClick = onToggleOnlyWatchable,
            trailing = { JtvSwitch(checked = onlyWatchable) },
            modifier = Modifier.focusRequester(firstRowFocus).upToTab(),
        )
        JtvRow(
            label = stringResource(R.string.jtv_settings_hide_scores),
            description = stringResource(R.string.jtv_settings_hide_scores_desc),
            onClick = { onHideScoresChange(!hideScores) },
            trailing = { JtvSwitch(checked = hideScores) },
        )
        ServerBlock(info = info, modifier = Modifier.padding(top = 24.dp))
    }
}

/**
 * A square two-state switch in the design language: a 1dp square outline with a
 * filled accent square inside when on. Not a Material switch.
 */
@Composable
private fun JtvSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(26.dp)
                .border(
                    JtvDimens.hairline,
                    if (checked) JtvColors.accent else JtvColors.ruleStrong,
                ),
    ) {
        if (checked) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(JtvColors.accent),
            )
        }
    }
}

@Composable
private fun ServerBlock(
    info: JtvInfo?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.jtv_settings_plugin).uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
            maxLines = 1,
        )
        if (info == null) {
            Text(
                text = stringResource(R.string.jtv_server_unavailable),
                style = JtvType.hint,
                color = JtvColors.muted,
            )
        } else {
            Text(
                text =
                    stringResource(
                        R.string.jtv_server_version,
                        info.pluginBuild ?: "\u2014",
                        info.apiVersion,
                    ),
                style = JtvType.hint,
                color = JtvColors.muted,
            )
            Text(
                text =
                    stringResource(
                        R.string.jtv_server_features,
                        info.features.joinToString(", ").ifBlank { "\u2014" },
                    ),
                style = JtvType.hint,
                color = JtvColors.muted,
            )
        }
    }
}
