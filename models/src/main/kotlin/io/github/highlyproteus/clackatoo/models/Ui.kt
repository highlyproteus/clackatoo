// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The colours of this app's screens, the Material 3 roles the keyboard's settings use: the phone's role colours on
 * Android 14 and later, the nearest system tones on Android 12 and 13, a fixed table below that.
 */
internal class Palette(
    val surface: Int,
    val panel: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
) {
    companion object {
        /** The palette of the phone right now, in the phone's dark or light theme. */
        fun of(context: Context): Palette {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return when {
                Build.VERSION.SDK_INT >= 34 -> fromRoles(context, dark)
                Build.VERSION.SDK_INT >= 31 -> fromTones(context, dark)
                else -> fallback(dark)
            }
        }

        /** The keyboard's fallback table plus `onPrimary`; the light primary is darker so that it reads on [panel]. Plain JVM, for tests. */
        fun fallback(dark: Boolean) = if (dark) Palette(
            surface = 0xFF0E0F11.toInt(),
            panel = 0xFF1E2024.toInt(),
            onSurface = 0xFFECEDEF.toInt(),
            onSurfaceVariant = 0xFFA3A7AE.toInt(),
            primary = 0xFF9DB8F5.toInt(),
            onPrimary = 0xFF0E0F11.toInt(),
        ) else Palette(
            surface = 0xFFF5F6F8.toInt(),
            panel = 0xFFE6E8EC.toInt(),
            onSurface = 0xFF15171A.toInt(),
            onSurfaceVariant = 0xFF5A5F67.toInt(),
            primary = 0xFF315AB8.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
        )

        @TargetApi(34)
        private fun fromRoles(context: Context, dark: Boolean) = if (dark) Palette(
            surface = context.getColor(android.R.color.system_surface_dark),
            panel = context.getColor(android.R.color.system_surface_container_high_dark),
            onSurface = context.getColor(android.R.color.system_on_surface_dark),
            onSurfaceVariant = context.getColor(android.R.color.system_on_surface_variant_dark),
            primary = context.getColor(android.R.color.system_primary_dark),
            onPrimary = context.getColor(android.R.color.system_on_primary_dark),
        ) else Palette(
            surface = context.getColor(android.R.color.system_surface_light),
            panel = context.getColor(android.R.color.system_surface_container_high_light),
            onSurface = context.getColor(android.R.color.system_on_surface_light),
            onSurfaceVariant = context.getColor(android.R.color.system_on_surface_variant_light),
            primary = context.getColor(android.R.color.system_primary_light),
            onPrimary = context.getColor(android.R.color.system_on_primary_light),
        )

        /** The tones the keyboard's `ClackatooPalette.fromTones` uses for the same roles. */
        @TargetApi(31)
        private fun fromTones(context: Context, dark: Boolean) = if (dark) Palette(
            surface = context.getColor(android.R.color.system_neutral1_900),
            panel = context.getColor(android.R.color.system_neutral1_800),
            onSurface = context.getColor(android.R.color.system_neutral1_100),
            onSurfaceVariant = context.getColor(android.R.color.system_neutral2_200),
            primary = context.getColor(android.R.color.system_accent1_200),
            onPrimary = context.getColor(android.R.color.system_accent1_800),
        ) else Palette(
            surface = context.getColor(android.R.color.system_neutral1_10),
            panel = context.getColor(android.R.color.system_neutral1_50),
            onSurface = context.getColor(android.R.color.system_neutral1_900),
            onSurfaceVariant = context.getColor(android.R.color.system_neutral2_700),
            primary = context.getColor(android.R.color.system_accent1_600),
            onPrimary = context.getColor(android.R.color.system_accent1_0),
        )
    }
}

/** The building blocks of this app's screens, in the look of the keyboard's settings. Plain framework views. */
internal class Ui(private val activity: Activity) {
    val palette = Palette.of(activity)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** The scrolling page around [root]: 16dp padding plus the system bars, on the `surface` colour, as is the action bar. */
    fun page(root: LinearLayout): ScrollView {
        activity.window.setBackgroundDrawable(ColorDrawable(palette.surface))
        activity.actionBar?.setBackgroundDrawable(ColorDrawable(palette.surface))
        activity.actionBar?.elevation = 0f
        return ScrollView(activity).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            clipToPadding = false
            // edge-to-edge: keep the content out of the system bars
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
                insets
            }
        }
    }

    fun body(text: CharSequence, sp: Float = 14f) = textView(text, sp, palette.onSurfaceVariant)

    fun header(text: CharSequence) = textView(text, 14f, palette.onSurface).apply {
        typeface = medium
        setPadding(0, dp(24), 0, dp(8))
    }

    /** A 12dp card on the `panel` colour; its children are clipped to the rounded corners. */
    fun panel() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(palette.panel)
            cornerRadius = dp(12).toFloat()
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    /** A row of a [panel]: a title, an optional second line, and a ripple if it can be tapped. */
    fun row(title: CharSequence, subtitle: CharSequence?, onClick: (() -> Unit)?) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        if (onClick != null) clickable(this, RippleDrawable(ripple(palette.onSurface), null, ColorDrawable(palette.onSurface)), onClick)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(textView(title, 16f, palette.onSurface))
        if (subtitle != null) addView(textView(subtitle, 14f, palette.onSurfaceVariant))
    }

    /** A `primary` pill 40dp high inside a 48dp touch target. */
    fun filledButton(text: CharSequence, onClick: () -> Unit) = textView(text, 14f, palette.onPrimary).apply {
        typeface = medium
        gravity = Gravity.CENTER
        minHeight = dp(48)
        val pill = GradientDrawable().apply {
            setColor(palette.primary)
            cornerRadius = dp(20).toFloat()
        }
        clickable(this, RippleDrawable(ripple(palette.onPrimary), InsetDrawable(pill, 0, dp(4), 0, dp(4)), null), onClick)
        setPadding(dp(24), dp(4), dp(24), dp(4))
    }

    fun textButton(text: CharSequence, onClick: () -> Unit) = textView(text, 14f, palette.primary).apply {
        typeface = medium
        gravity = Gravity.CENTER
        minHeight = dp(48)
        val mask = GradientDrawable().apply {
            setColor(palette.primary)
            cornerRadius = dp(20).toFloat()
        }
        clickable(this, RippleDrawable(ripple(palette.primary), null, InsetDrawable(mask, 0, dp(4), 0, dp(4))), onClick)
        setPadding(dp(12), 0, dp(12), 0)
    }

    fun progress(permille: Int) = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = permille
        progressTintList = ColorStateList.valueOf(palette.primary)
    }

    private fun textView(text: CharSequence, sp: Float, color: Int) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    /** [color] at 12%, the pressed overlay of Material 3. */
    private fun ripple(color: Int) = ColorStateList.valueOf((color and 0x00FFFFFF) or ((0.12f * 255).roundToInt() shl 24))

    /** Makes [view] tappable with [background] and has screen readers announce it as a button, as the stock buttons were. */
    private fun clickable(view: View, background: Drawable, onClick: () -> Unit) {
        view.background = background
        view.isFocusable = true
        view.setOnClickListener { onClick() }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }
}
