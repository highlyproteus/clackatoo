// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.semantics
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/**
 * A miniature keyboard drawn from [colors]: suggestion strip, four rows of keys with their letters, the space bar and the Enter key,
 * in the colours the keyboard really has (a translucent theme shows over a colourful stand-in for the app behind it).
 * The theme picker and the theme swatches of the home screen draw it.
 */
@Composable
internal fun MiniKeyboard(colors: Colors, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds().semantics { }) {
        val corner = size.width * 0.022f // 8dp on a 360dp keyboard
        val round = CornerRadius(corner)
        val keyboardBackground = colors.get(ColorType.MAIN_BACKGROUND)
        if (keyboardBackground ushr 24 < 255) {
            // a stand-in for what is behind a translucent keyboard
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFFFFC857), Color(0xFF2BB5C8), Color(0xFF6A4FD8)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = round)
            drawCircle(Color(0xFFFFFFFF), radius = size.height * 0.3f, center = Offset(size.width * 0.2f, size.height * 0.25f))
            drawCircle(Color(0xFF111111), radius = size.height * 0.22f, center = Offset(size.width * 0.8f, size.height * 0.6f))
        }
        drawRoundRect(Color(keyboardBackground), size = size, cornerRadius = round)

        val pad = size.width * 0.012f
        val gap = size.width * 0.012f
        val stripHeight = size.height * 0.15f
        val rows = 4
        val rowHeight = (size.height - stripHeight - 2 * pad - gap * rows) / rows
        val keyFill = Color(colors.get(ColorType.KEY_BACKGROUND))
        val functionFill = Color(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        val spaceFill = Color(colors.get(ColorType.SPACE_BAR_BACKGROUND))
        val actionFill = Color(colors.get(ColorType.ACTION_KEY_BACKGROUND))
        val border = Color(colors.keyBorder)
        val text = colors.get(ColorType.KEY_TEXT)
        val actionText = colors.get(ColorType.ACTION_KEY_ICON)
        val hairline = size.width * 0.0025f

        // suggestion strip: three words
        for (i in 0 until 3) {
            val w = size.width * 0.18f
            val x = size.width * (0.17f + i * 0.28f)
            drawRoundRect(Color(colors.get(ColorType.SUGGESTED_WORD)).copy(alpha = if (i == 1) 0.9f else 0.55f), Offset(x, stripHeight * 0.4f), Size(w, stripHeight * 0.22f), CornerRadius(stripHeight * 0.11f))
        }
        drawRoundRect(Color(colors.get(ColorType.TOOL_BAR_KEY)).copy(alpha = 0.7f), Offset(size.width * 0.04f, stripHeight * 0.32f), Size(stripHeight * 0.4f, stripHeight * 0.4f), CornerRadius(corner / 2))

        fun key(x: Float, y: Float, w: Float, fill: Color, label: String?, labelColor: Int) {
            if (colors.hasKeyBorders) {
                drawRoundRect(fill, Offset(x, y), Size(w, rowHeight), round)
                drawRoundRect(border, Offset(x + hairline / 2, y + hairline / 2), Size(w - hairline, rowHeight - hairline), round, style = Stroke(hairline))
            }
            if (label != null) drawLabel(label, x + w / 2, y + rowHeight / 2, rowHeight * 0.5f, labelColor)
        }

        val keyWidth = (size.width - 2 * pad - 9 * gap) / 10
        var y = stripHeight + pad
        val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for ((r, letters) in letterRows.withIndex()) {
            val rowWidth = letters.length * keyWidth + (letters.length - 1) * gap
            val functionWidth = if (r == 2) (size.width - 2 * pad - rowWidth - 2 * gap) / 2 else 0f
            var x = pad + (size.width - 2 * pad - rowWidth - 2 * functionWidth - (if (r == 2) 2 * gap else 0f)) / 2 + (if (r == 2) 0f else 0f)
            if (r == 2) {
                key(pad, y, functionWidth, functionFill, "⇧", text)
                x = pad + functionWidth + gap
            }
            for (letter in letters) {
                key(x, y, keyWidth, keyFill, letter.toString(), text)
                x += keyWidth + gap
            }
            if (r == 2) key(size.width - pad - functionWidth, y, functionWidth, functionFill, "⌫", text)
            y += rowHeight + gap
        }
        // bottom row: ?123, comma, space, period, Enter
        val symbolWidth = keyWidth * 1.4f
        val enterWidth = keyWidth * 1.8f
        var x = pad
        key(x, y, symbolWidth, functionFill, "?123", text); x += symbolWidth + gap
        key(x, y, keyWidth, functionFill, ",", text); x += keyWidth + gap
        val spaceWidth = size.width - 2 * pad - symbolWidth - enterWidth - 2 * keyWidth - 4 * gap
        key(x, y, spaceWidth, spaceFill, null, text); x += spaceWidth + gap
        key(x, y, keyWidth, functionFill, ".", text); x += keyWidth + gap
        key(x, y, enterWidth, actionFill, "⏎", actionText)
    }
}

private fun DrawScope.drawLabel(label: String, cx: Float, cy: Float, textSize: Float, color: Int) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.nativeCanvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
    }
}
