// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.catalog

/**
 * The few things the keyboard and the "Clackatoo Models" companion app say to each other. Both directions are guarded
 * by a signature permission, so only apps signed with the Clackatoo key can speak at all, and the keyboard still checks
 * every file against its own [Catalog] (the companion is not trusted with content).
 *
 * keyboard -> companion: a Binder call on the companion's download service ([TX_REQUEST]: an action string and one catalog
 * id, nothing else). The companion checks who is calling (package and signature), looks the rest up in its own copy of
 * the [Catalog] and refuses anything else (unknown id, leftover data, a URL, a size or a digest), and limits how often
 * downloads can be started.
 *
 * companion -> keyboard: transactions on the keyboard's receiver service (numbers and the id only), and finally the
 * finished file as a read-only file descriptor.
 */
object ModelsProtocol {
    /** Name of the extra that carries a model id in the companion's own notification Cancel intent (requests from the keyboard are Binder calls). */
    const val EXTRA_ID = "id"
    const val MAX_ID_LENGTH = 64

    const val ACTION_DOWNLOAD = "io.github.highlyproteus.clackatoo.models.action.DOWNLOAD"
    const val ACTION_CANCEL = "io.github.highlyproteus.clackatoo.models.action.CANCEL"
    const val DOWNLOAD_SERVICE_CLASS = "io.github.highlyproteus.clackatoo.models.DownloadService"

    const val DOWNLOAD_DESCRIPTOR = "io.github.highlyproteus.clackatoo.models.IDownloadRequests"

    /** `writeString(ACTION_DOWNLOAD or ACTION_CANCEL); writeString(id)`; the reply is one int, a REQUEST_* code. */
    const val TX_REQUEST = 1
    const val REQUEST_OK = 0
    const val REQUEST_REFUSED = 1
    /** Too many requests too quickly (a cool-down after a cancel or failure, or the hourly cap). */
    const val REQUEST_LATER = 2

    const val RECEIVER_SERVICE_CLASS = "helium314.keyboard.clackatoo.models.ModelReceiverService"
    const val RECEIVER_DESCRIPTOR = "io.github.highlyproteus.clackatoo.models.IModelReceiver"

    /** `writeString(id); writeLong(bytesSoFar); writeLong(totalBytes)`, no reply. */
    const val TX_PROGRESS = 1
    /** `writeString(id); writeInt(FAIL_*)`, no reply. */
    const val TX_FAILED = 2
    /** `writeString(id); writeFileDescriptor(read-only)`; the reply is one int, a HANDOVER_* code. */
    const val TX_HANDOVER = 3
    /**
     * A custom (experimental, not catalog) model the user chose in the companion's Hugging Face browser. A separate,
     * explicitly typed call: `writeString(displayName); writeInt(CUSTOM_ROLE_*); writeFileDescriptor(read-only)` and nothing
     * else; the reply is one int, a HANDOVER_* code. The name is only a suggestion for the keyboard's own sanitised file
     * name (at most [MAX_CUSTOM_NAME_LENGTH] characters); the keyboard decides everything else (size cap, format from the
     * file's header, whether it fits the role, a test load in the model process) and treats the file as untrusted. The
     * keyboard sends nothing to the companion for this: no URL, query or id.
     */
    const val TX_CUSTOM_HANDOVER = 4

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
    /** Custom models only: the file is larger than [MAX_CUSTOM_BYTES]. */
    const val HANDOVER_TOO_LARGE = 8
    /** Custom models only: the user has not turned on "Use your own model (experimental)" in the keyboard's settings yet. */
    const val HANDOVER_CUSTOM_OFF = 9
    /** Custom models only: the file is a model, but not of the kind the role needs (for example a text model for speech). */
    const val HANDOVER_WRONG_ROLE = 10
    /** Custom models only: the model process could not load the file (it was removed again). */
    const val HANDOVER_LOAD_FAILED = 11
    /** Custom models only: the keyboard already holds [MAX_CUSTOM_MODELS] custom models. */
    const val HANDOVER_TOO_MANY = 12

    /** The role a custom model is for (the user picks it, in the keyboard or in the companion's browser). */
    const val CUSTOM_ROLE_SPEECH = 1
    const val CUSTOM_ROLE_CLEAN_UP = 2
    const val CUSTOM_ROLE_GRAMMAR = 3
    const val MAX_CUSTOM_NAME_LENGTH = 200
    /** The largest custom model file either app handles (4 GiB). */
    const val MAX_CUSTOM_BYTES = 4L * 1024 * 1024 * 1024
    /** The most custom models the keyboard keeps. */
    const val MAX_CUSTOM_MODELS = 8

    /** Where the keyboard sends the user when the companion is not installed (it never downloads anything itself). */
    const val RELEASES_URL = "https://github.com/highlyproteus/clackatoo/releases"
    /** Opened by an external browser; the keyboard never fetches this page itself. */
    const val PRIVACY_URL = "https://highlyproteus.github.io/clackatoo/privacy.html"

    /** Package names: the companion's is the keyboard's with `.models` before an optional `.debug`. */
    const val KEYBOARD_PACKAGE = "io.github.highlyproteus.clackatoo"
    const val COMPANION_PACKAGE = "io.github.highlyproteus.clackatoo.models"
}
