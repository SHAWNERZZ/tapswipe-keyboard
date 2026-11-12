package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Test

class TapSwipeTapPointerTest {
    @Test
    fun `a second finger's tap goes to the right stream, like a swipe`() {
        assertEquals(TapSwipeSession.Hand.RIGHT, TapSwipeTapPointer.handFor(1))
    }

    @Test
    fun `sequential taps and unknown pointers stay in the left stream`() {
        assertEquals(TapSwipeSession.Hand.LEFT, TapSwipeTapPointer.handFor(0))
        assertEquals(TapSwipeSession.Hand.LEFT, TapSwipeTapPointer.handFor(-1))
        // A third finger shares the left stream, as a third swipe does.
        assertEquals(TapSwipeSession.Hand.LEFT, TapSwipeTapPointer.handFor(2))
    }

    @Test
    fun `the pointer number is read once`() {
        TapSwipeTapPointer.lastPointerId = 1
        assertEquals(1, TapSwipeTapPointer.consume())
        assertEquals(-1, TapSwipeTapPointer.consume())
    }

    @Test
    fun `the reported word - the E tap from the other thumb joins the other stream`() {
        // "then": tap T (pointer 0), swipe H to N (pointer 0), tap E (pointer 1 as the swipe lifts).
        val session = TapSwipeSession()
        session.addTap('t'.code, 0.4f, 0.1f, 0f, TapSwipeTapPointer.handFor(0))
        session.addTap('e'.code, 0.2f, 0.1f, 250f, TapSwipeTapPointer.handFor(1))
        assertEquals(1, session.leftSegs().size)
        assertEquals(1, session.rightSegs().size)
    }
}
