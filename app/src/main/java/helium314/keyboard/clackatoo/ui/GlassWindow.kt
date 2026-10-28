// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
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
fun GlassWindowEffect(glass: Boolean, blur: Boolean) {
    val activity = LocalContext.current.getActivity() ?: return
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val radius = if (glass && blur) GlassStyle.BLUR_RADIUS_DP.dpToPx(activity.resources) else 0
    DisposableEffect(glass, blur, surface, radius) {
        val window = activity.window
        if (glass) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(GlassStyle.scrim(surface, blur)))
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
fun rememberCrossWindowBlurEnabled(): Boolean {
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

/** How many dialogs are open; the settings blur their content behind a frosted dialog (see [GlassDialogEffect]). */
val LocalOpenDialogs = staticCompositionLocalOf<MutableIntState?> { null }

/**
 * Call inside a `Dialog`: with glass on, the screen behind the dialog is dimmed less than the platform's 0.6, so the
 * wallpaper (behind the glass settings window) stays visible around the dialog, and the settings content behind it is
 * blurred ([Modifier.dialogBackdrop]) so that the frosted dialog can show it softly. The dialog window has no blur of
 * its own: `FLAG_BLUR_BEHIND` on top of the window blur hung the SurfaceFlinger render engine on the emulator.
 */
@Composable
fun GlassDialogEffect() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val glass = LocalGlass.current
    val open = LocalOpenDialogs.current
    DisposableEffect(window, glass, open) {
        if (glass) window?.setDimAmount(GlassStyle.DIALOG_DIM)
        open?.let { it.intValue++ }
        onDispose { open?.let { it.intValue-- } }
    }
}

/** The settings content, blurred while a frosted dialog is open on a phone that blurs; the content is not blurred otherwise. */
@Composable
fun Modifier.dialogBackdrop(): Modifier {
    val blurred = (LocalOpenDialogs.current?.intValue ?: 0) > 0 && LocalGlass.current && LocalGlassBlur.current &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return if (blurred) blur(GlassStyle.DIALOG_BACKDROP_BLUR_DP.dp) else this
}
