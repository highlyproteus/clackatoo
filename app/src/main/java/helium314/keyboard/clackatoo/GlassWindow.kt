// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    /** Whether the system's navigation icons were dark (light navigation bar) before the first glass theme. */
    private val originalLightNavigation = WeakHashMap<Window, Boolean>()
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
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (colors.translucentWindow) {
            if (!originalBackgrounds.containsKey(window)) {
                originalBackgrounds[window] = window.decorView.background
                originalLightNavigation[window] = controller.isAppearanceLightNavigationBars
            }
            // clear, except under the system's navigation icons (back, keyboard switcher), which get the frosted backing too
            if ((window.decorView.background as? NavigationBacking)?.color != colors.backing) window.setBackgroundDrawable(NavigationBacking(colors.backing))
            // the icons are dark on a light backing and light on a dark one
            controller.isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(ColorUtils.compositeColors(colors.backing, Color.GRAY)) > 0.5
        } else if (originalBackgrounds.containsKey(window)) {
            window.setBackgroundDrawable(originalBackgrounds.remove(window))
            originalLightNavigation.remove(window)?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }

    /** The bottom [height] pixels in [color], the rest clear: the window background of the glass keyboard. */
    private class NavigationBacking(@ColorInt val color: Int) : Drawable() {
        private val paint = Paint().apply { color = this@NavigationBacking.color }
        var height = 0
            set(value) {
                if (field == value) return
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            if (height > 0) canvas.drawRect(bounds.left.toFloat(), (bounds.bottom - height).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Tells the glass window background how high the system's navigation bar is now. */
    private fun updateNavigationBacking(window: Window) {
        val backing = window.decorView.background as? NavigationBacking ?: return
        backing.height = ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    }

    /**
     * Shows, moves or hides the blur behind the keyboard. [topY] is the top of the visible keyboard in the keyboard window's
     * coordinates, null if no keyboard is visible or the blur is not wanted (a floating keyboard).
     */
    @JvmStatic
    fun updateBlur(service: InputMethodService, colors: Colors, topY: Int?) {
        val imeWindow = service.window?.window
        imeWindow?.let { updateNavigationBacking(it) }
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
        // nothing to read or touch: the window is only a blurred background
        dialog.setContentView(View(service).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
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
