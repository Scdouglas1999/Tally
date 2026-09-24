package io.github.scdouglas1999.tally.ui.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.media.drawer.TallyGlyph
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * A page's top bar on a phone: [PhoneDimens.topBarHeight] under the status bar (its `ground` runs up behind the status
 * bar), an optional back arrow (48dp target), a [kicker] (`PhoneType.label`, uppercase, `muted`) and/or a [title]
 * (`PhoneType.headline`), then [actions] at the end (48dp targets, e.g. [PhoneTopBarAction]). The 1dp `rule` line
 * under it appears only once the page has [scrolled] (see [phoneScrolled]). [statusBarPadding] false leaves the status
 * bar to the page (a bar placed lower down).
 */
@Composable
fun PhoneTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    kicker: String? = null,
    onBack: (() -> Unit)? = null,
    scrolled: Boolean = false,
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(TallyColors.ground)
                .then(if (statusBarPadding) Modifier.phoneStatusBarPadding() else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.topBarHeight)
                    .drawWithContent {
                        drawContent()
                        if (scrolled) {
                            val stroke = PhoneDimens.hairline.toPx()
                            drawLine(
                                color = TallyColors.rule,
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke,
                            )
                        }
                    },
        ) {
            if (onBack != null) {
                Spacer(Modifier.width(4.dp))
                PhoneTopBarAction(
                    glyph = R.string.tally_phone_fa_arrow_left,
                    label = stringResource(R.string.tally_phone_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(PhoneDimens.margin))
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                if (kicker != null) {
                    Text(
                        text = kicker.tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (title != null) {
                    Text(
                        text = title,
                        style = PhoneType.headline,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * The top bar's back arrow for a page opened from the More sheet (Favorites, Surprise me, a library with tabs): the
 * system back, so it goes where Back goes. Null when there is no back dispatcher.
 */
@Composable
fun phoneSystemBack(): (() -> Unit)? {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return null
    return { dispatcher.onBackPressed() }
}

/** A 48dp top-bar button: a 20dp Font Awesome [glyph] (a `fa_` string) in `text`; [label] is its accessibility name. */
@Composable
fun PhoneTopBarAction(
    @StringRes glyph: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(PhoneDimens.touchTarget)
                .semantics { contentDescription = label }
                .phoneClickable(onClick = onClick),
    ) {
        PhoneGlyph(glyph = TallyGlyph.Font(glyph), size = 20.dp, color = TallyColors.text)
    }
}

/** True once the list has scrolled away from its top: [PhoneTopBar]'s `scrolled`. */
val LazyListState.phoneScrolled: Boolean
    get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** True once the grid has scrolled away from its top: [PhoneTopBar]'s `scrolled`. */
val LazyGridState.phoneScrolled: Boolean
    get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** True once the column has scrolled away from its top: [PhoneTopBar]'s `scrolled`. */
val ScrollState.phoneScrolled: Boolean
    get() = value > 0
