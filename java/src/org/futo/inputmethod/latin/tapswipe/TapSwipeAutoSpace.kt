package org.futo.inputmethod.latin.tapswipe

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.futo.inputmethod.latin.TapSwipeAutoSpaceDelaySetting
import org.futo.inputmethod.latin.uix.DataStoreHelper

/**
 * Auto space after a pause: a word that holds a swipe ends when no finger touches the keyboard for
 * a short time after the last lift. The keyboard then presses space for the user. See
 * `docs/plans/AUTO_SPACING.md`.
 *
 * ### The rule
 *
 * - The timer starts when the last finger lifts, if the host says the word wants a space (TapSwipe
 *   on, the setting on, a word with a swipe being composed).
 * - Any finger touching down cancels it. A lift does not: a finger still down holds the word open.
 * - When it fires, it checks again: no touch since it started, and the word is the same word (same
 *   session generation and stroke count). Then the host presses space, through the same path as
 *   the space key. The word segmenter decides there whether the strokes are several words.
 *
 * ### The seam
 *
 * Evidence: touches and strokes. Derived result: a space. The touch layer and this timer both run
 * on the main thread, so a touch-down always runs before or after the fire runnable, never during.
 * A touch-down after the runnable was queued bumps [touchSeq], and the runnable then does nothing.
 */
object TapSwipeAutoSpace {

    /** What the timer needs from input handling. Implemented by the general input engine. */
    interface Host {
        /**
         * Whether the open word should get an auto space. Checked at the lift. The word need not be
         * on screen yet: a swipe's word is written only when its decode lands, after the lift.
         */
        fun autoSpaceWanted(): Boolean

        /** Whether the word is on screen, so a space can follow it. Checked when the timer fires. */
        fun autoSpaceReady(): Boolean

        /** Identifies the word: changes when the session is reset or a stroke is added. */
        fun autoSpaceStamp(): Long

        /** Presses space. */
        fun autoSpaceFire()
    }

    @JvmStatic
    @Volatile
    var host: Host? = null

    /** Called when the countdown starts, stops, or fires, so the space bar can redraw. */
    @JvmStatic
    @Volatile
    var onChange: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private var touchSeq = 0
    private var armedSeq = -1
    private var armedStamp = 0L
    private var fireAtMs = 0L
    private var delayMs = 0L

    /** Any finger touched the keyboard. */
    @JvmStatic
    fun onTouchDown() {
        touchSeq++
        cancel()
    }

    /** The last finger lifted, after the keyboard has processed that lift. */
    @JvmStatic
    fun onAllFingersUp() {
        val h = host ?: return
        if (!h.autoSpaceWanted()) {
            cancel()
            return
        }
        delayMs = DataStoreHelper.getSetting(TapSwipeAutoSpaceDelaySetting).toLong().coerceAtLeast(50L)
        fireAtMs = SystemClock.uptimeMillis() + delayMs
        armedSeq = touchSeq
        armedStamp = h.autoSpaceStamp()
        waits = 0
        handler.removeCallbacks(fire)
        handler.postAtTime(fire, fireAtMs)
        TypingTestEvents.autoSpace("armed", delayMs.toInt())
        onChange?.run()
    }

    @JvmStatic
    fun cancel() {
        if (fireAtMs == 0L) return
        handler.removeCallbacks(fire)
        fireAtMs = 0L
        TypingTestEvents.autoSpace("cancelled", 0)
        onChange?.run()
    }

    /** How much of the countdown is left, from 1 down to 0. 0 when no countdown runs. */
    @JvmStatic
    fun remainingFraction(): Float {
        if (fireAtMs == 0L || delayMs <= 0L) return 0f
        val left = fireAtMs - SystemClock.uptimeMillis()
        return (left.toFloat() / delayMs).coerceIn(0f, 1f)
    }

    /** Retries left while the word's decode has not landed. See [fire]. */
    private var waits = 0
    private const val WAIT_STEP_MS = 50L
    private const val MAX_WAITS = 10

    private val fire = Runnable {
        val h = host
        val same = armedSeq == touchSeq && h != null && h.autoSpaceWanted() && h.autoSpaceStamp() == armedStamp
        if (same && !h!!.autoSpaceReady() && waits < MAX_WAITS) {
            // The decode of the last swipe has not been written yet. A space now would land before
            // the word. Try again shortly; a touch-down still cancels.
            waits++
            handler.postDelayed(this.fireRunnable(), WAIT_STEP_MS)
            return@Runnable
        }
        fireAtMs = 0L
        val ok = same && h!!.autoSpaceReady()
        TypingTestEvents.autoSpace(if (ok) "fired" else if (same) "not_ready" else "stale", waits)
        waits = 0
        onChange?.run()
        if (ok) h!!.autoSpaceFire()
    }

    private fun fireRunnable(): Runnable = fire
}
