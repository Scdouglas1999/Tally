package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType

/** Square portrait. Name over a muted role. No circles. */
@Composable
fun PersonCard(
    name: String,
    role: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    width: Dp = PersonWidth,
) {
    CardFrame(
        imageUrl = imageUrl,
        width = width,
        height = width,
        contentDescription = name,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onFocused = onFocused,
        label = {
            CardTitleText(text = name, style = PersonNameStyle)
            if (!role.isNullOrBlank()) CardDetailText(role)
        },
    )
}

private val PersonNameStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 15.sp,
    )

internal val PersonWidth = 104.dp
