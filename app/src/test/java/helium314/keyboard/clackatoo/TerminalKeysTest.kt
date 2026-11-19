// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.content.Context
import android.text.InputType
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyData
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.SimplePopups
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.TextKeyData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TerminalKeysTest {
    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun `a field that wants raw key events is a terminal, whatever the app`() {
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_NULL, "com.example.vnc"))
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_NULL, null))
    }

    @Test fun `Termux and ConnectBot send a variation without a class for the terminal`() {
        val charBased = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS // 0x00080090
        assertTrue(TerminalKeys.isTerminal(charBased, "com.termux"))
        assertTrue(TerminalKeys.isTerminal(charBased, "org.connectbot"))
    }

    @Test fun `a terminal app that asks for a text field is a terminal`() {
        assertTrue(TerminalKeys.isTerminal(text, "com.android.virtualization.terminal"))
        assertTrue(TerminalKeys.isTerminal(InputType.TYPE_CLASS_TEXT, "com.sonelli.juicessh"))
    }

    @Test fun `Termux's text box and ConnectBot's forms are not the terminal`() {
        assertFalse(TerminalKeys.isTerminal(InputType.TYPE_CLASS_TEXT, "com.termux"))
        assertFalse(TerminalKeys.isTerminal(text, "com.termux"))
        // the Nickname field of ConnectBot 1.11's "Add host"
        assertFalse(TerminalKeys.isTerminal(0x208001, "org.connectbot"))
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

    @Test fun `swipe typing is off in a terminal until it is asked for`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("terminal-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        assertTrue(TerminalKeys.swipeAllowed(prefs, terminal = false))
        assertFalse(TerminalKeys.swipeAllowed(prefs, terminal = true))
        prefs.edit { putBoolean(TerminalKeys.PREF_TERMINAL_SWIPE, true) }
        assertTrue(TerminalKeys.swipeAllowed(prefs, terminal = true))
    }

    private fun row(vararg labels: String): MutableList<KeyData> = labels.mapTo(mutableListOf()) { TextKeyData(label = it) }

    @Test fun `slash and dash take the place of the comma and the emoji key`() {
        val row = row("symbol_alpha", "comma", "emoji", "space", "period", "action")
        TerminalKeys.adjustBottomRow(row)
        assertEquals(listOf("symbol_alpha", "/", "-", "space", "period", "action"), row.map { it.label })
        assertEquals(TerminalKeys.SLASH_POPUPS, (row[1].popup as SimplePopups).popupKeys)
        assertEquals(TerminalKeys.DASH_POPUPS, (row[2].popup as SimplePopups).popupKeys)
    }

    @Test fun `without an emoji key the dash goes before the space bar`() {
        val row = row("symbol_alpha", "comma", "language_switch", "space", "period", "action")
        TerminalKeys.adjustBottomRow(row)
        assertEquals(listOf("symbol_alpha", "/", "language_switch", "-", "space", "period", "action"), row.map { it.label })
    }

    @Test fun `a bottom row without a comma key stays as it is`() {
        val row = row("symbol_alpha", "emoji", "space", "period", "action")
        TerminalKeys.adjustBottomRow(row)
        assertEquals(listOf("symbol_alpha", "emoji", "space", "period", "action"), row.map { it.label })
    }
}
