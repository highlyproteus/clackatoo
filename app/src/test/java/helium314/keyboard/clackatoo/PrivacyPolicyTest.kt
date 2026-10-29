// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.screens.openPrivacyPolicy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivacyPolicyTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `privacy policy is available in settings search`() {
        val settings = SettingsContainer(app)
        assertTrue(settings.filter("privacy").any { it.key == SettingsWithoutKey.PRIVACY_POLICY })
        assertEquals(app.getString(R.string.clackatoo_privacy_policy), settings[SettingsWithoutKey.PRIVACY_POLICY]?.title)
    }

    @Test fun `privacy policy opens only an external browser intent`() {
        openPrivacyPolicy(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(ModelsProtocol.PRIVACY_URL, intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `missing browser shows an error instead of crashing`() {
        val withoutBrowser = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        openPrivacyPolicy(withoutBrowser)
        assertEquals(app.getString(R.string.models_no_browser), ShadowToast.getTextOfLatestToast())
    }
}
