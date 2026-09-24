package io.github.scdouglas1999.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppMultiChoicePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.ExoPlayerPreferences
import com.github.damontecres.wholphin.preferences.MpvPreferences
import com.github.damontecres.wholphin.preferences.SkipSegmentPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.advancedPreferences
import com.github.damontecres.wholphin.preferences.basicPreferences
import com.github.damontecres.wholphin.preferences.experimentalPreferences
import com.github.damontecres.wholphin.preferences.screensaverPreferences
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.preferences.PreferencesContent
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.theme.getThemeColors
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.media.drawer.TallyNavDrawer
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The widest a settings row gets on the full-width page. */
private val ROW_MAX_WIDTH = 720.dp

/**
 * Upstream's settings list pads its content by 16dp at the real density (20dp at the Tally scale): the list sits
 * this far left of the page margin so its kicker and rows start at the margin.
 */
private val LIST_INSET = 20.dp

/** The description panel never gets narrower than this; the list gives way first. */
private val PANEL_MIN_WIDTH = 240.dp

/** Space between the rows' right edge and the description panel. */
private val PANEL_GAP = 40.dp

/**
 * The panel's rule lines up with the first group's rule: the kicker (24dp above, a 17dp line, [SettingsKickerGap]
 * under), the list's top padding and the group header's own 14dp.
 */
private val PANEL_TOP = 24.dp + 17.dp + SettingsKickerGap + LIST_INSET + 14.dp

private val SWATCH_SIZE = 20.dp
private val SWATCH_GAP = 6.dp

private val panelTitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

private val panelBodyStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

@HiltViewModel
class TallySettingsPageViewModel
    @Inject
    constructor(
        serverRepository: ServerRepository,
        userPreferencesService: UserPreferencesService,
    ) : ViewModel() {
        val current: StateFlow<CurrentUser?> = serverRepository.current
        val userPreferences: Flow<UserPreferences> = userPreferencesService.flow
    }

/**
 * Lives at `PreferencesPage`'s seam, which both themes pass through, so it outlasts a theme switch: it remembers the
 * theme the page was last drawn with, and whether the Tally page has to put focus back on the Application Theme row.
 */
internal class TallySettingsArrival {
    var lastTheme: AppThemeColors? = null
    var focusThemeRow = false
}

/** Set while the page is drawn inside its own drawer (see [TallyPreferencesPage]). */
private val LocalTallySettingsArrival = compositionLocalOf<TallySettingsArrival?> { null }

/**
 * The settings pages (`Destination.Settings`) while the Tally theme is selected: a full-width page with the drawer
 * rail at the left, upstream's list (its rows are the Tally rows) and a description panel for the focused row.
 * Returns false under a Wholphin theme, and upstream's page is drawn.
 *
 * Upstream's settings destinations are full screen (no drawer), so the page brings the Tally drawer itself; the
 * drawer draws its destination (this page again), which then only draws the page.
 */
@Composable
fun TallyPreferencesPage(
    initialPreferences: AppPreferences,
    screen: PreferenceScreenOption,
    modifier: Modifier = Modifier,
): Boolean {
    val theme = LocalTheme.current
    val tally = theme == AppThemeColors.TALLY
    val inDrawer = LocalTallySettingsArrival.current
    if (inDrawer != null) {
        if (!tally) return false
        TallySettingsBody(initialPreferences, screen, inDrawer, modifier)
        return true
    }
    val arrival = remember { TallySettingsArrival() }
    if (arrival.lastTheme != theme) {
        // Chosen on this page under a Wholphin theme: the Tally page replaces upstream's, and focus goes back to
        // the row that was used.
        if (tally && arrival.lastTheme != null) arrival.focusThemeRow = true
        arrival.lastTheme = theme
    }
    if (!tally) return false
    if (isPhone()) {
        // A phone: the list full width under its top bar, no drawer and no description panel.
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(TallyColors.ground)
                    // a full-screen destination: no bottom bar, only the gesture bar under it
                    .navigationBarsPadding(),
        ) {
            PreferencesContent(
                initialPreferences = initialPreferences,
                preferenceScreenOption = screen,
                modifier = Modifier.fillMaxSize(),
                onFocus = { _, _ -> },
            )
        }
        return true
    }

    val viewModel: TallySettingsPageViewModel = hiltViewModel()
    val current by viewModel.current.collectAsState()
    val signedIn = current
    if (signedIn == null) {
        TallySettingsBody(initialPreferences, screen, arrival, modifier)
        return true
    }
    val preferences by viewModel.userPreferences.collectAsState(UserPreferences(initialPreferences, null))
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerListState = rememberLazyListState()
    CompositionLocalProvider(LocalTallySettingsArrival provides arrival) {
        TallyNavDrawer(
            destination = Destination.Settings(screen),
            preferences = preferences,
            user = signedIn.user,
            server = signedIn.server,
            drawerState = drawerState,
            navDrawerListState = drawerListState,
            onClearBackdrop = {},
            modifier = modifier,
        )
    }
    return true
}

/** The page itself: upstream's list at the page margin, rows up to [ROW_MAX_WIDTH], the panel to its right. */
@Composable
private fun TallySettingsBody(
    initialPreferences: AppPreferences,
    screen: PreferenceScreenOption,
    arrival: TallySettingsArrival,
    modifier: Modifier,
) {
    val realDensity = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val preferencesViewModel: PreferencesViewModel = hiltViewModel()
    val preferences by preferencesViewModel.preferenceDataStore.data.collectAsState(initialPreferences)
    var focusedIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val themeTitle = stringResource(R.string.app_theme)
    val themeRowFocus = remember { if (arrival.focusThemeRow) TallyRowFocus(themeTitle) else null }
    if (themeRowFocus != null) {
        LaunchedEffect(themeRowFocus) {
            focusUntilShown(themeRowFocus.requester, { themeRowFocus.focused }, focusManager)
            arrival.focusThemeRow = false
        }
    }

    // the groups as the list shows them in the Tally look (About moved last: TallyExtraSettings)
    val context = LocalContext.current
    val shownGroups =
        remember(screen, context) {
            if (screen == PreferenceScreenOption.BASIC) {
                TallyExtraSettings.basicGroups(basicPreferences, context)
            } else {
                preferenceGroups(screen)
            }
        }
    val focused =
        focusedIndex?.let { (group, index) ->
            focusedPreference(shownGroups, preferences, group, index)
        }
    TallyScale {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(TallyColors.ground)
                    .padding(start = TallyDimens.marginHorizontal - LIST_INSET, end = TallyDimens.marginHorizontal),
        ) {
            val listWidth = min(ROW_MAX_WIDTH + LIST_INSET * 2, maxWidth - PANEL_GAP - PANEL_MIN_WIDTH + LIST_INSET)
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(listWidth).fillMaxHeight()) {
                    // Upstream's list draws its own rows at the Tally scale: it gets the real density back.
                    CompositionLocalProvider(
                        LocalDensity provides realDensity,
                        LocalTallyRowFocus provides themeRowFocus,
                    ) {
                        PreferencesContent(
                            initialPreferences = initialPreferences,
                            preferenceScreenOption = screen,
                            modifier = Modifier.fillMaxSize(),
                            onFocus = { group, index -> focusedIndex = group to index },
                        )
                    }
                }
                DescriptionPanel(
                    preference = focused,
                    preferences = preferences,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = PANEL_GAP - LIST_INSET, top = PANEL_TOP),
                )
            }
        }
    }
}

/**
 * Focus on arrival (the pattern of the sign-in screens' `requestUntilFocused`): wait a frame, then take focus again
 * if the row got it before it collected its focus interactions (it is then focused but drawn unfocused), and ask
 * until it has it.
 */
private suspend fun focusUntilShown(
    requester: FocusRequester,
    isFocused: () -> Boolean,
    focusManager: FocusManager,
) {
    delay(50)
    if (isFocused()) focusManager.clearFocus(force = true)
    repeat(40) {
        if (isFocused()) return
        requester.tryRequestFocus("tally-settings-theme-row")
        delay(50)
    }
}

/** The preference groups of a settings screen, as upstream's `PreferencesContent` picks them. */
internal fun preferenceGroups(screen: PreferenceScreenOption): List<PreferenceGroup<AppPreferences>> =
    when (screen) {
        PreferenceScreenOption.BASIC -> basicPreferences
        PreferenceScreenOption.ADVANCED -> advancedPreferences
        PreferenceScreenOption.EXO_PLAYER -> ExoPlayerPreferences
        PreferenceScreenOption.MPV -> MpvPreferences
        PreferenceScreenOption.SCREENSAVER -> screensaverPreferences
        PreferenceScreenOption.SKIP_SEGMENTS -> SkipSegmentPreferences
        PreferenceScreenOption.EXPERIMENTAL -> experimentalPreferences
    }

/**
 * The preference at upstream's (group, index) focus position: the group's own preferences, then the conditional
 * ones whose condition holds, exactly as the list lays them out.
 */
internal fun <P> focusedPreference(
    groups: List<PreferenceGroup<P>>,
    preferences: P,
    group: Int,
    index: Int,
): AppPreference<P, *>? {
    val found = groups.getOrNull(group) ?: return null
    val shown =
        found.preferences +
            found.conditionalPreferences
                .filter { it.condition.invoke(preferences) }
                .flatMap { it.preferences }
    return shown.getOrNull(index)
}

/**
 * Upstream's rows compute these summaries on the page from data the panel does not have (versions, cache sizes,
 * players, the signed-in user): the panel shows only their titles.
 */
private val PageSummarized: Set<AppPreference<AppPreferences, *>> =
    setOf(
        AppPreference.InstalledVersion,
        AppPreference.Update,
        AppPreference.ClearImageCache,
        AppPreference.ProtectProfilePreference,
        AppPreference.SeerrIntegration,
        AppPreference.ExternalPlayerApp,
        AppPreference.UserInterfaceLanguage,
    )

/** What upstream says about [preference]: its summary (or current choice) and the current choice's subtitle. */
@Suppress("UNCHECKED_CAST")
@Composable
private fun describe(
    preference: AppPreference<AppPreferences, *>,
    preferences: AppPreferences,
): List<String> {
    if (preference in PageSummarized) return emptyList()
    val context = LocalContext.current
    val pref = preference as AppPreference<AppPreferences, Any?>
    val value = pref.getter.invoke(preferences)
    val lines =
        when (pref) {
            is AppChoicePreference<*, *> -> {
                val choice = pref as AppChoicePreference<AppPreferences, Any?>
                val index = choice.valueToIndex.invoke(value)
                val values = stringArrayResource(choice.displayValues)
                val subtitles = choice.subtitles?.let { stringArrayResource(it) }
                listOf(
                    choice.summary?.let { stringResource(it) } ?: values.getOrNull(index),
                    subtitles?.getOrNull(index),
                )
            }

            is AppMultiChoicePreference<*, *> -> {
                listOf(pref.summary?.let { stringResource(it) } ?: pref.summary(context, value))
            }

            is AppSwitchPreference<*> -> {
                // The row already says ON or OFF: a summary that only restates it is left out, as on the row.
                listOf(pref.summary(context, value)?.takeIf { it !in switchRestatements() })
            }

            else -> {
                listOf(pref.summary(context, value))
            }
        }
    return lines.filterNotNull().filter { it.isNotBlank() }.distinct()
}

/**
 * The focused row's title (Sans Medium 20sp) and upstream's description of it (Sans 15sp); for the Application
 * Theme row, the themes as swatches with the current one marked.
 */
@Composable
private fun DescriptionPanel(
    preference: AppPreference<AppPreferences, *>?,
    preferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TallyDimens.hairline)
                .background(TallyColors.rule),
        )
        if (preference == null) return@Column
        Spacer(Modifier.height(14.dp))
        Text(text = stringResource(preference.title), style = panelTitleStyle, color = TallyColors.text)
        describe(preference, preferences).forEach { line ->
            Spacer(Modifier.height(8.dp))
            Text(text = line, style = panelBodyStyle, color = TallyColors.textSecondary)
        }
        if (preference == AppPreference.ThemeColors) {
            Spacer(Modifier.height(18.dp))
            ThemeSwatches(current = preferences.interfacePreferences.appThemeColors)
        }
    }
}

/** One square per theme: its ground with its accent in the middle; an accent square under the current one. */
@Composable
private fun ThemeSwatches(current: AppThemeColors) {
    val themes =
        remember {
            AppThemeColors.entries
                .filter { it != AppThemeColors.UNRECOGNIZED }
                .sortedBy { it.number }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
        themes.forEach { theme ->
            val scheme = remember(theme) { getThemeColors(theme).darkScheme }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(SWATCH_SIZE)
                            .background(scheme.background)
                            .border(TallyDimens.hairline, TallyColors.ruleStrong),
                ) {
                    Box(Modifier.size(SWATCH_SIZE / 2).background(scheme.primary))
                }
                if (theme == current) {
                    IndicatorSquare(color = TallyColors.accent, size = 6.dp)
                } else {
                    Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}
