package io.github.scdouglas1999.tally.ui.formfactor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Below this smallest width a phone browses in portrait; at or above it (a tablet) it rotates freely. */
private const val TABLET_SMALLEST_WIDTH_DP = 600

/**
 * The orientation a phone browses in: portrait on phones, unlocked on tablets, left as it is on TVs. Applied by
 * [TallyPhoneWindow.setUp] and restored by [LandscapeWhileShown].
 */
internal fun browsingOrientation(activity: Activity): Int? {
    if (TallyFormFactor.of(activity) != TallyFormFactor.PHONE) return null
    return if (activity.resources.configuration.smallestScreenWidthDp < TABLET_SMALLEST_WIDTH_DP) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

/**
 * Sensor landscape while this is in the composition (the video player on a phone); the orientation in effect
 * before it came in is restored when it leaves. Does nothing on a TV.
 */
@Composable
fun LandscapeWhileShown() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity == null || TallyFormFactor.of(context) != TallyFormFactor.PHONE) {
            return@DisposableEffect onDispose { }
        }
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = previous }
    }
}

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
