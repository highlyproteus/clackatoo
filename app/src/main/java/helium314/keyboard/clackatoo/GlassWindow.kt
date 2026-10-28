// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.Log
import java.util.WeakHashMap

/**
 * What the glass theme needs from windows ([Colors.translucentWindow], [Colors.windowBlurRadiusDp]).
 *
 * The keyboard window is translucent. Blurring what is behind it is a separate window: the IME window is as tall as the
 * screen (popups, key previews and the touchable region need that), and `Window.setBackgroundBlurRadius` blurs a window's whole
 * frame, so on the IME window it blurs the app above the keyboard too (tried: `View.clipBounds` does not limit it). So the blur
 * is the background of a small sub-window of the keyboard window, the size of the visible keyboard and drawn below it
 * (`TYPE_APPLICATION_MEDIA`, which is how a PopupWindow attaches to the keyboard window too).
 */
object GlassWindow {
    private const val TAG = "GlassWindow"
    /** The background the window had before the first glass theme, so that other themes get it back. */
    private val originalBackgrounds = WeakHashMap<Window, Drawable?>()
    private var blurDialog: Dialog? = null

    /**
     * True if the system blurs windows now: Android 12 and later, and not switched off by the device (battery saver,
     * a developer option, or a device that cannot do it).
     */
    @JvmStatic
    fun blurAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return windowManager.isCrossWindowBlurEnabled
    }

    /** Makes [window] translucent for [colors] (the glass theme) or puts it back as it was (every other theme). Cheap enough to call whenever the keyboard is shown. */
    @JvmStatic
    fun apply(window: Window?, colors: Colors) {
        window ?: return
        if (colors.translucentWindow) {
            if (!originalBackgrounds.containsKey(window)) originalBackgrounds[window] = window.decorView.background
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else if (originalBackgrounds.containsKey(window)) {
            window.setBackgroundDrawable(originalBackgrounds.remove(window))
        }
    }

    /**
     * Shows, moves or hides the blur behind the keyboard. [topY] is the top of the visible keyboard in the keyboard window's
     * coordinates, null if no keyboard is visible or the blur is not wanted (a floating keyboard).
     */
    @JvmStatic
    fun updateBlur(service: InputMethodService, colors: Colors, topY: Int?) {
        val imeWindow = service.window?.window
        val decor = imeWindow?.decorView
        val token = decor?.windowToken
        val height = if (decor != null && topY != null) decor.height - topY.coerceAtLeast(0) else 0
        val wanted = colors.translucentWindow && colors.windowBlurRadiusDp > 0 && token != null && height > 0 && blurAvailable(service)
        if (!wanted) {
            hideBlur()
            return
        }
        val radius = (colors.windowBlurRadiusDp * service.resources.displayMetrics.density).toInt()
        try {
            val dialog = blurDialog ?: createBlurDialog(service, token!!).also { blurDialog = it }
            val window = dialog.window ?: return
            val params = window.attributes
            if (params.height != height || params.token != token) {
                params.height = height
                params.token = token
                window.attributes = params
            }
            // calling this every time is cheap: the window only changes when the radius does
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(radius)
            if (!dialog.isShowing) dialog.show()
        } catch (e: RuntimeException) {
            // a blur that cannot be shown must not break typing: the keyboard stays translucent, only without blur
            Log.w(TAG, "no blur behind the keyboard", e)
            hideBlur()
        }
    }

    /** Removes the blur, as when the keyboard is hidden. */
    @JvmStatic
    fun hideBlur() {
        val dialog = blurDialog ?: return
        blurDialog = null
        try {
            dialog.dismiss()
        } catch (_: RuntimeException) {
            // the window is already gone
        }
    }

    private fun createBlurDialog(service: InputMethodService, token: android.os.IBinder): Dialog {
        val dialog = Dialog(service, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(View(service))
        dialog.setCancelable(false)
        val window = dialog.window!!
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val params = window.attributes
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA
        params.token = token
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.format = android.graphics.PixelFormat.TRANSLUCENT
        params.windowAnimations = 0
        params.dimAmount = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = params
        return dialog
    }
}
