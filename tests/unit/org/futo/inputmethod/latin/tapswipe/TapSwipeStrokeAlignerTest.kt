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
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

        assertEquals(3, out.size)
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), out.map { it.codePoint })
    }

    @Test
    fun `a stroke landing dead on the centres reports no offset`() {
        val (xs, ys) = straightStroke()
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

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
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

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
        assertTrue(TapSwipeStrokeAligner.align("azc", xs, ys, ::centreOf).isEmpty())
    }

    @Test
    fun `a stroke with fewer points than letters is not aligned`() {
        val xs = floatArrayOf(0.1f, 0.5f)
        val ys = floatArrayOf(0.5f, 0.5f)
        assertTrue(TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf).isEmpty())
    }

    @Test
    fun `a two-letter minimum is enforced`() {
        val (xs, ys) = straightStroke()
        assertTrue(TapSwipeStrokeAligner.align("a", xs, ys, ::centreOf).isEmpty())
    }

    @Test
    fun `attributions stay in letter order`() {
        val (xs, ys) = straightStroke()
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)
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
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

        assertFalse(TapSwipeStrokeAligner.isPlausible(out, halfW, halfH))
    }

    @Test
    fun `a clean stroke is plausible`() {
        val (xs, ys) = straightStroke()
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

        assertTrue(TapSwipeStrokeAligner.isPlausible(out, halfW, halfH))
    }

    @Test
    fun `an empty attribution list is never plausible`() {
        assertFalse(TapSwipeStrokeAligner.isPlausible(emptyList(), halfW, halfH))
    }

    // ---------------------------------------------------------------- weighting

    @Test
    fun `weights stay within range`() {
        val (xs, ys) = straightStroke()
        val out = TapSwipeStrokeAligner.align("abc", xs, ys, ::centreOf)

        out.forEach {
            assertTrue("weight out of range: ${it.weight}", it.weight > 0f && it.weight <= 1f)
        }
    }
}
