// Modified for Tally (https://github.com/Scdouglas1999/Tally), a fork of Wholphin
// (https://github.com/damontecres/Wholphin), from September 2026. Changes are marked TALLY: begin/end;
// each change and its date is in the git history. See NOTICE.md.
package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.tv.material3.ListItem

@Composable
fun ClickPreference(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // TALLY: begin
    if (io.github.scdouglas1999.tally.ui.settings.TallySettings.active) {
        io.github.scdouglas1999.tally.ui.settings
            .TallyClickPreference(title, onClick, modifier, summary, onLongClick, interactionSource)
        return
    }
    // TALLY: end
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = {
            PreferenceTitle(title)
        },
        supportingContent = {
            PreferenceSummary(summary)
        },
        interactionSource = interactionSource,
        modifier = modifier,
    )
}
