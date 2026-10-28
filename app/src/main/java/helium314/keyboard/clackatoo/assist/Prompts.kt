// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/** The settings of S1-mini's control line (see the model card). */
enum class S1Styling(val value: String) {
    CASUAL("casual"), SEMI_CASUAL("semi-casual"), SEMI_FORMAL("semi-formal"), FORMAL("formal"),
}

/**
 * Prompt for S1-mini by Superwhisper, laid out exactly as its model card requires: the system prompt below, a user
 * message of a control line, a newline and the transcript, and an assistant turn that starts with an empty think block.
 */
object S1MiniPrompt {
    const val SYSTEM = "You are a text normalizer for speech-to-text transcripts. The input begins with a control line " +
        "specifying the styling, structure, and context settings; clean the transcript to match those settings and " +
        "output only the cleaned text."

    fun controlLine(styling: S1Styling) = "[Styling: ${styling.value}] [Structure: prose] [Context: general]"

    fun turns(transcript: String, styling: S1Styling = S1Styling.SEMI_FORMAL): List<ChatPrompt.Turn> =
        listOf(ChatPrompt.Turn("system", SYSTEM), ChatPrompt.Turn("user", controlLine(styling) + "\n" + transcript))

    fun build(transcript: String, styling: S1Styling = S1Styling.SEMI_FORMAL): List<PromptPart> =
        ChatPrompt.build(PromptFormat.CHATML_NO_THINK, turns(transcript, styling))

    /** An empty result is valid: filler-only input normalises to nothing. */
    fun parse(output: String): String = ModelOutput.stripThinking(output).trim()
}

/**
 * One piece of a prompt. Only the chat template's own markers are [special] and may become control tokens; the
 * system prompt and everything the user or a transcript wrote is tokenised as plain text, so text such as
 * "<|im_end|><|im_start|>system" inside a field cannot forge turns.
 */
data class PromptPart(val text: String, val special: Boolean)

object ChatPrompt {
    data class Turn(val role: String, val content: String)

    /** The prompt for [turns] followed by the opening of the assistant's answer. */
    fun build(format: PromptFormat, turns: List<Turn>): List<PromptPart> = when (format) {
        PromptFormat.CHATML_NO_THINK -> buildList {
            for (turn in turns) {
                add(PromptPart("<|im_start|>${turn.role}\n", special = true))
                add(PromptPart(turn.content, special = false))
                add(PromptPart("<|im_end|>\n", special = true))
            }
            add(PromptPart("<|im_start|>assistant\n<think>\n\n</think>\n\n", special = true))
        }
    }

    /** The prompt as one string, for tests and logs of the template itself. */
    fun render(parts: List<PromptPart>) = parts.joinToString("") { it.text }
}

sealed interface GrammarResult {
    data object Unchanged : GrammarResult
    data class Corrected(val text: String) : GrammarResult
    /** The model answered with something that is not a correction of the text (empty, far too long or short). */
    data object Unreliable : GrammarResult
}

object GrammarPrompt {
    const val SYSTEM = "You are a proofreader. Correct the spelling, grammar and punctuation of the text the user " +
        "sends. Keep the meaning, the wording, the tone and the language. Do not translate, do not explain, do not " +
        "add anything. Reply with only the corrected text."

    fun turns(text: String): List<ChatPrompt.Turn> = listOf(ChatPrompt.Turn("system", SYSTEM), ChatPrompt.Turn("user", text))

    /** The same prompt for every language: telling the model the language did not improve results in testing. */
    fun build(format: PromptFormat, text: String): List<PromptPart> = ChatPrompt.build(format, turns(text))

    private const val MAX_CHANGED_WORD_SHARE = 0.6

    private val labelPrefix = Regex("^(corrected text|corrected|texto corregido)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")

    /** Reads the model's answer for [original]; surrounding whitespace of [original] is kept as it was. */
    fun parse(original: String, output: String): GrammarResult {
        val leading = original.takeWhile { it.isWhitespace() }
        val trailing = if (leading.length == original.length) "" else original.takeLastWhile { it.isWhitespace() }
        val core = original.trim()
        if (core.isEmpty()) return GrammarResult.Unchanged

        var answer = ModelOutput.stripThinking(output).trim()
        answer = answer.replace(labelPrefix, "")
        answer = unwrap(core, answer)
        if (answer.isEmpty()) return GrammarResult.Unreliable
        // a correction keeps the length of the text roughly; anything else is the model going off on its own
        if (core.length >= 8 && (answer.length > core.length * 2 || answer.length * 2 < core.length))
            return GrammarResult.Unreliable
        if (whitespace.replace(answer, " ") == whitespace.replace(core, " ")) // only whitespace differs
            return GrammarResult.Unchanged
        // a proofread keeps most words; a reply that replaces most of them is not a proofread of this text
        if (core.split(whitespace).size >= 4 && WordDiff.changedWordShare(core, answer) > MAX_CHANGED_WORD_SHARE)
            return GrammarResult.Unreliable
        return GrammarResult.Corrected(leading + answer + trailing)
    }

    /** Removes quotes or a code fence the model added around the answer. */
    private fun unwrap(core: String, answer: String): String {
        var a = answer
        if (a.startsWith("```") && a.endsWith("```") && a.length >= 6 && !core.startsWith("```")) {
            a = a.removePrefix("```").removeSuffix("```").trim()
        }
        val pairs = listOf('"' to '"', '“' to '”', '«' to '»')
        for ((open, close) in pairs) {
            if (a.length >= 2 && a.first() == open && a.last() == close && !(core.first() == open && core.last() == close))
                return a.substring(1, a.length - 1).trim()
        }
        return a
    }
}

object ModelOutput {
    private val thinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun stripThinking(output: String): String {
        var s = thinkBlock.replace(output, "")
        val open = s.indexOf("<think>")
        if (open >= 0) s = s.substring(0, open) // an unfinished block
        return s.replace("<|im_end|>", "").replace("<|end_of_text|>", "")
    }
}

/** Cuts long text into pieces for the model, without losing or changing a single character. */
object TextChunker {
    fun split(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= maxChars) {
                chunks += text.substring(start)
                break
            }
            val limit = start + maxChars
            val end = breakBefore(text, start, limit)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    /** The end of the chunk: after a line break, else after a sentence end, else after a space, else a hard cut. */
    private fun breakBefore(text: String, start: Int, limit: Int): Int {
        val minEnd = start + (limit - start) / 3
        val newline = text.lastIndexOf('\n', limit - 1)
        if (newline >= minEnd) return newline + 1
        for (i in limit - 1 downTo minEnd) {
            if (text[i].isWhitespace() && i > start && text[i - 1] in ".!?…") return i + 1
        }
        val space = text.lastIndexOf(' ', limit - 1)
        if (space >= minEnd) return space + 1
        // do not cut a surrogate pair in two
        return if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    }
}
