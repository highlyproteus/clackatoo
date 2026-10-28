// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelShortNameTest {
    @Test fun `every downloadable model has a short card title that is not longer than its full name`() {
        for (spec in ModelCatalog.all.filter { it.bundledAsset == null }) {
            assertTrue(spec.shortName.isNotBlank() && spec.shortName.length <= 24, "${spec.id}: ${spec.shortName}")
            assertTrue(spec.shortName.length <= spec.displayName.length, spec.id)
        }
    }

    @Test fun `short titles are the ones the cards show`() {
        assertEquals("Parakeet v3", ModelCatalog.byId("parakeet-tdt-0.6b-v3")!!.shortName)
        assertEquals("S1-mini", ModelCatalog.byId("s1-mini")!!.shortName)
        assertEquals("Qwen3.5 2B", ModelCatalog.byId("qwen3.5-2b")!!.shortName)
    }

    @Test fun `the full name stays the display name`() {
        assertTrue(ModelCatalog.byId("parakeet-tdt-ctc-110m")!!.displayName.contains("110M"))
    }
}
