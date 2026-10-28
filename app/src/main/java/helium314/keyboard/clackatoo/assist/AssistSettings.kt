// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import java.io.File

/** Preferences of voice typing and the grammar check, and the shared [ModelStore]. */
object AssistSettings {
    const val PREF_S1_MINI_ENABLED = "assist_s1_mini_enabled"
    const val PREF_S1_STYLING = "assist_s1_styling"
    /** Set when the user has read and accepted the experimental-models notice in Settings; the companion's custom hand-over is refused until then. */
    const val PREF_CUSTOM_MODELS_CONFIRMED = "assist_custom_models_confirmed"
    private const val BROKEN_PREFIX = "assist_broken_model_"
    /** In the app's private files folder (left out of backups, see backup_rules.xml); only the keyboard process opens it and hands the files to the model process as descriptors. */
    private const val MODEL_DIR_NAME = "models"

    fun s1MiniEnabled(prefs: SharedPreferences) = prefs.getBoolean(PREF_S1_MINI_ENABLED, true)

    fun customModelsConfirmed(prefs: SharedPreferences) = prefs.getBoolean(PREF_CUSTOM_MODELS_CONFIRMED, false)

    fun styling(prefs: SharedPreferences): S1Styling {
        val saved = prefs.getString(PREF_S1_STYLING, null)
        return S1Styling.entries.firstOrNull { it.value == saved } ?: S1Styling.SEMI_FORMAL
    }

    fun modelStore(context: Context): ModelStore {
        val prefs = context.prefs()
        val assets = context.applicationContext.assets
        return ModelStore(File(context.applicationContext.filesDir, MODEL_DIR_NAME), object : BrokenMarks {
            override fun get(fileName: String) = prefs.getString(BROKEN_PREFIX + fileName, null)
            override fun set(fileName: String, value: String?) = prefs.edit {
                if (value == null) remove(BROKEN_PREFIX + fileName) else putString(BROKEN_PREFIX + fileName, value)
            }
        }, openAsset = { assets.open(it) })
    }
}
