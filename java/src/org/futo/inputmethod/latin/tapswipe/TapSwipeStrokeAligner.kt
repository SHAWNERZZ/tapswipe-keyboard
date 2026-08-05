package org.futo.inputmethod.latin.tapswipe

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Attributes points along a swipe path to the letters of the word it produced.
 *
 * A swipe gives no per-letter touch events - it is one continuous path over several keys, so
 * unlike a tap there is nothing that says "the user aimed at L, here". The decoder knows, but it is
 * a prebuilt native model: strokes go in, a word comes out, and there is no per-letter attribution
 * to read back. So we recover it ourselves.
 *
 * Dynamic time warping does the job. Given the stroke and the (now known) letter sequence, find the
 * monotonic assignment of points to letters minimising total distance to each letter's key centre.
 * That segments the path, and each letter's closest approach within its own segment is the best
 * available estimate of where the user was aiming for it.
 *
 * The interesting property is that this reaches the **middle** of a word. Learning only from a
 * stroke's endpoints is the easy version - the ends are unambiguous - but the middle is where
 * corner-cutting and drift actually live, which is exactly the sloppiness worth learning.
 *
 * Everything is in normalized layout space.
 */
object TapSwipeStrokeAligner {

    /**
     * Longest stroke fed to the DP. Cost is O(points x letters); real strokes can run to several
     * hundred points and the extra resolution buys nothing, since the shape is smooth at this
     * scale.
     */
    private const val MAX_POINTS = 96

    /**
     * A letter needs at least this many points in its segment before its closest approach is
     * treated as a real measurement. One or two points is the aligner having to put the boundary
     * *somewhere*, not evidence about aim.
     */
    private const val MIN_SEGMENT_POINTS = 3

    /** One letter's recovered aim point. */
    data class Attribution(
        val codePoint: Int,
        /** Offset from the letter's nominal key centre, normalized space. */
        val dx: Float,
        val dy: Float,
        /** 0..1 - how much this observation deserves to be trusted. */
        val weight: Float
    )

    /**
     * @param word the decoded word, already accepted by the user
     * @param strokeX,strokeY the full swipe path in normalized space
     * @param centreOf nominal key centre for a code point, or null if the layout lacks it.
     *   **Must be the nominal centre, never a personalized one** - see [align].
     */
    fun align(
        word: String,
        strokeX: FloatArray,
        strokeY: FloatArray,
        centreOf: (Int) -> FloatArray?
    ): List<Attribution> {
        if (word.length < 2) return emptyList()
        if (strokeX.size != strokeY.size || strokeX.size < word.length) return emptyList()

        // Letter targets. A word containing anything the layout cannot place is not alignable -
        // a missing letter would silently shift every later letter's attribution.
        val letters = IntArray(word.length)
        val targetX = FloatArray(word.length)
        val targetY = FloatArray(word.length)
        for (i in word.indices) {
            val cp = Character.toLowerCase(word[i].code)
            val c = centreOf(cp) ?: return emptyList()
            letters[i] = cp
            targetX[i] = c[0]
            targetY[i] = c[1]
        }

        val (px, py) = downsample(strokeX, strokeY)
        val n = px.size
        val m = letters.size
        if (n < m) return emptyList()

        val path = warpPath(px, py, targetX, targetY, n, m) ?: return emptyList()

        // Closest approach within each letter's own segment.
        val out = ArrayList<Attribution>(m)
        for (j in 0 until m) {
            var bestIdx = -1
            var bestD2 = Float.MAX_VALUE
            var segCount = 0
            for (i in 0 until n) {
                if (path[i] != j) continue
                segCount++
                val d2 = dist2(px[i], py[i], targetX[j], targetY[j])
                if (d2 < bestD2) { bestD2 = d2; bestIdx = i }
            }
            if (bestIdx < 0 || segCount < MIN_SEGMENT_POINTS) continue

            out.add(
                Attribution(
                    codePoint = letters[j],
                    dx = px[bestIdx] - targetX[j],
                    dy = py[bestIdx] - targetY[j],
                    weight = weightFor(j, m, segCount, bestIdx, path, n)
                )
            )
        }
        return out
    }

    /**
     * How much to trust one letter's attribution.
     *
     * Mis-segmentation is this approach's failure mode, so the weight leans on how clearly the
     * point belonged to its letter rather than treating every alignment as equally sound:
     *
     * - a point sitting next to a segment boundary could belong to either neighbour, so it counts
     *   for less than one in the middle of its segment;
     * - a longer segment means the stroke genuinely dwelt near that key rather than sweeping past;
     * - the first and last letters are the most reliable of all, being anchored by the stroke's own
     *   endpoints rather than by an inferred boundary.
     */
    private fun weightFor(
        letterIdx: Int, letterCount: Int, segCount: Int, pointIdx: Int, path: IntArray, n: Int
    ): Float {
        var distanceToEdge = 0
        var k = pointIdx
        while (k > 0 && path[k - 1] == path[pointIdx]) { k--; distanceToEdge++ }
        var forward = 0
        k = pointIdx
        while (k < n - 1 && path[k + 1] == path[pointIdx]) { k++; forward++ }
        val interior = min(distanceToEdge, forward)

        // 0.4 at a boundary, approaching 1 well inside the segment.
        val interiority = 0.4f + 0.6f * min(1f, interior / 3f)
        // Segments of 3 points count for little; 10+ counts fully.
        val dwell = min(1f, segCount / 10f)
        val anchored = if (letterIdx == 0 || letterIdx == letterCount - 1) 1.15f else 1.0f

        return min(1f, interiority * dwell * anchored)
    }

    /**
     * DTW returning, for each stroke point, the letter it was assigned to.
     *
     * Constrained so every letter gets at least one point and order is preserved - a swipe visits
     * its letters in sequence, so an alignment that skips or reorders them is wrong by
     * construction, whatever it costs.
     */
    private fun warpPath(
        px: FloatArray, py: FloatArray,
        tx: FloatArray, ty: FloatArray,
        n: Int, m: Int
    ): IntArray? {
        val inf = Float.MAX_VALUE / 4f
        val cost = Array(n) { FloatArray(m) { inf } }
        val from = Array(n) { ByteArray(m) }   // 0 = same letter, 1 = advanced a letter

        cost[0][0] = dist2(px[0], py[0], tx[0], ty[0])
        for (i in 1 until n) {
            val maxJ = min(m - 1, i)
            for (j in 0..maxJ) {
                // Every remaining letter still needs a point.
                if (m - 1 - j > n - 1 - i) continue
                val d = dist2(px[i], py[i], tx[j], ty[j])
                val stay = cost[i - 1][j]
                val step = if (j > 0) cost[i - 1][j - 1] else inf
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

    /** Uniform subsample; strokes are smooth at this scale so nothing meaningful is lost. */
    private fun downsample(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
        val n = xs.size
        if (n <= MAX_POINTS) return xs to ys
        val ox = FloatArray(MAX_POINTS)
        val oy = FloatArray(MAX_POINTS)
        for (i in 0 until MAX_POINTS) {
            val src = (i.toLong() * (n - 1) / (MAX_POINTS - 1)).toInt()
            ox[i] = xs[src]
            oy[i] = ys[src]
        }
        return ox to oy
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    /**
     * Rejects an alignment whose average residual is too large to believe.
     *
     * If the stroke does not really pass near the letters of the word it decoded to - a wild swipe
     * rescued by the language model, say - then the "offsets" are not aim, they are the model
     * having guessed well. Learning from those would teach the keyboard nonsense.
     */
    fun isPlausible(attributions: List<Attribution>, halfW: Float, halfH: Float): Boolean {
        if (attributions.isEmpty()) return false
        val limit = 1.5f * sqrt(halfW * halfW + halfH * halfH)
        var total = 0f
        for (a in attributions) total += sqrt(a.dx * a.dx + a.dy * a.dy)
        val mean = total / attributions.size
        if (!mean.isFinite() || mean > limit) return false
        // A single wild letter also disqualifies the word.
        return attributions.none { abs(it.dx) > 3f * halfW || abs(it.dy) > 3f * halfH }
    }
}
