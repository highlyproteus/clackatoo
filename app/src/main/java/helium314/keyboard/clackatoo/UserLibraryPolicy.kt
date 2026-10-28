// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import helium314.keyboard.latin.BuildConfig

/**
 * Whether the keyboard may load a native library that the user supplied (HeliBoard's "load gesture typing library").
 * In Clackatoo builds it never may, in any build type: such a library runs inside the keyboard process with full access
 * to everything typed, and it is not needed, because swipe typing uses the built-in decoder
 * (app/src/main/jni/src/suggest/policyimpl/gesture). [BuildConfig.ALLOW_USER_LIBRARY] is
 * false for every build type (app/build.gradle.kts); the check is kept in two places, [helium314.keyboard.latin.utils.JniUtils]
 * (the loading) and the advanced settings screen (the setting that stores the library).
 */
object UserLibraryPolicy {
    private const val NO_USER_LIBRARY_BUILD_TYPE = "nouserlib"

    /** The answer for this build. */
    @JvmStatic
    fun isAllowed(): Boolean = isAllowed(BuildConfig.ALLOW_USER_LIBRARY, BuildConfig.BUILD_TYPE)

    /** Upstream's `nouserlib` build type never allows it; every other build type only when the flag says so. */
    @JvmStatic
    fun isAllowed(allowUserLibraryFlag: Boolean, buildType: String): Boolean =
        allowUserLibraryFlag && buildType != NO_USER_LIBRARY_BUILD_TYPE
}
