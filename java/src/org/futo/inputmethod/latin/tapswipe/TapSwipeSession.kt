package org.futo.inputmethod.latin.tapswipe

import android.util.Log
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.ml.inference.SwipeDecoder

/**
 * Word-scoped accumulator of input evidence for the TapSwipe re-architecture.
 *
 * A "session" is one word being built. It is opened by the first letter input and closed only
 * by a finalizer (space / punctuation / Enter), never by a finger lift.
 *
 * ## Statelessness contract (see TAPSWIPE_PLAN.md §5.5)
 *
 * The known failure mode for this design is accumulated state outliving the word it belongs to,
 * producing runaway word growth. Rather than trying to enumerate every event that should clear
 * the accumulator (invalidate-on-write, which leaks the moment one path is missed), this class is
 * **validate-on-read**: it carries an identity, and [validateOrReset] re-verifies that identity
 * against live editor state before every use. Anything unanticipated drops the session.
 *
 * Invariants:
 *  - No composing word in the editor ⇒ no session.
 *  - The cursor must still be where our last composing write left it.
 *  - Results computed for an older [generation] are stale and must be discarded.
 *  - The candidate is always a pure function of [strokes]; nothing is applied incrementally.
 */
class TapSwipeSession {
    enum class Kind { TAP, SWIPE }
    enum class Hand { LEFT, RIGHT }

    /**
     * One continuous finger-down period. A tap is just a very short stroke; the decoder resamples
     * every segment to a fixed 64 points, so a single point is sufficient (Phase 0 / S4).
     */
    class Stroke(
        val kind: Kind,
        val hand: Hand,
        val x: FloatArray,
        val y: FloatArray,
        val t: FloatArray,
        /** Code point, for TAP strokes where the key identity is known. */
        val codePoint: Int
    ) {
        val isEmpty: Boolean get() = x.isEmpty()
        fun toSeg(): SwipeDecoder.SwipeSeg = SwipeDecoder.SwipeSeg(x, y, t)
    }

    companion object {
        const val TAG = "TapSwipeSession"

        /**
         * Fail-safe cap. Past this many strokes something has leaked; we log loudly and reset
         * rather than letting the word grow without bound. A canary, not a fix.
         */
        const val MAX_STROKES = 12

        /** Anchor value meaning "no anchor established yet". */
        private const val NO_ANCHOR = -1
    }

    private val strokesInternal = ArrayList<Stroke>()
    val strokes: List<Stroke> get() = strokesInternal

    /**
     * Bumped on every [reset]. Async decode results carry the generation they were computed for;
     * see [isCurrent]. Guards against an in-flight decode on the InputLogicHandler thread
     * resurrecting a session cleared on the UI thread.
     */
    var generation: Int = 0
        private set

    /** Expected selection start immediately after our last composing write. */
    private var anchorSelStart: Int = NO_ANCHOR

    /** Last candidate text we wrote to the composing region. */
    var lastComposedText: String = ""
        private set

    val isOpen: Boolean get() = strokesInternal.isNotEmpty()

    /**
     * Whether this session has already written a composing region, i.e. at least one stroke has
     * been decoded and applied. Distinguishes a *continuation* stroke (must not finalize the
     * previous composing region, must not auto-space) from the *first* stroke of a word (which
     * should behave exactly like stock: finish the previous word, insert any phantom space).
     */
    val hasComposed: Boolean get() = anchorSelStart != NO_ANCHOR

    /** Swipe mode iff any stroke was a real gesture; otherwise peck mode. */
    val hasSwipe: Boolean get() = strokesInternal.any { it.kind == Kind.SWIPE }

    /** Number of taps recorded for this word. */
    val tapCount: Int get() = strokesInternal.count { it.kind == Kind.TAP }

    /**
     * Peck-mode literal text, *derived* from tap strokes rather than accumulated separately,
     * so it cannot drift out of sync with [strokes].
     */
    val literalText: String
        get() = buildString {
            for (s in strokesInternal) {
                if (s.kind == Kind.TAP && s.codePoint > 0) appendCodePoint(s.codePoint)
            }
        }

    fun isCurrent(gen: Int): Boolean = gen == generation

    // ------------------------------------------------------------------ lifecycle

    /** The single atomic clear. No partial resets anywhere. */
    fun reset(reason: String) {
        if (isOpen || anchorSelStart != NO_ANCHOR) {
            if (DEBUG) Log.d(TAG, "reset (${strokesInternal.size} strokes): $reason")
        }
        strokesInternal.clear()
        anchorSelStart = NO_ANCHOR
        lastComposedText = ""
        generation++
    }

    /**
     * Validate-on-read. Call before *every* use of the session.
     *
     * @param isComposingWord whether the editor still has a composing word
     * @param expectedSelStart the connection's current expected selection start
     * @return true if the session is still valid; false if it was reset (caller should treat the
     *         next input as starting a fresh word)
     */
    fun validateOrReset(
        isComposingWord: Boolean,
        typedWord: String,
        expectedSelStart: Int
    ): Boolean {
        if (!isOpen) return false

        // Fail-safe against a leak we haven't identified. Checked first and unconditionally.
        if (strokesInternal.size > MAX_STROKES) {
            Log.w(TAG, "stroke count ${strokesInternal.size} exceeds MAX_STROKES=$MAX_STROKES; " +
                    "this indicates leaked session state. Resetting to fail safe.")
            reset("stroke cap exceeded")
            return false
        }

        // The editor-state invariants below only hold once this session has actually written a
        // composing region. There is a legitimate window - between absorbing the first stroke and
        // the decode result being written back - where strokes exist but the editor is not
        // composing anything yet. Enforcing the invariants there would wipe the stroke we just
        // absorbed, which is precisely the "second swipe replaces the first word" bug.
        if (!hasComposed) return true

        // Invariant 1: the editor is no longer composing, so whatever we accumulated belongs to a
        // word that has already been committed, reverted, or torn down.
        if (!isComposingWord) {
            reset("editor is no longer composing")
            return false
        }

        // Invariant 2: the composing word is not the text we last wrote, so something replaced it
        // (recorrection, suggestion pick, external edit).
        if (typedWord != lastComposedText) {
            reset("composing word changed: expected '$lastComposedText', got '$typedWord'")
            return false
        }

        // Invariant 3: the cursor is not where our last write left it.
        if (expectedSelStart != anchorSelStart) {
            reset("cursor moved: expected $anchorSelStart, got $expectedSelStart")
            return false
        }

        return true
    }

    /**
     * Records where the cursor ended up after writing [text] to the composing region, so
     * [validateOrReset] can detect later movement.
     */
    fun noteComposingWrite(text: String, expectedSelStart: Int) {
        lastComposedText = text
        anchorSelStart = expectedSelStart
    }

    // ------------------------------------------------------------------ accumulation

    fun addStroke(stroke: Stroke): Boolean {
        if (stroke.isEmpty) return false
        if (strokesInternal.size >= MAX_STROKES) {
            Log.w(TAG, "refusing stroke; at MAX_STROKES=$MAX_STROKES")
            return false
        }
        strokesInternal.add(stroke)
        return true
    }

    fun addTap(codePoint: Int, x: Float, y: Float, t: Float, hand: Hand = Hand.LEFT): Boolean =
        addStroke(
            Stroke(
                Kind.TAP, hand,
                floatArrayOf(x), floatArrayOf(y), floatArrayOf(t),
                codePoint
            )
        )

    /**
     * Deep-copies the gesture segments of a completed batch into the session.
     *
     * Deep copy is mandatory: [InputPointers.set] only copies segment *references*
     * (`InputPointers.java:104-105`) and the live `sAggregatedPointers` segments keep being
     * mutated on the UI thread, while `InputPointers` is explicitly not thread-safe.
     *
     * @param batchOriginMs absolute time corresponding to t=0 of these segments, used to re-base
     *        onto a single session timeline. Each batch has its own origin
     *        (`BatchInputArbiter.sGestureFirstDownTime`), so raw segment times are not comparable
     *        across batches.
     * @param sessionOriginMs absolute time of this session's t=0.
     */
    fun addSwipeSegments(
        segments: List<InputPointers.GestureSegment>,
        batchOriginMs: Long,
        sessionOriginMs: Long,
        normalizeX: (Float) -> Float,
        normalizeY: (Float) -> Float
    ): Int {
        val shift = (batchOriginMs - sessionOriginMs).toFloat()
        var added = 0
        for (seg in segments) {
            val n = seg.x.length
            if (n <= 0) continue

            val xs = FloatArray(n)
            val ys = FloatArray(n)
            val ts = FloatArray(n)
            val rawX = seg.x.primitiveArray
            val rawY = seg.y.primitiveArray
            val rawT = seg.t.primitiveArray
            for (i in 0 until n) {
                xs[i] = normalizeX(rawX[i].toFloat())
                ys[i] = normalizeY(rawY[i].toFloat())
                ts[i] = rawT[i] + shift
            }

            val hand = if (seg.pointerId == 1) Hand.RIGHT else Hand.LEFT
            if (addStroke(Stroke(Kind.SWIPE, hand, xs, ys, ts, 0))) added++
        }
        return added
    }

    /** Removes the most recent stroke. Callers must fully recompute the candidate afterwards. */
    fun popLastStroke(): Boolean {
        if (strokesInternal.isEmpty()) return false
        strokesInternal.removeAt(strokesInternal.size - 1)
        if (strokesInternal.isEmpty()) {
            reset("last stroke popped")
        }
        return true
    }

    // ------------------------------------------------------------------ decode input

    fun leftSegs(): Array<SwipeDecoder.SwipeSeg> =
        strokesInternal.filter { it.hand == Hand.LEFT }.map { it.toSeg() }.toTypedArray()

    fun rightSegs(): Array<SwipeDecoder.SwipeSeg> =
        strokesInternal.filter { it.hand == Hand.RIGHT }.map { it.toSeg() }.toTypedArray()

    /** Debug summary for the MemDebug panel. */
    fun describe(): String = buildString {
        appendLine("gen=$generation open=$isOpen hasSwipe=$hasSwipe hasComposed=$hasComposed strokes=${strokesInternal.size}")
        appendLine("anchorSelStart=$anchorSelStart lastComposed='$lastComposedText'")
        appendLine("literal='$literalText'")
        strokesInternal.forEachIndexed { i, s ->
            val label = if (s.kind == Kind.TAP && s.codePoint > 0)
                "TAP '${String(Character.toChars(s.codePoint))}'" else s.kind.name
            appendLine("  [$i] $label ${s.hand} pts=${s.x.size} t=${s.t.firstOrNull()?.toInt()}..${s.t.lastOrNull()?.toInt()}")
        }
    }
}

private const val DEBUG = true
