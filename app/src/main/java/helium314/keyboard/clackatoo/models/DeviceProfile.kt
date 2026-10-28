// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * What the model suggestions need to know about this phone. Read once per process ([read]); the rules that use it
 * ([ModelSuggestions]) are plain functions of it, so they are tested with made-up phones.
 *
 * [totalMemBytes] is `ActivityManager.MemoryInfo.totalMem`: the RAM the kernel manages, which is a little less than the
 * number on the box (a "4 GB" phone reports about 3.7 GiB). [dotprod] is whether the CPU has the Arm dot-product
 * instructions (`asimddp`), which the Parakeet build needs on arm64; it is true on x86_64, where the question does not arise
 * (the emulator).
 */
data class DeviceProfile(
    val totalMemBytes: Long,
    val abi: String?,
    val sdk: Int,
    val dotprod: Boolean,
    val cores: Int,
) {
    /** The `abiFilters` and `ANDROID_PLATFORM` of the native builds: nothing runs on other phones. */
    val runsModels: Boolean get() = abi in MODEL_ABIS && sdk >= MODEL_MIN_SDK

    companion object {
        /** The `abiFilters` of `ml/build.gradle.kts` and `asr/build.gradle.kts`; change them together. */
        val MODEL_ABIS = setOf("arm64-v8a", "x86_64")

        /** Both native builds use `-DANDROID_PLATFORM=android-28`; older systems cannot load them. */
        const val MODEL_MIN_SDK = 28

        @Volatile private var cached: DeviceProfile? = null

        fun read(context: Context): DeviceProfile = cached ?: synchronized(this) {
            cached ?: build(context).also { cached = it }
        }

        private fun build(context: Context): DeviceProfile {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
            val abi = Build.SUPPORTED_ABIS.firstOrNull()
            val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (_: Exception) { "" }
            return DeviceProfile(info.totalMem, abi, Build.VERSION.SDK_INT, hasDotprod(abi, cpuinfo), Runtime.getRuntime().availableProcessors())
        }

        /** Whether [cpuinfo] (the text of /proc/cpuinfo) lists `asimddp` among the CPU features; always true off arm64. */
        fun hasDotprod(abi: String?, cpuinfo: String): Boolean {
            if (abi != "arm64-v8a") return true
            return cpuinfo.lineSequence().filter { it.startsWith("Features") }.any { line ->
                line.substringAfter(':').trim().split(' ', '\t').contains("asimddp")
            }
        }
    }
}
