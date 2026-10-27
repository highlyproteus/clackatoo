// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ToolbarCheckKeyTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("toolbar-test", Context.MODE_PRIVATE)

    private fun legacyList(vararg enabled: ToolbarKey) =
        ToolbarKey.entries.filter { it != ToolbarKey.CHECK && it != ToolbarKey.CLOSE_HISTORY }
            .joinToString(Separators.ENTRY) { it.name + Separators.KV + (it in enabled) }

    @Test fun `existing installs get the Check key next to Voice, enabled`() {
        prefs.edit {
            clear()
            putString(Settings.PREF_TOOLBAR_KEYS, legacyList(ToolbarKey.SETTINGS, ToolbarKey.VOICE, ToolbarKey.CLIPBOARD, ToolbarKey.UNDO))
        }
        upgradeToolbarPrefs(prefs)
        val enabled = getEnabledToolbarKeys(prefs)
        assertEquals(listOf(ToolbarKey.VOICE, ToolbarKey.CHECK, ToolbarKey.CLIPBOARD, ToolbarKey.UNDO, ToolbarKey.SETTINGS), enabled)
    }

    @Test fun `the upgrade does not re-enable a Check key the user turned off`() {
        prefs.edit {
            clear()
            putString(
                Settings.PREF_TOOLBAR_KEYS,
                legacyList(ToolbarKey.VOICE, ToolbarKey.COPY) + Separators.ENTRY + ToolbarKey.CHECK.name + Separators.KV + "false",
            )
        }
        upgradeToolbarPrefs(prefs)
        assertFalse(ToolbarKey.CHECK in getEnabledToolbarKeys(prefs))
    }

    @Test fun `other toolbars get the Check key switched off`() {
        prefs.edit {
            clear()
            putString(Settings.PREF_PINNED_TOOLBAR_KEYS, legacyList())
        }
        upgradeToolbarPrefs(prefs)
        assertTrue(getPinnedToolbarKeys(prefs).isEmpty())
        assertTrue(prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, "")!!.contains("CHECK${Separators.KV}false"))
    }
}
