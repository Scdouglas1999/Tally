// Modified for Tally (https://github.com/Scdouglas1999/Tally), a fork of Wholphin
// (https://github.com/damontecres/Wholphin), from September 2026. Changes are marked TALLY: begin/end;
// each change and its date is in the git history. See NOTICE.md.
package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.SelectedLeadingContent

@Composable
fun <T> ChoicePreference(
    title: String,
    summary: String?,
    possibleValues: List<T>,
    selectedIndex: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    valueDisplay: @Composable (index: Int, item: T) -> Unit = { _, item -> Text(item.toString()) },
    subtitleDisplay: (index: Int, item: T) -> @Composable (() -> Unit)? = { _, _ -> null },
) {
    // TALLY: begin
    if (io.github.scdouglas1999.tally.ui.settings.TallySettings.active) {
        io.github.scdouglas1999.tally.ui.settings.TallyChoicePreference(
            title = title,
            summary = summary,
            possibleValues = possibleValues,
            selectedIndex = selectedIndex,
            onValueChange = onValueChange,
            modifier = modifier,
            interactionSource = interactionSource,
            valueDisplay = valueDisplay,
            subtitleDisplay = subtitleDisplay,
        )
        return
    }
    // TALLY: end
    var dialogParams by remember { mutableStateOf<DialogParams?>(null) }
    ClickPreference(
        title = title,
        summary = summary,
        onClick = {
            dialogParams =
                DialogParams(
                    title = title,
                    fromLongClick = false,
                    items =
                        possibleValues.mapIndexed { index, item ->
                            DialogItem(
                                headlineContent = { valueDisplay.invoke(index, item) },
                                leadingContent = {
                                    SelectedLeadingContent(index == selectedIndex)
                                },
                                supportingContent = subtitleDisplay.invoke(index, item),
                                onClick = {
                                    onValueChange.invoke(index)
                                    dialogParams = null
                                },
                            )
                        },
                )
        },
        interactionSource = interactionSource,
        modifier = modifier,
    )
    AnimatedVisibility(dialogParams != null) {
        dialogParams?.let {
            DialogPopup(
                showDialog = true,
                title = it.title,
                dialogItems = it.items,
                onDismissRequest = { dialogParams = null },
                waitToLoad = false,
                dismissOnClick = false,
            )
        }
    }
}
