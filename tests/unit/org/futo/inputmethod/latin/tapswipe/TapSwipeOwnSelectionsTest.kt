package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSwipeOwnSelectionsTest {

    @Test
    fun `the reported case - a late report of the tap's write is stale`() {
        val own = TapSwipeOwnSelections()
        own.note(27, 1000)  // decode wrote "different"
        own.note(28, 1100)  // tap wrote "differentt"
        own.note(27, 1135)  // decode rewrote "different"; expected is 27 again
        // The editor reports the tap's write late: 27 -> 28, while 27 is expected.
        assertTrue(own.consumeIfStale(28, 27, 1140))
    }

    @Test
    fun `a report that matches the expected position is left to the belated check`() {
        val own = TapSwipeOwnSelections()
        own.note(27, 1000)
        assertFalse(own.consumeIfStale(27, 27, 1010))
    }

    @Test
    fun `a position the keyboard never produced is a real cursor move`() {
        val own = TapSwipeOwnSelections()
        own.note(27, 1000)
        own.note(28, 1100)
        assertFalse(own.consumeIfStale(12, 27, 1150))
    }

    @Test
    fun `old positions expire, so a later real move to the same place is kept`() {
        val own = TapSwipeOwnSelections()
        own.note(28, 1000)
        assertFalse(own.consumeIfStale(28, 27, 1000 + TapSwipeOwnSelections.MAX_AGE_MS + 1))
    }

    @Test
    fun `a match consumes itself and everything older`() {
        val own = TapSwipeOwnSelections()
        own.note(30, 1000)
        own.note(31, 1010)
        own.note(32, 1020)
        assertTrue(own.consumeIfStale(31, 32, 1030))
        assertFalse(own.consumeIfStale(30, 32, 1031))   // older than the match: gone
        assertTrue(own.consumeIfStale(32, 33, 1032))    // newer than the match: still there
    }

    @Test
    fun `capacity drops the oldest`() {
        val own = TapSwipeOwnSelections()
        for (i in 0 until TapSwipeOwnSelections.CAPACITY + 2) own.note(100 + i, 1000L + i)
        assertFalse(own.consumeIfStale(100, 0, 1020))
        assertTrue(own.consumeIfStale(100 + TapSwipeOwnSelections.CAPACITY + 1, 0, 1020))
    }
}
