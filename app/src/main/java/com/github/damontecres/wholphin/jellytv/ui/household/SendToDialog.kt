package com.github.damontecres.wholphin.jellytv.ui.household

import androidx.compose.runtime.Composable
import java.util.UUID

/** Lists the other controllable devices; OK sends [itemId] there at [positionMs] and closes. STUB. */
@Composable
fun SendToDialog(
    itemId: UUID,
    positionMs: Long,
    onDismiss: () -> Unit,
) {
    onDismiss()
}
