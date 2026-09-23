package io.github.scdouglas1999.tally.media.search.phone

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.titleStringRes
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.settings.TallySquareSwitch
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetListMaxHeight
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Search's view options on a phone, as a sheet (upstream's `SearchViewOptionsDialog` on the TV): combined results and
 * the voice button as square switches, then "Include types", which opens [PhoneSearchTypesSheet].
 */
@Composable
internal fun PhoneSearchViewOptionsSheet(
    combinedResults: Boolean,
    onCombinedResultsChange: (Boolean) -> Unit,
    voiceSearchButtonVisible: Boolean,
    onVoiceSearchButtonVisibleChange: (Boolean) -> Unit,
    onClickFilterTypes: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    PhoneSheet(onDismiss = onDismissRequest) {
        PhoneSheetTitle(stringResource(R.string.view_options))
        PhoneDialogRow(
            onClick = { onCombinedResultsChange(!combinedResults) },
            headline = { Text(stringResource(R.string.combined_search_results)) },
            supporting = {
                Text(
                    stringResource(
                        if (combinedResults) R.string.combined_search_results_on else R.string.combined_search_results_off,
                    ),
                )
            },
            trailing = { TallySquareSwitch(checked = combinedResults) },
        )
        PhoneDialogRow(
            onClick = { onVoiceSearchButtonVisibleChange(!voiceSearchButtonVisible) },
            headline = { Text(stringResource(R.string.show_voice_search_button)) },
            supporting = {
                Text(stringResource(if (voiceSearchButtonVisible) R.string.visible_ui else R.string.hidden_ui))
            },
            trailing = { TallySquareSwitch(checked = voiceSearchButtonVisible) },
        )
        PhoneDialogRow(
            onClick = onClickFilterTypes,
            headline = { Text(stringResource(R.string.include_types)) },
            trailing = { Text(text = "›", style = PhoneType.body, color = TallyColors.muted, maxLines = 1) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The types search looks in, as a sheet (upstream's `SearchTypeOptionsDialog` on the TV): one row per type with a
 * square switch (on = included), and Discover when a Seerr server is set up.
 */
@Composable
internal fun PhoneSearchTypesSheet(
    onDismissRequest: () -> Unit,
    searchableTypes: List<BaseItemKind>,
    excludedSearchableTypes: List<BaseItemKind>,
    discoverAvailable: Boolean,
    discoverEnabled: Boolean,
    onClick: (BaseItemKind) -> Unit,
    onClickDiscover: () -> Unit,
) {
    PhoneSheet(onDismiss = onDismissRequest) {
        PhoneSheetTitle(stringResource(R.string.include_types))
        LazyColumn(modifier = Modifier.heightIn(max = phoneSheetListMaxHeight())) {
            items(searchableTypes) { type ->
                PhoneDialogRow(
                    onClick = { onClick(type) },
                    headline = { Text(stringResource(type.titleStringRes)) },
                    trailing = { TallySquareSwitch(checked = type !in excludedSearchableTypes) },
                )
            }
            if (discoverAvailable) {
                item {
                    PhoneDialogRow(
                        onClick = onClickDiscover,
                        headline = { Text(stringResource(R.string.discover)) },
                        trailing = { TallySquareSwitch(checked = discoverEnabled) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
