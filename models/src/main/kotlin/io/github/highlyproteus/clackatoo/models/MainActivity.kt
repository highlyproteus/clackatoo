// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol

/**
 * The whole screen of this app: what is downloading, the licences of the models, and a button that opens the keyboard's
 * settings. Downloads are started from the keyboard, not here. Plain framework views, no libraries.
 */
class MainActivity : Activity() {
    private val listener = object : DownloadTracker.Listener {
        override fun onChanged(all: List<Progress>) {
            runOnUiThread { render(all) }
        }
    }
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(text(R.string.intro, 14f).apply { setPadding(0, 0, 0, dp(16)) })
        root.addView(button(R.string.open_clackatoo) { openKeyboard() })
        root.addView(button(R.string.browse_huggingface) { startActivity(Intent(this, BrowseActivity::class.java)) })
        root.addView(button(R.string.licences) { showLicences() })
        root.addView(text(R.string.downloads, 18f).apply { setPadding(0, dp(24), 0, dp(8)) })
        empty = text(R.string.empty, 14f)
        root.addView(empty)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            clipToPadding = false
            // edge-to-edge: keep the content out of the system bars
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            root.addView(text(R.string.notification_permission_hint, 13f).apply { setPadding(0, dp(16), 0, 0) })
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadTracker.add(listener)
    }

    override fun onStop() {
        DownloadTracker.remove(listener)
        super.onStop()
    }

    private fun render(all: List<Progress>) {
        list.removeAllViews()
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        for (p in all.asReversed()) {
            val title = p.title ?: Catalog.byId(p.id)?.displayName ?: continue
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply { text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            row.addView(TextView(this).apply { text = describe(p); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
            if (p.phase == Progress.Phase.DOWNLOADING || p.phase == Progress.Phase.VERIFYING) {
                row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (p.total > 0) (p.bytes * 1000 / p.total).toInt() else 0
                })
            }
            if (p.phase == Progress.Phase.DOWNLOADING || p.phase == Progress.Phase.QUEUED) {
                row.addView(button(R.string.cancel) { cancel(p.id) })
            }
            list.addView(row)
        }
    }

    private fun describe(p: Progress): String = when (p.phase) {
        Progress.Phase.QUEUED -> getString(R.string.queued)
        Progress.Phase.DOWNLOADING -> getString(R.string.downloading, Formatter.formatShortFileSize(this, p.bytes), Formatter.formatShortFileSize(this, p.total))
        Progress.Phase.VERIFYING -> getString(R.string.verifying)
        Progress.Phase.HANDING_OVER -> getString(R.string.handing_over)
        Progress.Phase.DONE -> getString(R.string.done)
        Progress.Phase.CANCELLED -> getString(R.string.cancelled)
        Progress.Phase.FAILED -> if (p.reason == ModelsProtocol.HANDOVER_WRONG_ROLE) getString(R.string.failed_wrong_role, roleName(p.role)) else getString(
            when (p.reason) {
                ModelsProtocol.FAIL_SIZE -> R.string.failed_size
                ModelsProtocol.FAIL_DIGEST -> R.string.failed_digest
                ModelsProtocol.FAIL_NO_SPACE -> R.string.failed_space
                ModelsProtocol.FAIL_UNTRUSTED_HOST -> R.string.failed_host
                ModelsProtocol.FAIL_NETWORK -> R.string.failed_network
                ModelsProtocol.FAIL_OTHER -> R.string.failed_other
                // the keyboard's answers to a custom model (5 is never a download failure: a cancel has its own phase)
                ModelsProtocol.HANDOVER_NOT_A_MODEL -> R.string.failed_not_a_model
                ModelsProtocol.HANDOVER_TOO_LARGE -> R.string.failed_too_large
                ModelsProtocol.HANDOVER_CUSTOM_OFF -> R.string.failed_custom_off
                ModelsProtocol.HANDOVER_LOAD_FAILED -> R.string.failed_load
                ModelsProtocol.HANDOVER_TOO_MANY -> R.string.failed_too_many
                else -> R.string.failed_keyboard
            }
        )
    }

    /** The role of a custom download as it reads in a sentence ("That file is not a speech model"). */
    private fun roleName(role: Int): String = getString(
        when (role) {
            ModelsProtocol.CUSTOM_ROLE_SPEECH -> R.string.role_speech
            ModelsProtocol.CUSTOM_ROLE_CLEAN_UP -> R.string.role_clean_up
            else -> R.string.role_grammar
        }
    )

    private fun cancel(id: String) = DownloadService.cancel(id)

    private fun openKeyboard() {
        val intent = packageManager.getLaunchIntentForPackage(BuildConfig.KEYBOARD_APPLICATION_ID) ?: return
        startActivity(intent)
    }

    private fun showLicences() {
        val notices = Catalog.all.joinToString("\n\n") {
            "${it.displayName}\nBy ${it.maker}. Licence: ${it.licence}.\nSource: ${it.url.substringBefore("/resolve/")}"
        }
        AlertDialog.Builder(this).setTitle(R.string.licences).setMessage(notices)
            .setPositiveButton(R.string.close, null).show()
    }

    private fun text(res: Int, sp: Float) = TextView(this).apply { setText(res); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }

    private fun button(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
