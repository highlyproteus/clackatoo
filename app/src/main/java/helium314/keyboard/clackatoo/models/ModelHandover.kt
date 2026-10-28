// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ImportResult
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.ModelStore
import helium314.keyboard.clackatoo.assist.RejectReason
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.InputStream

/**
 * What the keyboard does with a file the companion app hands over. The companion is not trusted with content: the
 * bytes go through [ModelStore.import], the same temporary file, header, exact size and SHA-256 check as a file the
 * user imports by hand, against the keyboard's own copy of the catalog. A wrong file leaves nothing behind.
 */
internal object ModelHandover {
    /** Returns one of the `ModelsProtocol.HANDOVER_*` codes. */
    fun accept(store: ModelStore, id: String?, input: InputStream, lookup: (String?) -> ModelSpec? = ModelCatalog::byId): Int {
        val spec = lookup(id) ?: return ModelsProtocol.HANDOVER_BAD_REQUEST
        // import() matches by file name, so only the catalog's own name for this id can be installed
        return when (val result = store.import(spec.fileName, input)) {
            is ImportResult.Imported -> ModelsProtocol.HANDOVER_OK
            is ImportResult.Rejected -> when (result.reason) {
                RejectReason.WRONG_SIZE -> ModelsProtocol.HANDOVER_WRONG_SIZE
                RejectReason.WRONG_CONTENT -> ModelsProtocol.HANDOVER_WRONG_CONTENT
                RejectReason.NOT_A_MODEL -> ModelsProtocol.HANDOVER_NOT_A_MODEL
                RejectReason.NOT_ENOUGH_SPACE -> ModelsProtocol.HANDOVER_NO_SPACE
                RejectReason.UNKNOWN_FILE_NAME -> ModelsProtocol.HANDOVER_BAD_REQUEST
                RejectReason.READ_ERROR -> ModelsProtocol.HANDOVER_READ_ERROR
            }
        }
    }
}
