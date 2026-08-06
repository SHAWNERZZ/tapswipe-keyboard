package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the TapSwipe session state machine.
 *
 * Every case here corresponds to a bug that actually shipped. The session is the one piece of this
 * feature with no Android dependencies, so these run in seconds with no device:
 *
 *     ./gradlew testUnstableDebugUnitTest
 */
class TapSwipeSessionTest {

    private fun session() = TapSwipeSession()

    private fun TapSwipeSession.tap(cp: Int, t: Float = 0f) =
        addTap(cp, 0.5f, 0.5f, t)

    private fun TapSwipeSession.swipe(t: Float = 0f) = addStroke(
        TapSwipeSession.Stroke(
            TapSwipeSession.Kind.SWIPE, TapSwipeSession.Hand.LEFT,
            floatArrayOf(0.1f, 0.2f, 0.3f),
            floatArrayOf(0.1f, 0.2f, 0.3f),
            floatArrayOf(t, t + 16f, t + 32f),
            0
        )
    )

    // ---------------------------------------------------------------- popLastStroke

    /**
     * Regression: clearing the anchor on pop also cleared hasComposed, which the apply path reads
     * to decide whether it is continuing a word or starting one. That made the rewrite call
     * finishComposingText() and append the re-decode after the old text - "But" + "By".
     */
    @Test
    fun `pop preserves hasComposed so the rewrite replaces rather than appends`() {
        val s = session()
        s.swipe()
        s.tap('t'.code)
        s.noteComposingWrite("but", 42)
        assertTrue(s.hasComposed)

        s.popLastStroke()

        assertTrue("pop must not clear hasComposed", s.hasComposed)
        assertEquals("but", s.lastComposedText)
    }

    /**
     * Regression: pop did not bump the generation, so a decode already in flight for the pre-pop
     * evidence passed the staleness guard and was applied to strokes that no longer existed.
     */
    @Test
    fun `pop bumps the generation so in-flight decodes are rejected`() {
        val s = session()
        s.swipe()
        s.tap('t'.code)
        val before = s.generation

        s.popLastStroke()

        assertNotEquals(before, s.generation)
        assertFalse(s.isCurrent(before))
    }

    @Test
    fun `popping the final stroke closes the session`() {
        val s = session()
        s.tap('a'.code)
        val before = s.generation

        assertTrue(s.popLastStroke())

        assertFalse(s.isOpen)
        assertFalse("a closed session must invalidate in-flight work", s.isCurrent(before))
    }

    @Test
    fun `popping an empty session reports no work done`() {
        assertFalse(session().popLastStroke())
    }

    // ---------------------------------------------------------------- validate-on-read

    /**
     * Regression: the first stroke is absorbed before the decode is written back, so there is a
     * window where strokes exist and the editor is not composing yet. Enforcing the invariant
     * there destroyed the stroke that had just been absorbed.
     */
    @Test
    fun `validate tolerates the window before the first composing write`() {
        val s = session()
        s.swipe()

        assertTrue(s.validateOrReset(isComposingWord = false, typedWord = "", expectedSelStart = 5))
        assertTrue("session must survive the pre-write window", s.isOpen)
    }

    @Test
    fun `validate drops the session once the editor stops composing`() {
        val s = session()
        s.swipe()
        s.noteComposingWrite("by", 10)

        assertFalse(s.validateOrReset(isComposingWord = false, typedWord = "", expectedSelStart = 10))
        assertFalse(s.isOpen)
    }

    @Test
    fun `validate drops the session when the composing word was replaced`() {
        val s = session()
        s.swipe()
        s.noteComposingWrite("by", 10)

        assertFalse(s.validateOrReset(isComposingWord = true, typedWord = "elsewhere", expectedSelStart = 10))
        assertFalse(s.isOpen)
    }

    @Test
    fun `validate drops the session when the cursor moved`() {
        val s = session()
        s.swipe()
        s.noteComposingWrite("by", 10)

        assertFalse(s.validateOrReset(isComposingWord = true, typedWord = "by", expectedSelStart = 25))
        assertFalse(s.isOpen)
    }

    @Test
    fun `validate keeps a session whose editor state still matches`() {
        val s = session()
        s.swipe()
        s.noteComposingWrite("by", 10)

        assertTrue(s.validateOrReset(isComposingWord = true, typedWord = "by", expectedSelStart = 10))
        assertTrue(s.isOpen)
    }

    /**
     * The cap is enforced where strokes are added, so a session can reach MAX_STROKES but never
     * exceed it, and a word sitting exactly at the cap is still valid.
     *
     * The matching `size > MAX_STROKES` check inside validateOrReset is therefore unreachable
     * through addStroke - it is defence in depth for some future path that appends strokes another
     * way, not a condition normal use can produce. Worth knowing rather than assuming it fires.
     */
    @Test
    fun `a session at the stroke cap is still valid`() {
        val s = session()
        repeat(TapSwipeSession.MAX_STROKES + 4) { s.swipe(it * 100f) }
        s.noteComposingWrite("x", 1)

        assertEquals(TapSwipeSession.MAX_STROKES, s.strokes.size)
        assertTrue(s.validateOrReset(isComposingWord = true, typedWord = "x", expectedSelStart = 1))
        assertTrue(s.isOpen)
    }

    @Test
    fun `strokes are capped rather than accumulating without bound`() {
        val s = session()
        repeat(TapSwipeSession.MAX_STROKES + 10) { s.tap('a'.code + (it % 26), it * 100f) }

        assertTrue(s.strokes.size <= TapSwipeSession.MAX_STROKES)
    }

    // ---------------------------------------------------------------- cadence

    @Test
    fun `cadence is unknown with fewer than two taps`() {
        val s = session()
        assertEquals(-1, s.medianTapGapMs())
        s.noteTapTime(1000)
        assertEquals(-1, s.medianTapGapMs())
    }

    @Test
    fun `cadence is the median gap between taps`() {
        val s = session()
        listOf(0L, 100L, 200L, 300L).forEach { s.noteTapTime(it) }
        assertEquals(100, s.medianTapGapMs())
    }

    /**
     * Median, not mean: one pause - reaching for a far key, or a moment's thought - must not by
     * itself make fluent typing look like deliberate spelling.
     */
    @Test
    fun `a single long pause does not drag fluent typing into peck territory`() {
        val s = session()
        listOf(0L, 80L, 160L, 1200L, 1280L, 1360L).forEach { s.noteTapTime(it) }

        assertTrue("median should stay fast, was ${s.medianTapGapMs()}", s.medianTapGapMs() < 200)
    }

    // ---------------------------------------------------------------- derived state

    @Test
    fun `literal text is derived from taps only`() {
        val s = session()
        s.tap('c'.code)
        s.tap('a'.code)
        s.swipe()
        s.tap('t'.code)

        assertEquals("cat", s.literalText)
    }

    @Test
    fun `hasSwipe distinguishes peck words from swiped ones`() {
        val peck = session()
        peck.tap('h'.code); peck.tap('i'.code)
        assertFalse(peck.hasSwipe)
        assertEquals(2, peck.tapCount)

        val swiped = session()
        swiped.tap('h'.code); swiped.swipe()
        assertTrue(swiped.hasSwipe)
    }

    @Test
    fun `reset clears strokes, cadence, latch and anchor together`() {
        val s = session()
        s.swipe()
        s.tap('t'.code, 10f)
        s.noteTapTime(1000)
        s.noteTapTime(1300)
        s.noteComposingWrite("but", 7)
        s.latchMode(TapSwipeMode.PECK)

        s.reset("test")

        assertFalse(s.isOpen)
        assertFalse(s.hasComposed)
        assertEquals("", s.lastComposedText)
        assertEquals(null, s.latchedMode)
        assertEquals(-1, s.medianTapGapMs())
        assertEquals("", s.literalText)
    }

    // ---------------------------------------------------------------- restoreStrokes

    /**
     * The grace-reopen path's whole reason to exist: rebuild a session from a snapshot taken
     * before an earlier reset, so a stroke can be popped from a word that already committed.
     */
    @Test
    fun `restoreStrokes rebuilds a session closed by reset`() {
        val original = session()
        original.swipe()
        original.tap('t'.code)
        val snapshot = original.strokes.toList()
        original.reset("finalized")
        assertFalse(original.isOpen)

        val revived = session()
        revived.restoreStrokes(snapshot)

        assertTrue(revived.isOpen)
        assertEquals(2, revived.strokes.size)
    }

    @Test
    fun `restoreStrokes leaves an already-open session untouched`() {
        val s = session()
        s.tap('a'.code)
        val snapshot = listOf(
            TapSwipeSession.Stroke(
                TapSwipeSession.Kind.SWIPE, TapSwipeSession.Hand.LEFT,
                floatArrayOf(0.1f), floatArrayOf(0.1f), floatArrayOf(0f), 0
            )
        )

        // Restoring into a session that already has strokes would merge two words' evidence -
        // exactly the runaway-word bug class this class exists to prevent.
        s.restoreStrokes(snapshot)

        assertEquals(1, s.strokes.size)
    }

    @Test
    fun `restoring an empty snapshot is a no-op`() {
        val s = session()
        assertFalse(s.isOpen)

        s.restoreStrokes(emptyList())

        assertFalse(s.isOpen)
    }

    @Test
    fun `a restored session can still be popped and re-emptied`() {
        val original = session()
        original.tap('a'.code)
        val snapshot = original.strokes.toList()
        original.reset("finalized")

        val revived = session()
        revived.restoreStrokes(snapshot)

        assertTrue(revived.popLastStroke())
        assertFalse(revived.isOpen)
    }

    @Test
    fun `restoreStrokes bumps the generation`() {
        val s = session()
        val before = s.generation
        s.restoreStrokes(listOf(
            TapSwipeSession.Stroke(
                TapSwipeSession.Kind.TAP, TapSwipeSession.Hand.LEFT,
                floatArrayOf(0.5f), floatArrayOf(0.5f), floatArrayOf(0f), 'a'.code
            )
        ))
        assertNotEquals(before, s.generation)
    }

    @Test
    fun `hands route to the left and right decoder streams`() {
        val s = session()
        s.addStroke(
            TapSwipeSession.Stroke(
                TapSwipeSession.Kind.SWIPE, TapSwipeSession.Hand.LEFT,
                floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0f), 0
            )
        )
        s.addStroke(
            TapSwipeSession.Stroke(
                TapSwipeSession.Kind.SWIPE, TapSwipeSession.Hand.RIGHT,
                floatArrayOf(1f), floatArrayOf(1f), floatArrayOf(1f), 0
            )
        )

        assertEquals(1, s.leftSegs().size)
        assertEquals(1, s.rightSegs().size)
    }
}
