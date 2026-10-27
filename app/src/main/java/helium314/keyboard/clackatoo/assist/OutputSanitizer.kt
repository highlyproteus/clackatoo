// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/**
 * Cleans what the models produce before it is shown or inserted. Model output is untrusted: a transcript or a
 * "correction" could contain a line break that a terminal runs as Enter, control characters, characters that
 * reorder or hide text (bidirectional overrides, zero-width and other invisible characters, tag characters), or just
 * be far longer than anything that was said.
 */
object OutputSanitizer {
    /** Longest text that [samples] samples of 16 kHz speech can plausibly contain: about 40 characters per second plus some slack. */
    fun maxCharsForClip(samples: Int): Int {
        val seconds = samples.toDouble() / MlProtocol.SAMPLE_RATE
        return (64 + 40 * seconds).toInt().coerceAtMost(8000)
    }

    /**
     * [text] without control characters (C0, DEL, C1), without every Unicode format character (category Cf: bidirectional
     * controls, zero-width characters, soft hyphen, invisible operators, interlinear annotation, tag characters, ...),
     * every variation selector except U+FE0F, and the other invisible characters in [isOtherInvisible], cut to
     * [maxChars] characters at most. A character from those sets stays only if it also occurs in [original], the
     * text the user wrote that this text is a correction of: a corrected chunk must not lose the user's own ZWNJ, ZWJ
     * or bidirectional marks, and a model must not add any. Line breaks and tabs stay only if [multiLine] (the field
     * takes them); otherwise they become one space. Line and paragraph separators count as line breaks. A zero-width
     * joiner that the user did not write stays only between two symbols, where it is part of an emoji sequence; the
     * emoji presentation selector U+FE0F always stays.
     */
    fun clean(text: String, multiLine: Boolean, maxChars: Int = Int.MAX_VALUE, original: String = ""): String {
        val limit = maxChars.coerceAtLeast(0)
        val users = invisibleIn(original)
        val out = StringBuilder(minOf(text.length.toLong(), limit.toLong() + 2).toInt())
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            when {
                cp == '\r'.code -> {
                    // CR LF is one line break
                    if (next >= text.length || text[next] != '\n') lineBreak(out, multiLine)
                }
                cp == '\n'.code || cp == 0x85 || cp == 0x2028 || cp == 0x2029 -> lineBreak(out, multiLine)
                cp == '\t'.code -> if (multiLine) out.append('\t') else space(out)
                cp < 0x20 || cp in 0x7F..0x9F -> Unit
                cp == 0xFE0F -> out.appendCodePoint(cp)
                isInvisible(cp) -> {
                    if (cp in users || (cp == 0x200D && joinsSymbols(out, text, next))) out.appendCodePoint(cp)
                }
                cp in 0xD800..0xDFFF || cp == 0xFFFE || cp == 0xFFFF -> Unit // lone surrogate, non-character
                else -> out.appendCodePoint(cp)
            }
            i = next
            if (out.length.toLong() >= limit.toLong() + 2) break
        }
        var end = minOf(out.length, limit)
        if (end < out.length && end > 0 && Character.isHighSurrogate(out[end - 1])) end-- // do not cut a surrogate pair in two
        return out.substring(0, end)
    }

    /** The invisible characters (see [isInvisible]) that occur in [text]. */
    private fun invisibleIn(text: String): Set<Int> {
        if (text.isEmpty()) return emptySet()
        val found = HashSet<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isInvisible(cp)) found.add(cp)
            i += Character.charCount(cp)
        }
        return found
    }

    /**
     * Format characters (Cf), variation selectors (U+FE00..FE0F, U+E0100..E01EF, Mongolian U+180B..180D and U+180F), and
     * invisible letters and marks that draw nothing. Lone surrogates and non-characters are not here: they never stay.
     */
    private fun isInvisible(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() || isVariationSelector(cp) || isOtherInvisible(cp)

    private fun isVariationSelector(cp: Int) =
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x180B..0x180D || cp == 0x180F

    // combining grapheme joiner, Hangul choseong and jungseong fillers, Hangul filler, halfwidth Hangul filler
    // (and the whole tag block, of which only the assigned characters are Cf)
    private fun isOtherInvisible(cp: Int) =
        cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp in 0xE0000..0xE007F

    private fun lineBreak(out: StringBuilder, multiLine: Boolean) {
        if (multiLine) out.append('\n') else space(out)
    }

    private fun space(out: StringBuilder) {
        if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' ')
    }

    private fun joinsSymbols(out: CharSequence, text: String, next: Int): Boolean {
        if (out.isEmpty() || next >= text.length) return false
        return isSymbolLike(Character.codePointBefore(out, out.length)) && isSymbolLike(text.codePointAt(next))
    }

    private fun isSymbolLike(cp: Int): Boolean {
        if (cp == 0xFE0F) return true // emoji presentation selector
        return when (Character.getType(cp)) {
            Character.OTHER_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
            else -> false
        }
    }
}
