package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM tests for swipe-to-letter attribution.
 *
 * The aligner is the one piece of the adaptive-geometry feature with real logic and no Android
 * dependencies, and it is the piece whose failure mode is silent: a mis-segmented stroke still
 * produces plausible-looking offsets, which then persist and move keys the wrong way. So the cases
 * here are mostly about what it *refuses* to do.
 *
 *     ./gradlew testUnstableDebugUnitTest
 */
class TapSwipeStrokeAlignerTest {

    /** Three keys in a row, a quarter of the way apart, like a slice of a keyboard row. */
    private val centres = mapOf(
        'a'.code to floatArrayOf(0.1f, 0.5f),
        'b'.code to floatArrayOf(0.5f, 0.5f),
        'c'.code to floatArrayOf(0.9f, 0.5f)
    )

    private fun centreOf(cp: Int): FloatArray? = centres[cp]

    private fun swipeStroke(
        xs: FloatArray, ys: FloatArray, t0: Float = 0f
    ) = TapSwipeStrokeAligner.StrokeInput(
        TapSwipeSession.Kind.SWIPE, 0, xs, ys,
        FloatArray(xs.size) { t0 + it * 16f }
    )

    private fun tapStroke(cp: Int, x: Float, y: Float, t: Float) =
        TapSwipeStrokeAligner.StrokeInput(
            TapSwipeSession.Kind.TAP, cp, floatArrayOf(x), floatArrayOf(y), floatArrayOf(t)
        )

    /** The old single-stroke call, kept so the existing cases read unchanged. */
    private fun alignOne(word: String, xs: FloatArray, ys: FloatArray) =
        TapSwipeStrokeAligner.align(word, listOf(swipeStroke(xs, ys)), ::centreOf)

    private val halfW = 0.1f
    private val halfH = 0.1f

    /** A straight path through the three key centres, [perLeg] points per leg. */
    private fun straightStroke(perLeg: Int = 20, dy: Float = 0f): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val pts = listOf(0.1f to 0.5f, 0.5f to 0.5f, 0.9f to 0.5f)
        xs.add(pts[0].first); ys.add(pts[0].second + dy)
        for (i in 1 until pts.size) {
            for (s in 1..perLeg) {
                val f = s.toFloat() / perLeg
                xs.add(pts[i - 1].first + (pts[i].first - pts[i - 1].first) * f)
                ys.add(pts[i - 1].second + (pts[i].second - pts[i - 1].second) * f + dy)
            }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a clean stroke through three keys attributes all three`() {
        val (xs, ys) = straightStroke()
        val out = alignOne("abc", xs, ys)

        assertEquals(3, out.size)
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), out.map { it.codePoint })
    }

    @Test
    fun `a stroke landing dead on the centres reports no offset`() {
        val (xs, ys) = straightStroke()
        val out = alignOne("abc", xs, ys)

        out.forEach {
            assertTrue("expected ~0 offset for '${it.codePoint.toChar()}', got ${it.dx},${it.dy}",
                abs(it.dx) < 0.02f && abs(it.dy) < 0.02f)
        }
    }

    /**
     * The property the whole feature rests on: a stroke consistently offset from the key centres
     * must produce offsets in that direction, so the model learns to move the keys there.
     */
    @Test
    fun `a stroke riding consistently low reports a downward offset`() {
        val (xs, ys) = straightStroke(dy = 0.05f)
        val out = alignOne("abc", xs, ys)

        assertTrue(out.isNotEmpty())
        out.forEach {
            assertTrue("expected +dy for '${it.codePoint.toChar()}', got ${it.dy}", it.dy > 0.02f)
        }
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `a word with a letter the layout cannot place is not aligned`() {
        val (xs, ys) = straightStroke()
        // 'z' has no centre - aligning anyway would shift every later letter's attribution.
        assertTrue(alignOne("azc", xs, ys).isEmpty())
    }

    @Test
    fun `a stroke with fewer points than letters is not aligned`() {
        val xs = floatArrayOf(0.1f, 0.5f)
        val ys = floatArrayOf(0.5f, 0.5f)
        assertTrue(alignOne("abc", xs, ys).isEmpty())
    }

    @Test
    fun `a two-letter minimum is enforced`() {
        val (xs, ys) = straightStroke()
        assertTrue(alignOne("a", xs, ys).isEmpty())
    }

    @Test
    fun `attributions stay in letter order`() {
        val (xs, ys) = straightStroke()
        val out = alignOne("abc", xs, ys)
        // A swipe visits its letters in sequence; an alignment that reorders them is wrong however
        // little it costs.
        assertEquals(out.map { it.codePoint }, out.map { it.codePoint }.distinct())
    }

    // ---------------------------------------------------------------- plausibility

    @Test
    fun `a stroke nowhere near its word is rejected as implausible`() {
        // Path far below the keys: the language model rescued the word, the geometry says nothing.
        val xs = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        val ys = FloatArray(5) { 2.0f }
        val out = alignOne("abc", xs, ys)

        assertFalse(TapSwipeStrokeAligner.isPlausible(out, halfW, halfH))
    }

    @Test
    fun `a clean stroke is plausible`() {
        val (xs, ys) = straightStroke()
        val out = alignOne("abc", xs, ys)

        assertTrue(TapSwipeStrokeAligner.isPlausible(out, halfW, halfH))
    }

    @Test
    fun `an empty attribution list is never plausible`() {
        assertFalse(TapSwipeStrokeAligner.isPlausible(emptyList(), halfW, halfH))
    }

    // ---------------------------------------------------------------- multi-stroke

    /**
     * The common shape: swipe part of a word, tap the rest. Sequential strokes are ordered by the
     * clock, so this attributes cleanly and must not be thrown away.
     */
    @Test
    fun `a swipe followed by a tap attributes every letter`() {
        // Swipe a to b, then tap c.
        val xs = FloatArray(21) { 0.1f + (0.4f * it / 20f) }
        val ys = FloatArray(21) { 0.5f }
        val strokes = listOf(
            swipeStroke(xs, ys, t0 = 0f),
            tapStroke('c'.code, 0.9f, 0.5f, t = 400f)
        )

        val out = TapSwipeStrokeAligner.align("abc", strokes, ::centreOf)

        assertEquals(3, out.size)
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), out.map { it.codePoint })
        assertTrue("the tapped letter should be marked as a tap",
            out.first { it.codePoint == 'c'.code }.fromTap)
    }

    @Test
    fun `a tapped letter reports its exact offset from the key centre`() {
        val xs = FloatArray(21) { 0.1f + (0.4f * it / 20f) }
        val ys = FloatArray(21) { 0.5f }
        // Tap c well right of and below its centre (0.9, 0.5).
        val strokes = listOf(
            swipeStroke(xs, ys),
            tapStroke('c'.code, 0.94f, 0.53f, t = 400f)
        )

        val c = TapSwipeStrokeAligner.align("abc", strokes, ::centreOf)
            .first { it.codePoint == 'c'.code }

        assertEquals(0.04f, c.dx, 1e-4f)
        assertEquals(0.03f, c.dy, 1e-4f)
    }

    /**
     * The anchoring property: a tap can only land on a letter matching its code point. A tap whose
     * letter is nowhere in the word means the alignment cannot be trusted at all.
     */
    @Test
    fun `a tap on a letter absent from the word blocks the alignment`() {
        val xs = FloatArray(21) { 0.1f + (0.4f * it / 20f) }
        val ys = FloatArray(21) { 0.5f }
        val strokes = listOf(
            swipeStroke(xs, ys),
            tapStroke('c'.code, 0.9f, 0.5f, t = 400f)
        )

        // Word has no 'c' at all.
        assertTrue(TapSwipeStrokeAligner.align("ab", strokes, ::centreOf).isEmpty())
    }

    @Test
    fun `taps carry more weight than inferred swipe segments`() {
        val xs = FloatArray(21) { 0.1f + (0.4f * it / 20f) }
        val ys = FloatArray(21) { 0.5f }
        val out = TapSwipeStrokeAligner.align(
            "abc",
            listOf(swipeStroke(xs, ys), tapStroke('c'.code, 0.9f, 0.5f, t = 400f)),
            ::centreOf
        )

        val tap = out.first { it.fromTap }
        assertTrue("tap weight should be full, was ${tap.weight}", tap.weight >= 1f)
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `sequential strokes are not treated as concurrent`() {
        val a = swipeStroke(floatArrayOf(0.1f, 0.3f), floatArrayOf(0.5f, 0.5f), t0 = 0f)
        val b = tapStroke('c'.code, 0.9f, 0.5f, t = 500f)

        assertFalse(TapSwipeStrokeAligner.overlapsInTime(listOf(a, b)))
    }

    /** Two thumbs: the decoder orders these by lexicon, so the clock cannot attribute them. */
    @Test
    fun `overlapping strokes are detected as concurrent`() {
        val a = swipeStroke(FloatArray(20) { 0.1f }, FloatArray(20) { 0.5f }, t0 = 0f)
        val b = swipeStroke(FloatArray(20) { 0.9f }, FloatArray(20) { 0.5f }, t0 = 100f)

        // a runs 0..304ms, b starts at 100ms.
        assertTrue(TapSwipeStrokeAligner.overlapsInTime(listOf(a, b)))
    }

    @Test
    fun `concurrency detection ignores stroke ordering in the list`() {
        val a = swipeStroke(FloatArray(20) { 0.1f }, FloatArray(20) { 0.5f }, t0 = 0f)
        val b = swipeStroke(FloatArray(20) { 0.9f }, FloatArray(20) { 0.5f }, t0 = 100f)

        assertTrue(TapSwipeStrokeAligner.overlapsInTime(listOf(b, a)))
    }

    // ---------------------------------------------------------------- weighting

    @Test
    fun `weights stay within range`() {
        val (xs, ys) = straightStroke()
        val out = alignOne("abc", xs, ys)

        out.forEach {
            assertTrue("weight out of range: ${it.weight}", it.weight > 0f && it.weight <= 1f)
        }
    }
}
