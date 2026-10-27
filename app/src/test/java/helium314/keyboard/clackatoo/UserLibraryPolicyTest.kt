// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import helium314.keyboard.latin.BuildConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserLibraryPolicyTest {
    @Test fun `this build never allows a user-supplied library`() {
        assertFalse(BuildConfig.ALLOW_USER_LIBRARY)
        assertFalse(UserLibraryPolicy.isAllowed())
    }

    @Test fun `no build type allows it while the flag is false`() {
        for (type in listOf("debug", "debugNoMinify", "runTests", "release", "nouserlib", "anything"))
            assertFalse(UserLibraryPolicy.isAllowed(false, type), type)
    }

    @Test fun `the nouserlib build type refuses even with the flag set`() {
        assertFalse(UserLibraryPolicy.isAllowed(true, "nouserlib"))
        assertTrue(UserLibraryPolicy.isAllowed(true, "release")) // the flag alone is what keeps release closed
    }
}
