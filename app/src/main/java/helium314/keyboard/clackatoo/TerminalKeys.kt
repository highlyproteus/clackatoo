// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.content.SharedPreferences
import android.text.InputType
import helium314.keyboard.latin.utils.ToolbarKey

/**
 * Terminal keys: in a terminal the suggestion strip shows Esc, Tab, Ctrl and the arrow keys instead of suggestions
 * (terminals have none). The keys are toolbar keys, so they send the same key events; Ctrl is one-shot like a Ctrl key
 * on the keyboard: it stays on until the next key, so Ctrl then c sends Ctrl+C.
 */
object TerminalKeys {
    const val PREF_TERMINAL_KEYS = "terminal_keys"
    const val DEFAULT_TERMINAL_KEYS = true

    val KEYS = listOf(ToolbarKey.ESCAPE, ToolbarKey.TAB, ToolbarKey.CTRL, ToolbarKey.LEFT, ToolbarKey.UP, ToolbarKey.DOWN, ToolbarKey.RIGHT)

    private const val TERMUX = "com.termux"

    /** Terminal and SSH apps whose terminal view may ask for something else than raw key events (Termux with char-based input, web terminals). */
    private val TERMINAL_PACKAGES = setOf(
        TERMUX,
        "org.connectbot",
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
            0 -> true // Termux with "enforce char based input": a variation and flags without a class (0x00080090)
            InputType.TYPE_CLASS_TEXT -> packageName != TERMUX // Termux's own text field is its text box, which wants suggestions
            else -> false // number, phone and date fields are never the terminal
        }
    }

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(PREF_TERMINAL_KEYS, DEFAULT_TERMINAL_KEYS)
}
