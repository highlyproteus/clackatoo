// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalKeysTest {
    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun `a field that wants raw key events is a terminal, whatever the app`() {
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_NULL, "com.example.vnc"))
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_NULL, null))
    }

    @Test fun `Termux with char-based input sends a variation without a class and still is a terminal`() {
        val charBased = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(TerminalKeys.isTerminal(charBased, "com.termux"))
        assertTrue(TerminalKeys.isTerminal(text, "com.android.virtualization.terminal"))
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_CLASS_TEXT, "org.connectbot"))
    }

    @Test fun `Termux's text box is not the terminal`() {
        assertFalse(TerminalKeys.isTerminal(InputType.TYPE_CLASS_TEXT, "com.termux"))
        assertFalse(TerminalKeys.isTerminal(text, "com.termux"))
    }

    @Test fun `number and phone fields of a terminal app are not terminals`() {
        assertFalse(TerminalKeys.isTerminal(InputType.TYPE_CLASS_NUMBER, "org.connectbot"))
        assertFalse(TerminalKeys.isTerminal(InputType.TYPE_CLASS_PHONE, "org.connectbot"))
    }

    @Test fun `text fields of other apps are not terminals`() {
        assertFalse(TerminalKeys.isTerminal(text, "com.google.android.apps.messaging"))
        assertFalse(TerminalKeys.isTerminal(InputType.TYPE_CLASS_NUMBER, "com.termux.api"))
        assertFalse(TerminalKeys.isTerminal(text, null))
    }
}
