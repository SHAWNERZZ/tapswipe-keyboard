package org.futo.inputmethod.latin.tapswipe

import org.futo.ml.inference.SwipeDecoder

/**
 * An immutable snapshot of the evidence to decode for one word, carried through the existing
 * suggestion pipeline on [org.futo.inputmethod.latin.common.ComposedData].
 *
 * Built fresh for every decode from the session's completed strokes plus any in-progress stroke,
 * so nothing about it is incremental — see the statelessness contract in [TapSwipeSession].
 *
 * [generation] is the session generation this input was built from. Results computed for a stale
 * generation must be discarded rather than applied.
 */
class TapSwipeDecodeInput(
    @JvmField val left: Array<SwipeDecoder.SwipeSeg>,
    @JvmField val right: Array<SwipeDecoder.SwipeSeg>,
    /** True if any stroke was a real gesture; false means peck mode (no swipe decoding). */
    @JvmField val hasSwipe: Boolean,
    @JvmField val generation: Int
) {
    val isEmpty: Boolean get() = left.isEmpty() && right.isEmpty()

    val segmentCount: Int get() = left.size + right.size

    override fun toString(): String =
        "TapSwipeDecodeInput(L=${left.size} R=${right.size} hasSwipe=$hasSwipe gen=$generation)"
}
