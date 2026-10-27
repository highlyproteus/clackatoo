// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import helium314.keyboard.compat.ClipboardManagerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardPolicyTest {
    @Test fun `a clip marked sensitive is not stored`() {
        assertFalse(ClipboardPolicy.mayStore(true))
    }

    @Test fun `a clip not marked, or that the phone can't judge, is stored`() {
        assertTrue(ClipboardPolicy.mayStore(false))
        assertTrue(ClipboardPolicy.mayStore(null))
    }

    @Test fun `the IS_SENSITIVE extra of a real clip decides`() {
        val marked = ClipDescription("password", arrayOf("text/plain")).apply {
            extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        val plain = ClipData.newPlainText("note", "hello").description
        assertFalse(ClipboardPolicy.mayStore(ClipboardManagerCompat.getClipSensitivity(marked)))
        assertTrue(ClipboardPolicy.mayStore(ClipboardManagerCompat.getClipSensitivity(plain)))
    }
}
