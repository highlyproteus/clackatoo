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
        // a panel that was shown and hidden before keeps the size of its last layout while it is GONE
        panel.visibility = View.VISIBLE
        layOut()
        panel.hide()
        idle()
        assertTrue(panel.width > 0 && panel.height > 0, "the hidden panel keeps its old size")
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

    // ---- the controller: the microphone opens only once the panel is on screen, and a recording ends when it no longer is

    /** Asks for a recording and lets the window lay out and draw; returns the outcome, or null while the start is still pending. */
    private fun start(controller: AssistController, live: (() -> LiveCall)? = null): AssistController.ListeningStart? {
        var result: AssistController.ListeningStart? = null
        controller.startListening(panel, newLiveCall = live) { result = it }
        idle()
        return result
    }

    /** Keeps the key wrapper, and so the panel below it, at zero height from now on: the panel can never be on screen. */
    private fun keepPanelOffScreen() {
        wrapper.layoutParams = FrameLayout.LayoutParams(-1, 0)
        wrapper.layout(0, 0, width, 0)
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test fun `recording starts when the panel can be seen`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        assertEquals(AssistController.ListeningStart.STARTED, start(controller))
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
        assertEquals(AssistController.ListeningStart.PANEL_HIDDEN, start(controller))
        assertEquals(0, recorders.size, "the microphone must not even be asked")
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
    }

    @Test fun `recording never starts in a panel without area`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        layOutFrame(0, 0)
        assertEquals(AssistController.ListeningStart.PANEL_HIDDEN, start(controller))
        assertEquals(0, recorders.size)
        assertTrue(controller.isIdle)
    }

    @Test fun `a microphone that does not open leaves nothing listening`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.recorderFactory = { onLevel, _, _ -> FakeRecorder(onLevel).also { it.startResult = false; recorders += it } }
        assertEquals(AssistController.ListeningStart.MIC_UNAVAILABLE, start(controller))
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
        assertEquals(AssistController.ListeningStart.STARTED, start(controller))
        layOut()
        wrapper.visibility = View.GONE
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `hiding the whole keyboard frame while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        start(controller)
        layOut()
        frame.visibility = View.GONE // emoji or clipboard take over
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `detaching the panel while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        start(controller)
        layOut()
        wrapper.removeView(panel)
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `the panel's tree leaving the window while recording cancels the recording`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        start(controller)
        layOut()
        activity.setContentView(View(activity))
        assertCancelledForGood(controller, recorders.single(), ime)
    }

    @Test fun `a panel that shrinks to nothing is noticed by the level check`() {
        val recorders = ArrayList<FakeRecorder>()
        val ime = mock(LatinIME::class.java)
        val controller = controller(recorders, ime)
        start(controller)
        layOut()
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

    @Test fun `a panel that stays on screen keeps recording and shows the level`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        start(controller)
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
        start(controller)
        layOut()
        wrapper.visibility = View.GONE
        wrapper.visibility = View.VISIBLE
        layOut()
        assertTrue(controller.isIdle)
        assertEquals(1, recorders.single().cancelled)
        assertFalse(panel.isShowing)
    }

    // ---- STARTING: the microphone stays closed until the panel is on screen

    @Test fun `the microphone is not opened before the panel is on screen`() {
        val recorders = ArrayList<FakeRecorder>()
        var lives = 0
        val controller = controller(recorders)
        var result: AssistController.ListeningStart? = null
        controller.startListening(panel, newLiveCall = { lives++; throw IllegalStateException("no live call before the microphone") }) { result = it }
        // the panel was made visible but has not been laid out or drawn yet
        assertTrue(controller.isStarting)
        assertEquals(0, recorders.size, "the recorder is not even created")
        assertEquals(0, lives)
        assertEquals(null, result)
        assertTrue(panel.isShowing)
    }

    @Test fun `the microphone opens once, as soon as the panel is on screen`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        var result: AssistController.ListeningStart? = null
        var results = 0
        controller.startListening(panel) { result = it; results++ }
        assertEquals(0, recorders.size)
        idle() // the panel's first layout and draw
        assertEquals(AssistController.ListeningStart.STARTED, result)
        assertEquals(1, results)
        assertEquals(1, recorders.size)
        assertEquals(1, recorders.single().started)
        assertTrue(controller.isListening)
        idle(); idleFor(AssistController.START_TIMEOUT_MS + 100) // the timeout of the start does nothing to a recording
        assertTrue(controller.isListening)
        assertEquals(1, recorders.single().started)
    }

    @Test fun `a live call is created only when the microphone opens`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        var lives = 0
        controller.startListening(panel, newLiveCall = { lives++; LiveCall() }) { }
        assertEquals(0, lives)
        idle()
        assertEquals(1, lives)
        assertEquals(1, recorders.single().started)
    }

    @Test fun `a start whose panel is never on screen is cancelled at the timeout with the microphone never opened`() {
        val recorders = ArrayList<FakeRecorder>()
        var lives = 0
        val controller = controller(recorders)
        var result: AssistController.ListeningStart? = null
        controller.startListening(panel, newLiveCall = { lives++; LiveCall() }) { result = it }
        keepPanelOffScreen()
        idleFor(AssistController.START_TIMEOUT_MS - 100)
        assertTrue(controller.isStarting)
        assertEquals(null, result)
        idleFor(200)
        assertEquals(AssistController.ListeningStart.PANEL_HIDDEN, result)
        assertEquals(0, recorders.size)
        assertEquals(0, lives)
        assertTrue(controller.isIdle)
        assertFalse(panel.isShowing)
        // nothing happens later either
        idleFor(AssistController.START_TIMEOUT_MS)
        assertEquals(0, recorders.size)
    }

    private fun assertNeverOpened(controller: AssistController, recorders: List<FakeRecorder>, lives: () -> Int) {
        keepPanelOffScreen() // had the start survived, nothing but the timeout could have ended it
        idleFor(AssistController.START_TIMEOUT_MS + 200)
        restoreLayout()
        idle()
        assertEquals(0, recorders.size, "the microphone was never asked for")
        assertEquals(0, lives())
        assertTrue(controller.isIdle)
    }

    private fun restoreLayout() {
        wrapper.layoutParams = FrameLayout.LayoutParams(-1, -1)
        layOut()
    }

    @Test fun `every way out of a pending start leaves the microphone closed`() {
        val ways = linkedMapOf<String, (AssistController) -> Unit>(
            "close" to { it.close() },
            "the field, keyboard or language changed" to { it.onSessionChanged() },
            "Stop" to { it.onStop() },
            "Cancel" to { it.onCancel() },
            "the panel is replaced" to { it.attachPanel(AssistPanelView(activity)) },
            "an ancestor is hidden" to { wrapper.visibility = View.GONE },
            "the whole keyboard frame is hidden" to { frame.visibility = View.GONE },
            "the panel is detached" to { wrapper.removeView(panel) },
            "the window content is replaced" to { activity.setContentView(View(activity)) },
        )
        for ((name, way) in ways) {
            tearDownViews(); setUp()
            val recorders = ArrayList<FakeRecorder>()
            var lives = 0
            val controller = controller(recorders)
            controller.startListening(panel, newLiveCall = { lives++; LiveCall() }) { }
            assertTrue(controller.isStarting, name)
            way(controller)
            idle()
            assertTrue(controller.isIdle, name)
            assertEquals(0, recorders.size, name)
            assertEquals(0, lives, name)
            idleFor(AssistController.START_TIMEOUT_MS + 200)
            assertEquals(0, recorders.size, "$name: nor later")
            assertEquals(0, lives, "$name: nor later")
            assertTrue(controller.isIdle, name)
        }
    }

    private fun tearDownViews() { activity.finish() }

    @Test fun `a late layout of a cancelled start does nothing, and does not disturb the next start`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        var first = 0
        controller.startListening(panel) { first++ }
        controller.close() // before any layout happened
        idle() // the layout and draw that the cancelled start was waiting for
        assertEquals(0, recorders.size)
        assertEquals(0, first)
        assertTrue(controller.isIdle)
        var second: AssistController.ListeningStart? = null
        controller.startListening(panel) { second = it }
        idle()
        assertEquals(AssistController.ListeningStart.STARTED, second)
        assertEquals(1, recorders.size)
        assertEquals(0, first, "the first start never reports")
    }

    @Test fun `a microphone that does not open ends the start`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.recorderFactory = { onLevel, _, _ -> FakeRecorder(onLevel).also { it.startResult = false; recorders += it } }
        assertEquals(AssistController.ListeningStart.MIC_UNAVAILABLE, start(controller))
        assertTrue(controller.isIdle)
    }

    @Test fun `a panel that leaves the screen once recording is closed at the first level update, with no allowance`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        assertEquals(AssistController.ListeningStart.STARTED, start(controller))
        wrapper.layout(0, 0, width, 0)
        recorders.single().onLevel(0.5f)
        idle()
        assertEquals(1, recorders.single().cancelled)
        assertTrue(controller.isIdle)
    }

    @Test fun `a second start does not open the microphone on the stale size of the hidden panel`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        assertEquals(AssistController.ListeningStart.STARTED, start(controller))
        assertEquals(1, recorders.size)
        controller.close() // hides the panel: GONE, but it keeps its old width and height
        assertTrue(panel.width > 0 && panel.height > 0)
        var result: AssistController.ListeningStart? = null
        controller.startListening(panel) { result = it }
        // no layout or draw has run yet since the panel was made visible again
        assertTrue(panel.isLayoutRequested)
        assertEquals(1, recorders.size, "no second recorder before the layout")
        assertTrue(controller.isStarting)
        assertEquals(null, result)
        idle()
        assertEquals(2, recorders.size)
        assertEquals(AssistController.ListeningStart.STARTED, result)
        assertFalse(panel.isLayoutRequested, "the microphone opened after the layout")
    }

    @Test fun `many starts in a row each wait for their layout`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        repeat(5) { n ->
            controller.startListening(panel) { }
            assertEquals(n, recorders.size, "start ${n + 1}: nothing before the layout")
            idle()
            assertEquals(n + 1, recorders.size)
            controller.close()
        }
    }

    @Test fun `the window being hidden while a start is pending cancels it, microphone closed`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        controller.startListening(panel) { }
        assertTrue(controller.isStarting)
        setWindowVisibility(View.GONE)
        idle()
        assertTrue(controller.isIdle)
        setWindowVisibility(View.VISIBLE)
        idle(); idleFor(AssistController.START_TIMEOUT_MS + 100)
        assertEquals(0, recorders.size)
    }

    @Test fun `a recording whose panel loses its area ends on the next frame, without waiting for a level update`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        assertEquals(AssistController.ListeningStart.STARTED, start(controller))
        assertTrue(controller.isListening)
        wrapper.layoutParams = FrameLayout.LayoutParams(-1, 0)
        idle() // the next layout and draw: the panel has no area any more
        assertTrue(controller.isIdle)
        assertEquals(1, recorders.single().cancelled)
        assertEquals(0, recorders.single().stopped)
    }

    @Test fun `the frame watch ends with the recording and with every other way out of listening`() {
        val recorders = ArrayList<FakeRecorder>()
        val controller = controller(recorders)
        start(controller)
        controller.close()
        // a later frame without area must not touch a new pending start or anything else
        wrapper.layoutParams = FrameLayout.LayoutParams(-1, 0)
        idle()
        assertTrue(controller.isIdle)
        assertEquals(1, recorders.single().cancelled)
        restoreLayout()
        start(controller)
        assertEquals(2, recorders.size)
        assertTrue(controller.isListening)
    }
}
