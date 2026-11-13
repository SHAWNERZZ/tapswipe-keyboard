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
    @JvmField val generation: Int,
    /** Stroke timing in session order, for the soft order across streams. Empty mid-swipe. */
    @JvmField val timings: List<TapSwipeTimingOrder.Timing> = emptyList(),
    /** The finished strokes in session order, for the word segmenter. Empty mid-swipe. */
    @JvmField val strokes: List<StrokeSnapshot> = emptyList()
) {
    /** One finished stroke: what the segmenter needs to decode part of the word on its own. */
    class StrokeSnapshot(
        @JvmField val isSwipe: Boolean,
        @JvmField val codePoint: Int,
        @JvmField val seg: SwipeDecoder.SwipeSeg,
        @JvmField val right: Boolean
    )

    val isEmpty: Boolean get() = left.isEmpty() && right.isEmpty()

    val segmentCount: Int get() = left.size + right.size

    override fun toString(): String =
        "TapSwipeDecodeInput(L=${left.size} R=${right.size} hasSwipe=$hasSwipe gen=$generation)"
}
