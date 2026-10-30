// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.rememberCleanUpEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * The clean-up setting has two switches: the voice panel's toggle in the keyboard and the one in Settings > Voice
 * typing. Settings must show what is stored, also after the keyboard changed it while Settings was in the background.
 */
@RunWith(RobolectricTestRunner::class)
class CleanUpSwitchStateTest {
    @Test fun `a change made by the keyboard while settings is stopped shows when settings comes back`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.prefs()
        prefs.edit { remove(AssistSettings.PREF_S1_MINI_ENABLED) } // on by default
        var shown: Boolean? = null
        activity.setContent { shown = rememberCleanUpEnabled(prefs).value }
        idle()
        assertEquals(true, shown)

        controller.pause().stop()
        AssistSettings.toggleS1Mini(prefs) // the voice panel's toggle, with the settings screen in the background
        controller.restart().resume()
        idle()
        assertEquals(false, shown)

        // the switch in Settings flips the stored value, so the next tap turns clean-up back on
        assertTrue(AssistSettings.toggleS1Mini(prefs))
        idle()
        assertEquals(true, shown)
        assertTrue(AssistSettings.s1MiniEnabled(prefs))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
