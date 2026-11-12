package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.TapSwipeNormalizers
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.ml.inference.SwipeDecoder

/**
 * Builds a [TapSwipeDecodeInput] from a session, optionally unioned with an in-progress stroke.
 *
 * ## Why the live stroke is passed in rather than accumulated
 *
 * A stroke is absorbed into the session exactly once, at batch end (`InputLogic.onEndBatchInput`).
 * Mid-swipe the current stroke is *not* in the session yet, so it is unioned in here, read fresh
 * from the live pointers on every decode. That keeps mid-swipe decoding free of any incremental
 * bookkeeping — there is no "how much of this batch have I already consumed?" state to get wrong,
 * which is the class of bug that produces runaway word growth.
 *
 * Caller distinguishes the two cases by input style: `INPUT_STYLE_UPDATE_BATCH` passes the live
 * segments, `INPUT_STYLE_TAIL_BATCH` does not (the session already owns them).
 */
object TapSwipeInputBuilder {
    @JvmStatic
    fun build(
        session: TapSwipeSession,
        liveSegments: List<InputPointers.GestureSegment>?,
        norm: TapSwipeNormalizers?,
        batchOriginMs: Long,
        sessionOriginMs: Long,
        /** Letters near a normalized point, and the key width there; null skips position checks. */
        nearKeys: ((Float, Float) -> Set<Char>)? = null,
        keyWidth: Float = 0f
    ): TapSwipeDecodeInput? {
        val left = ArrayList<SwipeDecoder.SwipeSeg>()
        val right = ArrayList<SwipeDecoder.SwipeSeg>()
        var hasSwipe = session.hasSwipe

        for (s in session.strokes) {
            if (s.hand == TapSwipeSession.Hand.RIGHT) right.add(s.toSeg()) else left.add(s.toSeg())
        }

        if (liveSegments != null && norm != null) {
            val shift = if (batchOriginMs > 0 && sessionOriginMs > 0) {
                (batchOriginMs - sessionOriginMs).toFloat()
            } else 0f

            for (seg in liveSegments) {
                val n = seg.x.length
                if (n <= 0) continue

                val xs = FloatArray(n)
                val ys = FloatArray(n)
                val ts = FloatArray(n)
                val rawX = seg.x.primitiveArray
                val rawY = seg.y.primitiveArray
                val rawT = seg.t.primitiveArray
                for (i in 0 until n) {
                    xs[i] = norm.x(rawX[i].toFloat())
                    ys[i] = norm.y(rawY[i].toFloat())
                    ts[i] = rawT[i] + shift
                }

                // Live segments always come from gesture input, so this word involves a swipe.
                hasSwipe = true
                val seg2 = SwipeDecoder.SwipeSeg(xs, ys, ts)
                if (seg.pointerId == 1) right.add(seg2) else left.add(seg2)
            }
        }

        if (left.isEmpty() && right.isEmpty()) return null

        // Mirror the existing single-segment behaviour (SwipeDecoderDictionary.kt:433-435): a lone
        // stroke goes to the left stream regardless of its pointer id. Avoids handing the decoder
        // an empty left stream with a populated right one, which is an untested configuration.
        if (left.isEmpty()) {
            left.addAll(right)
            right.clear()
        }

        // Timing for the soft order across the two streams. Only for a decode of finished strokes:
        // mid-swipe, the live stroke is not in the session and its letters are still moving.
        val timings = if (liveSegments != null) emptyList() else session.strokes.map { s ->
            val right = s.hand == TapSwipeSession.Hand.RIGHT
            if (s.kind == TapSwipeSession.Kind.TAP) {
                val lift = if (s.t.isNotEmpty()) s.t[0] else 0f
                TapSwipeTimingOrder.Timing(true, s.codePoint, right,
                    if (s.downT.isNaN()) lift else s.downT, lift)
            } else {
                val t0 = if (s.t.isNotEmpty()) s.t.first() else 0f
                val t1 = if (s.t.isNotEmpty()) s.t.last() else 0f
                var length = 0f
                for (i in 1 until s.x.size) length += Math.hypot(
                    (s.x[i] - s.x[i - 1]).toDouble(), (s.y[i] - s.y[i - 1]).toDouble()).toFloat()
                val start = if (nearKeys != null && s.x.isNotEmpty()) nearKeys(s.x.first(), s.y.first()) else emptySet()
                val end = if (nearKeys != null && s.x.isNotEmpty()) nearKeys(s.x.last(), s.y.last()) else emptySet()
                if (keyWidth > 0f && length < keyWidth * 0.5f && start.isNotEmpty()) {
                    // Barely moved: a second finger that landed during a gesture is folded in as a
                    // segment. It is a tap on one of the keys under it.
                    TapSwipeTimingOrder.Timing(true, 0, right, t0, t1, startKeys = start)
                } else {
                    TapSwipeTimingOrder.Timing(false, 0, right, t0, t1, startKeys = start, endKeys = end,
                        xs = s.x, ys = s.y, ts = s.t)
                }
            }
        }

        return TapSwipeDecodeInput(
            left.toTypedArray(),
            right.toTypedArray(),
            hasSwipe,
            session.generation,
            timings
        )
    }
}
