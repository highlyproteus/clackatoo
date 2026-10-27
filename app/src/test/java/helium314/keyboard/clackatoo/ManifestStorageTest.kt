// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * As a system app (a ROM that preinstalls the keyboard) the manifest's defaultToDeviceProtectedStorage and an application-wide directBootAware
 * take effect and would put clipboard history and the models where they are readable before the first unlock. Only the
 * input method service may be direct-boot aware.
 */
class ManifestStorageTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "") // the comments explain this rule by naming the attributes

    @Test fun `the application keeps its files in credential-protected storage`() {
        assertFalse("defaultToDeviceProtectedStorage" in manifest)
    }

    @Test fun `only the input method service is direct boot aware`() {
        assertEquals(1, Regex("directBootAware=\"true\"").findAll(manifest).count())
        val service = Regex("<service android:name=\"LatinIME\".*?>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        assertTrue("directBootAware=\"true\"" in service)
    }

    @Test fun `the clipboard database is left out of backups`() {
        for (file in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml"))
            assertTrue("heliboard.db" in File(file).readText(), file)
    }
}
