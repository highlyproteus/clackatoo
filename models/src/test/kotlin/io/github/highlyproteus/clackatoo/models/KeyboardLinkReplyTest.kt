// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.os.Parcel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** An empty or missing reply reads as 0 = HANDOVER_OK: it must never count as the keyboard's answer. */
@RunWith(RobolectricTestRunner::class)
class KeyboardLinkReplyTest {
    private fun reply(write: (Parcel) -> Unit = {}) = Parcel.obtain().also { write(it) }

    @Test fun `a real answer is read`() {
        assertEquals(ModelsProtocol.HANDOVER_OK, KeyboardLink.readHandoverReply(true, reply { it.writeNoException(); it.writeInt(0) }))
        assertEquals(ModelsProtocol.HANDOVER_LOAD_FAILED, KeyboardLink.readHandoverReply(true, reply { it.writeNoException(); it.writeInt(ModelsProtocol.HANDOVER_LOAD_FAILED) }))
    }

    @Test fun `an empty reply is no answer, not OK`() {
        assertNull(KeyboardLink.readHandoverReply(true, reply()))
    }

    @Test fun `a call the keyboard did not handle is no answer, whatever the parcel holds`() {
        assertNull(KeyboardLink.readHandoverReply(false, reply()))
        assertNull(KeyboardLink.readHandoverReply(false, reply { it.writeNoException(); it.writeInt(0) }))
    }

    @Test fun `an exception marker without a code is no answer`() {
        assertNull(KeyboardLink.readHandoverReply(true, reply { it.writeNoException() }))
    }

    @Test fun `a security exception from the keyboard is thrown for the caller to treat as a refusal`() {
        assertFailsWith<SecurityException> { KeyboardLink.readHandoverReply(true, reply { it.writeException(SecurityException("no")) }) }
    }
}
