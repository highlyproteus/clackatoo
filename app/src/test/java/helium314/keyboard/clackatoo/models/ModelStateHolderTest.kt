// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelStateHolderTest {
    private val installed = HashSet<String>()
    private val holder = ModelStateHolder { it in installed }

    @Test fun `a model starts as installed only when its file is there`() {
        installed += "a"
        assertEquals(ModelState.Installed, holder.state("a").value)
        assertEquals(ModelState.NotInstalled, holder.state("b").value)
    }

    @Test fun `download goes from requested through progress and installing to installed`() {
        holder.requested("a", 100)
        assertEquals(ModelState.Downloading(0, 100), holder.state("a").value)
        holder.progress("a", 40, 100)
        assertEquals(ModelState.Downloading(40, 100), holder.state("a").value)
        holder.installing("a")
        assertEquals(ModelState.Installing, holder.state("a").value)
        holder.installed("a")
        assertEquals(ModelState.Installed, holder.state("a").value)
    }

    @Test fun `late progress never undoes installing or installed`() {
        holder.installing("a")
        holder.progress("a", 10, 100)
        assertEquals(ModelState.Installing, holder.state("a").value)
        holder.installed("a")
        holder.progress("a", 10, 100)
        assertEquals(ModelState.Installed, holder.state("a").value)
    }

    @Test fun `progress is clamped to the total and a restarted keyboard picks up a running download`() {
        holder.progress("a", 500, 100)
        assertEquals(ModelState.Downloading(100, 100), holder.state("a").value)
        holder.progress("b", -5, 100)
        assertEquals(ModelState.Downloading(0, 100), holder.state("b").value)
    }

    @Test fun `cancelling returns to not installed but never undoes a finished install`() {
        holder.requested("a", 100)
        holder.cancelled("a")
        assertEquals(ModelState.NotInstalled, holder.state("a").value)
        holder.installing("b")
        holder.cancelled("b")
        assertEquals(ModelState.Installing, holder.state("b").value)
    }

    @Test fun `a failure keeps its message and a new request replaces it`() {
        holder.requested("a", 100)
        holder.failed("a", "no network")
        assertEquals(ModelState.Failed("no network"), holder.state("a").value)
        holder.requested("a", 100)
        assertEquals(ModelState.Downloading(0, 100), holder.state("a").value)
    }

    @Test fun `refresh follows the files but leaves running downloads alone`() {
        holder.requested("a", 100)
        installed += "a"
        holder.refresh(listOf("a"))
        assertTrue(holder.state("a").value is ModelState.Downloading)
        holder.failed("a", "x")
        holder.refresh(listOf("a"))
        assertEquals(ModelState.Installed, holder.state("a").value)
        installed -= "a" // deleted from outside
        holder.refresh(listOf("a"))
        assertEquals(ModelState.NotInstalled, holder.state("a").value)
    }

    @Test fun `the activity callback fires for requests and progress`() {
        val seen = ArrayList<String>()
        holder.onActivity = { seen += it }
        holder.requested("a", 10)
        holder.progress("a", 1, 10)
        assertEquals(listOf("a", "a"), seen)
    }
}
