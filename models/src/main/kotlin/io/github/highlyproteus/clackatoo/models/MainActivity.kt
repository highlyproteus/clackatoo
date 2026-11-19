// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var ui: Ui
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ui.body(getString(R.string.intro)).apply { setPadding(0, 0, 0, ui.dp(16)) })
        val actions = ui.panel()
        actions.addView(ui.row(getString(R.string.open_clackatoo), null) { openKeyboard() })
        actions.addView(ui.row(getString(R.string.browse_huggingface), null) { startActivity(Intent(this, BrowseActivity::class.java)) })
        actions.addView(ui.row(getString(R.string.licences), null) { showLicences() })
        actions.addView(ui.row(getString(R.string.privacy_policy), null) { openPrivacyPolicy() })
        root.addView(actions)
        root.addView(ui.header(getString(R.string.downloads)))
        empty = ui.body(getString(R.string.empty))
        root.addView(empty)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ui.page(root))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            root.addView(ui.body(getString(R.string.notification_permission_hint), 13f).apply { setPadding(0, ui.dp(16), 0, 0) })
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
            val card = ui.panel()
            card.addView(ui.row(title, describe(p), null))
            if (p.phase == Progress.Phase.DOWNLOADING || p.phase == Progress.Phase.VERIFYING) {
                val permille = if (p.total > 0) (p.bytes * 1000 / p.total).toInt() else 0
                card.addView(ui.progress(permille), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = ui.dp(16)
                    rightMargin = ui.dp(16)
                    bottomMargin = ui.dp(12)
                })
            }
            if (p.phase == Progress.Phase.DOWNLOADING || p.phase == Progress.Phase.QUEUED) {
                card.addView(ui.textButton(getString(R.string.cancel)) { cancel(p.id) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    rightMargin = ui.dp(4)
                })
            }
            list.addView(card)
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
                NO_ANSWER -> R.string.failed_no_answer
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

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelsProtocol.PRIVACY_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicences() {
        val notices = Catalog.all.joinToString("\n\n") {
            getString(R.string.licence_entry, it.displayName, it.maker, it.licence, it.url.substringBefore("/resolve/"))
        }
        AlertDialog.Builder(this).setTitle(R.string.licences).setMessage(notices)
            .setPositiveButton(R.string.close, null).show()
    }

    private companion object {
        /** `Progress.reason` when the keyboard gave no answer to the hand-over (see `KeyboardLink.readHandoverReply`). */
        const val NO_ANSWER = -1
    }
}
