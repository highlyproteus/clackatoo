// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recording panel and the controller on a real view tree: window > frame (the keyboard frame) > wrapper (the key
 * wrapper, hidden in toolbar-only mode) > panel. Nothing may record, or go on recording, behind a panel nobody can see.
 */
@RunWith(RobolectricTestRunner::class)
class PanelVisibilityTest {
    private class Unavailable : AssistPanelView.Listener {
        var calls = 0
        /** What canBeSeen() said inside the callback. */
        val seenInside = ArrayList<Boolean>()
        var panel: AssistPanelView? = null
        override fun onStop() {}
        override fun onCancel() {}
        override fun onAccept() {}
        override fun onClose() {}
        override fun onOpenSettings() {}
        override fun onPanelUnavailable() {
            calls++
            seenInside += panel?.canBeSeen() == true
        }
    }

    private class FakeRecorder(val onLevel: (Float) -> Unit) : VoiceRecorder {
        var started = 0
        var stopped = 0
        var cancelled = 0
        var startResult = true
        override fun start() = startResult.also { if (it) started++ }
        override fun stop(): ShortArray { stopped++; return ShortArray(16_000) { 5000 } }
        override fun cancel() { cancelled++ }
    }

    private lateinit var activity: Activity
    private lateinit var frame: FrameLayout
    private lateinit var wrapper: FrameLayout
    private lateinit var panel: AssistPanelView

    private val width = 400
    private val height = 300

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<App>()
        Settings.getInstance().loadSettings(app)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        frame = FrameLayout(activity)
        wrapper = FrameLayout(activity)
        panel = AssistPanelView(activity)
        frame.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        wrapper.addView(panel, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(frame, ViewGroup.LayoutParams(width, height))
        idle()
        setWindowVisibility(View.VISIBLE)
        layOut()
    }

    /**
     * Robolectric's window never reports itself as visible (the visibility a real window's root view hands to its views);
     * this does what the root view does when a window is shown or hidden.
     */
    private fun setWindowVisibility(visibility: Int) {
        val decor = activity.window.decorView
        val attachInfo = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", visibility)
        decor.dispatchWindowVisibilityChanged(visibility)
    }

    @After fun tearDown() {
        activity.finish()
    }

    /** Measures and lays out the whole window now, at [width] x [height]. */
    private fun layOut() {
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
        layOutFrame(width, height) // also when the frame was laid out differently before (measure results are cached)
    }

    /** Measures and lays out the keyboard frame and everything below it at [w] x [h], leaving the views above as they are. */
    private fun layOutFrame(w: Int, h: Int) {
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun controller(recorders: MutableList<FakeRecorder>, ime: LatinIME = mock(LatinIME::class.java)): AssistController {
        val controller = AssistController(ime)
        controller.recorderFactory = { onLevel, _, _ -> FakeRecorder(onLevel).also { recorders += it } }
        controller.attachPanel(panel)
        return controller
    }

    // ---- the panel on its own

    @Test fun `an attached panel in visible parents can be seen, also while it is still hidden itself`() {
        assertTrue(panel.isAttachedToWindow)
        assertFalse(panel.isShowing)
        assertTrue(panel.canBeSeen()) // GONE itself: it has no layout yet, only the views above it count
        assertFalse(panel.isOnScreen())
    }

    @Test fun `an ancestor set to GONE hides the panel and tells the listener`() {
        val listener = Unavailable().also { it.panel = panel }
        panel.listener = listener
        panel.showListening()
        layOut()
        assertTrue(panel.canBeSeen())
        assertTrue(panel.isOnScreen())
        wrapper.visibility = View.GONE
        assertFalse(panel.canBeSeen())
        assertFalse(panel.isOnScreen())
        assertTrue(listener.calls >= 1, "calls=${listener.calls}")
        assertTrue(listener.seenInside.all { !it }, "canBeSeen inside the callback: ${listener.seenInside}")
    }

    @Test fun `a higher ancestor set to GONE or INVISIBLE hides the panel too`() {
        val listener = Unavailable().also { it.panel = panel }
        panel.listener = listener
        panel.showListening()
        layOut()
        frame.visibility = View.INVISIBLE
        assertFalse(panel.canBeSeen())
        assertTrue(listener.calls >= 1)
        frame.visibility = View.VISIBLE
        assertTrue(panel.canBeSeen())
        frame.visibility = View.GONE
        assertFalse(panel.canBeSeen())
    }

    @Test fun `a panel taken out of its parent can not be seen and the listener is told`() {
        val listener = Unavailable().also { it.panel = panel }
        panel.listener = listener
        panel.showListening()
        layOut()
        wrapper.removeView(panel)
        assertFalse(panel.isAttachedToWindow)
        assertFalse(panel.canBeSeen())
        assertTrue(listener.calls >= 1)
        assertTrue(listener.seenInside.all { !it }, "canBeSeen inside the callback: ${listener.seenInside}")
    }

    @Test fun `a panel whose whole tree leaves the window can not be seen and the listener is told`() {
        val listener = Unavailable().also { it.panel = panel }
        panel.listener = listener
        panel.showListening()
        layOut()
        activity.setContentView(View(activity)) // the panel keeps its parents, but the window loses all of them
        assertFalse(panel.isAttachedToWindow)
        assertFalse(panel.canBeSeen())
        assertTrue(listener.calls >= 1)
        assertTrue(listener.seenInside.all { !it }, "canBeSeen inside the callback: ${listener.seenInside}")
    }

    @Test fun `a panel in a window that is not shown can not be seen`() {
        val listener = Unavailable().also { it.panel = panel }
        panel.listener = listener
        panel.showListening()
        layOut()
        val before = listener.calls
        setWindowVisibility(View.GONE)
        assertFalse(panel.canBeSeen())
        assertTrue(listener.calls > before)
        setWindowVisibility(View.VISIBLE)
        assertTrue(panel.canBeSeen())
    }

    @Test fun `zero width or zero height means nothing can be seen`() {
        panel.showListening()
        layOut()
        assertTrue(panel.canBeSeen())
        // an ancestor without area
        layOutFrame(0, height)
        assertFalse(panel.canBeSeen(), "zero width")
        layOutFrame(width, 0)
        assertFalse(panel.canBeSeen(), "zero height")
        layOut()
        assertTrue(panel.canBeSeen())
        wrapper.layout(0, 0, width, 0)
        assertFalse(panel.canBeSeen(), "wrapper without height")
        layOut()
        // the shown panel itself without area
        panel.layout(0, 0, 0, height)
        assertFalse(panel.canBeSeen(), "panel without width")
        panel.layout(0, 0, width, 0)
        assertFalse(panel.canBeSeen(), "panel without height")
        assertFalse(panel.isOnScreen())
    }

    // ---- the controller: a recording ends when its panel can no longer be seen

    @Test fun `recording starts when the panel can be seen`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        assertEquals(AssistController.ListeningStart.STARTED, controller.startListening(panel))
        assertEquals(1, recorders.size)
        assertEquals(1, recorders[0].started)
        assertTrue(controller.isListening)
        assertTrue(panel.isShowing)
    }

    @Test fun `recording never starts behind a hidden ancestor`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        wrapper.visibility = View.GONE
        assertEquals(AssistController.ListeningStart.PANEL_HIDDEN, controller.startListening(panel))
        assertEquals(0, recorders.size, "the microphone must not even be asked")
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
    }

    @Test fun `recording never starts in a panel without area`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        layOutFrame(0, 0)
        assertEquals(AssistController.ListeningStart.PANEL_HIDDEN, controller.startListening(panel))
        assertEquals(0, recorders.size)
        assertTrue(controller.isIdle)
    }

    @Test fun `a microphone that does not open leaves nothing listening`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.recorderFactory = { onLevel, _, _ -> FakeRecorder(onLevel).also { it.startResult = false; recorders += it } }
        assertEquals(AssistController.ListeningStart.MIC_UNAVAILABLE, controller.startListening(panel))
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
    }

    /** After the recording was cancelled: nothing is transcribed or inserted, however late the Stop button or the audio thread comes. */
    private fun assertCancelledForGood(controller: AssistController, recorder: FakeRecorder, ime: LatinIME) {
        assertEquals(1, recorder.cancelled, "the recorder is cancelled (stopped and released) exactly once")
        assertEquals(0, recorder.stopped, "the audio is dropped, never taken")
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
        controller.onStop() // a late press of Stop
        recorder.onLevel(0.8f) // a late level from the audio thread
        idle()
        assertEquals(0, recorder.stopped)
        assertEquals(1, recorder.cancelled)
        assertTrue(controller.isIdle)
        // transcribing and inserting both start by asking the keyboard for the editor and the models: never happened
        verifyNoInteractions(ime)
    }

    @Test fun `hiding an ancestor while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        assertEquals(AssistController.ListeningStart.STARTED, controller.startListening(panel))
        layOut()
        wrapper.visibility = View.GONE
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `hiding the whole keyboard frame while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        controller.startListening(panel)
        layOut()
        frame.visibility = View.GONE // emoji or clipboard take over
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `detaching the panel while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        controller.startListening(panel)
        layOut()
        wrapper.removeView(panel)
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `the panel's tree leaving the window while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        controller.startListening(panel)
        layOut()
        activity.setContentView(View(activity))
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `a panel that shrinks to nothing is noticed by the level check`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        controller.startListening(panel)
        layOut()
        recorders.single().onLevel(0.5f) // the panel is seen on screen at the first level update
        idle()
        // no visibility change, so no callback: only the check on every level update (every 100 ms) can see this
        wrapper.layout(0, 0, width, 0)
        assertTrue(controller.isListening)
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertEquals(0, recorders.single().stopped)
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
    }

    // The bug: on a keyboard that has just started, the first level update (100 ms after the start) can come before the panel's
    // first layout pass. It saw a panel without area, ended the recording and cancelled the live call that was still waiting for
    // the model process to connect: nothing happened, silently.

    /** The state of the bug: the key wrapper above the panel is laid out, the panel itself has no area and a layout is pending for it. */
    private fun panelWaitingForLayout() {
        idle() // let the layout that showing the panel asked for happen first, then take it away again
        panel.layout(0, 0, 0, 0)
        panel.forceLayout() // a layout is pending, but no traversal is scheduled that would run it
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test fun `a panel waiting for its first layout is waited for, not treated as hidden`() {
        val recorders = ArrayList<FakeRecorder>()
        var now = 1_000L
        val controller = controller(recorders)
        controller.elapsedRealtime = { now }
        controller.startListening(panel)
        panelWaitingForLayout()
        assertFalse(panel.isOnScreen())
        assertTrue(panel.isVisibleAwaitingLayout())
        now += 100
        recorders.single().onLevel(0.5f)
        idle()
        assertTrue(controller.isListening)
        assertEquals(0, recorders.single().cancelled)
        // once it is laid out it is seen, and from then on losing its area ends the recording at once
        panel.layout(0, 0, width, height)
        assertTrue(panel.isOnScreen())
        recorders.single().onLevel(0.5f)
        idle()
        assertTrue(controller.isListening)
        wrapper.layout(0, 0, width, 0)
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `a panel that never gets its layout ends the recording after the allowance, on the next level update`() {
        val recorders = ArrayList<FakeRecorder>()
        var now = 1_000L
        val controller = controller(recorders)
        controller.elapsedRealtime = { now }
        controller.startListening(panel)
        panelWaitingForLayout()
        now += AssistController.FIRST_LAYOUT_MS + 1
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `the allowance ends on the clock even when no audio arrives`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        panelWaitingForLayout()
        idleFor(AssistController.FIRST_LAYOUT_MS - 100)
        assertTrue(controller.isListening)
        idleFor(200)
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `the delayed check leaves a panel that was seen alone, and one recording's check never ends the next`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        recorders.last().onLevel(0.5f)
        idle()
        idleFor(AssistController.FIRST_LAYOUT_MS + 100)
        assertTrue(controller.isListening)
        controller.close()
        controller.startListening(panel)
        panelWaitingForLayout()
        idleFor(AssistController.FIRST_LAYOUT_MS - 100) // the first recording's check is long past; this one's is not due yet
        assertTrue(controller.isListening)
    }

    @Test fun `a key wrapper that collapses before the panel was seen ends the recording at once`() {
        val recorders = ArrayList<FakeRecorder>()
        var now = 1_000L
        val controller = controller(recorders)
        controller.elapsedRealtime = { now }
        controller.startListening(panel)
        idle()
        wrapper.layout(0, 0, width, 0) // laid out without area: the panel below it has none either
        assertFalse(panel.isVisibleAwaitingLayout())
        now += 100
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `a finished layout that gives the panel no size ends the recording at once`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        idle()
        panel.layout(0, 0, 0, 0) // laid out, no layout pending
        assertFalse(panel.isLayoutRequested)
        assertFalse(panel.isVisibleAwaitingLayout())
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `a panel that is hidden, not merely unlaid, ends the recording at once even inside the allowance`() {
        val recorders = ArrayList<FakeRecorder>()
        var now = 1_000L
        val controller = controller(recorders)
        controller.elapsedRealtime = { now }
        controller.startListening(panel)
        panelWaitingForLayout()
        wrapper.visibility = View.GONE
        idle()
        now += 100
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `stopping a recording whose panel was never seen drops it instead of transcribing it`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        panelWaitingForLayout()
        controller.onStop()
        assertEquals(1, recorders.single().cancelled)
        assertEquals(0, recorders.single().stopped)
        assertTrue(controller.isIdle)
    }

    @Test fun `a panel that stays on screen keeps recording and shows the level`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        layOut()
        recorders.single().onLevel(0.5f)
        idle()
        assertTrue(controller.isListening)
        assertEquals(0, recorders.single().cancelled)
        assertTrue(panel.isShowing)
    }

    @Test fun `showing the wrapper again does not bring a cancelled recording back`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel)
        layOut()
        wrapper.visibility = View.GONE
        wrapper.visibility = View.VISIBLE
        layOut()
        assertTrue(controller.isIdle)
        assertEquals(1, recorders.single().cancelled)
        assertFalse(panel.isShowing)
    }
}
