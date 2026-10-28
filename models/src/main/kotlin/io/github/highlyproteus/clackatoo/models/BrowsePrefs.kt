// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.Context

/** Whether the user has read the warning about Hugging Face models (once; the screen asks until they have). */
internal object BrowsePrefs {
    private const val FILE = "browse"
    private const val CONFIRMED = "custom_models_confirmed"

    fun confirmed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(CONFIRMED, false)

    fun setConfirmed(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(CONFIRMED, true).apply()
    }
}
