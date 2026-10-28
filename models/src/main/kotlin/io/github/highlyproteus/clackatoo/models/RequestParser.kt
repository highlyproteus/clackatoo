// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

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
 * The URL, size and digest come from this app's own copy of the catalog. A request is refused unless it is exactly one of
 * the two actions with a string that is the id of a catalog model: a URL, a size or a hash in its place is refused.
 * ([DownloadBinder] reads the two strings off the Binder call and refuses anything left over in it.)
 */
internal object RequestParser {
    /** The rules (see the class comment). */
    fun check(action: String?, id: String?): Request? {
        if (id == null || id.length > ModelsProtocol.MAX_ID_LENGTH) return null
        val model = Catalog.byId(id) ?: return null
        return when (action) {
            ModelsProtocol.ACTION_DOWNLOAD -> Request.Download(model)
            ModelsProtocol.ACTION_CANCEL -> Request.Cancel(model)
            else -> null
        }
    }
}
