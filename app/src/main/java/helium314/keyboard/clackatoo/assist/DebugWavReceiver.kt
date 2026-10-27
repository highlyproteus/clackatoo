// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * Debug builds only: transcribes a WAV file through the same pipeline as a real recording (speech to text, English
 * clean-up, insert into the focused field), because the emulator has no microphone.
 *
 *     adb shell am broadcast -a io.github.highlyproteus.clackatoo.debug.TRANSCRIBE_WAV --es name en.wav [--es language es]
 *
 * The file must be 16 kHz mono 16-bit PCM in the app's files/debug folder (see ml/README.md). Safeguards:
 * [register] is called only when BuildConfig.DEBUG is true and the app is debuggable, so release and nouserlib builds
 * never register it (and shrinking removes the class); the receiver is not declared in the manifest; and it is
 * registered with the DUMP permission as broadcast permission, which only the adb shell and the system hold, so other
 * apps cannot trigger it. The field rules of the Voice key apply (no password fields), and nothing that is said or
 * transcribed is logged.
 */
class DebugWavReceiver private constructor(private val context: Context, private val assist: AssistController) : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val name = intent.getStringExtra("name")
        if (name == null || name.contains('/') || name.contains("..")) return Log.w(TAG, "debug wav: bad name")
        val file = File(File(context.filesDir, "debug"), name)
        if (!file.isFile) return Log.w(TAG, "debug wav: ${file.path} not found")
        if (file.length() > MAX_FILE_BYTES) return Log.w(TAG, "debug wav: file too large")
        val language = intent.getStringExtra("language")?.let { code -> AssistLanguages.all.firstOrNull { it.code == code } }
            ?: AssistLanguages.forLocale(helium314.keyboard.latin.RichInputMethodManager.getInstance().currentSubtypeLocale)
            ?: return Log.w(TAG, "debug wav: no supported language")
        val pcm = try {
            WavReader.read(file.readBytes())
        } catch (e: WavReader.WavException) {
            return Log.w(TAG, "debug wav: ${e.message}")
        }
        Log.i(TAG, "debug wav: ${pcm.size} samples, language ${language.code}")
        assist.transcribeAndInsert(pcm, language)
    }

    companion object {
        const val ACTION = "io.github.highlyproteus.clackatoo.debug.TRANSCRIBE_WAV"
        private const val TAG = "ClackatooAssist"
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        @JvmStatic
        fun register(context: Context, assist: AssistController): DebugWavReceiver? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val receiver = DebugWavReceiver(context, assist)
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION), Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED,
            )
            return receiver
        }
    }
}
