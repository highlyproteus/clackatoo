// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

/** Where one role of the setup checklist (voice model, grammar model) stands. */
internal enum class SetupStep {
    /** No model of the role can run on this phone: it is left out. */
    SKIPPED,
    MISSING,
    READY;

    companion object {
        fun of(runsHere: Boolean, ready: Boolean) = if (ready) READY else if (runsHere) MISSING else SKIPPED
    }
}

/**
 * The setup card of the home screen: the keyboard is already on (the set-up card replaces it until it is), then one row per
 * model role. [forPhone] is null while there is nothing left to do, so the card disappears once everything is done. On a phone
 * that cannot run any model the card stays with one line that says why, because nothing there can ever be set up.
 */
internal class SetupChecklist private constructor(val voice: SetupStep, val grammar: SetupStep) {
    /** Neither role can run on this phone. */
    val unsupported: Boolean get() = voice == SetupStep.SKIPPED && grammar == SetupStep.SKIPPED

    companion object {
        fun forPhone(voice: SetupStep, grammar: SetupStep): SetupChecklist? {
            val list = SetupChecklist(voice, grammar)
            return if (list.unsupported || voice == SetupStep.MISSING || grammar == SetupStep.MISSING) list else null
        }
    }
}
