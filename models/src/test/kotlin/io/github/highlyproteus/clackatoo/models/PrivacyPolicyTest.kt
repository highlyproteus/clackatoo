// SPDX-License-Identifier: GPL-3.0-only
package io.github.highlyproteus.clackatoo.models

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PrivacyPolicyTest {
    @Test fun `main screen privacy button opens the shared policy in the browser`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val button = findButton(activity.findViewById(android.R.id.content), activity.getString(R.string.privacy_policy))
            assertNotNull(button).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(ModelsProtocol.PRIVACY_URL, intent.dataString)
        }
    }

    private fun findButton(view: View, title: String): Button? {
        if (view is Button && view.text.toString() == title) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findButton(view.getChildAt(i), title)?.let { return it }
        }
        return null
    }
}
