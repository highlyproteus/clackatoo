// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val urlPattern = Regex("https://huggingface\\.co/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/resolve/[0-9a-f]{40}/[A-Za-z0-9._-]+")

    @Test
    fun `every model is an https huggingface co file pinned to a 40 hex revision with a 64 hex digest`() {
        for (m in Catalog.all) {
            assertTrue(urlPattern.matches(m.url), "${m.id}: ${m.url}")
            assertTrue(m.url.endsWith("/${m.fileName}"), "${m.id}: url must name the file")
            assertTrue(Regex("[0-9a-f]{40}").matches(m.revision), "${m.id}: revision")
            assertTrue(Regex("[0-9a-f]{64}").matches(m.sha256), "${m.id}: sha256 must be lower-case hex")
            assertTrue(m.sizeBytes > 1_000_000, "${m.id}: size")
        }
    }

    @Test
    fun `ids and file names are unique and ids are simple`() {
        assertEquals(Catalog.all.size, Catalog.all.map { it.id }.toSet().size)
        assertEquals(Catalog.all.size, Catalog.all.map { it.fileName }.toSet().size)
        for (m in Catalog.all) assertTrue(Regex("[a-z0-9.-]{1,64}").matches(m.id), m.id)
    }

    @Test
    fun `the digests and sizes of the shipped options are the reference files`() {
        // taken from the Hugging Face tree API of each pinned revision
        val expected = mapOf(
            "parakeet-tdt-0.6b-v3" to (741_867_360L to "5ebd1d55609b5ad9dac1c457eeb87a9904f199d6fbbb738453182d010646c2e4"),
            "parakeet-tdt-0.6b-v2" to (705_039_648L to "1ba06d039a2c38d2d1b6b512230ab62ecd04d8bf3be45234dd1ddc507b6393b8"),
            "parakeet-tdt-ctc-110m" to (143_290_496L to "1316a7a708a083179ea133421dc437ceee6719a159489204a1bb6ff559793e5b"),
            "nemotron-3.5-asr-streaming-0.6b" to (784_801_888L to "7bb14605a707f821560952521034002c91653d676272a80dca0cd2aa886d0ab2"),
            "whisper-tiny" to (32_152_673L to "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
            "whisper-base" to (59_707_625L to "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            "whisper-small" to (190_085_487L to "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            "whisper-large-v3-turbo" to (574_041_195L to "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
            "s1-mini" to (484_219_808L to "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634"),
            "qwen3.5-0.8b" to (532_517_120L to "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"),
            "qwen3.5-2b" to (1_280_835_840L to "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"),
        )
        assertEquals(expected.keys, Catalog.all.map { it.id }.toSet())
        for (m in Catalog.all) assertEquals(expected.getValue(m.id), m.sizeBytes to m.sha256, m.id)
    }

    @Test
    fun `engines match kinds and each role has a recommended model`() {
        for (m in Catalog.all) when (m.kind) {
            CatalogKind.SPEECH -> assertTrue(m.engine == CatalogEngine.WHISPER || m.engine == CatalogEngine.PARAKEET, m.id)
            else -> assertEquals(CatalogEngine.LLAMA, m.engine, m.id)
        }
        for (kind in CatalogKind.entries) assertTrue(Catalog.forKind(kind).any { it.recommended }, "$kind")
    }

    @Test
    fun `language support`() {
        assertTrue(Catalog.PARAKEET_TDT_V3.supports("es"))
        assertFalse(Catalog.PARAKEET_TDT_V2.supports("es"))
        assertTrue(Catalog.QWEN3_5_0_8B.supports("es"))
        assertTrue(Catalog.WHISPER_TINY.supports("es"))
        assertFalse(Catalog.S1_MINI.supports("es"))
    }

    @Test
    fun `lookup by id only finds catalog models`() {
        assertEquals(Catalog.S1_MINI, Catalog.byId("s1-mini"))
        assertNull(Catalog.byId(null))
        assertNull(Catalog.byId(""))
        assertNull(Catalog.byId("https://evil.example/model.bin"))
        assertNull(Catalog.byId("S1-MINI"))
    }
}
