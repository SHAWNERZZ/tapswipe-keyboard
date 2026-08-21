package org.futo.inputmethod.keyboard.internal

/**
 * The gestures that built the word being typed, kept so they can be drawn.
 *
 * The keyboard's own trail fades on a clock, about a second after a finger lifts. That is right for
 * feedback about the stroke just made, and wrong for showing what a word is made of. This holds
 * every gesture in the current word until the word ends.
 *
 * ### Brightness counts gestures, not seconds
 *
 * The newest gesture is drawn brightest and older ones step down. The grading follows position in
 * the word rather than elapsed time, which matters for two reasons.
 *
 * A reader can count backwards. The second-brightest gesture is the second one a backspace would
 * remove, whether it was made a moment ago or a minute ago. Time-based fading would instead report
 * how slowly someone was typing.
 *
 * It also costs less to run. Alpha changes only when a gesture arrives or leaves, so the view
 * repaints on those events. A time-based fade has to repaint continuously while anything is
 * visible.
 *
 * ### Coordinates
 *
 * Points are in keyboard view pixels, captured where the touch happened. The word session stores
 * its own copy in the decoder's normalized space. Keeping a separate copy here avoids inverting
 * that transform on every frame, and keeps drawing independent of the decoder's coordinate space.
 * Both copies follow the same lifecycle, so they cannot disagree about which gestures exist.
 */
object WordGestureTrail {

    /** A tap holds one point. A swipe holds the sampled path. */
    class Gesture(val isTap: Boolean, val xs: FloatArray, val ys: FloatArray)

    /**
     * Fail-safe cap, matching the word session's own stroke limit in spirit. Past this something
     * has leaked, and an unbounded list would be drawn on every frame.
     */
    private const val MAX_GESTURES = 48

    private val lock = Any()
    private val gestures = ArrayList<Gesture>()

    /** Called when the set of gestures changes, so the view can repaint. Set by the preview. */
    @JvmStatic
    var onChanged: Runnable? = null

    @JvmStatic
    fun addTap(x: Float, y: Float) {
        synchronized(lock) {
            if (gestures.size >= MAX_GESTURES) return
            gestures.add(Gesture(true, floatArrayOf(x), floatArrayOf(y)))
        }
        onChanged?.run()
    }

    /**
     * @param xs,ys the path in view pixels; copied, because the caller reuses its buffers
     * @param count how many entries of [xs] and [ys] are valid
     */
    @JvmStatic
    fun addSwipe(xs: FloatArray, ys: FloatArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            if (gestures.size >= MAX_GESTURES) return
            gestures.add(Gesture(false, xs.copyOf(count), ys.copyOf(count)))
        }
        onChanged?.run()
    }

    /** Drops the newest gesture, for a backspace that removed one. */
    @JvmStatic
    fun removeLast() {
        synchronized(lock) {
            if (gestures.isEmpty()) return
            gestures.removeAt(gestures.size - 1)
        }
        onChanged?.run()
    }

    /** The word ended, or the session was discarded. */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            if (gestures.isEmpty()) return
            gestures.clear()
        }
        onChanged?.run()
    }

    @JvmStatic
    fun isEmpty(): Boolean = synchronized(lock) { gestures.isEmpty() }

    /** A copy, so drawing never iterates a list the touch thread may be changing. */
    @JvmStatic
    fun snapshot(): List<Gesture> = synchronized(lock) { ArrayList(gestures) }

    // ---------------------------------------------------------------- the grading rule

    /** Alpha of the newest gesture. */
    private const val FULL_ALPHA = 255

    /** Alpha that the oldest gestures settle at. Low enough to stay behind the key labels. */
    private const val FLOOR_ALPHA = 55

    /** How much dimmer each older gesture is. */
    private const val ALPHA_STEP = 50

    /**
     * @param indexFromEnd 0 for the newest gesture, 1 for the one before it
     * @return alpha from 0 to 255
     */
    @JvmStatic
    fun alphaFor(indexFromEnd: Int): Int {
        if (indexFromEnd <= 0) return FULL_ALPHA
        return maxOf(FLOOR_ALPHA, FULL_ALPHA - indexFromEnd * ALPHA_STEP)
    }
}
