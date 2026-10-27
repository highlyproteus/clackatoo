// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

/**
 * Clackatoo: when the extracted-dictionary cleanup of an app upgrade may run. It lists and deletes files in the app's
 * private files folder, which is credential-protected: before the user's first unlock after a restart (an OTA that raises
 * the version restarts the phone) the folder's names are not readable, and a cleanup that keeps only the names ending in
 * the user-dictionary suffix would delete the user's own dictionaries. So while the user is locked it is postponed, with
 * a flag in device-protected preferences, and runs on the first start after unlock. No Android in here, so a test can
 * check it.
 */
object DictionaryCleanupPolicy {
    enum class Action { RunNow, Postpone, Nothing }

    /** [upgrading]: this start is an upgrade; [pending]: an earlier upgrade postponed the cleanup. */
    fun decide(upgrading: Boolean, pending: Boolean, userUnlocked: Boolean): Action = when {
        !upgrading && !pending -> Action.Nothing
        userUnlocked -> Action.RunNow
        else -> Action.Postpone
    }

    /** The extracted dictionaries go, the user's own stay. */
    fun shouldDelete(fileName: String, userDictionarySuffix: String) = !fileName.endsWith(userDictionarySuffix)
}
