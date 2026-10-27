// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * A keyboard cannot show the system permission dialog itself, so this transparent activity does it. It is started
 * only by the Voice key when the microphone permission is missing; it records nothing.
 */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        private const val REQUEST = 1

        fun start(context: Context) {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
