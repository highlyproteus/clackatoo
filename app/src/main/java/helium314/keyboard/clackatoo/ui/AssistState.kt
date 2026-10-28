// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.content.Context
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.SpeechEngine
import helium314.keyboard.clackatoo.models.ModelChoice
import helium314.keyboard.latin.R

/** The state lines the home screen shows for the voice and grammar rows. */
object AssistState {
    fun voice(context: Context): String {
        val spec = ModelChoice.active(context, ModelKind.SPEECH)
            ?: return context.getString(R.string.home_model_not_downloaded)
        val engine = context.getString(if (spec.engine == SpeechEngine.PARAKEET) R.string.home_voice_live else R.string.home_voice_whisper)
        return context.getString(R.string.home_voice_ready, engine)
    }

    fun grammar(context: Context): String =
        if (ModelChoice.active(context, ModelKind.GRAMMAR) != null) context.getString(R.string.home_grammar_ready)
        else context.getString(R.string.home_model_not_downloaded)
}
