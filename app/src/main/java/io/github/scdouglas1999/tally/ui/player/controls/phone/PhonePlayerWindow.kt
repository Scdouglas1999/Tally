package io.github.scdouglas1999.tally.ui.player.controls.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor

/**
 * The player's window on a phone (seam W37 at the top of `PlaybackPage`, and the post-play page): landscape (either
 * way up, by the sensor) and full screen with the status and navigation bars hidden (a swipe from an edge shows them
 * for a moment) while it is shown; the orientation and the bars in effect before come back when it leaves. Nothing on
 * a TV.
 *
 * Held by a count, and let go a moment late: when the player hands over to the post-play page (both landscape) the
 * phone does not turn to portrait and back in between.
 */
@Composable
fun PhonePlayerWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity == null || TallyFormFactor.of(context) != TallyFormFactor.PHONE) {
            return@DisposableEffect onDispose { }
        }
        PlayerWindowHold.acquire(activity)
        onDispose { PlayerWindowHold.release(activity) }
    }
}

private object PlayerWindowHold {
    /** How long the window stays as it is after the last holder leaves, in case another takes it straight away. */
    private const val RELEASE_DELAY_MS = 350L

    private val handler = Handler(Looper.getMainLooper())
    private var holders = 0
    private var previousOrientation: Int? = null
    private var pendingRelease: Runnable? = null

    fun acquire(activity: Activity) {
        pendingRelease?.let { handler.removeCallbacks(it) }
        pendingRelease = null
        if (holders == 0 && previousOrientation == null) {
            previousOrientation = activity.requestedOrientation
        }
        holders++
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insetsController(activity).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun release(activity: Activity) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        val restore =
            Runnable {
                pendingRelease = null
                if (holders > 0) return@Runnable
                insetsController(activity).show(WindowInsetsCompat.Type.systemBars())
                previousOrientation?.let { activity.requestedOrientation = it }
                previousOrientation = null
            }
        pendingRelease = restore
        handler.postDelayed(restore, RELEASE_DELAY_MS)
    }

    private fun insetsController(activity: Activity): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
