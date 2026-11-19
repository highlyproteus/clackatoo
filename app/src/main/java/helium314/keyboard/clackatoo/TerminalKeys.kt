// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.content.SharedPreferences
import android.text.InputType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyData
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyLabel
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.SimplePopups
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.TextKeyData
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.toolbarKeyStrings

/**
 * Terminal keys: in a terminal the suggestion strip shows Esc, Tab, Ctrl, Alt and the arrow keys instead of suggestions
 * (terminals have none). The keys are toolbar keys, so they send the same key events; Ctrl and Alt are one-shot like a
 * modifier key on the keyboard: they stay on until the next key, so Ctrl then c sends Ctrl+C. In a terminal the letters'
 * bottom row has "/" and "-", the two characters a command line needs most, in the place of the comma and the emoji key.
 */
object TerminalKeys {
    const val PREF_TERMINAL_KEYS = "terminal_keys"
    const val DEFAULT_TERMINAL_KEYS = true
    const val PREF_TERMINAL_SWIPE = "terminal_swipe"
    const val DEFAULT_TERMINAL_SWIPE = false
    const val PREF_TERMINAL_BOTTOM_ROW = "terminal_bottom_row"
    const val DEFAULT_TERMINAL_BOTTOM_ROW = true

    val KEYS = listOf(ToolbarKey.ESCAPE, ToolbarKey.TAB, ToolbarKey.CTRL, ToolbarKey.ALT,
        ToolbarKey.LEFT, ToolbarKey.UP, ToolbarKey.DOWN, ToolbarKey.RIGHT)

    /** What a long press on "/" and on "-" offers; the first is the key's hint. */
    val SLASH_POPUPS = listOf("|", "~", "`")
    val DASH_POPUPS = listOf("_", "=", "+")

    /** The bottom row of a terminal: "/" takes the comma key's place and "-" the emoji key's (or goes before the space bar). */
    fun adjustBottomRow(row: MutableList<KeyData>) {
        val comma = row.indexOfFirst { it.label == KeyLabel.COMMA || it.groupId == KeyData.GROUP_COMMA }
        if (comma < 0) return // a custom bottom row without a comma key stays as it is
        row[comma] = terminalKey("/", SLASH_POPUPS, row[comma].width)
        val emoji = row.indexOfFirst { it.label == toolbarKeyStrings[ToolbarKey.EMOJI] }
        if (emoji >= 0) row[emoji] = terminalKey("-", DASH_POPUPS, row[emoji].width)
        else row.add(row.indexOfFirst { it.label == KeyLabel.SPACE }.takeIf { it >= 0 } ?: (comma + 1), terminalKey("-", DASH_POPUPS, 0f))
    }

    // a function key like comma and period; no label flags, so the first popup shows as the hint, as on letter keys
    private fun terminalKey(label: String, popups: List<String>, width: Float): KeyData =
        TextKeyData(type = KeyType.FUNCTION, label = label, popup = SimplePopups(popups), width = width)

    private const val TERMUX = "com.termux"
    private const val CONNECTBOT = "org.connectbot"

    /**
     * Apps whose terminal view never asks for a text field (measured: Termux sends TYPE_NULL or 0x00080090, ConnectBot
     * 1.11 sends 0x00080090), so a text field of theirs is one of the app's own forms or Termux's text box.
     */
    private val RAW_KEY_TERMINALS = setOf(TERMUX, CONNECTBOT)

    /** Terminal and SSH apps whose terminal view may ask for something else than raw key events (Termux with char-based input, web terminals). */
    private val TERMINAL_PACKAGES = setOf(
        TERMUX,
        CONNECTBOT,
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client", // Termius
        "com.android.virtualization.terminal", // Android's Linux terminal
        "com.google.android.virtualization.terminal",
        "jackpal.androidterm",
        "com.termoneplus",
        "com.offsec.nhterm",
    )

    /**
     * Whether a field is a terminal: it asks for raw key events (TYPE_NULL, as Termux and ConnectBot do), or it belongs to a
     * known terminal app and is not one of that app's other fields.
     */
    fun isTerminal(inputType: Int, packageName: String?): Boolean {
        if (inputType == InputType.TYPE_NULL) return true
        if (packageName !in TERMINAL_PACKAGES) return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            0 -> true // Termux with "enforce char based input", ConnectBot: a variation and flags without a class (0x00080090)
            // Termux's own text field is its text box and ConnectBot's are its forms: they want suggestions
            InputType.TYPE_CLASS_TEXT -> packageName !in RAW_KEY_TERMINALS
            else -> false // number, phone and date fields are never the terminal
        }
    }

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(PREF_TERMINAL_KEYS, DEFAULT_TERMINAL_KEYS)

    /** Swipe typing: everywhere but in a terminal, where a slide over the keys would type a word nobody sees coming, unless it is wanted there. */
    fun swipeAllowed(prefs: SharedPreferences, terminal: Boolean) =
        !terminal || prefs.getBoolean(PREF_TERMINAL_SWIPE, DEFAULT_TERMINAL_SWIPE)
}
