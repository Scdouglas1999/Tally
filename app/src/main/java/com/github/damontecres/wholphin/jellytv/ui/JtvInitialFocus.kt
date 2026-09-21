package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * A modifier that takes focus once, when its element first appears. A plain function rather than a Modifier
 * extension so an upstream seam can call it by its fully-qualified name without touching the import list.
 */
@Composable
fun initialFocusModifier(tag: String): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus(tag) }
    return Modifier.focusRequester(requester)
}
