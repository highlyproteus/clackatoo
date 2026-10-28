// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import helium314.keyboard.latin.BuildConfig
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelDownloadsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `without the companion app Download opens the releases page in the browser and fetches nothing`() {
        ModelDownloads.init(app)
        assertFalse(ModelDownloads.isCompanionInstalled(app))
        ModelDownloads.download(app, Catalog.WHISPER_TINY.id)
        val opened = shadowOf(app).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(ModelsProtocol.RELEASES_URL, opened.dataString)
        assertNull(shadowOf(app).nextStartedService) // nothing was sent to a companion
        assertEquals(ModelState.NotInstalled, ModelDownloads.state(Catalog.WHISPER_TINY.id).value)
    }

    @Test fun `an unknown id does nothing at all`() {
        ModelDownloads.init(app)
        ModelDownloads.download(app, "https://evil.example/model.bin")
        ModelDownloads.cancel(app, "nope")
        ModelDownloads.delete(app, "nope")
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `a debug build talks to the debug companion and a release build to the release one`() {
        val debug = BuildConfig.APPLICATION_ID.endsWith(".debug")
        assertEquals("io.github.highlyproteus.clackatoo.models" + if (debug) ".debug" else "", ModelDownloads.companionPackage)
        assertEquals(ModelsProtocol.COMPANION_PACKAGE + if (debug) ".debug" else "", ModelDownloads.companionPackage)
    }
}
