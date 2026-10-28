// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol

/** The Cancel button of the download notification. Not exported: only this app's own PendingIntent reaches it. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ModelsProtocol.EXTRA_ID)
        if (Catalog.byId(id) != null || CustomDownloads.isKnown(id)) DownloadService.cancel(id!!)
    }
}
