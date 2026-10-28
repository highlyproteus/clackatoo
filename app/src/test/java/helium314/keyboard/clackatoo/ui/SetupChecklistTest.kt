// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the setup card of the home screen shows, and which rows it has. */
class SetupChecklistTest {
    private fun list(voice: SetupStep, grammar: SetupStep) = SetupChecklist.forPhone(voice, grammar)

    @Test fun `the card goes once both models are ready`() {
        assertNull(list(SetupStep.READY, SetupStep.READY))
    }

    @Test fun `a missing model keeps the card, with the ready role as a check`() {
        val onlyGrammar = list(SetupStep.READY, SetupStep.MISSING)!!
        assertEquals(SetupStep.READY, onlyGrammar.voice)
        assertEquals(SetupStep.MISSING, onlyGrammar.grammar)
        assertNotNull(list(SetupStep.MISSING, SetupStep.READY))
        assertNotNull(list(SetupStep.MISSING, SetupStep.MISSING))
    }

    @Test fun `a role that cannot run here is skipped and does not keep the card`() {
        assertNull(list(SetupStep.READY, SetupStep.SKIPPED))
        assertNull(list(SetupStep.SKIPPED, SetupStep.READY))
        assertFalse(list(SetupStep.SKIPPED, SetupStep.MISSING)!!.unsupported)
    }

    @Test fun `a phone that cannot run any model gets the one line instead of the rows`() {
        val list = list(SetupStep.SKIPPED, SetupStep.SKIPPED)!!
        assertTrue(list.unsupported)
    }

    @Test fun `a model that is installed is ready even where the phone could not run it, else running decides`() {
        assertEquals(SetupStep.READY, SetupStep.of(runsHere = false, ready = true))
        assertEquals(SetupStep.READY, SetupStep.of(runsHere = true, ready = true))
        assertEquals(SetupStep.MISSING, SetupStep.of(runsHere = true, ready = false))
        assertEquals(SetupStep.SKIPPED, SetupStep.of(runsHere = false, ready = false))
    }
}
