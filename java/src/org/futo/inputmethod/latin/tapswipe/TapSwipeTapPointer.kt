package org.futo.inputmethod.latin.tapswipe

/**
 * Carries the pointer number of a tap from the touch layer to the session, so a tap joins the
 * decoder stream of its thumb, as a swipe already does.
 *
 * ## Why
 *
 * Reported 2026-11-11, second typing test: "then" typed as tap T, one thumb swiping H to N, and the
 * other thumb tapping E as the swipe lifted. Android gave the E tap pointer number 1, its mark for a
 * second finger. But taps always went to the left stream, after the swipe, so the decoder read T,
 * H..N, E and chose "the". Backspace popped the E and "then" came back. Swipes pick their stream by
 * pointer number (design rationale 1.3): two pointer numbers only coexist when two fingers are down
 * together, which is exactly when the decoder should interleave two streams by the lexicon.
 *
 * ## Hand-off
 *
 * `PointerTracker.callListenerOnCodeInput` sets [lastPointerId] right before it calls the listener.
 * The listener runs synchronously on the main thread, and `InputLogic` takes the value with
 * [consume] during that call. A tap whose pointer is not known goes to the left stream, as before.
 */
object TapSwipeTapPointer {
    @JvmField
    @Volatile
    var lastPointerId: Int = -1

    /** Returns the pointer number of the code input being handled, once, then forgets it. */
    @JvmStatic
    fun consume(): Int {
        val id = lastPointerId
        lastPointerId = -1
        return id
    }

    /** Same rule as swipes in [TapSwipeSession.addSwipeSegments]: pointer 1 is the right stream. */
    @JvmStatic
    fun handFor(pointerId: Int): TapSwipeSession.Hand =
        if (pointerId == 1) TapSwipeSession.Hand.RIGHT else TapSwipeSession.Hand.LEFT
}
