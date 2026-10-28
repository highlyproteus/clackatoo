// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.text.NumberFormat
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * "Browse Hugging Face": the user types a search term or a repository id, sees repositories and then their model files
 * (`.gguf` and `.bin`, with their sizes), picks a file and what it is for, and the download service fetches it and hands
 * it to the keyboard as a custom model. Everything shown from the server passed [HuggingFace]'s checks first; all
 * requests go through [HfApi] and [DownloadPolicy] on a background thread. Not exported: only this app's own screen
 * starts a custom download (the keyboard never sends an address, a query or anything else).
 */
class BrowseActivity : Activity() {
    private val api = HfApi()
    private val executor = Executors.newSingleThreadExecutor()
    private var request = 0 // the number of the latest request; an older answer that arrives late is dropped

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var confirmation: AlertDialog? = null

    private var direct: RepoId? = null
    private var hits: List<RepoHit> = emptyList()
    private var open: Pair<RepoId, RepoFiles>? = null
    private var busy = false
    private var error: ApiError? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.browse_title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(text(R.string.browse_warning, 14f).apply { setPadding(0, 0, 0, dp(16)) })

        input = EditText(this).apply {
            setHint(R.string.browse_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(HuggingFace.MAX_QUERY_LENGTH))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
            }
        }
        root.addView(input)
        root.addView(button(R.string.browse_search) { search() })
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)

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
        render()
        if (!BrowsePrefs.confirmed(this)) showConfirmation()
    }

    override fun onDestroy() {
        request++ // nothing that is still on its way is shown any more
        executor.shutdownNow()
        confirmation?.dismiss()
        super.onDestroy()
    }

    /** The warning, once: "I understand" is remembered, "Cancel" closes the screen. */
    private fun showConfirmation() {
        confirmation = AlertDialog.Builder(this)
            .setTitle(R.string.browse_confirm_title)
            .setMessage(R.string.browse_confirm_message)
            .setCancelable(false)
            .setPositiveButton(R.string.browse_confirm_ok) { _, _ -> BrowsePrefs.setConfirmed(this) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    // ---------------------------------------------------------------- requests

    private fun search() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        val mine = ++request
        direct = RepoId.parse(query)
        hits = emptyList()
        open = null
        error = null
        busy = true
        render()
        background(mine, { api.search(query) }) { result ->
            when (result) {
                is ApiResult.Ok -> hits = result.value
                is ApiResult.Failed -> error = result.error
            }
        }
    }

    private fun openRepo(repo: RepoId) {
        val mine = ++request
        error = null
        busy = true
        render()
        background(mine, { api.files(repo) }) { result ->
            when (result) {
                is ApiResult.Ok -> open = repo to result.value
                is ApiResult.Failed -> error = result.error
            }
        }
    }

    /** Runs [call] on the background thread and, if no newer request was made meanwhile, applies its result on the main thread. */
    private fun <T> background(mine: Int, call: () -> ApiResult<T>, apply: (ApiResult<T>) -> Unit) {
        try {
            executor.execute {
                val result = call()
                runOnUiThread {
                    if (mine != request || isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    apply(result)
                    render()
                }
            }
        } catch (_: RejectedExecutionException) { // the screen is closing
        }
    }

    // ---------------------------------------------------------------- screen

    private fun render() {
        content.removeAllViews()
        val failure = error
        status.text = when {
            busy -> getString(R.string.browse_loading)
            failure != null -> getString(errorText(failure))
            open == null && hits.isEmpty() && direct == null && input.text.isNotBlank() -> getString(R.string.browse_no_results)
            else -> ""
        }
        val files = open
        if (files != null) showFiles(files.first, files.second) else showRepos()
    }

    private fun errorText(error: ApiError) = when (error) {
        ApiError.NETWORK -> R.string.browse_error_network
        ApiError.UNTRUSTED_HOST -> R.string.browse_error_host
        ApiError.TOO_BIG -> R.string.browse_error_too_big
        ApiError.NOT_FOUND -> R.string.browse_error_not_found
        ApiError.REFUSED -> R.string.browse_error_refused
        ApiError.UNREADABLE -> R.string.browse_error_unreadable
    }

    private fun showRepos() {
        direct?.let { repo -> content.addView(leftButton(getString(R.string.browse_open_repo, repo.toString())) { openRepo(repo) }) }
        for (hit in hits) {
            if (hit.id == direct) continue
            val label = if (hit.downloads == null) hit.id.toString() else getString(
                R.string.two_lines, hit.id.toString(),
                resources.getQuantityString(R.plurals.browse_downloads, hit.downloads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), NumberFormat.getIntegerInstance().format(hit.downloads)),
            )
            content.addView(leftButton(label) { openRepo(hit.id) })
        }
    }

    private fun showFiles(repo: RepoId, files: RepoFiles) {
        content.addView(button(R.string.browse_back) { open = null; error = null; render() })
        content.addView(text(R.string.browse_files_of, 16f, repo.toString()).apply { setPadding(0, dp(8), 0, dp(8)) })
        if (files.files.isEmpty()) content.addView(text(R.string.browse_no_files, 14f))
        for (file in files.files) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply {
                text = getString(R.string.two_lines, file.path, Formatter.formatShortFileSize(this@BrowseActivity, file.sizeBytes))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            roles.addView(text(R.string.browse_use_as, 13f).apply { setPadding(0, 0, dp(8), 0) })
            for ((label, role) in listOf(
                R.string.role_button_speech to ModelsProtocol.CUSTOM_ROLE_SPEECH,
                R.string.role_button_clean_up to ModelsProtocol.CUSTOM_ROLE_CLEAN_UP,
                R.string.role_button_grammar to ModelsProtocol.CUSTOM_ROLE_GRAMMAR,
            )) {
                roles.addView(button(label) { start(repo, files.sha, file, role) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            row.addView(roles)
            content.addView(row)
        }
    }

    // ---------------------------------------------------------------- download

    /** Queues the download of [file] for the service (this app's own queue, never an Intent extra) and goes back to the list of downloads. */
    private fun start(repo: RepoId, commit: String, file: RepoFile, role: Int) {
        val url = HuggingFace.buildResolveUrl(repo, commit, file.path)
        val name = file.name.take(ModelsProtocol.MAX_CUSTOM_NAME_LENGTH)
        val custom = if (url == null) null else CustomDownloads.enqueue(DownloadJob(url, name, file.sizeBytes, file.sha256), role, name)
        if (custom == null) return failed()
        try {
            val intent = Intent(this, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: RuntimeException) { // the phone does not let us start it now: nothing stays queued
            CustomDownloads.withdraw(custom)
            return failed()
        }
        Toast.makeText(this, getString(R.string.browse_started, name), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun failed() {
        Toast.makeText(this, R.string.browse_start_failed, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- views

    private fun text(res: Int, sp: Float, vararg args: Any) = TextView(this).apply {
        text = getString(res, *args)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun button(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        gravity = Gravity.CENTER
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun leftButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
