package org.futo.inputmethod.latin.tapswipe

/** Language-model context for a word that is already composing. */
object TapSwipeContext {
    /**
     * Removes the word being composed from the context words. [line] is the text before the cursor;
     * if it does not end in whitespace, its last token is the composing word, not a previous word.
     */
    @JvmStatic
    fun dropComposing(line: String, words: List<String>): List<String> {
        if (line.isEmpty() || words.isEmpty()) return words
        return if (line.last().isWhitespace()) words else words.dropLast(1)
    }
}

/**
 * Soft time order between the two thumbs' streams.
 *
 * ## Why
 *
 * The decoder keeps the order inside each stream, but interleaves the two streams by the lexicon
 * alone. Once a second-finger tap joined its own stream (2026-11-11, for "then"), order across the
 * streams stopped counting at all. Reported the same day: "form" typed as tap F, one thumb swiping O
 * to M, the other thumb tapping R during the swipe, always read "from". Both words interleave the
 * strokes, and "from" is more common. But "from" needs the R before the O, and the R came 225 ms
 * after the O began.
 *
 * ## Rule
 *
 * For a candidate word, find the attribution of its letters to the strokes (taps match their key;
 * a swipe takes a run of one or more letters; each stream keeps its order) that needs the least
 * going backwards in time. Each letter gets a time window: a tap's contact time; a swipe's start for
 * its first letter, its end for its last letter, and its whole span for letters between. The
 * inversion is the sum, over neighboring letters, of how far the earlier letter's window starts
 * after the later letter's window ends, minus a tolerance for thumbs moving together. The caller
 * subtracts a penalty in proportion.
 *
 * It only reorders candidates the decoder already produced. Words typed one finger at a time are
 * never touched: there the decoder already keeps strict order.
 */
object TapSwipeTimingOrder {
    /** One stroke's timing, in session milliseconds. */
    data class Timing(
        val isTap: Boolean, val codePoint: Int, val right: Boolean, val t0: Float, val t1: Float,
        /** Keys near a swipe's start, or a tap-like stroke's keys. Empty means any key. */
        val startKeys: Set<Char> = emptySet(),
        /** Keys near a swipe's end. Empty means any key. */
        val endKeys: Set<Char> = emptySet(),
    )

    /** Overlap allowed before an order counts as reversed: thumbs moving together. */
    const val TOLERANCE_MS = 50f

    /**
     * How long before the lift a swipe may already have reached its last letter. Its first letter
     * is exact (the touch), its last is not: reported 2026-11-11, "house" with S-E started by the
     * other thumb 90 ms before the H-O-U thumb lifted.
     */
    const val SWIPE_END_SLACK_MS = 150f

    /** Score penalty per millisecond of inversion, in the decoder's raw score units. */
    const val PENALTY_PER_MS = 0.06f

    private const val MAX_LETTERS = 12
    private const val MAX_STROKES = 8

    /**
     * @return the smallest total inversion in milliseconds over all attributions, or null when no
     * attribution fits (a tapped key the word does not contain) or the word is too long to search.
     */
    @JvmStatic
    fun inversionMs(word: String, strokes: List<Timing>): Float? {
        val letters = word.lowercase().toList()
        if (letters.isEmpty() || letters.size > MAX_LETTERS || strokes.isEmpty() || strokes.size > MAX_STROKES) {
            return null
        }
        val left = strokes.filter { !it.right }
        val right = strokes.filter { it.right }
        var best: Float? = null
        val masks = if (right.isEmpty()) 1 else 1 shl letters.size
        for (mask in 0 until masks) {
            val lIdx = letters.indices.filter { mask and (1 shl it) == 0 }
            val rIdx = letters.indices.filter { mask and (1 shl it) != 0 }
            if ((left.isEmpty() && lIdx.isNotEmpty()) || (right.isEmpty() && rIdx.isNotEmpty())) continue
            if (left.isNotEmpty() && lIdx.isEmpty()) continue
            if (right.isNotEmpty() && rIdx.isEmpty()) continue
            val lWins = splits(left, lIdx.map { letters[it] })
            if (lWins.isEmpty()) continue
            val rWins = splits(right, rIdx.map { letters[it] })
            if (rWins.isEmpty()) continue
            for (lw in lWins) for (rw in rWins) {
                val windows = arrayOfNulls<FloatArray>(letters.size)
                lIdx.forEachIndexed { k, i -> windows[i] = lw[k] }
                rIdx.forEachIndexed { k, i -> windows[i] = rw[k] }
                var total = 0f
                for (i in 0 until letters.size - 1) {
                    val gap = windows[i]!![0] - windows[i + 1]!![1] - TOLERANCE_MS
                    if (gap > 0f) total += gap
                }
                if (best == null || total < best) best = total
                if (best == 0f) return 0f
            }
        }
        return best
    }

    /** Every way to split a stream's letters across its strokes, as per-letter [start, end] windows. */
    private fun splits(strokes: List<Timing>, letters: List<Char>): List<List<FloatArray>> {
        val out = ArrayList<List<FloatArray>>()
        fun go(s: Int, l: Int, acc: List<FloatArray>) {
            if (out.size >= 256) return
            if (s == strokes.size) {
                if (l == letters.size) out.add(acc)
                return
            }
            val st = strokes[s]
            if (st.isTap) {
                val matches = l < letters.size && (
                    (st.codePoint > 0 && letters[l] == Character.toLowerCase(st.codePoint).toChar()) ||
                    (st.codePoint <= 0 && (st.startKeys.isEmpty() || letters[l] in st.startKeys)))
                if (matches) go(s + 1, l + 1, acc + floatArrayOf(st.t0, st.t1))
                return
            }
            // A swipe's first letter is near where it started, its last near where it ended. Without
            // this, a candidate could give a stroke any letter and so dodge the order entirely.
            if (l < letters.size && st.startKeys.isNotEmpty() && letters[l] !in st.startKeys) return
            for (k in 1..(letters.size - l)) {
                if (st.endKeys.isNotEmpty() && letters[l + k - 1] !in st.endKeys) continue
                val run = (0 until k).map { j ->
                    when {
                        k == 1 -> floatArrayOf(st.t0, st.t1)
                        j == 0 -> floatArrayOf(st.t0, st.t0)
                        // A swipe often reaches its last letter before the finger lifts.
                        j == k - 1 -> floatArrayOf(maxOf(st.t0, st.t1 - SWIPE_END_SLACK_MS), st.t1)
                        else -> floatArrayOf(st.t0, st.t1)
                    }
                }
                go(s + 1, l + k, acc + run)
            }
        }
        go(0, 0, emptyList())
        return out
    }

    /**
     * Re-ranks decoder results by score minus the timing penalty. Unchanged when no stroke is in the
     * right stream, or when no candidate can be attributed.
     *
     * @return the indexes of [words] in their new order, and the penalty applied to each
     */
    @JvmStatic
    fun rerank(words: List<String>, scores: List<Float>, strokes: List<Timing>): Pair<List<Int>, List<Float>> {
        val penalties = MutableList(words.size) { 0f }
        if (strokes.none { it.right } || words.size < 2) return words.indices.toList() to penalties
        for (i in words.indices) {
            val inv = inversionMs(words[i], strokes) ?: continue
            penalties[i] = inv * PENALTY_PER_MS
        }
        val order = words.indices.sortedByDescending { scores[it] - penalties[it] }
        return order to penalties
    }
}
