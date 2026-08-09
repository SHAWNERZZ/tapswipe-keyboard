package org.futo.inputmethod.latin.tapswipe

import android.util.Log
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import kotlin.math.abs

/**
 * Whole-stroke gestures that mean something other than a word.
 *
 * The original Nintype had a handful of these: shapes that are unmistakably not attempts at
 * spelling, claimed before the decoder ever sees them. This is the framework plus the first one -
 * a straight pull down from V onto the space bar, producing a comma.
 *
 * ### Why not a flick key
 *
 * The layout system has a `flick` key type that looks like the obvious fit, and is not. In
 * `PointerTracker.onMoveEventInternal` the flick branch returns *before* gesture detection, so
 * making V a flick key would stop any swipe that starts on V from becoming a gesture at all -
 * silently breaking swipe-typing through one of the letters. That is a far worse trade than not
 * having the shortcut. These are matched against the completed stroke instead, in the gesture path,
 * where they cost nothing until a gesture actually ends.
 *
 * ### Why matching has to be strict
 *
 * A false positive eats a word the user meant to type and replaces it with punctuation, which is
 * worse than a miss - a miss just means swiping again. So each shape asserts its start key, its end
 * key, a direction, and how far the path is allowed to wander, and anything failing one of them is
 * handed back to the decoder untouched.
 */
object NintypeGestures {
    const val TAG = "NintypeGestures"
    private const val DEBUG = true

    /** What a matched stroke means. */
    enum class Shortcut(val codePoint: Int) {
        COMMA(','.code)
    }

    /** Minimum downward travel, as a fraction of key height, before a pull counts as deliberate. */
    private const val MIN_DOWN_FRACTION = 0.5f

    /** Maximum sideways wander from the starting column, as a fraction of key width. */
    private const val MAX_DRIFT_FRACTION = 1.0f

    /**
     * @param pointers the completed stroke, in keyboard-view coordinates
     * @param keyboard the layout the stroke was made on
     * @return the shortcut this stroke means, or null to let the decoder have it
     */
    @JvmStatic
    fun match(pointers: InputPointers?, keyboard: Keyboard?): Shortcut? {
        if (pointers == null || keyboard == null) return null

        // Two fingers is a two-handed word, never a shortcut.
        val segments = pointers.gestureSegments
        if (segments == null || segments.size != 1) return null

        val n = pointers.pointerSize
        if (n < 3) return null

        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates

        val startKey = keyboard.getNearestKeys(xs[0], ys[0])
            .firstOrNull { it.isOnKey(xs[0], ys[0]) } ?: return null
        val endKey = keyboard.getNearestKeys(xs[n - 1], ys[n - 1])
            .firstOrNull { it.isOnKey(xs[n - 1], ys[n - 1]) } ?: return null

        return matchShape(
            startKey.code, endKey.code, xs, ys, n, startKey.width, startKey.height
        )
    }

    /**
     * The geometric half, separated from key lookup so it can be tested.
     *
     * Key resolution needs a [Keyboard], which reaches native proximity code and cannot run off a
     * device. The shape rules are the part with thresholds worth tuning and worth pinning down, and
     * they are pure arithmetic.
     */
    @JvmStatic
    internal fun matchShape(
        startCode: Int,
        endCode: Int,
        xs: IntArray,
        ys: IntArray,
        n: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Shortcut? {
        if (n < 3 || keyWidth <= 0 || keyHeight <= 0) return null

        // --- comma: straight down from V onto the space bar -----------------------------------
        if (Character.toLowerCase(startCode) != 'v'.code) return null
        if (endCode != Constants.CODE_SPACE) return null

        val dx = xs[n - 1] - xs[0]
        val dy = ys[n - 1] - ys[0]

        // Downward, and far enough to be deliberate rather than a slipped tap.
        if (dy < keyHeight * MIN_DOWN_FRACTION) return null
        // Steeper than 45 degrees, so a swipe setting off sideways toward other letters reads as a
        // word even if it happens to finish low.
        if (abs(dx) > dy) return null
        // And it stays near the column it started in: a word swipe can cross the space bar on its
        // way somewhere, but it does not travel straight down it.
        var maxDrift = 0
        for (i in 0 until n) {
            val drift = abs(xs[i] - xs[0])
            if (drift > maxDrift) maxDrift = drift
        }
        if (maxDrift > keyWidth * MAX_DRIFT_FRACTION) return null

        if (DEBUG) {
            Log.d(TAG, "matched COMMA (dx=$dx dy=$dy drift=$maxDrift " +
                    "keyW=$keyWidth keyH=$keyHeight)")
        }
        return Shortcut.COMMA
    }
}
