package io.github.scdouglas1999.tally.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import com.github.damontecres.wholphin.R

/**
 * Tally design tokens: flat, near-black, hairline rules, square corners, one accent.
 * Same palette as the Tally web UI, TV-sized. See TALLY.md.
 */
object TallyColors {
    /** Screen background. */
    val ground = Color(0xFF0E0F0E)

    /** Focused card ground. */
    val groundRaised = Color(0xFF1B1C1A)

    /** Video / monitor faces. */
    val screen = Color(0xFF050505)

    /** Channel label bars. */
    val labelBar = Color(0xFF000000)

    /** Structural hairlines (1dp). */
    val rule = Color(0xFF2A2C2A)

    /** Control / card borders (1dp), idle indicator squares. */
    val ruleStrong = Color(0xFF3A3D38)

    /** Primary text. */
    val text = Color(0xFFE3E5DE)

    /** Secondary text. */
    val textSecondary = Color(0xFFA9ADA3)

    /** Captions, labels. */
    val muted = Color(0xFF8B9084)

    /** Focus frame, "now", active tab. Text on accent is [onAccent]. */
    val accent = Color(0xFFFFB000)

    /** Text/icons drawn on top of [accent]. */
    val onAccent = Color(0xFF0E0F0E)

    /** LIVE indicators (and failure). */
    val live = Color(0xFFFF3B30)

    /** Small live-red text. */
    val liveText = Color(0xFFFF6A61)
}

/**
 * IBM Plex Sans for reading, IBM Plex Mono for clocks, scores, and labels.
 * Mono labels are rendered UPPERCASE with letter spacing.
 */
object TallyType {
    val Sans =
        FontFamily(
            Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
            Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
            Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
            Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
        )

    val Mono =
        FontFamily(
            Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
            Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
            Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
        )

    /** Mono Medium 14sp, used UPPERCASE for league/channel/kicker labels. */
    val label =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 2.sp,
        )

    /** Mono Medium 16sp, used UPPERCASE for tab and row headers. */
    val labelLarge =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            letterSpacing = 2.sp,
        )

    val clock =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )

    val score =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 30.sp,
        )

    val scoreHero =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 84.sp,
        )

    val teamCard =
        TextStyle(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )

    val teamHero =
        TextStyle(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 44.sp,
        )

    val body =
        TextStyle(
            fontFamily = Sans,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            lineHeight = 28.sp,
        )

    val situation =
        TextStyle(
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
        )

    val hint =
        TextStyle(
            fontFamily = Sans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
        )
}

object TallyDimens {
    /** Safe margins: 48dp left/right, 27dp top/bottom. */
    val marginHorizontal = 48.dp
    val marginVertical = 27.dp

    val cardWidth = 300.dp
    val cardHeight = 176.dp
    val cardGap = 18.dp

    /** Focused element border width. */
    val focusBorder = 3.dp

    /** Structural hairline width. */
    val hairline = 1.dp

    val topBarHeight = 78.dp
    val heroHeight = 312.dp
}

/**
 * Full-size surface on [TallyColors.ground] that sets [TallyColors.text] as the content color and
 * [TallyType.body] as the default text style. Root of every Tally screen.
 */
@Composable
fun TallySurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .background(TallyColors.ground),
                    content = content,
                )
            }
        }
    }
}

/**
 * Every Tally dimension (dp and sp) is authored against a 1200 x 675 canvas: the 1920 x 1080 mockups at
 * 0.625, i.e. 20% larger than drawn, for reading from a sofa. A TV is 960 x 540 dp, so Tally UI is laid out
 * at 0.8 of the real density. Anything drawn outside a [TallySurface] (the overlays on top of the upstream
 * player) must be wrapped in this too.
 */
@Composable
fun TallyScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = remember(density) { Density(density.density * JTV_SCALE, density.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

private const val JTV_SCALE = 0.8f
