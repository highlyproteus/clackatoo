// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Fields whose content must never be read by the grammar check. */
object SensitiveField {
    /** EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING (API 26), the "private" / incognito flag. */
    private const val NO_PERSONALIZED_LEARNING = 0x1000000

    private val passwordVariations = mapOf(
        InputType.TYPE_CLASS_TEXT to listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ),
        InputType.TYPE_CLASS_NUMBER to listOf(InputType.TYPE_NUMBER_VARIATION_PASSWORD),
    )

    fun isSensitive(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and NO_PERSONALIZED_LEARNING != 0) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return passwordVariations[inputType and InputType.TYPE_MASK_CLASS]?.contains(variation) == true
    }

    fun isSensitive(info: EditorInfo?): Boolean =
        info == null || isSensitive(info.inputType, info.imeOptions)
}

/** What a field accepts. */
object FieldKind {
    /** Whether the field takes line breaks and tabs: only multi-line text fields do. */
    fun acceptsLineBreaks(inputType: Int) =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
}

/** Where dictated text goes. */
object Dictation {
    private const val NO_SPACE_BEFORE = ".,;:!?)]}%…»”’"

    /** [text] with a leading space when it would otherwise stick to the word before the cursor. */
    fun prepareInsertion(textBeforeCursor: CharSequence?, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val last = textBeforeCursor?.lastOrNull() ?: return trimmed
        if (last.isWhitespace()) return trimmed
        if (trimmed[0] in NO_SPACE_BEFORE) return trimmed
        if (last in "([{¿¡“‘«\"'") return trimmed
        return " $trimmed"
    }
}

/**
 * What an editor held at one moment, read in a single call so the text and the selection belong together.
 * [selectionStart] <= [selectionEnd] are positions in [text]; [offset] is where [text] starts in the field, or -1 when unknown.
 */
data class EditorSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int, val offset: Int = -1) {
    val hasSelection get() = selectionStart != selectionEnd
    val selected get() = text.substring(selectionStart, selectionEnd)

    /** Same text, same selection and, when both know it, the same place in the field. */
    fun sameAs(other: EditorSnapshot) =
        text == other.text && selectionStart == other.selectionStart && selectionEnd == other.selectionEnd &&
            (offset < 0 || other.offset < 0 || offset == other.offset)

    companion object {
        /** Null when the positions do not fit the text (an editor that reports nonsense is not read). */
        fun of(text: CharSequence?, selectionStart: Int, selectionEnd: Int, offset: Int = -1): EditorSnapshot? {
            if (text == null) return null
            val start = minOf(selectionStart, selectionEnd)
            val end = maxOf(selectionStart, selectionEnd)
            if (start < 0 || end > text.length) return null
            return EditorSnapshot(text.toString(), start, end, offset)
        }
    }
}

/**
 * The text the grammar check works on: the selection if there is one, else the whole field (only when nothing is
 * selected). It remembers the [snapshot] it was read from, and a correction is only applied to an editor that
 * still holds exactly that.
 */
sealed interface CheckTarget {
    val text: String
    val snapshot: EditorSnapshot

    data class Selection(override val snapshot: EditorSnapshot) : CheckTarget {
        override val text get() = snapshot.selected
    }

    /** The cursor is at one place, nothing is selected, and [text] is everything in the field. */
    data class Field(override val snapshot: EditorSnapshot) : CheckTarget {
        override val text get() = snapshot.text
    }

    companion object {
        const val MAX_CHARS = 8000

        /**
         * [snapshot] null means the editor could not be read at all: never guessed, never treated as empty.
         *
         * [expectedSelectionStart] and [expectedSelectionEnd] are the selection the keyboard itself was told about
         * (onUpdateSelection). An editor that does not say where its text starts (offset -1: Android's default
         * getSurroundingText, built from three separate calls that can each be short or empty) is only trusted when
         * its selection is the same as the keyboard's: then the text before the cursor is exactly as long as the
         * cursor position, so it starts at the beginning of the field, and a selection it reports is the real one.
         */
        fun from(snapshot: EditorSnapshot?, expectedSelectionStart: Int, expectedSelectionEnd: Int): Result {
            if (snapshot == null) return Result.Unreadable
            if (snapshot.offset < 0 &&
                (snapshot.selectionStart != minOf(expectedSelectionStart, expectedSelectionEnd) ||
                    snapshot.selectionEnd != maxOf(expectedSelectionStart, expectedSelectionEnd))
            ) return Result.Unreadable
            if (snapshot.hasSelection) {
                if (snapshot.selectionEnd - snapshot.selectionStart > MAX_CHARS) return Result.TooLong
                return if (snapshot.selected.isBlank()) Result.Empty else Result.Found(Selection(snapshot))
            }
            // the text read must start at the start of the field, or replacing "everything" would replace a part
            if (snapshot.offset > 0) return Result.Unreadable
            // a side that is as long as the limit may be cut off, and then we would replace only part of the field
            if (snapshot.selectionStart >= MAX_CHARS || snapshot.text.length - snapshot.selectionEnd >= MAX_CHARS) return Result.TooLong
            return if (snapshot.text.isBlank()) Result.Empty else Result.Found(Field(snapshot))
        }
    }

    sealed interface Result {
        data class Found(val target: CheckTarget) : Result
        data object Empty : Result
        data object TooLong : Result
        /** The editor does not give out its text (or reported something inconsistent). */
        data object Unreadable : Result
    }
}

/** How to apply a correction to the editor. */
sealed interface Replacement {
    val text: String

    /** Replace the current selection. */
    data class OverSelection(override val text: String) : Replacement
    /** Replace the whole field: select its first [length] characters (all of it), then insert [text]. */
    data class OverField(val length: Int, override val text: String) : Replacement
}

object TextReplacement {
    /**
     * The replacement to apply [corrected] to the editor as it is now ([current], read together with its selection
     * right before replacing), or null when anything differs from what was checked ([original]): the text, the
     * selection or the cursor. Nothing but the text that was read and shown may ever be replaced.
     */
    fun plan(original: CheckTarget, current: CheckTarget?, corrected: String): Replacement? {
        if (current == null || !original.snapshot.sameAs(current.snapshot)) return null
        return when {
            original is CheckTarget.Selection && current is CheckTarget.Selection -> Replacement.OverSelection(corrected)
            original is CheckTarget.Field && current is CheckTarget.Field ->
                Replacement.OverField(current.text.length, corrected)
            else -> null
        }
    }
}
