// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextEditTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val noPersonalizedLearning = 0x1000000

    @Test fun `every password kind is sensitive`() {
        assertTrue(SensitiveField.isSensitive(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertTrue(SensitiveField.isSensitive(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0))
        assertTrue(SensitiveField.isSensitive(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0))
        assertTrue(SensitiveField.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
    }

    @Test fun `password flags on top of other bits are still sensitive`() {
        val multiLineAutoCap = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        assertTrue(SensitiveField.isSensitive(text or multiLineAutoCap or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
    }

    @Test fun `private fields are sensitive`() {
        assertTrue(SensitiveField.isSensitive(text, noPersonalizedLearning))
        assertTrue(SensitiveField.isSensitive(text, noPersonalizedLearning or 6))
    }

    @Test fun `ordinary fields are not sensitive`() {
        assertFalse(SensitiveField.isSensitive(text, 0))
        assertFalse(SensitiveField.isSensitive(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 6))
        assertFalse(SensitiveField.isSensitive(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0))
        assertFalse(SensitiveField.isSensitive(InputType.TYPE_CLASS_NUMBER, 0))
        // a number class value that happens to equal a text password variation must not match
        assertFalse(SensitiveField.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
    }

    @Test fun `a missing editor is treated as sensitive`() {
        assertTrue(SensitiveField.isSensitive(null))
    }

    @Test fun `dictated text gets a space only where it needs one`() {
        assertEquals("hello", Dictation.prepareInsertion(null, "  hello "))
        assertEquals("hello", Dictation.prepareInsertion("", "hello"))
        assertEquals("hello", Dictation.prepareInsertion("Hi ", "hello"))
        assertEquals("hello", Dictation.prepareInsertion("Hi\n", "hello"))
        assertEquals(" hello", Dictation.prepareInsertion("Hi", "hello"))
        assertEquals(" Hello there.", Dictation.prepareInsertion("Well.", "Hello there."))
        assertEquals(".", Dictation.prepareInsertion("Hi", "."))
        assertEquals("hello", Dictation.prepareInsertion("(", "hello"))
        assertEquals("", Dictation.prepareInsertion("Hi", "   "))
    }

    // ---- reading the editor: text and selection come together, a failed read is a failure

    private fun snap(text: String, start: Int, end: Int = start, offset: Int = 0) = EditorSnapshot.of(text, start, end, offset)

    /** For an editor that knows its offset the keyboard's own selection is not consulted; pass the snapshot's own. */
    private fun fromSnap(s: EditorSnapshot?) = CheckTarget.from(s, s?.selectionStart ?: 0, s?.selectionEnd ?: 0)

    @Test fun `a selection is the target when there is one`() {
        val target = assertIs<CheckTarget.Result.Found>(fromSnap(snap("before SEL after", 7, 10))).target
        assertIs<CheckTarget.Selection>(target)
        assertEquals("SEL", target.text)
    }

    @Test fun `without a selection the whole field is the target`() {
        val target = assertIs<CheckTarget.Result.Found>(fromSnap(snap("hello world", 6))).target
        assertIs<CheckTarget.Field>(target)
        assertEquals("hello world", target.text)
    }

    @Test fun `the whole-field path is never taken while something is selected`() {
        // the old failure: a selection that could not be read fell back to the text around it
        for ((start, end) in listOf(0 to 3, 3 to 6, 6 to 9, 0 to 9, 4 to 5)) {
            val target = assertIs<CheckTarget.Result.Found>(fromSnap(snap("AAABBBCCC", start, end))).target
            assertIs<CheckTarget.Selection>(target)
            assertEquals("AAABBBCCC".substring(start, end), target.text)
        }
    }

    @Test fun `a reversed selection is read as the same selection`() {
        assertEquals(snap("abcdef", 1, 4), snap("abcdef", 4, 1))
    }

    @Test fun `a read that failed is a failure, never an empty field`() {
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(null, 0, 0))
        // an editor that reports positions outside its text is not trusted either
        assertNull(snap("abc", 0, 9))
        assertNull(snap("abc", -1, 2))
        assertNull(EditorSnapshot.of(null, 0, 0))
    }

    @Test fun `text that does not start at the start of the field is not the whole field`() {
        assertEquals(CheckTarget.Result.Unreadable, fromSnap(snap("middle of a long text", 3, offset = 5000)))
    }

    // ---- an editor that does not say where its text starts (offset -1) is cross-checked against the keyboard's selection

    @Test fun `offset -1 is accepted when the selection is the one the keyboard knows`() {
        val whole = assertIs<CheckTarget.Result.Found>(CheckTarget.from(snap("hello world", 6, offset = -1), 6, 6)).target
        assertIs<CheckTarget.Field>(whole)
        val selection = assertIs<CheckTarget.Result.Found>(CheckTarget.from(snap("before SEL after", 7, 10, offset = -1), 7, 10)).target
        assertIs<CheckTarget.Selection>(selection)
        assertEquals("SEL", selection.text)
        // the keyboard may hold a reversed selection
        assertIs<CheckTarget.Result.Found>(CheckTarget.from(snap("before SEL after", 7, 10, offset = -1), 10, 7))
    }

    @Test fun `offset -1 is refused when the text before the cursor is shorter than the cursor position`() {
        // an editor that returned a shortened prefix: the snapshot starts later in the field than 0
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(snap("rld", 3, offset = -1), 11, 11))
    }

    @Test fun `offset -1 is refused when the editor hides a selection`() {
        // getSelectedText returned null: the default getSurroundingText then reports an empty selection
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(snap("hello world", 6, offset = -1), 2, 6))
        // or the editor reports a selection the keyboard does not know about
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(snap("hello world", 2, 6, offset = -1), 6, 6))
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(snap("hello world", 2, 6, offset = -1), 2, 7))
    }

    @Test fun `offset -1 is refused when the keyboard does not know the selection`() {
        assertEquals(CheckTarget.Result.Unreadable, CheckTarget.from(snap("hello", 0, offset = -1), -1, -1))
    }

    @Test fun `a known offset does not depend on the keyboard's selection`() {
        assertIs<CheckTarget.Result.Found>(CheckTarget.from(snap("hello world", 6, offset = 0), -1, -1))
    }

    @Test fun `nothing to check`() {
        assertEquals(CheckTarget.Result.Empty, fromSnap(snap("", 0)))
        assertEquals(CheckTarget.Result.Empty, fromSnap(snap(" \n ", 1)))
        assertEquals(CheckTarget.Result.Empty, fromSnap(snap("x   y", 1, 4))) // selected whitespace only
    }

    @Test fun `text that may be cut off is refused instead of partly replaced`() {
        val long = "a".repeat(CheckTarget.MAX_CHARS)
        assertEquals(CheckTarget.Result.TooLong, fromSnap(snap(long, long.length)))
        assertEquals(CheckTarget.Result.TooLong, fromSnap(snap(long, 0)))
        assertEquals(CheckTarget.Result.TooLong, fromSnap(snap(long + "b", 0, long.length + 1)))
        assertIs<CheckTarget.Result.Found>(fromSnap(snap(long.dropLast(1), long.length - 1)))
    }

    @Test fun `snapshots are the same only when text, selection and place agree`() {
        val a = snap("hello", 2, 4, offset = 0)!!
        assertTrue(a.sameAs(snap("hello", 2, 4, offset = 0)!!))
        assertTrue(a.sameAs(snap("hello", 2, 4, offset = -1)!!)) // one side does not know its offset
        assertFalse(a.sameAs(snap("hellO", 2, 4, offset = 0)!!))
        assertFalse(a.sameAs(snap("hello", 2, 3, offset = 0)!!))
        assertFalse(a.sameAs(snap("hello", 3, 4, offset = 0)!!))
        assertFalse(a.sameAs(snap("hello", 2, 4, offset = 7)!!))
    }

    // ---- replacing: only exactly what was read and shown

    private fun target(text: String, start: Int, end: Int = start): CheckTarget =
        assertIs<CheckTarget.Result.Found>(fromSnap(snap(text, start, end))).target

    @Test fun `a selection is replaced only while it is still the same selection`() {
        val original = target("i has a apple", 0, 13)
        assertEquals(Replacement.OverSelection("I have an apple"), TextReplacement.plan(original, target("i has a apple", 0, 13), "I have an apple"))
        assertNull(TextReplacement.plan(original, target("something else", 0, 14), "x"))
        assertNull(TextReplacement.plan(original, null, "x"))
    }

    @Test fun `a selection is not replaced when the selection moved but the selected text is the same`() {
        val original = target("go go go", 0, 2)
        assertNull(TextReplacement.plan(original, target("go go go", 3, 5), "x"))
        assertNull(TextReplacement.plan(original, target("go go go", 0, 5), "x"))
    }

    @Test fun `a selection is not replaced when the text around it changed`() {
        val original = target("AAA[SEL]BBB", 3, 8)
        assertNull(TextReplacement.plan(original, target("AAAA[SEL]BBB", 4, 9), "x"))
        assertNull(TextReplacement.plan(original, target("AAA[SEL]BBBB", 3, 8), "x"))
    }

    @Test fun `a field is replaced as a whole, from its start to its end`() {
        val original = target("i has a apple", 6)
        assertEquals(Replacement.OverField(13, "I have an apple"), TextReplacement.plan(original, target("i has a apple", 6), "I have an apple"))
    }

    @Test fun `a field is not replaced when its text, its cursor or its selection changed`() {
        val original = target("i has a apple", 6)
        assertNull(TextReplacement.plan(original, target("i has a apples", 6), "x"))
        assertNull(TextReplacement.plan(original, target("i has a apple", 7), "x")) // the cursor moved
        assertNull(TextReplacement.plan(original, target("i has a apple", 2, 5), "x")) // a selection appeared
        assertNull(TextReplacement.plan(original, null, "x"))
    }

    @Test fun `only multi-line text fields accept line breaks`() {
        assertTrue(FieldKind.acceptsLineBreaks(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(FieldKind.acceptsLineBreaks(text))
        assertFalse(FieldKind.acceptsLineBreaks(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }
}
