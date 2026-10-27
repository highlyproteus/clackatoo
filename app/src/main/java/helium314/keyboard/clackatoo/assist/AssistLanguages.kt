// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.util.Locale

/**
 * A language that voice typing and the grammar check are set up for. To add a language, add a row to [all]:
 * [whisperCode] is Whisper's code for it (the multilingual Whisper model already knows about 99 languages) and
 * [englishName] is used inside the grammar prompt. [cleanUpWithS1Mini] must stay false for everything but English,
 * because S1-mini is an English-only model.
 */
data class AssistLanguage(
    val code: String,
    val englishName: String,
    val whisperCode: String,
    val cleanUpWithS1Mini: Boolean,
)

object AssistLanguages {
    val ENGLISH = AssistLanguage("en", "English", "en", cleanUpWithS1Mini = true)
    val SPANISH = AssistLanguage("es", "Spanish", "es", cleanUpWithS1Mini = false)

    val all = listOf(ENGLISH, SPANISH)

    /** The language for the keyboard's current [locale] (region and script are ignored), or null if not supported. */
    fun forLocale(locale: Locale?): AssistLanguage? {
        val language = locale?.language ?: return null
        return all.firstOrNull { it.code == language }
    }
}
