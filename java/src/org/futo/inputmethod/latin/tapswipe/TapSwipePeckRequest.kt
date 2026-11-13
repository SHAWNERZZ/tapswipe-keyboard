package org.futo.inputmethod.latin.tapswipe

/**
 * A manual request for peck mode, from the Peck mode toolbar action.
 *
 * One word at a time: the request puts the word being typed, or the next one, in peck mode (letters
 * committed as typed, learned, no auto space). The finalizer clears it when that word is committed.
 * A swipe still wins: a word with a swipe is decoded as usual. Pressing the action again cancels
 * the request.
 *
 * Main thread only: the action runs from the toolbar, and input handling reads it while typing.
 */
object TapSwipePeckRequest {
    @JvmStatic
    var requested: Boolean = false
        private set

    /** Called when the request changes, so the keyboard can show peck mode at once. */
    @JvmStatic
    var listener: Runnable? = null

    @JvmStatic
    fun toggle() {
        requested = !requested
        listener?.run()
    }

    @JvmStatic
    fun clear() {
        if (!requested) return
        requested = false
        listener?.run()
    }
}
