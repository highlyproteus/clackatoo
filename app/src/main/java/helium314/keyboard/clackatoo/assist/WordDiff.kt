// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/** Word-level difference between a text and its correction, to show what changed. */
object WordDiff {
    private val token = Regex("[\\p{L}\\p{N}'’]+|\\s+|[^\\s\\p{L}\\p{N}]")
    /** Above this many comparisons the diff is skipped (nothing highlighted) to keep the keyboard responsive. */
    private const val MAX_CELLS = 4_000_000L

    private data class Token(val text: String, val start: Int) {
        val isWord get() = text[0].isLetterOrDigit()
    }

    private fun tokens(s: String) = token.findAll(s).map { Token(it.value, it.range.first) }.toList()

    /** Ranges in [corrected] that are words or punctuation not present in [original] (in order); null if too long to diff. */
    fun changedRanges(original: String, corrected: String): List<IntRange>? {
        val a = tokens(original)
        val b = tokens(corrected)
        if (a.size.toLong() * b.size > MAX_CELLS) return null
        // longest common subsequence of tokens
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            lcs[i][j] = if (a[i].text == b[j].text) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val changed = mutableListOf<IntRange>()
        var i = 0
        var j = 0
        while (j < b.size) {
            if (i < a.size && a[i].text == b[j].text) { i++; j++ }
            else if (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1]) i++
            else {
                val t = b[j]
                if (t.text.isNotBlank()) {
                    val range = t.start until t.start + t.text.length
                    val last = changed.lastOrNull()
                    // merge neighbouring changes separated only by a space
                    if (last != null && corrected.substring(last.last + 1, range.first).isBlank())
                        changed[changed.size - 1] = last.first..range.last
                    else changed += range
                }
                j++
            }
        }
        return changed
    }

    /** Share of the words in [corrected] that are not in [original], 0..1. */
    fun changedWordShare(original: String, corrected: String): Double {
        val ranges = changedRanges(original, corrected) ?: return 0.0
        val words = tokens(corrected).count { it.isWord }
        if (words == 0) return 0.0
        val changedWords = tokens(corrected).count { t ->
            t.isWord && ranges.any { t.start >= it.first && t.start <= it.last }
        }
        return changedWords.toDouble() / words
    }
}
