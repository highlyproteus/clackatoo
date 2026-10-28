// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

/**
 * HeliBoard's gesture data gathering (the NLNet project that collects swipes, with the typed words, and has them mailed
 * to HeliBoard) stays closed in Clackatoo. Upstream offers it only when a gesture library is loaded, to evaluate
 * Google's library; Clackatoo's built-in decoder means a gesture library is now always loaded, so the menu, the
 * promotion dialog and background gathering check this as well.
 */
object GestureDataPolicy {
    const val GATHERING_ALLOWED = false
}
