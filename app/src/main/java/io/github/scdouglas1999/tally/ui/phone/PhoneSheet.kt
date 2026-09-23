package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** Scrim behind an open sheet. */
private const val SCRIM_ALPHA = 0.6f

/**
 * The phone's bottom sheet, the form every dialog and menu takes on a phone: square top corners, `groundRaised`,
 * a 1dp `ruleStrong` top edge and a 32x3dp `ruleStrong` grab bar; no elevation, no ripple. Swiping it down, tapping
 * the scrim or Back calls [onDismiss]. The content ends above the gesture bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RectangleShape,
        containerColor = TallyColors.groundRaised,
        contentColor = TallyColors.text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = SCRIM_ALPHA),
        dragHandle = null,
        contentWindowInsets = { WindowInsets.navigationBars },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.hairline)
                    .background(TallyColors.ruleStrong),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 3.dp)
                        .background(TallyColors.ruleStrong),
                )
            }
            content()
        }
    }
}
