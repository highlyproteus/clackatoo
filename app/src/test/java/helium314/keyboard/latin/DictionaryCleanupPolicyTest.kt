// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import helium314.keyboard.latin.DictionaryCleanupPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryCleanupPolicyTest {
    @Test fun `an upgrade with the user unlocked cleans now`() {
        assertEquals(Action.RunNow, DictionaryCleanupPolicy.decide(upgrading = true, pending = false, userUnlocked = true))
    }

    @Test fun `an upgrade before the first unlock postpones, and deletes nothing`() {
        assertEquals(Action.Postpone, DictionaryCleanupPolicy.decide(upgrading = true, pending = false, userUnlocked = false))
    }

    @Test fun `a postponed cleanup stays postponed while locked and runs after unlock`() {
        assertEquals(Action.Postpone, DictionaryCleanupPolicy.decide(upgrading = false, pending = true, userUnlocked = false))
        assertEquals(Action.RunNow, DictionaryCleanupPolicy.decide(upgrading = false, pending = true, userUnlocked = true))
    }

    @Test fun `an ordinary start does nothing, locked or not`() {
        assertEquals(Action.Nothing, DictionaryCleanupPolicy.decide(upgrading = false, pending = false, userUnlocked = true))
        assertEquals(Action.Nothing, DictionaryCleanupPolicy.decide(upgrading = false, pending = false, userUnlocked = false))
    }

    @Test fun `user dictionaries are kept, extracted ones go`() {
        assertFalse(DictionaryCleanupPolicy.shouldDelete("xx_user.dict", "user.dict"))
        assertTrue(DictionaryCleanupPolicy.shouldDelete("main.dict", "user.dict"))
    }
}
