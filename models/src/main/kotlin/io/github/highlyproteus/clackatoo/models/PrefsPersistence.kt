// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.Context

/** [RequestLimiter]'s memory in the app's private preferences: start times as "t,t,t", cool-downs as "id=t;id=t". */
internal class PrefsPersistence(context: Context) : RequestLimiter.Persistence {
    private val prefs = context.getSharedPreferences("request-limits", Context.MODE_PRIVATE)

    override fun load(): Pair<List<Long>, Map<String, Long>> {
        val starts = prefs.getString("starts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val blocked = prefs.getString("blocked", "").orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(i + 1).toLongOrNull()?.let { t -> it.substring(0, i) to t }
        }.toMap()
        return starts to blocked
    }

    override fun save(starts: List<Long>, blockedUntil: Map<String, Long>) {
        prefs.edit().putString("starts", starts.joinToString(",")).putString("blocked", blockedUntil.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }
}
