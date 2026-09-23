package io.github.scdouglas1999.tally.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import kotlinx.coroutines.delay

/**
 * Requests focus on [requester] until [isFocused] says it (or something under it) has it. It waits a frame first:
 * a request made before the target's own effects start is lost to its focus visuals (the interaction is emitted
 * before anything collects it, so the element is focused but draws unfocused), and the tiles of a LazyRow are not
 * attached on the first frame (the old "nothing focused on Select Server" bug).
 *
 * On a phone it does nothing, so no screen opens with a focus ring on it (and so [initialFocus] and [arrivalFocus]
 * do nothing there either).
 */
internal suspend fun requestUntilFocused(
    requester: FocusRequester,
    isFocused: () -> Boolean,
    focusManager: FocusManager,
    tag: String,
) {
    if (TallyFormFactor.current == TallyFormFactor.PHONE) return
    delay(50)
    // Compose may already have moved focus here on the first frame (the element focused before was removed), before
    // this element collects focus interactions: it is then focused but draws unfocused. Take focus again.
    if (isFocused()) focusManager.clearFocus(force = true)
    repeat(40) {
        if (isFocused()) return
        requester.tryRequestFocus(tag)
        delay(50)
    }
}

/** A modifier that gives its element focus on arrival (again whenever [key] changes), via [requestUntilFocused]. */
@Composable
internal fun initialFocus(
    tag: String,
    key: Any? = Unit,
): Modifier {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(key) { requestUntilFocused(requester, { focused }, focusManager, tag) }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
}

/**
 * Focus on arrival for a page whose target is chosen elsewhere (a restored row, the play button): gives [target]
 * focus via [requestUntilFocused] once (again whenever [key] changes) and returns the modifier for the page's root,
 * which tells the helper when focus has landed inside the page. Replaces upstream's `RequestOrRestoreFocus` and bare
 * first-frame `tryRequestFocus` calls, which can leave the target focused but drawn unfocused.
 */
@Composable
internal fun arrivalFocus(
    target: FocusRequester?,
    tag: String,
    key: Any? = Unit,
): Modifier {
    val focusManager = LocalFocusManager.current
    var pageFocused by remember { mutableStateOf(false) }
    if (target != null) {
        LaunchedEffect(key) { requestUntilFocused(target, { pageFocused }, focusManager, tag) }
    }
    return Modifier.onFocusChanged { pageFocused = it.hasFocus }
}
