// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.util.EnumMap

/**
 * Which models the isolated model process keeps in memory. Only the models of the current use stay resident, so that the
 * memory a phone needs is that of one use at a time (what `ModelSuggestions` budgets), not of everything used since the process
 * started:
 *
 * - **voice typing**: one speech engine (Whisper [Slot.STT] or Parakeet [Slot.ASR], never both), the voice detector
 *   [Slot.VAD] of live text, and the clean-up text model [Slot.CLEAN_UP];
 * - **grammar check**: the grammar text model [Slot.GRAMMAR].
 *
 * Starting a request frees everything that is not part of its use, and a slot that holds another file (a different cache key)
 * is freed before the new one loads. At most one text model is resident per purpose. Pure bookkeeping, no native calls: the
 * service frees what [toFree] names, on its worker thread, while nothing is registered for cancelling, and reports back with
 * [loaded] and [freed].
 */
class ModelResidency {
    enum class Slot { STT, ASR, VAD, CLEAN_UP, GRAMMAR }

    private val keys = EnumMap<Slot, String>(Slot::class.java)

    /** The cache key of what [slot] holds, or null when it is empty. */
    fun keyOf(slot: Slot): String? = keys[slot]

    fun loaded(slot: Slot, key: String) { keys[slot] = key }

    fun freed(slot: Slot) { keys.remove(slot) }

    fun clear() = keys.clear()

    /** The slots now in memory. */
    fun resident(): Set<Slot> = keys.keys.toSet()

    /**
     * The resident slots to free before [need] is used with the file [key]: all that do not belong to its use, plus [need]
     * itself when it holds a different file.
     */
    fun toFree(need: Slot, key: String): Set<Slot> {
        val keep = keepWith(need)
        val free = keys.keys.filter { it !in keep }.toMutableSet()
        val held = keys[need]
        if (held != null && held != key) free += need
        return free
    }

    private fun keepWith(need: Slot): Set<Slot> = when (need) {
        Slot.STT -> setOf(Slot.STT, Slot.CLEAN_UP)
        Slot.ASR -> setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP)
        Slot.VAD -> setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP)
        Slot.CLEAN_UP -> setOf(Slot.STT, Slot.ASR, Slot.VAD, Slot.CLEAN_UP)
        Slot.GRAMMAR -> setOf(Slot.GRAMMAR)
    }
}
