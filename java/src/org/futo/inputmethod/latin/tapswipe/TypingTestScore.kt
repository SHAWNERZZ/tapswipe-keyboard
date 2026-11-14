package org.futo.inputmethod.latin.tapswipe

/**
 * Pure rules for the typing test: word lists, alignment of typed words to prompt words, and the
 * totals shown at the end. No Android types, so all of it has JVM tests.
 */
object TypingTestScore {

    /**
     * Reads a word list. Words are split on any whitespace, so a line may hold a phrase such as
     * `see me`. A line that starts with `#` is a comment.
     */
    fun parseWordList(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .filter { it.isNotEmpty() }
            .toList()

    /** Splits field text into words. */
    fun words(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Number of words the user has finished. A word is finished once whitespace follows it. The
     * word under the cursor is not finished, because the keyboard may still change it.
     */
    fun finishedWordCount(text: String): Int {
        val w = words(text)
        if (w.isEmpty()) return 0
        return if (text.last().isWhitespace()) w.size else w.size - 1
    }

    /**
     * Whether two words are the same word, ignoring case and end punctuation. Used to line typed
     * words up with prompt words, so that one punctuation slip does not shift every word after it.
     * Whether the word was typed right is [exact].
     */
    fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    private val END_PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':')

    private fun norm(s: String): String = s.lowercase().trimEnd(*END_PUNCTUATION)

    private fun endOf(s: String): String = s.substring(s.trimEnd(*END_PUNCTUATION).length)

    /**
     * Whether the typed word is right: the same word, the same end punctuation, and, when
     * [strictCase], the same capitals. Added 2026-11-13: the real-text lists test punctuation and
     * capitals, and the user found both were ignored.
     */
    fun exact(prompt: String, typed: String, strictCase: Boolean): Boolean {
        if (!same(prompt, typed)) return false
        if (endOf(prompt) != endOf(typed)) return false
        return !strictCase || prompt.trimEnd(*END_PUNCTUATION) == typed.trimEnd(*END_PUNCTUATION)
    }

    /**
     * Capitals count only in lists that have capitals (the real-text lists). In a list of
     * lowercase words, the keyboard capitalizing the first word of the field is not a mistake.
     */
    fun strictCaseFor(prompt: List<String>): Boolean = prompt.any { w -> w.any { it.isUpperCase() } }

    enum class Kind { MATCH, WRONG, MISSED, EXTRA }

    data class Aligned(val kind: Kind, val prompt: String?, val typed: String?, val promptIndex: Int)

    /**
     * Aligns typed words to prompt words by edit distance over whole words.
     *
     * Position-by-position matching is not enough here. When the keyboard splits one word into two,
     * or fuses two into one, every later word would shift and count as wrong. Edit distance absorbs
     * the shift, so one mistake costs one word.
     */
    fun align(prompt: List<String>, typed: List<String>): List<Aligned> {
        val n = prompt.size
        val m = typed.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val sub = d[i - 1][j - 1] + if (same(prompt[i - 1], typed[j - 1])) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val out = ArrayList<Aligned>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val match = same(prompt[i - 1], typed[j - 1])
                if (d[i][j] == d[i - 1][j - 1] + (if (match) 0 else 1)) {
                    out.add(Aligned(if (match) Kind.MATCH else Kind.WRONG, prompt[i - 1], typed[j - 1], i - 1))
                    i--; j--; continue
                }
            }
            if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                out.add(Aligned(Kind.MISSED, prompt[i - 1], null, i - 1)); i--
            } else {
                out.add(Aligned(Kind.EXTRA, null, typed[j - 1], i)); j--
            }
        }
        return out.reversed()
    }

    data class Totals(
        val promptWords: Int,
        val correctWords: Int,
        val wrongWords: Int,
        val missedWords: Int,
        val extraWords: Int,
        /** The right word, but with wrong end punctuation or capitals. Not counted as right. */
        val formatErrors: Int,
        val wpm: Double,
        val accuracy: Double,
    )

    /**
     * WPM follows the usual convention: correct characters, plus one space per correct word, divided
     * by five, per minute.
     */
    fun totals(prompt: List<String>, typedText: String, elapsedMs: Long): Totals {
        val a = align(prompt, words(typedText))
        val strict = strictCaseFor(prompt)
        val matched = a.filter { it.kind == Kind.MATCH }
        val correct = matched.filter { exact(it.prompt!!, it.typed!!, strict) }
        val chars = correct.sumOf { (it.typed ?: "").length + 1 }
        val minutes = elapsedMs / 60000.0
        return Totals(
            promptWords = prompt.size,
            correctWords = correct.size,
            wrongWords = a.count { it.kind == Kind.WRONG },
            missedWords = a.count { it.kind == Kind.MISSED },
            extraWords = a.count { it.kind == Kind.EXTRA },
            formatErrors = matched.size - correct.size,
            wpm = if (minutes > 0) chars / 5.0 / minutes else 0.0,
            accuracy = if (prompt.isEmpty()) 0.0 else correct.size.toDouble() / prompt.size,
        )
    }
}
