// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputSanitizerTest {
    private fun clean(s: String, multiLine: Boolean = false, max: Int = Int.MAX_VALUE) = OutputSanitizer.clean(s, multiLine, max)

    @Test fun `plain text is not touched`() {
        assertEquals("Hello, world. ¿Qué tal? 日本語 😀 café", clean("Hello, world. ¿Qué tal? 日本語 😀 café"))
    }

    @Test fun `line breaks become a space in single-line fields`() {
        assertEquals("rm -rf / next", clean("rm -rf /\nnext"))
        assertEquals("a b", clean("a\r\nb"))
        assertEquals("a b", clean("a\rb"))
        assertEquals("a b c", clean("a\u2028b\u2029c"))
        assertEquals("a b", clean("a\u0085b"))
        assertEquals("a b", clean("a\tb"))
        assertEquals("a b", clean("a\n\nb")) // runs of breaks collapse to one space
    }

    @Test fun `line breaks and tabs stay in multi-line fields, in one form`() {
        assertEquals("a\nb\tc", clean("a\r\nb\tc", multiLine = true))
        assertEquals("a\nb", clean("a\u2028b", multiLine = true))
    }

    @Test fun `control characters are removed`() {
        assertEquals("ab", clean("a\u0000\u0007\u001Bb"))
        assertEquals("ab", clean("a\u007F\u0080\u009Fb"))
        assertEquals("a[31mb", clean("a\u001B[31mb")) // the escape byte goes; what follows it is ordinary text
    }

    @Test fun `bidirectional controls are removed`() {
        assertEquals("abc", clean("a\u202Eb\u202Cc"))
        assertEquals("abc", clean("\u202Aa\u202Bb\u202Dc\u2066\u2067\u2068\u2069"))
        assertEquals("ab", clean("a\u200E\u200F\u061Cb"))
    }

    @Test fun `zero-width characters are removed`() {
        assertEquals("pay", clean("p\u200Ba\u200Cy\u2060\uFEFF"))
        assertEquals("ab", clean("a\u180Eb"))
        assertEquals("ab", clean("a\uDB40\uDC41b")) // a tag character (U+E0041)
    }

    @Test fun `a zero-width joiner survives only inside an emoji sequence`() {
        val family = "👨\u200D👩\u200D👧"
        assertEquals(family, clean(family))
        assertEquals("ab", clean("a\u200Db"))
        assertEquals("a", clean("a\u200D"))
        assertEquals("", clean("\u200D"))
    }

    @Test fun `lone surrogates and non-characters are removed`() {
        assertEquals("ab", clean("a\uD800b"))
        assertEquals("ab", clean("a\uDC00b"))
        assertEquals("ab", clean("a\uFFFEb\uFFFF"))
    }

    @Test fun `the length is capped without splitting an emoji`() {
        assertEquals("abcde", clean("abcdefghij", max = 5))
        assertEquals("ab", clean("ab😀cd", max = 3)) // the emoji is two chars; a half emoji is never left
        assertEquals("ab😀", clean("ab😀cd", max = 4))
        assertEquals("", clean("abc", max = 0))
        assertEquals(5000, clean("a".repeat(100_000), max = 5000).length)
    }

    @Test fun `the cap is applied after removing what is not kept`() {
        assertEquals("abcd", clean("a\u200Bb\u200Bc\u200Bd\u200Be", max = 4))
    }

    @Test fun `the cap follows the length of the clip`() {
        val oneSecond = OutputSanitizer.maxCharsForClip(16_000)
        val twoMinutes = OutputSanitizer.maxCharsForClip(16_000 * 120)
        assertTrue(oneSecond in 60..150, "$oneSecond")
        assertTrue(twoMinutes in 3000..8000, "$twoMinutes")
        assertTrue(OutputSanitizer.maxCharsForClip(16_000 * 5) > oneSecond)
        // about 40 characters per second is more than anyone speaks (150 words per minute is about 14)
        assertTrue(OutputSanitizer.maxCharsForClip(16_000 * 10) >= 14 * 10)
        // a hallucinated loop of one second of audio cannot become a page of text
        assertEquals(oneSecond, clean("la ".repeat(10_000), max = oneSecond).length)
    }

    // ---- every format (Cf) character and variation selector goes, unless the user's own text had it

    private fun corrected(s: String, original: String) = OutputSanitizer.clean(s, false, Int.MAX_VALUE, original)

    @Test fun `every character the review listed is removed from model output`() {
        val listed = buildList<Int> {
            addAll(0xFE00..0xFE0E) // variation selectors (U+FE0F stays)
            addAll(0xE0100..0xE01EF) // variation selectors supplement
            add(0x00AD); add(0x034F) // soft hyphen, combining grapheme joiner
            addAll(0x2061..0x2064); addAll(0x206A..0x206F) // invisible operators, deprecated format characters
            addAll(0xFFF9..0xFFFB) // interlinear annotation
            add(0x115F); add(0x1160); add(0x3164); add(0xFFA0) // Hangul fillers
            addAll(0x180B..0x180D); add(0x180F) // Mongolian variation selectors
            add(0x180E); add(0xFEFF); addAll(0x200B..0x200F); addAll(0x202A..0x202E); addAll(0x2066..0x2069); add(0x061C)
            addAll(0xE0000..0xE007F) // tag characters
        }
        for (cp in listed) {
            val c = String(Character.toChars(cp))
            assertEquals("ab", clean("a${c}b"), "U+%04X".format(cp))
        }
    }

    @Test fun `no Unicode format character survives in model output`() {
        var checked = 0
        for (cp in 0..0x10FFFF) {
            if (Character.getType(cp) != Character.FORMAT.toInt() || cp == 0x200D) continue
            checked++
            assertEquals("ab", clean("a${String(Character.toChars(cp))}b"), "U+%04X".format(cp))
        }
        assertTrue(checked > 100, "$checked")
    }

    @Test fun `emoji sequences keep their joiner and their presentation selector`() {
        val family = "👨\u200D👩\u200D👧"
        assertEquals(family, clean(family))
        assertEquals("❤\uFE0F", clean("❤\uFE0F"))
        assertEquals("1\uFE0F\u20E3", clean("1\uFE0F\u20E3"))
        assertEquals("❤\uFE0F", corrected("❤\uFE0F\uFE0E", ""))
        assertEquals("ab", clean("a\u200Db")) // a joiner between letters is not an emoji sequence
    }

    @Test fun `the user's own invisible characters survive in a corrected chunk`() {
        // Persian: the user's ZWNJ is part of the spelling; the model's text has it as well
        val persian = "می\u200Cخواهم"
        assertEquals(persian, corrected(persian, persian))
        // ZWJ between letters (Indic, Arabic) and bidi marks the user typed
        assertEquals("a\u200Db", corrected("a\u200Db", "a\u200Db"))
        assertEquals("\u200Fhello\u200E", corrected("\u200Fhello\u200E", "\u200Fhelo\u200E"))
        assertEquals("\u202Bhello\u202C", corrected("\u202Bhello\u202C", "\u202Bhelo\u202C"))
        // a soft hyphen and a text-presentation selector the user wrote
        assertEquals("co\u00ADoperate", corrected("co\u00ADoperate", "co\u00ADoperate"))
        assertEquals("\u2603\uFE0E", corrected("\u2603\uFE0E", "\u2603\uFE0E"))
    }

    @Test fun `only the characters the user had survive, not the same kind of character`() {
        // the user had a ZWNJ; the model added a ZWSP, a bidi override and a variation selector as well
        assertEquals("a\u200Cb c d e", corrected("a\u200Cb\u200B c\u202E d\uFE01 e", "a\u200Cb c d e"))
        // the user's character is allowed, but not more kinds of it than were there: only the same character
        assertEquals("ab", corrected("a\u200Eb", "a\u200Fb"))
    }

    @Test fun `invisible characters in the original do not let control characters or line breaks through`() {
        val original = "a\u200Cb"
        assertEquals("a\u200Cb c", corrected("a\u200Cb\u0000\n\u202Ec", original))
        assertEquals("a\u200Cb", corrected("a\u200Cb\uD800", original))
    }

    @Test fun `without an original text nothing invisible stays`() {
        assertEquals("ab", corrected("a\u200Cb", ""))
    }
}
