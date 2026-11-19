// SPDX-License-Identifier: GPL-3.0-only
package io.github.highlyproteus.clackatoo.models

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    @Test fun `main screen privacy row opens the shared policy in the browser`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val row = findClickable(activity.findViewById(android.R.id.content), activity.getString(R.string.privacy_policy))
            assertNotNull(row).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(ModelsProtocol.PRIVACY_URL, intent.dataString)
        }
    }

    /** The view that reacts to a tap on the text [title]: the text itself or the row around it. */
    private fun findClickable(view: View, title: String): View? {
        if (view is TextView && view.text.toString() == title)
            return generateSequence(view as View) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findClickable(view.getChildAt(i), title)?.let { return it }
        }
        return null
    }
}
