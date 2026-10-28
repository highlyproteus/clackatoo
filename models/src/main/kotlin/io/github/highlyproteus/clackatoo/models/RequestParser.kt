// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.Intent
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol

/** What the keyboard may ask of this app. */
sealed interface Request {
    val model: CatalogModel
    data class Download(override val model: CatalogModel) : Request
    data class Cancel(override val model: CatalogModel) : Request
}

/**
 * The only thing this app accepts from the keyboard is the id of a catalog model (and whether to download or cancel it).
 * The URL, size and digest come from this app's own copy of the catalog. A request is refused unless it is exactly:
 * one of the two actions, no data URI, no clip data, no category, no explicit type, and a single extra named `id` holding
 * a string that is the id of a catalog model. In particular an extra that carries a URL, a size or a hash is refused.
 */
internal object RequestParser {
    fun parse(intent: Intent?): Request? {
        if (intent == null) return null
        // reading the extras unparcels them; a malformed or hostile Bundle must not crash this app
        return try {
            val extras = intent.extras
            val keys = extras?.keySet().orEmpty()
            val id = if (keys == setOf(ModelsProtocol.EXTRA_ID)) extras?.getString(ModelsProtocol.EXTRA_ID) else null
            check(
                action = intent.action,
                hasData = intent.data != null || intent.type != null || intent.clipData != null,
                hasCategories = !intent.categories.isNullOrEmpty(),
                hasSelector = intent.selector != null,
                extraKeys = keys,
                id = id,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    /** The rules, on plain values (see the class comment). */
    fun check(action: String?, hasData: Boolean, hasCategories: Boolean, hasSelector: Boolean, extraKeys: Set<String>, id: String?): Request? {
        if (hasData || hasCategories || hasSelector) return null
        if (extraKeys != setOf(ModelsProtocol.EXTRA_ID)) return null
        if (id == null || id.length > ModelsProtocol.MAX_ID_LENGTH) return null
        val model = Catalog.byId(id) ?: return null
        return when (action) {
            ModelsProtocol.ACTION_DOWNLOAD -> Request.Download(model)
            ModelsProtocol.ACTION_CANCEL -> Request.Cancel(model)
            else -> null
        }
    }
}
