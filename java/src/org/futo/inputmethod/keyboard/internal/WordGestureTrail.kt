package org.futo.inputmethod.keyboard.internal

import kotlin.math.abs
import kotlin.math.max

/**
 * What to draw for the word being typed.
 *
 * A projection of the word session's strokes, republished whole whenever they change. It is never
 * appended to or removed from piecemeal.
 *
 * That is the whole design. An earlier version collected points from touch events into its own
 * list, which then had to agree with the session about which strokes existed, in what order, and
 * how many. It failed on all three. It ordered two-thumb swipes by which finger lifted while the
 * session orders them by which went down, and it applied a different test for whether a stroke had
 * happened at all, so the lists could differ in length and every backspace after that removed the
 * wrong drawing. Replacing the whole list makes those failures impossible to express.
 *
 * ### Brightness counts strokes, not seconds
 *
 * The newest stroke is brightest and older ones step down. The grading follows position in the word
 * rather than elapsed time, so it answers "how many backspace presses away is this". Time-based
 * fading would instead report how slowly someone was typing.
 *
 * It also costs less. Alpha changes only when the strokes change, so the view repaints on those
 * events rather than continuously.
 */
object WordGestureTrail {

    /**
     * One stroke, ready to draw.
     *
     * [isTap] is decided by shape rather than by the session's own label. See [looksLikeTap].
     */
    class Gesture(val isTap: Boolean, val xs: FloatArray, val ys: FloatArray)

    private val lock = Any()
    private var gestures: List<Gesture> = emptyList()

    /** Called when the drawing changes, so the view can repaint. Set by the preview. */
    @JvmStatic
    var onChanged: Runnable? = null

    /**
     * Replaces everything drawn.
     *
     * @param published the strokes to draw, oldest first, in the session's order
     */
    @JvmStatic
    fun publish(published: List<Gesture>) {
        synchronized(lock) {
            if (gestures.isEmpty() && published.isEmpty()) return
            gestures = published
        }
        onChanged?.run()
    }

    /** The word ended, or the session was discarded. */
    @JvmStatic
    fun clear() = publish(emptyList())

    @JvmStatic
    fun isEmpty(): Boolean = synchronized(lock) { gestures.isEmpty() }

    @JvmStatic
    fun snapshot(): List<Gesture> = synchronized(lock) { gestures }

    // ---------------------------------------------------------------- shape

    /**
     * Whether a stroke should be drawn as a tap rather than as a path.
     *
     * Decided by what the finger did, not by how the session labelled the stroke. A tap made while
     * another finger is mid-swipe reaches the session through the swipe path and is recorded as a
     * swipe, because the flag that says "a gesture is in progress" is shared by every finger.
     * Drawing that as a path would show a stroke the user never made.
     *
     * Two conditions, both required.
     *
     * The stroke has to be short, no further than one key width. And it has to have stayed on one
     * key. Distance alone is not enough: a swipe between two neighbouring keys can travel less than
     * a key width if it runs edge to edge, and drawing that as a dot hides a real swipe. Swiping
     * `bet` and then `er` should show two paths, not a path and a dot.
     *
     * @param keyWidthPx width of a letter key. A non-positive value disables the test, so an
     *   unknown key width leaves the session's own labelling in charge.
     * @param stayedOnOneKey whether every point falls on the key the stroke began on. See
     *   [withinKey], which the caller uses to work this out.
     */
    @JvmStatic
    fun looksLikeTap(
        xs: FloatArray, ys: FloatArray, keyWidthPx: Float, stayedOnOneKey: Boolean
    ): Boolean {
        if (xs.size <= 1) return true
        if (keyWidthPx <= 0f) return false
        if (!stayedOnOneKey) return false

        var minX = xs[0]; var maxX = xs[0]
        var minY = ys[0]; var maxY = ys[0]
        for (i in 1 until xs.size) {
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        // The larger of the two extents, so a stroke that is long in one direction only still
        // counts as a swipe.
        return max(abs(maxX - minX), abs(maxY - minY)) <= keyWidthPx
    }

    /**
     * Whether every point sits inside a key's box, allowing for the box being in the wrong place.
     *
     * The drawn key is where the layout puts it. Where the user's finger actually goes for that key
     * can be somewhere else, which is the whole premise of the learned geometry. A tap that lands
     * consistently low and left would otherwise cross the drawn boundary and be read as a swipe.
     *
     * [tolerancePx] absorbs that. Passing the cap the learned model is allowed to shift a key by
     * means no amount of learning can move a key far enough to break this test, without this code
     * having to read the model.
     */
    @JvmStatic
    fun withinKey(
        xs: FloatArray, ys: FloatArray,
        left: Float, top: Float, right: Float, bottom: Float,
        tolerancePx: Float
    ): Boolean {
        if (right <= left || bottom <= top) return false
        for (i in xs.indices) {
            if (xs[i] < left - tolerancePx || xs[i] > right + tolerancePx) return false
            if (ys[i] < top - tolerancePx || ys[i] > bottom + tolerancePx) return false
        }
        return true
    }

    // ---------------------------------------------------------------- the grading rule

    /** Alpha of the newest stroke. */
    private const val FULL_ALPHA = 255

    /** Alpha the oldest strokes settle at. Low enough to stay behind the key labels. */
    private const val FLOOR_ALPHA = 55

    /** How much dimmer each older stroke is. */
    private const val ALPHA_STEP = 50

    /**
     * @param indexFromEnd 0 for the newest stroke, 1 for the one before it
     * @return alpha from 0 to 255
     */
    @JvmStatic
    fun alphaFor(indexFromEnd: Int): Int {
        if (indexFromEnd <= 0) return FULL_ALPHA
        return maxOf(FLOOR_ALPHA, FULL_ALPHA - indexFromEnd * ALPHA_STEP)
    }
}
