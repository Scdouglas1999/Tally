package io.github.scdouglas1999.tally.ui.settings.phone

import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitlePreferencesContent
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.toSubtitleStyle
import com.github.damontecres.wholphin.util.Media3SubtitleOverride
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** The live preview's height on a phone. */
private val PreviewHeight = 220.dp

/** False inside a phone page that draws its settings title itself (the subtitle style page, over its preview). */
internal val LocalPhoneSettingsTitleShown = staticCompositionLocalOf { true }

/**
 * The subtitle style page on a phone (seam in upstream's `SubtitleStylePage`): the top bar, upstream's live preview
 * (the three example lines on its picture, in the chosen style) pinned under it, and upstream's list of settings
 * scrolling below.
 */
@OptIn(UnstableApi::class)
@Composable
fun PhoneSubtitleStylePage(
    title: String,
    preferences: SubtitlePreferences,
    prefList: List<PreferenceGroup<SubtitlePreferences>>,
    onPreferenceChange: suspend (SubtitlePreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(TallyColors.ground)
                .navigationBarsPadding(),
    ) {
        PhoneSettingsTopBar(title = title)
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PreviewHeight),
        ) {
            Image(
                painter = painterResource(R.mipmap.eclipse),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(12.dp).fillMaxSize(),
            ) {
                // Upstream's example lines.
                listOf(
                    "Subtitles will look like this",
                    "This is another example",
                    "Longer multi line subtitles will\nlook like this",
                ).forEach { text ->
                    AndroidView(
                        factory = { context -> SubtitleView(context) },
                        update = {
                            it.setStyle(preferences.toSubtitleStyle())
                            it.setFixedTextSize(Dimension.SP, preferences.fontSize.toFloat())
                            it.setCues(listOf(Cue.Builder().setText(text).build()))
                            Media3SubtitleOverride(preferences.calculateEdgeSize(density)).apply(it)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
        CompositionLocalProvider(LocalPhoneSettingsTitleShown provides false) {
            SubtitlePreferencesContent(
                title = title,
                preferences = preferences,
                prefList = prefList,
                onPreferenceChange = onPreferenceChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
