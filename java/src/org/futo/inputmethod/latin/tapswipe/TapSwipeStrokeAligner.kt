package org.futo.inputmethod.latin.tapswipe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Attributes input to the letters of the word it produced, across every stroke of a word.
 *
 * A swipe gives no per-letter touch events - it is one continuous path over several keys, so unlike
 * a tap there is nothing saying "the user aimed at L, here". The decoder knows, but it is a prebuilt
 * native model: strokes go in, a word comes out, with no per-letter attribution to read back. So we
 * recover it ourselves with dynamic time warping - the monotonic assignment of points to letters
 * that minimises total distance to each letter's key centre.
 *
 * The interesting property is that this reaches the **middle** of a word. Learning only from a
 * stroke's endpoints is the easy version, since the ends are unambiguous, but the middle is where
 * corner-cutting and drift actually live - exactly the sloppiness worth learning.
 *
 * ### Why taps make this easier, not harder
 *
 * Most words are a *sequence* of swipes and taps rather than one glide, and taps are the better
 * evidence of the two: the code point is known exactly, so there is nothing to infer. They also
 * **anchor** the alignment - a tap on `l` in "hello" can only be one of the two `l`s, never the `h`.
 * Feeding that in as a hard constraint makes the whole alignment more certain than a pure swipe.
 *
 * The case that genuinely cannot be resolved is *concurrent* strokes - two thumbs overlapping in
 * time, where the decoder orders letters by the lexicon rather than by the clock. Those are
 * rejected by the caller; see [overlapsInTime].
 *
 * Everything is in normalized layout space.
 */
object TapSwipeStrokeAligner {

    /**
     * Longest concatenated stroke fed to the DP. Cost is O(points x letters); real strokes can run
     * to several hundred points and the extra resolution buys nothing, since the shape is smooth at
     * this scale.
     */
    private const val MAX_POINTS = 128

    /**
     * A *swiped* letter needs at least this many points in its segment before its closest approach
     * counts. One or two points is the aligner having to put the boundary somewhere, not evidence
     * about aim. Taps are exempt - a tap is one point by nature, and an exact one.
     */
    private const val MIN_SEGMENT_POINTS = 3

    /** One stroke of a word, as the aligner needs it. */
    class StrokeInput(
        val kind: TapSwipeSession.Kind,
        /** Code point for a TAP; ignored for a SWIPE. */
        val codePoint: Int,
        val x: FloatArray,
        val y: FloatArray,
        val t: FloatArray
    ) {
        val startT: Float get() = if (t.isEmpty()) 0f else t[0]
        val endT: Float get() = if (t.isEmpty()) 0f else t[t.size - 1]
    }

    /** One letter's recovered aim point. */
    data class Attribution(
        val codePoint: Int,
        /** Offset from the letter's nominal key centre, normalized space. */
        val dx: Float,
        val dy: Float,
        /** 0..1 - how much this observation deserves to be trusted. */
        val weight: Float,
        /** Taps carry an exact code point; swipe attributions are inferred. */
        val fromTap: Boolean
    )

    /**
     * True when any two strokes overlap in time - two thumbs moving at once.
     *
     * The alignment assumes time order equals letter order. For overlapping strokes that premise
     * fails: the decoder interleaves the two hands using the lexicon, so the clock cannot say which
     * letter came first and any attribution would be a guess.
     */
    fun overlapsInTime(strokes: List<StrokeInput>): Boolean {
        val sorted = strokes.filter { it.x.isNotEmpty() }.sortedBy { it.startT }
        for (i in 1 until sorted.size) {
            if (sorted[i].startT < sorted[i - 1].endT) return true
        }
        return false
    }

    /**
     * @param word the decoded word, already accepted by the user
     * @param strokes every stroke of the word, in any order (sorted here by time)
     * @param centreOf nominal key centre for a code point, or null if the layout lacks it.
     *   **Must be the nominal centre, never a personalized one** - measuring against the previous
     *   correction would let the model drift under its own bias.
     */
    fun align(
        word: String,
        strokes: List<StrokeInput>,
        centreOf: (Int) -> FloatArray?
    ): List<Attribution> {
        if (word.length < 2) return emptyList()
        val usable = strokes.filter { it.x.isNotEmpty() && it.x.size == it.y.size }
        if (usable.isEmpty()) return emptyList()

        // Letter targets. A word containing anything the layout cannot place is not alignable - a
        // missing letter would silently shift every later letter's attribution.
        val m = word.length
        val letters = IntArray(m)
        val targetX = FloatArray(m)
        val targetY = FloatArray(m)
        for (i in 0 until m) {
            val cp = Character.toLowerCase(word[i].code)
            val c = centreOf(cp) ?: return emptyList()
            letters[i] = cp
            targetX[i] = c[0]
            targetY[i] = c[1]
        }

        val flat = flatten(usable) ?: return emptyList()
        val n = flat.x.size
        if (n < m) return emptyList()

        val path = warpPath(flat, letters, targetX, targetY, n, m) ?: return emptyList()

        val out = ArrayList<Attribution>(m)
        for (j in 0 until m) {
            var bestIdx = -1
            var bestD2 = Float.MAX_VALUE
            var segCount = 0
            var sawTap = false
            for (i in 0 until n) {
                if (path[i] != j) continue
                segCount++
                if (flat.tapCode[i] >= 0) sawTap = true
                val d2 = dist2(flat.x[i], flat.y[i], targetX[j], targetY[j])
                if (d2 < bestD2) { bestD2 = d2; bestIdx = i }
            }
            if (bestIdx < 0) continue
            // A tap is one exact point; only inferred swipe segments need to prove themselves.
            if (!sawTap && segCount < MIN_SEGMENT_POINTS) continue

            val fromTap = flat.tapCode[bestIdx] >= 0
            out.add(
                Attribution(
                    codePoint = letters[j],
                    dx = flat.x[bestIdx] - targetX[j],
                    dy = flat.y[bestIdx] - targetY[j],
                    weight = if (fromTap) TAP_WEIGHT
                             else weightFor(j, m, segCount, bestIdx, path, n),
                    fromTap = fromTap
                )
            )
        }
        return out
    }

    /**
     * A tap is the strongest evidence available - an exact position with an exact identity, and no
     * inference between them - so it counts for full weight where a swipe segment has to earn it.
     */
    private const val TAP_WEIGHT = 1.0f

    private class Flat(val x: FloatArray, val y: FloatArray, val tapCode: IntArray)

    /**
     * Concatenates strokes in time order, budgeting points so one long swipe cannot crowd out the
     * rest of the word. Tap strokes always survive intact - they are single points and the most
     * informative ones.
     */
    private fun flatten(strokes: List<StrokeInput>): Flat? {
        val ordered = strokes.sortedBy { it.startT }
        val taps = ordered.count { it.kind == TapSwipeSession.Kind.TAP }
        val swipePoints = ordered.filter { it.kind != TapSwipeSession.Kind.TAP }.sumOf { it.x.size }
        if (swipePoints + taps == 0) return null

        val swipeBudget = max(0, MAX_POINTS - taps)

        val xs = ArrayList<Float>(min(MAX_POINTS, swipePoints + taps))
        val ys = ArrayList<Float>(xs.size)
        val codes = ArrayList<Int>(xs.size)

        for (s in ordered) {
            if (s.kind == TapSwipeSession.Kind.TAP) {
                xs.add(s.x[0]); ys.add(s.y[0]); codes.add(Character.toLowerCase(s.codePoint))
                continue
            }
            val share = if (swipePoints <= swipeBudget) s.x.size
                        else max(2, s.x.size * swipeBudget / max(1, swipePoints))
            val take = min(s.x.size, share)
            for (i in 0 until take) {
                val src = if (take == 1) 0 else (i.toLong() * (s.x.size - 1) / (take - 1)).toInt()
                xs.add(s.x[src]); ys.add(s.y[src]); codes.add(-1)
            }
        }
        if (xs.isEmpty()) return null
        return Flat(xs.toFloatArray(), ys.toFloatArray(), codes.toIntArray())
    }

    /**
     * How much to trust one *swiped* letter's attribution.
     *
     * Mis-segmentation is this approach's failure mode, so the weight leans on how clearly a point
     * belonged to its letter rather than treating every alignment as equally sound: a point beside
     * a segment boundary could belong to either neighbour; a longer segment means the stroke really
     * dwelt near that key rather than sweeping past; and the first and last letters are anchored by
     * the stroke's own endpoints rather than by an inferred boundary.
     */
    private fun weightFor(
        letterIdx: Int, letterCount: Int, segCount: Int, pointIdx: Int, path: IntArray, n: Int
    ): Float {
        var back = 0
        var k = pointIdx
        while (k > 0 && path[k - 1] == path[pointIdx]) { k--; back++ }
        var forward = 0
        k = pointIdx
        while (k < n - 1 && path[k + 1] == path[pointIdx]) { k++; forward++ }
        val interior = min(back, forward)

        val interiority = 0.4f + 0.6f * min(1f, interior / 3f)
        val dwell = min(1f, segCount / 10f)
        val anchored = if (letterIdx == 0 || letterIdx == letterCount - 1) 1.15f else 1.0f

        return min(1f, interiority * dwell * anchored)
    }

    /**
     * DTW returning, for each point, the letter it was assigned to.
     *
     * Two constraints beyond plain DTW. Order is preserved and every letter gets at least one point,
     * because a swipe visits its letters in sequence - an alignment that skips or reorders them is
     * wrong however little it costs. And a tap point may only sit on a letter matching its code
     * point, which is what turns taps into anchors: if no path satisfies every tap, the word is not
     * alignable and we learn nothing from it rather than guessing.
     */
    private fun warpPath(
        flat: Flat, letters: IntArray,
        tx: FloatArray, ty: FloatArray,
        n: Int, m: Int
    ): IntArray? {
        val inf = Float.MAX_VALUE / 4f
        val cost = Array(n) { FloatArray(m) { inf } }
        val from = Array(n) { ByteArray(m) }

        fun localCost(i: Int, j: Int): Float {
            val tc = flat.tapCode[i]
            if (tc >= 0 && tc != letters[j]) return inf
            return dist2(flat.x[i], flat.y[i], tx[j], ty[j])
        }

        val c0 = localCost(0, 0)
        cost[0][0] = if (c0 >= inf) inf else c0

        for (i in 1 until n) {
            val maxJ = min(m - 1, i)
            for (j in 0..maxJ) {
                // Every remaining letter still needs a point.
                if (m - 1 - j > n - 1 - i) continue
                val d = localCost(i, j)
                if (d >= inf) continue
                val stay = cost[i - 1][j]
                val step = if (j > 0) cost[i - 1][j - 1] else inf
                if (stay >= inf && step >= inf) continue
                if (stay <= step) {
                    cost[i][j] = stay + d
                    from[i][j] = 0
                } else {
                    cost[i][j] = step + d
                    from[i][j] = 1
                }
            }
        }
        if (cost[n - 1][m - 1] >= inf) return null

        val path = IntArray(n)
        var j = m - 1
        for (i in n - 1 downTo 0) {
            path[i] = j
            if (i > 0 && from[i][j].toInt() == 1) j--
        }
        return if (j == 0) path else null
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    /**
     * Rejects an alignment whose average residual is too large to believe.
     *
     * If the input does not really pass near the letters of the word it decoded to - a wild swipe
     * rescued by the language model, say - then the offsets are not aim, they are the model having
     * guessed well. Learning from those would teach the keyboard nonsense.
     */
    fun isPlausible(attributions: List<Attribution>, halfW: Float, halfH: Float): Boolean {
        if (attributions.isEmpty()) return false
        val limit = 1.5f * sqrt(halfW * halfW + halfH * halfH)
        var total = 0f
        for (a in attributions) total += sqrt(a.dx * a.dx + a.dy * a.dy)
        val mean = total / attributions.size
        if (!mean.isFinite() || mean > limit) return false
        return attributions.none { abs(it.dx) > 3f * halfW || abs(it.dy) > 3f * halfH }
    }
}
