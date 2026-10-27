// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Background of a key in the Clackatoo style: a square with a small corner radius and, optionally, a thin
 * border. The fill comes from the tint list that [helium314.keyboard.latin.common.Colors] applies to every
 * key background (so the pressed state keeps working), the border is a separate colour that a tint can't express.
 *
 * @param borderColor border colour of a key at rest, ignored if [strokeWidthPx] is 0
 * @param pressedBorderColor border colour while the key is pressed
 */
class ClackatooKeyDrawable(
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float,
    @ColorInt private val borderColor: Int,
    @ColorInt private val pressedBorderColor: Int,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }
    private val rect = RectF()
    private var tint: ColorStateList? = null

    override fun isStateful() = true

    override fun onStateChange(stateSet: IntArray): Boolean {
        updatePaints(stateSet)
        invalidateSelf()
        return true
    }

    private fun updatePaints(stateSet: IntArray) {
        fillPaint.color = tint?.getColorForState(stateSet, tint!!.defaultColor) ?: 0xFFFFFFFF.toInt()
        strokePaint.color = if (stateSet.contains(android.R.attr.state_pressed)) pressedBorderColor else borderColor
    }

    override fun draw(canvas: Canvas) {
        updatePaints(state)
        rect.set(bounds)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)
        if (strokeWidthPx <= 0f) return
        // stroke is centered on the path, so inset by half its width to keep it inside the bounds
        val inset = strokeWidthPx / 2f
        rect.inset(inset, inset)
        val r = (cornerRadiusPx - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    // the fill colour is fully defined by the tint list, whatever the tint mode
    override fun setTintMode(tintMode: PorterDuff.Mode?) {}

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
