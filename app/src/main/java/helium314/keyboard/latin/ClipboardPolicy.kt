// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

/** Clackatoo: which copied clips the history may keep. No Android in here, so a test can check it. */
object ClipboardPolicy {
    /**
     * [sensitive] is what the source declared with `android.content.extra.IS_SENSITIVE` (see
     * [helium314.keyboard.compat.ClipboardManagerCompat.getClipSensitivity]); null when the phone can't tell. A clip
     * marked sensitive is never stored: it would sit in the history, and in storage, until it expires.
     */
    fun mayStore(sensitive: Boolean?): Boolean = sensitive != true
}
