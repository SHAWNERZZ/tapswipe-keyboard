package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.tapswipe.TapSwipeTimingOrder.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSwipeContextTest {
    @Test
    fun `the word being composed is not context`() {
        assertEquals(listOf("view"), TapSwipeContext.dropComposing("view fromr", listOf("view", "fromr")))
        assertEquals(listOf<String>(), TapSwipeContext.dropComposing("The", listOf("The")))
    }

    @Test
    fun `after a space every word is context`() {
        assertEquals(listOf("view", "form"), TapSwipeContext.dropComposing("view form ", listOf("view", "form")))
    }
}

class TapSwipeTimingOrderTest {
    // The user's "form", 2026-11-11: tap F, swipe O to M, other thumb taps R during the swipe.
    private val form = listOf(
        Timing(true, 'f'.code, false, 0f, 112f),
        Timing(false, 0, false, 183f, 412f),
        Timing(true, 'r'.code, true, 408f, 516f),
    )

    @Test
    fun `form fits the timing and from runs backwards`() {
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("form", form)!!, 0.01f)
        // "from" puts R before O: the R tap started at 408, the swipe began at 183.
        assertEquals(408f - 183f - TapSwipeTimingOrder.TOLERANCE_MS,
            TapSwipeTimingOrder.inversionMs("from", form)!!, 0.01f)
    }

    @Test
    fun `the timing penalty flips the user's ranking`() {
        // Scores from the user's recording: from 9.97, form 8.01.
        val (order, _) = TapSwipeTimingOrder.rerank(listOf("from", "form", "former"), listOf(9.97f, 8.01f, 0.56f), form)
        assertEquals(1, order[0])
    }

    @Test
    fun `a genuine from, with R tapped before the swipe, keeps from`() {
        val from = listOf(
            Timing(true, 'f'.code, false, 0f, 100f),
            Timing(true, 'r'.code, true, 110f, 150f),
            Timing(false, 0, false, 250f, 450f),
        )
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("from", from)!!, 0.01f)
        assertTrue(TapSwipeTimingOrder.inversionMs("form", from)!! > 0f)
    }

    @Test
    fun `the user's then still reads then`() {
        // Tap T, swipe H to N, other thumb taps E as the swipe lifts.
        val then = listOf(
            Timing(true, 't'.code, false, 0f, 80f),
            Timing(false, 0, false, 150f, 305f),
            Timing(true, 'e'.code, true, 305f, 380f),
        )
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("then", then)!!, 0.01f)
    }

    @Test
    fun `one finger at a time is never reranked`() {
        val seq = listOf(Timing(true, 'f'.code, false, 0f, 100f), Timing(false, 0, false, 200f, 400f))
        val (order, pen) = TapSwipeTimingOrder.rerank(listOf("from", "form"), listOf(9f, 8f), seq)
        assertEquals(listOf(0, 1), order)
        assertEquals(listOf(0f, 0f), pen)
    }

    @Test
    fun `a word missing a tapped key cannot be attributed`() {
        assertNull(TapSwipeTimingOrder.inversionMs("film", form))
    }
}

class TapSwipeTimingPositionTest {
    @Test
    fun `a second finger folded into the gesture is a tap on the key under it`() {
        // The user's form #46: tap F, swipe O to M, R pressed by the other thumb during the swipe and
        // folded into the gesture as a barely moving segment over R.
        val strokes = listOf(
            Timing(true, 'f'.code, false, -113f, 0f),
            Timing(false, 0, false, 70f, 295f, startKeys = setOf('o', 'i', 'p', 'l', 'k'), endKeys = setOf('m', 'n', 'k')),
            Timing(true, 0, true, 225f, 402f, startKeys = setOf('r', 'e', 't', 'd', 'f')),
        )
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("form", strokes)!!, 0.01f)
        assertTrue(TapSwipeTimingOrder.inversionMs("from", strokes)!! > 100f)
    }
}

class TapSwipeTimingOverlapTest {
    @Test
    fun `overlapping thumbs, the second starting before the first lifts, is not an inversion`() {
        // The user's "house": one thumb H to O to U (689..1018 ms), the other S to E (926..1081 ms).
        val strokes = listOf(
            Timing(false, 0, false, 689f, 1018f, startKeys = setOf('h', 'g', 'j'), endKeys = setOf('i', 'o', 'u', 'k')),
            Timing(false, 0, true, 926f, 1081f, startKeys = setOf('s', 'a', 'd'), endKeys = setOf('e', 'w', 'r')),
        )
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("house", strokes)!!, 0.01f)
    }
}

/** The user's two techniques, 2026-11-12, on a grid of 1-unit keys. */
class TapSwipePathTimingTest {
    private val pos = HashMap<Char, FloatArray>().apply {
        "qwertyuiop".forEachIndexed { i, c -> put(c, floatArrayOf(i.toFloat(), 0f)) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, floatArrayOf(i + 0.5f, 1f)) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, floatArrayOf(i + 1.5f, 2f)) }
    }
    private val geo = TapSwipeTimingOrder.Geometry({ pos[it] }, 1f, 1f)

    /** A swipe through the given keys, at even speed, from t0 to t1, sampled every 10 ms. */
    private fun swipe(keys: String, t0: Float, t1: Float, right: Boolean): Timing {
        val pts = keys.map { pos[it]!! }
        val n = ((t1 - t0) / 10f).toInt() + 1
        val xs = FloatArray(n); val ys = FloatArray(n); val ts = FloatArray(n)
        val legs = pts.size - 1
        for (i in 0 until n) {
            val u = i.toFloat() / (n - 1) * legs
            val leg = minOf(u.toInt(), legs - 1)
            val f = u - leg
            xs[i] = pts[leg][0] + f * (pts[leg + 1][0] - pts[leg][0])
            ys[i] = pts[leg][1] + f * (pts[leg + 1][1] - pts[leg][1])
            ts[i] = t0 + i * 10f
        }
        // As the app does: letters within about one key of where the swipe started and ended.
        fun near(x: Float, y: Float) = pos.filter { (_, p) ->
            kotlin.math.hypot((p[0] - x).toDouble(), (p[1] - y).toDouble()) <= 1.0 }.keys
        return Timing(false, 0, right, t0, t1, startKeys = near(xs.first(), ys.first()),
            endKeys = near(xs.last(), ys.last()), xs = xs, ys = ys, ts = ts)
    }

    @Test
    fun `form - F thumb slides to R and lifts after the other thumb touched O`() {
        // Left: F to R, 0..300 ms. Right: touches O at 120 ms, slides to M by 450 ms.
        val strokes = listOf(swipe("fr", 0f, 300f, false), swipe("om", 120f, 450f, true))
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("form", strokes, geo)!!, 0.01f)
        assertTrue(TapSwipeTimingOrder.inversionMs("from", strokes, geo)!! > 50f)
    }

    @Test
    fun `from - one thumb drags F to O to M, the other taps R on the way to O`() {
        // Right: F to O to M, 0..600 ms, passing O at 300. Left: taps R at 150 ms.
        val strokes = listOf(
            Timing(true, 'r'.code, false, 150f, 230f),
            swipe("fom", 0f, 600f, true),
        )
        assertEquals(0f, TapSwipeTimingOrder.inversionMs("from", strokes, geo)!!, 0.01f)
        assertTrue(TapSwipeTimingOrder.inversionMs("form", strokes, geo)!! > 50f)
    }
}
