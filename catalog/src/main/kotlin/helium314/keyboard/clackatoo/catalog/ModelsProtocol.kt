// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.catalog

/**
 * The few things the keyboard and the "Clackatoo Models" companion app say to each other. Both directions are guarded
 * by a signature permission, so only apps signed with the Clackatoo key can speak at all, and the keyboard still checks
 * every file against its own [Catalog] (the companion is not trusted with content).
 *
 * keyboard -> companion: an explicit start of the download service whose Intent carries one string extra, [EXTRA_ID];
 * the companion looks the rest up in its own copy of the [Catalog] and refuses anything else (unknown id, any other
 * extra, a data URI, a URL, a size or a digest).
 *
 * companion -> keyboard: transactions on the keyboard's receiver service (numbers and the id only), and finally the
 * finished file as a read-only file descriptor.
 */
object ModelsProtocol {
    const val EXTRA_ID = "id"
    const val MAX_ID_LENGTH = 64

    const val ACTION_DOWNLOAD = "io.github.highlyproteus.clackatoo.models.action.DOWNLOAD"
    const val ACTION_CANCEL = "io.github.highlyproteus.clackatoo.models.action.CANCEL"
    const val DOWNLOAD_SERVICE_CLASS = "io.github.highlyproteus.clackatoo.models.DownloadService"

    const val RECEIVER_SERVICE_CLASS = "helium314.keyboard.clackatoo.models.ModelReceiverService"
    const val RECEIVER_DESCRIPTOR = "io.github.highlyproteus.clackatoo.models.IModelReceiver"

    /** `writeString(id); writeLong(bytesSoFar); writeLong(totalBytes)`, no reply. */
    const val TX_PROGRESS = 1
    /** `writeString(id); writeInt(FAIL_*)`, no reply. */
    const val TX_FAILED = 2
    /** `writeString(id); writeFileDescriptor(read-only)`; the reply is one int, a HANDOVER_* code. */
    const val TX_HANDOVER = 3

    const val FAIL_NETWORK = 1
    const val FAIL_SIZE = 2
    const val FAIL_DIGEST = 3
    const val FAIL_NO_SPACE = 4
    const val FAIL_CANCELLED = 5
    const val FAIL_OTHER = 6
    const val FAIL_UNTRUSTED_HOST = 7

    const val HANDOVER_OK = 0
    const val HANDOVER_BAD_REQUEST = 1
    const val HANDOVER_WRONG_SIZE = 2
    const val HANDOVER_WRONG_CONTENT = 3
    const val HANDOVER_NO_SPACE = 4
    const val HANDOVER_NOT_A_MODEL = 5
    const val HANDOVER_READ_ERROR = 6
    const val HANDOVER_UNTRUSTED_CALLER = 7

    /** Where the keyboard sends the user when the companion is not installed (it never downloads anything itself). */
    const val RELEASES_URL = "https://github.com/highlyproteus/clackatoo/releases"

    /** Package names: the companion's is the keyboard's with `.models` before an optional `.debug`. */
    const val KEYBOARD_PACKAGE = "io.github.highlyproteus.clackatoo"
    const val COMPANION_PACKAGE = "io.github.highlyproteus.clackatoo.models"
}
