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
        /** A swipe's points (normalized layout space) and their session times; null for a tap. */
        val xs: FloatArray? = null,
        val ys: FloatArray? = null,
        val ts: FloatArray? = null,
    )

    /** Key centres in the same normalized space as stroke points, and the key size there. */
    class Geometry(val center: (Char) -> FloatArray?, val keyW: Float, val keyH: Float)

    /** Mean distance in keys past which a swipe's path does not fit a run of letters. */
    const val MAX_FIT_KEYS = 1.2f

    private val MISFIT: List<FloatArray> = emptyList()

    /** A tap's moment is its touch. Holding it longer does not move it later. */
    const val TAP_SPAN_MS = 0f

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
    fun inversionMs(word: String, strokes: List<Timing>, geo: Geometry? = null): Float? {
        val letters = word.lowercase().toList()
        if (letters.isEmpty() || letters.size > MAX_LETTERS || strokes.isEmpty() || strokes.size > MAX_STROKES) {
            return null
        }
        val left = strokes.filter { !it.right }
        val right = strokes.filter { it.right }
        // Which thumb could each letter belong to? A thumb qualifies when one of its taps is that
        // key, or one of its swipes passed within reach of it. For most letters only one thumb does,
        // so only the genuinely ambiguous letters are searched. Before this, every split of the
        // letters was tried: 4096 for a 12-letter word, and long two-thumb words lagged by up to
        // 0.7 s (2026-11-12).
        val canL = BooleanArray(letters.size) { right.isEmpty() || couldHold(left, letters[it], geo) }
        val canR = BooleanArray(letters.size) { right.isNotEmpty() && couldHold(right, letters[it], geo) }
        if (letters.indices.any { !canL[it] && !canR[it] }) return null
        val open = letters.indices.filter { canL[it] && canR[it] }
        if (open.size > MAX_OPEN) return null
        var fixedMask = 0
        for (i in letters.indices) if (canR[i] && !canL[i]) fixedMask = fixedMask or (1 shl i)
        val cache = HashMap<String, List<FloatArray>?>()
        var best: Float? = null
        val combos = 1 shl open.size
        for (c in 0 until combos) {
            var mask = fixedMask
            open.forEachIndexed { b, i -> if (c and (1 shl b) != 0) mask = mask or (1 shl i) }
            val lIdx = letters.indices.filter { mask and (1 shl it) == 0 }
            val rIdx = letters.indices.filter { mask and (1 shl it) != 0 }
            if ((left.isEmpty() && lIdx.isNotEmpty()) || (right.isEmpty() && rIdx.isNotEmpty())) continue
            if (left.isNotEmpty() && lIdx.isEmpty()) continue
            if (right.isNotEmpty() && rIdx.isEmpty()) continue
            val lWins = splits(left, lIdx.map { letters[it] }, geo, cache, "L")
            if (lWins.isEmpty()) continue
            val rWins = splits(right, rIdx.map { letters[it] }, geo, cache, "R")
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
    /** Most ambiguous letters searched; past this the word is left to the decoder. */
    private const val MAX_OPEN = 10

    /** How close, in keys, a swipe's path must pass for a letter to be on that stroke. */
    private const val REACH_KEYS = 1.2f

    private fun couldHold(strokes: List<Timing>, c: Char, geo: Geometry?): Boolean = strokes.any { st ->
        if (st.isTap) {
            if (st.codePoint > 0) Character.toLowerCase(st.codePoint).toChar() == c
            else st.startKeys.isEmpty() || c in st.startKeys
        } else {
            val xs = st.xs
            val ys = st.ys
            val center = geo?.center?.invoke(c)
            if (xs == null || ys == null || center == null) true
            else xs.indices.any { i ->
                val dx = (xs[i] - center[0]) / geo.keyW
                val dy = (ys[i] - center[1]) / geo.keyH
                dx * dx + dy * dy <= REACH_KEYS * REACH_KEYS
            }
        }
    }

    private fun splits(strokes: List<Timing>, letters: List<Char>, geo: Geometry?,
                       cache: HashMap<String, List<FloatArray>?>, side: String): List<List<FloatArray>> {
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
                if (matches) go(s + 1, l + 1, acc + floatArrayOf(st.t0, minOf(st.t1, st.t0 + TAP_SPAN_MS)))
                return
            }
            // A swipe's first letter is near where it started, its last near where it ended. Without
            // this, a candidate could give a stroke any letter and so dodge the order entirely.
            if (l < letters.size && st.startKeys.isNotEmpty() && letters[l] !in st.startKeys) return
            for (k in 1..(letters.size - l)) {
                if (st.endKeys.isNotEmpty() && letters[l + k - 1] !in st.endKeys) continue
                val key = "$side$s:" + String(letters.subList(l, l + k).toCharArray())
                val fitted = if (cache.containsKey(key)) cache[key] else
                    pathWindows(st, letters.subList(l, l + k), geo).also { cache[key] = it }
                if (fitted === MISFIT) continue
                val run = fitted ?: (0 until k).map { j ->
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
     * Per-letter windows for a swipe's run of letters, read from its timed path: the touch for the
     * first letter; the moment the path passed closest to each middle letter; the arrival near the
     * last letter, through the lift. Null when the path or the key positions are unknown.
     *
     * Reported 2026-11-11 by the user: "from" is one thumb dragging F to O to M while the other taps R
     * on the way to O. Only the path's own timing says the R came before the O.
     */
    private fun pathWindows(st: Timing, run: List<Char>, geo: Geometry?): List<FloatArray>? {
        val xs = st.xs ?: return null
        val ys = st.ys ?: return null
        val ts = st.ts ?: return null
        if (geo == null || run.size < 2 || xs.size < 2 || xs.size != ys.size || xs.size != ts.size) return null
        val centers = run.map { geo.center(it) ?: return null }
        val n = xs.size
        val m = run.size
        if (m > n) return null
        fun dist(i: Int, j: Int): Float {
            val dx = (xs[i] - centers[j][0]) / geo.keyW
            val dy = (ys[i] - centers[j][1]) / geo.keyH
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        // Monotonic warping of points onto letters: point 0 to the first letter, the last to the last.
        val inf = Float.MAX_VALUE
        val cost = Array(n) { FloatArray(m) { inf } }
        val back = Array(n) { IntArray(m) { -1 } }
        cost[0][0] = dist(0, 0)
        for (i in 1 until n) for (j in 0 until m) {
            val stay = cost[i - 1][j]
            val move = if (j > 0) cost[i - 1][j - 1] else inf
            val (best, from) = if (stay <= move) stay to j else move to (j - 1)
            if (best < inf) {
                cost[i][j] = best + dist(i, j)
                back[i][j] = from
            }
        }
        if (cost[n - 1][m - 1] == inf) return null
        // The path must actually pass these letters. A run the path never went near is not a
        // reading of this stroke, so the attribution is impossible, not merely late.
        if (cost[n - 1][m - 1] / n > MAX_FIT_KEYS) return MISFIT
        val lo = IntArray(m) { n }
        val hi = IntArray(m) { -1 }
        var j = m - 1
        for (i in n - 1 downTo 0) {
            if (i < lo[j]) lo[j] = i
            if (i > hi[j]) hi[j] = i
            if (i > 0) j = back[i][j]
        }
        return (0 until m).map { k ->
            when (k) {
                0 -> floatArrayOf(st.t0, st.t0)
                else -> {
                    // The moment the path came closest to this letter's key. For the last letter the
                    // finger may dwell there until the lift.
                    var near = lo[k]
                    for (i in lo[k]..hi[k]) if (dist(i, k) < dist(near, k)) near = i
                    floatArrayOf(ts[near], if (k == m - 1) st.t1 else ts[near])
                }
            }
        }
    }

    /**
     * Re-ranks decoder results by score minus the timing penalty. Unchanged when no stroke is in the
     * right stream, or when no candidate can be attributed.
     *
     * @return the indexes of [words] in their new order, and the penalty applied to each
     */
    @JvmStatic
    fun rerank(words: List<String>, scores: List<Float>, strokes: List<Timing>,
               geo: Geometry? = null): Pair<List<Int>, List<Float>> {
        val penalties = MutableList(words.size) { 0f }
        if (strokes.none { it.right } || words.size < 2) return words.indices.toList() to penalties
        for (i in words.indices) {
            val inv = inversionMs(words[i], strokes, geo) ?: continue
            penalties[i] = inv * PENALTY_PER_MS
        }
        val order = words.indices.sortedByDescending { scores[it] - penalties[it] }
        return order to penalties
    }
}
