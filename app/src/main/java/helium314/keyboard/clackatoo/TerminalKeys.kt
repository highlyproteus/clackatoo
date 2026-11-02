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

    /** Terminal and SSH apps whose terminal view asks for a text field type (Termux with char-based input, web terminals). */
    private val TERMINAL_PACKAGES = setOf(
        "com.termux",
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
     * Whether a field is a terminal: it asks for raw key events (TYPE_NULL, as Termux and ConnectBot do) or belongs to a
     * known terminal app.
     */
    fun isTerminal(inputType: Int, packageName: String?): Boolean =
        inputType == InputType.TYPE_NULL || packageName in TERMINAL_PACKAGES

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(PREF_TERMINAL_KEYS, DEFAULT_TERMINAL_KEYS)
}
