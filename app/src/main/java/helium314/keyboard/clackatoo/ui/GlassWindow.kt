// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getActivity
import java.util.function.Consumer

/**
 * Makes the settings window glass (or solid): with [glass] the wallpaper shows behind the window under a tinted
 * scrim, blurred too on Android 12+ while the phone allows blurring windows (not in battery saver, not on every
 * device); without it the window is an opaque Material 3 surface. The activity's theme only has to be translucent
 * and show the wallpaper (`platformActivityTheme.Settings`).
 */
@Composable
fun GlassWindowEffect(glass: Boolean) {
    val activity = LocalContext.current.getActivity() ?: return
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val blur = rememberCrossWindowBlurEnabled()
    val radius = if (glass && blur) GlassStyle.BLUR_RADIUS_DP.dpToPx(activity.resources) else 0
    DisposableEffect(glass, surface, radius) {
        val window = activity.window
        if (glass) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(GlassStyle.scrim(surface)))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(surface or (0xFF shl 24)))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(radius)
        onDispose { }
    }
}

/** Whether the phone blurs windows right now (Android 12+; the setting can change while the screen is open). */
@Composable
private fun rememberCrossWindowBlurEnabled(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val activity = LocalContext.current.getActivity() ?: return false
    val windowManager = activity.windowManager
    var enabled by remember { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}
