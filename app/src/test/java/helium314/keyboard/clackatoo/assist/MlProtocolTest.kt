// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MlProtocolTest {
    @Test fun `times survive the trip in one int`() {
        val packed = MlProtocol.packTimes(1_400, 12_500)
        assertEquals(1_400, MlProtocol.loadMsOf(packed))
        assertEquals(12_500, MlProtocol.runMsOf(packed))
        assertEquals(0, MlProtocol.loadMsOf(MlProtocol.packTimes(0, 7)))
    }

    @Test fun `times that do not fit are capped, never wrapped or negative`() {
        val packed = MlProtocol.packTimes(10_000_000, -5)
        assertEquals(65_535, MlProtocol.loadMsOf(packed))
        assertEquals(0, MlProtocol.runMsOf(packed))
        // whatever a hostile process sends back decodes to something in range
        for (arg in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertTrue(MlProtocol.loadMsOf(arg) in 0..65_535)
            assertTrue(MlProtocol.runMsOf(arg) in 0..65_535)
        }
    }

    @Test fun `only plausible language codes reach the model process`() {
        assertTrue(MlProtocol.isLanguageCode("en"))
        assertTrue(MlProtocol.isLanguageCode("es"))
        assertTrue(MlProtocol.isLanguageCode("auto"))
        assertTrue(MlProtocol.isLanguageCode("haw"))
        for (bad in listOf("", "e", "EN", "en-US", "english", "../x", "e\u0000")) assertFalse(MlProtocol.isLanguageCode(bad), bad)
    }

    @Test fun `limits are consistent`() {
        assertTrue(MlProtocol.MAX_RESULT_BYTES < 64 * 1024) // fits in a pipe, so the service never blocks writing it
        assertTrue(MlProtocol.MIN_CONTEXT < 2048 && 2048 < MlProtocol.MAX_CONTEXT)
        assertEquals(1_920_000, MlProtocol.MAX_SAMPLES)
    }
}
