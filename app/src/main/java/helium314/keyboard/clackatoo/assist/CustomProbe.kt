// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The test load of an experimental custom model: the isolated model process loads the file once and frees it again
 * ([MlClient.probe]). Blocks, so it must not be called on the main thread (an import runs on an IO thread, a hand-over
 * on a Binder thread). A file that cannot be loaded, a process that died (for example out of memory) and a load that takes
 * longer than [TIMEOUT_MS] all count as "cannot be used".
 */
object CustomProbe {
    const val TIMEOUT_MS = 180_000L

    fun run(context: Context, file: File, spec: ModelSpec): Boolean {
        val app = context.applicationContext
        return runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                // MlClient belongs to the main thread
                withContext(Dispatchers.Main) {
                    val client = MlClient(app)
                    try {
                        client.probe(file, spec)
                        true
                    } catch (_: MlException) {
                        false
                    } finally {
                        client.release()
                    }
                }
            } ?: false
        }
    }
}
