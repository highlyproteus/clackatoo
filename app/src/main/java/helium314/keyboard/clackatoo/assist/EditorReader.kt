// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Reads text and selection from an editor in one call (never from the keyboard's own caches, which can be stale). */
object EditorReader {
    /**
     * Up to [maxChars] characters before and after the selection plus the selection itself, with the selection
     * positions; null when the editor does not give out its text.
     */
    fun snapshot(ic: InputConnection?, maxChars: Int): EditorSnapshot? {
        if (ic == null) return null
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = ic.getSurroundingText(maxChars, maxChars, 0)
            if (surrounding != null)
                return EditorSnapshot.of(surrounding.text, surrounding.selectionStart, surrounding.selectionEnd, surrounding.offset)
        }
        val request = ExtractedTextRequest().apply { hintMaxChars = 2 * maxChars + 1; flags = 0 }
        val extracted = ic.getExtractedText(request, 0) ?: return null
        if (extracted.partialStartOffset >= 0 || extracted.partialEndOffset >= 0) return null // a part of the text only
        return EditorSnapshot.of(extracted.text, extracted.selectionStart, extracted.selectionEnd, extracted.startOffset)
    }
}
