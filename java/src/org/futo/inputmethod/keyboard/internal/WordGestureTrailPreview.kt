package org.futo.inputmethod.keyboard.internal

import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.futo.inputmethod.keyboard.PointerTracker
import org.futo.inputmethod.latin.uix.DynamicThemeProvider

/**
 * Draws every gesture in the word being typed, graded so the newest is brightest.
 *
 * Separate from [GestureTrailsDrawingPreview], which draws the stroke under a finger right now and
 * lets it fade. That one answers "did the keyboard see my finger". This one answers "what is this
 * word made of", which is the question a backspace that removes one gesture forces the user to ask.
 *
 * Drawn straight to the canvas with no offscreen buffer. The other preview needs one because it
 * repaints continuously while a trail fades. Here the picture only changes when a gesture arrives
 * or leaves, so the paint cost is paid on those events instead of on every frame.
 *
 * Colour and widths come from the live trail's own parameters, so the two read as one thing. Those
 * parameters are package-private upstream, which is why they are resolved here rather than passed
 * in by the view that owns this preview.
 */
class WordGestureTrailPreview(
    mainKeyboardViewAttr: TypedArray,
    provider: DynamicThemeProvider?
) : AbstractDrawingPreview() {

    private val params = GestureTrailDrawingParams(mainKeyboardViewAttr, provider)
    private val trailColor = params.mTrailColor
    private val strokeWidth = params.mTrailEndWidth
    private val tapRadius = params.mTrailStartWidth

    private val paint = Paint().apply {
        isAntiAlias = true
        color = trailColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    init {
        WordGestureTrail.onChanged = Runnable { invalidateDrawingView() }
    }

    override fun onDeallocateMemory() {
        // Nothing retained. The gestures belong to WordGestureTrail, and there is no buffer.
    }

    override fun setPreviewPosition(tracker: PointerTracker) {
        // Position comes from the stored gestures, not from a live pointer.
    }

    override fun drawPreview(canvas: Canvas) {
        if (!isPreviewEnabled) return

        val gestures = WordGestureTrail.snapshot()
        if (gestures.isEmpty()) return

        val newest = gestures.size - 1
        for (i in gestures.indices) {
            val g = gestures[i]
            paint.color = trailColor
            paint.alpha = WordGestureTrail.alphaFor(newest - i)

            if (g.isTap) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(g.xs[0], g.ys[0], tapRadius, paint)
                continue
            }

            if (g.xs.size < 2) continue
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            path.reset()
            path.moveTo(g.xs[0], g.ys[0])
            for (p in 1 until g.xs.size) {
                path.lineTo(g.xs[p], g.ys[p])
            }
            canvas.drawPath(path, paint)
        }
    }
}
