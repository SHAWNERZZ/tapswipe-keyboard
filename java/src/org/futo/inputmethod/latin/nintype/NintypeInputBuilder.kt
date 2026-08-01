package org.futo.inputmethod.latin.nintype

import org.futo.inputmethod.latin.NintypeNormalizers
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.ml.inference.SwipeDecoder

/**
 * Builds a [NintypeDecodeInput] from a session, optionally unioned with an in-progress stroke.
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
object NintypeInputBuilder {
    @JvmStatic
    fun build(
        session: NintypeSession,
        liveSegments: List<InputPointers.GestureSegment>?,
        norm: NintypeNormalizers?,
        batchOriginMs: Long,
        sessionOriginMs: Long
    ): NintypeDecodeInput? {
        val left = ArrayList<SwipeDecoder.SwipeSeg>()
        val right = ArrayList<SwipeDecoder.SwipeSeg>()
        var hasSwipe = session.hasSwipe

        for (s in session.strokes) {
            if (s.hand == NintypeSession.Hand.RIGHT) right.add(s.toSeg()) else left.add(s.toSeg())
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

        return NintypeDecodeInput(
            left.toTypedArray(),
            right.toTypedArray(),
            hasSwipe,
            session.generation
        )
    }
}
