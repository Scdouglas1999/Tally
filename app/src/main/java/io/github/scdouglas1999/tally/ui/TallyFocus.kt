package io.github.scdouglas1999.tally.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/** The selected tab of the Tally page, so the top row of any tab's content can send UP straight to it. */
val LocalTallyUpTarget = compositionLocalOf<FocusRequester?> { null }

/**
 * Sends UP from this element to the selected tab. Custom focus properties are inherited only down to the
 * nearest focus group, and every lazy row and grid is one, so this goes on the top row's own elements rather
 * than on a page-level parent (which would only reach the empty states).
 */
fun Modifier.upToTab(): Modifier =
    composed {
        val target = LocalTallyUpTarget.current
        if (target == null) this else focusProperties { up = target }
    }
