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
 * having the shortcut.
 *
 * ### Two entry points, one rule
 *
 * A shortcut is checked in two places, because a short deliberate pull often is not a gesture at
 * all. The batch recogniser wants a fast move followed by enough further travel - thresholds
 * calibrated for long, quick word swipes - and a careful one-key pull frequently fails both. So:
 *
 * - fast enough to register: claimed in `GeneralIME.onEndBatchInput` via [match]
 * - too slow to register: claimed in `PointerTracker` on release, via [matchStraightPull]
 *
 * Both reduce the stroke to the same handful of numbers and call the same rule, so the two paths
 * cannot slowly disagree about what the shape is.
 *
 * ### Why matching has to be strict
 *
 * A false positive eats a word the user meant to type and replaces it with punctuation, which is
 * worse than a miss - a miss just means swiping again. So the shape asserts its start key, its end
 * key, a direction, and how far the path may wander, and anything failing one of them is handed
 * back untouched.
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
     * How far either side of V the pull may start, beyond the key's own edges, as a fraction of key
     * width.
     *
     * Reaching V with the right hand naturally lands a little toward B, and the finger is moving
     * before it settles, so demanding the stroke begin inside V's own bounds rejects strokes that
     * were unmistakably this gesture. The band is anchored to V's centre rather than to whichever
     * key was touched: starting at the far edge of B is a whole key away and still refused, while
     * a near miss on either side is accepted.
     */
    private const val START_BAND_FRACTION = 0.5f

    /** Keys near enough to V to plausibly begin the pull; the band below decides the rest. */
    private val COMMA_START_CODES = setOf('v'.code, 'b'.code, 'c'.code)

    /**
     * Matches a completed *gesture*, where the whole point list is available.
     *
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

        return matchStraightPull(
            keyboard, startKey.code, endKey.code, xs[0],
            xs[n - 1] - xs[0], ys[n - 1] - ys[0], maxDriftOf(xs, n),
            startKey.width, startKey.height
        )
    }

    /**
     * Entry point for callers holding a [Keyboard]: resolves the anchor key, then applies the rule.
     */
    @JvmStatic
    fun matchStraightPull(
        keyboard: Keyboard?,
        startCode: Int,
        endCode: Int,
        startX: Int,
        dx: Int,
        dy: Int,
        maxDrift: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Shortcut? {
        // Cheap rejection first, so the anchor is only looked up for strokes that could qualify.
        if (Character.toLowerCase(startCode) !in COMMA_START_CODES) return null

        // Without a V on this layout there is nothing to anchor the band to.
        val anchor = keyboard?.getKey('v'.code) ?: return null

        return matchCommaPull(
            startCode, endCode, startX,
            anchor.x + anchor.width / 2, anchor.width,
            dx, dy, maxDrift, keyWidth, keyHeight
        )
    }

    /**
     * The rule itself, in already-measured numbers rather than objects.
     *
     * Takes the anchor as a centre and a width rather than a [Keyboard], so the whole rule stays
     * pure arithmetic and testable - resolving a key needs native proximity code that cannot run
     * off a device, and the thresholds here are the part actually worth pinning down.
     *
     * Measurements rather than a path is also what lets both entry points share it: the gesture
     * path has every point, while the live path has only the endpoints and the widest sideways
     * excursion, because a stroke that never became a gesture leaves no path to inspect.
     */
    @JvmStatic
    internal fun matchCommaPull(
        startCode: Int,
        endCode: Int,
        startX: Int,
        anchorCentreX: Int,
        anchorWidth: Int,
        dx: Int,
        dy: Int,
        maxDrift: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Shortcut? {
        if (keyWidth <= 0 || keyHeight <= 0 || anchorWidth <= 0) return null

        // Start and end are checked first, cheaply, because a stroke failing them is not an attempt
        // at this shortcut and should not be noise in the log.
        if (Character.toLowerCase(startCode) !in COMMA_START_CODES) return null
        if (endCode != Constants.CODE_SPACE) return null

        // Anchored to V's centre, so the tolerance is identical whichever neighbouring key the
        // finger happened to land on.
        val maxStartOffset = anchorWidth / 2 + anchorWidth * START_BAND_FRACTION
        val startOffset = abs(startX - anchorCentreX)

        val minDown = keyHeight * MIN_DOWN_FRACTION
        val maxAllowedDrift = keyWidth * MAX_DRIFT_FRACTION
        val reason = when {
            startOffset > maxStartOffset ->
                "started too far from V (offset=$startOffset needs <= ${maxStartOffset.toInt()})"
            dy < minDown -> "too short (dy=$dy needs >= ${minDown.toInt()})"
            abs(dx) > dy -> "too shallow (|dx|=${abs(dx)} > dy=$dy)"
            maxDrift > maxAllowedDrift ->
                "wandered (drift=$maxDrift needs <= ${maxAllowedDrift.toInt()})"
            else -> null
        }

        if (reason != null) {
            if (DEBUG) {
                Log.d(TAG, "V->space rejected: $reason " +
                        "(dx=$dx dy=$dy drift=$maxDrift startOffset=$startOffset " +
                        "keyW=$keyWidth keyH=$keyHeight)")
            }
            return null
        }

        if (DEBUG) {
            Log.d(TAG, "matched COMMA (dx=$dx dy=$dy drift=$maxDrift " +
                    "startOffset=$startOffset keyW=$keyWidth keyH=$keyHeight)")
        }
        return Shortcut.COMMA
    }

    /** Point-list form, for callers that have the whole stroke. */
    @JvmStatic
    internal fun matchShape(
        startCode: Int,
        endCode: Int,
        xs: IntArray,
        ys: IntArray,
        n: Int,
        anchorCentreX: Int,
        anchorWidth: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Shortcut? {
        if (n < 3) return null
        return matchCommaPull(
            startCode, endCode, xs[0], anchorCentreX, anchorWidth,
            xs[n - 1] - xs[0], ys[n - 1] - ys[0], maxDriftOf(xs, n),
            keyWidth, keyHeight
        )
    }

    private fun maxDriftOf(xs: IntArray, n: Int): Int {
        var maxDrift = 0
        for (i in 0 until n) {
            val drift = abs(xs[i] - xs[0])
            if (drift > maxDrift) maxDrift = drift
        }
        return maxDrift
    }
}
