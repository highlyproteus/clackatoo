// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import helium314.keyboard.clackatoo.GlassBacking
import helium314.keyboard.clackatoo.ClackatooStyle
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * The panel that covers the keyboard keys while voice typing records and while the grammar check works or shows its
 * result. It has no logic of its own: [Listener] gets the button presses.
 */
class AssistPanelView(context: Context) : FrameLayout(context) {
    interface Listener {
        fun onStop()
        fun onCancel()
        fun onAccept()
        fun onClose()
        fun onOpenSettings()
        /** The panel was hidden, or it or a view above it was hidden or detached (the keyboard switched to emoji, clipboard, toolbar-only mode...). */
        fun onPanelUnavailable()
        /** "Download" in the needs-a-model sheet, with the ids of the models that are ticked. */
        fun onSheetDownload(ids: List<String>) {}
        /** "Cancel" while the sheet's downloads run: stop them all. */
        fun onSheetCancel() {}
    }

    /** One model offered by the needs-a-model sheet. */
    class SheetItem(val id: String, val label: String, val sizeBytes: Long)

    /** How the sheet's downloads are going. */
    enum class SheetProgress { RUNNING, DONE, FAILED }

    var listener: Listener? = null

    private val title = TextView(context)
    private val meter = LevelMeterView(context)
    /** The level meter with the live text (Parakeet) below it. */
    private val listeningBox = LinearLayout(context)
    private val liveText = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val message = TextView(context)
    private val previewScroll = ScrollView(context)
    private val preview = TextView(context)
    private val buttons = LinearLayout(context)
    /** What the panel shows between its title and its buttons. */
    private val body = FrameLayout(context)
    private val content = LinearLayout(context)
    /** The needs-a-model sheet: a description, one row per model (tick box, name and size, later its progress) and the total. */
    private val sheetScroll = ScrollView(context)
    private val sheetBox = LinearLayout(context)
    private val sheetDescription = TextView(context)
    private val sheetRows = LinearLayout(context)
    private val sheetTotal = TextView(context)
    private val sheetChecks = LinkedHashMap<String, CheckBox>()
    private val sheetStatus = LinkedHashMap<String, TextView>()

    init {
        visibility = GONE
        isClickable = true // swallow touches so the keys underneath do not react
        isFocusable = false
        content.orientation = LinearLayout.VERTICAL
        val pad = dp(12)
        content.setPadding(pad, dp(8), pad, pad)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        content.addView(title, LinearLayout.LayoutParams(-2, -2))

        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(8) })
        listeningBox.orientation = LinearLayout.VERTICAL
        listeningBox.addView(meter, LinearLayout.LayoutParams(-1, 0, 1f))
        liveText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        liveText.maxLines = LIVE_TEXT_LINES
        liveText.ellipsize = TextUtils.TruncateAt.START
        liveText.visibility = GONE
        listeningBox.addView(liveText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(listeningBox, LayoutParams(-1, -1))
        progress.isIndeterminate = true
        body.addView(progress, LayoutParams(-1, dp(6), Gravity.CENTER_VERTICAL))
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        message.gravity = Gravity.CENTER_VERTICAL
        body.addView(message, LayoutParams(-1, -1))
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        preview.setTextIsSelectable(false)
        previewScroll.addView(preview, ViewGroup.LayoutParams(-1, -2))
        body.addView(previewScroll, LayoutParams(-1, -1))
        sheetBox.orientation = LinearLayout.VERTICAL
        sheetDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        sheetRows.orientation = LinearLayout.VERTICAL
        sheetTotal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        sheetBox.addView(sheetDescription, LinearLayout.LayoutParams(-1, -2))
        sheetBox.addView(sheetRows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        sheetBox.addView(sheetTotal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        sheetScroll.addView(sheetBox, ViewGroup.LayoutParams(-1, -2))
        body.addView(sheetScroll, LayoutParams(-1, -1))

        buttons.orientation = LinearLayout.HORIZONTAL
        content.addView(buttons, LinearLayout.LayoutParams(-1, dp(48)))
    }

    /** Exactly as high as the keys it covers, so showing the panel never changes the keyboard's height. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keys = (parent as? ViewGroup)?.findViewById<View>(R.id.keyboard_view)
        val height = keys?.measuredHeight?.takeIf { it > 0 } ?: dp(240)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private val colors get() = Settings.getValues().mColors
    private val textColor get() = colors.get(ColorType.KEY_TEXT)
    /** the accent: what is going on right now (the level, the progress, the button that does it) */
    private val accent get() = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND)
    private val keyColor get() = colors.get(ColorType.KEY_BACKGROUND)
    private val borderColor get() = colors.keyBorder

    private fun styleForTheme() {
        val glass = colors.translucentWindow
        if (glass) {
            // clear glass, with the keys underneath hidden (see keysHidden); the title and the body carry text, so each has a frosted backing
            background = null
            GlassBacking.apply(title, colors)
            title.setPadding(dp(12), dp(5), dp(12), dp(5))
            GlassBacking.apply(body, colors)
            body.setPadding(dp(12), dp(8), dp(12), dp(8))
        } else {
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            title.background = null
            title.setPadding(0, 0, 0, 0)
            body.background = null
            body.setPadding(0, 0, 0, 0)
        }
        keysHidden = glass
        title.setTextColor(textColor)
        message.setTextColor(textColor)
        preview.setTextColor(textColor)
        liveText.setTextColor(textColor)
        sheetDescription.setTextColor(textColor)
        sheetTotal.setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        for (box in sheetChecks.values) {
            box.setTextColor(textColor)
            box.buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        for (status in sheetStatus.values) status.setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        meter.color = accent
        progress.indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        // the keyboard's font: Clackatoo's Inter (Medium for the title), or the font the user picked
        title.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT_BOLD)
        message.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
        preview.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
        liveText.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val radius = ClackatooStyle.CORNER_RADIUS_DP * resources.displayMetrics.density
        val b = TextView(context)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.isClickable = true
        b.isFocusable = false
        b.contentDescription = label
        // the main button looks like the Enter key, the others like letter keys
        b.setTextColor(if (primary) colors.get(ColorType.ACTION_KEY_ICON) else textColor)
        val actionFill = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        b.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (primary) actionFill else keyColor)
            setStroke(ClackatooStyle.BORDER_WIDTH_PX.toInt(), if (primary) accent else borderColor)
        }
        b.typeface = KeyboardTypeface.resolve(label, Typeface.DEFAULT)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun setButtons(vararg items: TextView) {
        buttons.removeAllViews()
        items.forEachIndexed { i, b ->
            buttons.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun show(
        titleText: CharSequence, showMeter: Boolean, showProgress: Boolean, messageText: CharSequence?, showPreview: Boolean,
        showSheet: Boolean = false,
    ) {
        styleForTheme()
        // keep the buttons clear of the navigation bar that the keyboard window draws at its bottom
        val navigationBar = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        content.setPadding(dp(12), dp(8), dp(12), dp(12) + navigationBar)
        title.text = titleText
        listeningBox.visibility = if (showMeter) VISIBLE else GONE
        progress.visibility = if (showProgress) VISIBLE else GONE
        message.visibility = if (messageText != null) VISIBLE else GONE
        message.text = messageText
        previewScroll.visibility = if (showPreview) VISIBLE else GONE
        sheetScroll.visibility = if (showSheet) VISIBLE else GONE
        visibility = VISIBLE
    }

    fun showListening() {
        meter.clear()
        liveText.text = ""
        liveText.visibility = GONE
        show(context.getString(R.string.assist_listening), true, false, null, false)
        setButtons(
            button(context.getString(R.string.assist_cancel), false) { listener?.onCancel() },
            button(context.getString(R.string.assist_stop), true) { listener?.onStop() },
        )
    }

    fun setLevel(level: Float) = meter.push(level)

    /**
     * Live dictation: the text so far, the finished utterances in the label colour and the one still being spoken in
     * the hint colour. It is shown here only; nothing reaches the app until Stop.
     */
    fun showLiveText(committed: String, partial: String) {
        val text = if (committed.isEmpty() || partial.isEmpty()) committed + partial else "$committed $partial"
        // only the tail is laid out: the text can be as long as a whole dictation
        val cut = maxOf(0, text.length - LIVE_TEXT_MAX_CHARS)
        val shown = if (cut == 0) text else text.substring(cut)
        val spannable = SpannableString(shown)
        if (partial.isNotEmpty()) {
            val start = shown.length - minOf(partial.length, shown.length)
            spannable.setSpan(ForegroundColorSpan(colors.get(ColorType.KEY_HINT_TEXT)), start, shown.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        liveText.text = lastLines(spannable)
        liveText.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /**
     * The last [LIVE_TEXT_LINES] lines of [text] with "…" in front when something is cut, so the newest words always show.
     * (A TextView only ellipsizes the start of a single line, so a multi-line text has to be cut here.)
     */
    private fun lastLines(text: Spannable): CharSequence {
        val width = liveText.width - liveText.paddingLeft - liveText.paddingRight
        if (width <= 0 || text.isEmpty()) return text
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, liveText.paint, width).build()
        if (layout.lineCount <= LIVE_TEXT_LINES) return text
        val start = layout.getLineStart(layout.lineCount - LIVE_TEXT_LINES)
        return SpannableStringBuilder("…").append(text, start, text.length)
    }

    fun showWorking(titleText: CharSequence) {
        show(titleText, false, true, null, false)
        setButtons(button(context.getString(R.string.assist_cancel), false) { listener?.onCancel() })
    }

    /** [changed]: ranges of [corrected] to highlight; null for none. */
    fun showPreview(corrected: String, changed: List<IntRange>?) {
        show(context.getString(R.string.assist_check_result), false, false, null, true)
        val spannable = SpannableString(corrected)
        val highlight = ColorUtils.setAlphaComponent(accent, 90)
        changed?.forEach {
            spannable.setSpan(BackgroundColorSpan(highlight), it.first, it.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), it.first, it.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(textColor), it.first, it.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        preview.text = spannable
        previewScroll.scrollTo(0, 0)
        setButtons(
            button(context.getString(R.string.assist_cancel), false) { listener?.onCancel() },
            button(context.getString(R.string.assist_accept), true) { listener?.onAccept() },
        )
    }

    fun showMessage(titleText: CharSequence, text: CharSequence, offerSettings: Boolean) {
        show(titleText, false, false, text, false)
        val close = button(context.getString(R.string.assist_close), !offerSettings) { listener?.onClose() }
        if (offerSettings) setButtons(close, button(context.getString(R.string.assist_open_settings), true) { listener?.onOpenSettings() })
        else setButtons(close)
    }

    /**
     * The "needs a model" sheet: [items] with a ticked box each, their total size, and Not now / Download. Without the
     * companion app ([companionInstalled] false) the main button reads "Get Clackatoo Models" and opens its download page.
     */
    fun showModelSheet(titleText: CharSequence, description: CharSequence, items: List<SheetItem>, companionInstalled: Boolean) {
        sheetDescription.text = description
        sheetRows.removeAllViews()
        sheetChecks.clear()
        sheetStatus.clear()
        fun updateTotal() {
            val bytes = items.filter { sheetChecks[it.id]?.isChecked == true }.sumOf { it.sizeBytes }
            sheetTotal.text = if (companionInstalled) context.getString(R.string.models_sheet_total, android.text.format.Formatter.formatShortFileSize(context, bytes))
            else context.getString(R.string.models_sheet_companion_hint)
        }
        for (item in items) {
            val box = CheckBox(context)
            box.text = context.getString(R.string.models_sheet_item, item.label, android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes))
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            box.isChecked = true
            box.isFocusable = false
            box.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
            box.setOnCheckedChangeListener { _, _ -> updateTotal() }
            sheetChecks[item.id] = box
            sheetRows.addView(box, LinearLayout.LayoutParams(-1, -2))
        }
        show(titleText, false, false, null, false, showSheet = true)
        updateTotal()
        sheetTotal.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
        sheetDescription.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
        sheetScroll.scrollTo(0, 0)
        setButtons(
            button(context.getString(R.string.models_sheet_not_now), false) { listener?.onClose() },
            button(context.getString(if (companionInstalled) R.string.models_download else R.string.models_sheet_get_companion), true) {
                val ids = sheetChecks.filter { it.value.isChecked }.keys.toList()
                if (companionInstalled && ids.isEmpty()) sheetTotal.text = context.getString(R.string.models_sheet_choose_one)
                else listener?.onSheetDownload(ids.ifEmpty { items.map { it.id } })
            },
        )
    }

    /** The sheet while its downloads run (or after they ended): one status line per model, and buttons that fit [progress]. */
    fun showSheetProgress(titleText: CharSequence, items: List<SheetItem>, statuses: Map<String, String>, progress: SheetProgress) {
        sheetRows.removeAllViews()
        sheetChecks.clear()
        sheetStatus.clear()
        for (item in items) {
            val label = TextView(context)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            label.setTextColor(textColor)
            label.text = item.label
            label.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
            val status = TextView(context)
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            status.text = statuses[item.id].orEmpty()
            status.typeface = KeyboardTypeface.resolve(null, Typeface.DEFAULT)
            sheetStatus[item.id] = status
            sheetRows.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            sheetRows.addView(status, LinearLayout.LayoutParams(-1, -2))
        }
        sheetDescription.text = ""
        sheetTotal.text = if (progress == SheetProgress.DONE) context.getString(R.string.models_sheet_done) else ""
        show(titleText, false, false, null, false, showSheet = true)
        val close = button(context.getString(R.string.assist_close), progress == SheetProgress.DONE) { listener?.onClose() }
        when (progress) {
            SheetProgress.RUNNING -> setButtons(button(context.getString(R.string.assist_cancel), false) { listener?.onSheetCancel() }, close)
            SheetProgress.DONE -> setButtons(close)
            SheetProgress.FAILED -> setButtons(close, button(context.getString(R.string.models_download), true) { listener?.onSheetDownload(items.map { it.id }) })
        }
    }

    /** Set while the listener is told that the panel is gone: the view may still have its window info and parents then. */
    private var beingHidden = false

    /**
     * Whether the panel would be on screen if it were shown: attached to a visible window, and neither it (once it is
     * shown) nor any view above it is hidden or has no area (the key wrapper is hidden in toolbar-only mode, the whole
     * keyboard frame while emoji or the clipboard show). A GONE panel has no layout of its own yet, so its own size
     * only counts while it is visible.
     */
    fun canBeSeen(): Boolean {
        if (!isAttachedToWindow || beingHidden || windowVisibility != VISIBLE) return false
        if (visibility == VISIBLE && !hasArea(this)) return false
        var view = parent
        while (view is View) {
            if (view.visibility != VISIBLE || !hasArea(view)) return false
            view = view.parent
        }
        return true
    }

    /**
     * True only in the state of a panel that was made visible a moment ago and is waiting for its first layout pass (slow
     * right after the keyboard's process started): the panel itself has no area, a layout is pending for it, and everything
     * above it is attached, VISIBLE and has area, in a shown window. A view above the panel that has no area, a finished layout
     * that gave the panel no size (one-handed mode), or anything hidden is not this state.
     */
    fun isVisibleAwaitingLayout(): Boolean {
        if (!isAttachedToWindow || beingHidden || windowVisibility != VISIBLE || visibility != VISIBLE) return false
        if (hasArea(this) || !(isLayoutRequested || !isLaidOut)) return false
        var view = parent
        while (view is View) {
            if (view.visibility != VISIBLE || !hasArea(view)) return false
            view = view.parent
        }
        return true
    }

    /** The panel is visible and [canBeSeen]: what a recording needs to go on. */
    fun isOnScreen(): Boolean = visibility == VISIBLE && canBeSeen()

    private fun hasArea(view: View) = view.width > 0 && view.height > 0

    // Whenever the panel or anything above it loses its visibility, or the panel leaves the window, the controller
    // is told, so that it can stop a recording that nobody can see.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            if (changedView === this) keysHidden = false
            notifyUnavailable()
        }
    }

    // also called, with GONE, while the panel is being detached from its window
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) notifyUnavailable()
    }

    override fun onDetachedFromWindow() {
        keysHidden = false
        super.onDetachedFromWindow()
        notifyUnavailable()
    }

    private fun notifyUnavailable() {
        beingHidden = true // the view still has its window info and parents while it is told it lost them
        try {
            listener?.onPanelUnavailable()
        } finally {
            beingHidden = false
        }
    }

    /**
     * In the glass theme the panel is clear, so the keys under it must not show through: they are made invisible (not hidden: the
     * keyboard's blur and its touch region follow the keys being shown) while the panel is up.
     */
    private var keysHidden = false
        set(value) {
            field = value
            (parent as? ViewGroup)?.findViewById<View>(R.id.keyboard_view)?.alpha = if (value) 0f else 1f
        }

    fun hide() {
        keysHidden = false
        visibility = GONE
        sheetRows.removeAllViews()
        sheetChecks.clear()
        sheetStatus.clear()
        preview.text = ""
        liveText.text = ""
        meter.clear()
    }

    val isShowing get() = visibility == VISIBLE

    private companion object {
        const val LIVE_TEXT_LINES = 3
        const val LIVE_TEXT_MAX_CHARS = 1_000
    }

    /** Recent input levels as square bars. */
    private class LevelMeterView(context: Context) : View(context) {
        private val levels = FloatArray(BARS)
        private var next = 0
        private val paint = Paint()
        var color: Int
            get() = paint.color
            set(value) { paint.color = value }

        fun push(level: Float) {
            levels[next] = level
            next = (next + 1) % BARS
            postInvalidateOnAnimation()
        }

        fun clear() {
            levels.fill(0f)
            next = 0
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val slot = width.toFloat() / BARS
            val gap = slot * 0.3f
            val minHeight = height * 0.04f
            for (i in 0 until BARS) {
                val level = levels[(next + i) % BARS]
                val h = minHeight + (height - minHeight) * level
                val left = i * slot + gap / 2
                canvas.drawRect(left, (height - h) / 2, left + slot - gap, (height + h) / 2, paint)
            }
        }

        private companion object {
            const val BARS = 40
        }
    }
}
