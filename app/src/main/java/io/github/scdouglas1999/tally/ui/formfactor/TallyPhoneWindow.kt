package io.github.scdouglas1999.tally.ui.formfactor

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import java.util.Collections
import java.util.WeakHashMap

/** The activity-level set-up of a phone (seam W42 in `MainActivity.onCreate`). Nothing on a TV. */
object TallyPhoneWindow {
    private val phoneWindows: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    /** True when [context] belongs to an activity set up here as a phone window: the app itself, on a phone. */
    fun isPhoneWindow(context: Context): Boolean = context.findActivity()?.let { it in phoneWindows } == true

    /**
     * On a phone: edge to edge with light status and navigation bar icons over the dark app, the window left
     * unresized by the keyboard (the phone shell reads its insets instead), and portrait while browsing on a phone
     * (not on a tablet).
     */
    fun setUp(activity: ComponentActivity) {
        if (TallyFormFactor.of(activity) != TallyFormFactor.PHONE) return
        phoneWindows.add(activity)
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        @Suppress("DEPRECATION")
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        browsingOrientation(activity)?.let { activity.requestedOrientation = it }
    }
}
