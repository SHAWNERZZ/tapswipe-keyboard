package org.futo.inputmethod.latin.tapswipe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.inputmethod.latin.TapSwipeAdaptiveGeometrySetting
import org.futo.inputmethod.latin.TapSwipeRealTapPositionSetting
import org.futo.inputmethod.latin.settings.SettingsValues
import org.futo.inputmethod.latin.uix.DataStoreHelper
import kotlin.math.max
import kotlin.math.min

/**
 * Turns accepted swipes into evidence for [TapSwipeTouchModel].
 *
 * Called at the moment a word finalizes, which is the first point at which the word is actually
 * known - before that the decode is provisional and can still change with another stroke.
 *
 * ### What counts as evidence
 *
 * Deliberately narrow, because a wrong sample is worse than a missing one: it moves a key toward
 * somewhere the user never aimed, and it persists.
 *
 * - **No two strokes overlapping in time.** Sequential swipes and taps are ordered by the clock and
 *   attribute cleanly, and they are how most words are actually built. Concurrent strokes - two
 *   thumbs at once - are the case that cannot be resolved, because the decoder interleaves the
 *   hands using the lexicon rather than the clock, so no attribution can be trusted.
 * - **At least one swipe.** A word of nothing but taps is either pecked or close to it, and the
 *   feature exists for fast sloppy input rather than deliberate spelling.
 * - **A clear win in the beam search.** If the runner-up was nearly as good, the decoder was not
 *   confident which word this was, so it cannot serve as ground truth for where the letters are.
 * - **A plausible alignment.** A wild swipe rescued by the language model has real letters and a
 *   path that never went near them; see [TapSwipeStrokeAligner.isPlausible].
 *
 * Taps inside those words are kept, and are the *best* evidence available - an exact position with
 * an exact identity. They also anchor the alignment of the swipes around them.
 *
 * Notably absent: peck words. They are the most *accurate* taps available and the least useful -
 * someone spelling a word out deliberately is not typing the way this feature exists to support.
 * The whole value is in fast, sloppy input.
 */
object TapSwipeLearner {
    const val TAG = "TapSwipeLearner"

    /**
     * How far the winning candidate must beat the runner-up before its word is trusted as ground
     * truth. Scores are log-domain, so this is a ratio rather than a difference in probability.
     */
    private const val MIN_MARGIN = 0.75f

    /** Words this short are dominated by their endpoints and carry little shape information. */
    private const val MIN_WORD_LENGTH = 3

    /**
     * Extra weight for a sample whose word the user named explicitly.
     *
     * Not because the gesture is better - it is the same sloppy stroke either way - but because the
     * *label* is certain. An accepted decode only tells us the decoder agreed with itself; a picked
     * correction tells us what the person actually meant.
     */
    private const val CORRECTION_WEIGHT = 1.5f

    /** What was recorded for the most recent word, so it can be taken back if that word is undone. */
    private class LearnedBatch(
        val layoutKey: String,
        val word: String,
        val attributions: List<TapSwipeStrokeAligner.Attribution>,
        val weightScale: Float
    )

    @Volatile
    private var lastBatch: LearnedBatch? = null

    /**
     * The most recent word learned from, kept so the settings page can replay it.
     *
     * Holds the strokes as separate timed segments rather than one flattened path, because a tap
     * and a swipe are different events and drawing them as one polyline misrepresents what
     * happened - a tap became a line joining it to wherever the finger had been. Timestamps are
     * kept so a replay can honour the gaps too: when a tap landed relative to a swipe is part of
     * what the aligner had to work with.
     *
     * Thinned and capped, so this stays a few hundred bytes rather than a transcript of typing.
     */
    class ReplaySegment(
        val isTap: Boolean,
        /** Code point for a tap; meaningless for a swipe. */
        val codePoint: Int,
        val x: FloatArray,
        val y: FloatArray,
        /** Milliseconds from the start of the word. */
        val t: FloatArray
    ) {
        val startT: Float get() = if (t.isEmpty()) 0f else t[0]
        val endT: Float get() = if (t.isEmpty()) 0f else t[t.size - 1]
    }

    class LastLearned(
        val word: String,
        val attributions: List<TapSwipeStrokeAligner.Attribution>,
        val segments: List<ReplaySegment>,
        val wasCorrection: Boolean
    ) {
        /** Wall-clock length of the whole word, so a replay can run at a faithful relative speed. */
        val durationMs: Float
            get() {
                if (segments.isEmpty()) return 0f
                val start = segments.minOf { it.startT }
                val end = segments.maxOf { it.endT }
                return (end - start).coerceAtLeast(0f)
            }
    }

    @Volatile
    @JvmStatic
    var lastLearned: LastLearned? = null
        private set

    /** Points retained across all swipe segments. Enough to read the shape, far fewer than raw. */
    private const val REPLAY_POINTS = 96

    /**
     * Copies the strokes for replay, in time order, thinning swipes to fit the budget.
     *
     * Taps are never thinned - they are one point each, and they are the events whose timing
     * matters most for understanding an alignment.
     */
    private fun replaySegments(
        inputs: List<TapSwipeStrokeAligner.StrokeInput>
    ): List<ReplaySegment> {
        val ordered = inputs.sortedBy { it.startT }
        val swipePoints = ordered.filter { it.kind != TapSwipeSession.Kind.SWIPE }
            .let { taps -> ordered.sumOf { it.x.size } - taps.sumOf { it.x.size } }
        val budget = REPLAY_POINTS.coerceAtLeast(1)

        return ordered.mapNotNull { stroke ->
            if (stroke.x.isEmpty()) return@mapNotNull null
            val isTap = stroke.kind == TapSwipeSession.Kind.TAP
            if (isTap) {
                return@mapNotNull ReplaySegment(
                    true, Character.toLowerCase(stroke.codePoint),
                    floatArrayOf(stroke.x[0]), floatArrayOf(stroke.y[0]),
                    floatArrayOf(if (stroke.t.isNotEmpty()) stroke.t[0] else 0f)
                )
            }
            val share = if (swipePoints <= budget) stroke.x.size
                        else max(2, stroke.x.size * budget / max(1, swipePoints))
            val take = min(stroke.x.size, share)
            val ox = FloatArray(take)
            val oy = FloatArray(take)
            val ot = FloatArray(take)
            for (i in 0 until take) {
                val src = if (take == 1) 0
                          else (i.toLong() * (stroke.x.size - 1) / (take - 1)).toInt()
                ox[i] = stroke.x[src]
                oy[i] = stroke.y[src]
                ot[i] = if (src < stroke.t.size) stroke.t[src] else 0f
            }
            ReplaySegment(false, 0, ox, oy, ot)
        }
    }

    /**
     * Why words were turned away, since the last app start.
     *
     * The gates are deliberately strict, and strict gates are indistinguishable from broken ones
     * when all you can see is that nothing was learned. These say which gate is doing it - in
     * particular whether [MIN_MARGIN], which is a guess, is rejecting nearly everything.
     */
    object Counters {
        @Volatile @JvmStatic var accepted = 0
        @Volatile @JvmStatic var samples = 0
        @Volatile @JvmStatic var tooShort = 0
        @Volatile @JvmStatic var noSwipe = 0
        @Volatile @JvmStatic var concurrent = 0
        @Volatile @JvmStatic var wordMismatch = 0
        @Volatile @JvmStatic var lowMargin = 0
        @Volatile @JvmStatic var notAligned = 0
        @Volatile @JvmStatic var implausible = 0
        @Volatile @JvmStatic var blockedByField = 0
        @Volatile @JvmStatic var corrected = 0
        @Volatile @JvmStatic var retracted = 0
        @Volatile @JvmStatic var lastRejectedWord = ""
        @Volatile @JvmStatic var lastMarginSeen = 0f

        @JvmStatic
        fun summary(): String =
            "accepted=$accepted (samples=$samples)  short=$tooShort  noSwipe=$noSwipe  " +
            "concurrent=$concurrent  mismatch=$wordMismatch  lowMargin=$lowMargin  " +
            "unaligned=$notAligned  implausible=$implausible  noLearnField=$blockedByField\n" +
            "corrected=$corrected  retracted=$retracted"

        @JvmStatic
        fun reset() {
            accepted = 0; samples = 0; tooShort = 0; noSwipe = 0; concurrent = 0
            wordMismatch = 0; lowMargin = 0; notAligned = 0; implausible = 0
            blockedByField = 0; corrected = 0; retracted = 0
            lastRejectedWord = ""; lastMarginSeen = 0f
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Save is debounced by sample count rather than time - writes are tiny and infrequent anyway. */
    private const val SAVE_EVERY_N_SAMPLES = 12
    private var samplesSinceSave = 0

    @JvmStatic
    fun isEnabled(): Boolean =
        DataStoreHelper.getSetting(TapSwipeAdaptiveGeometrySetting)

    /**
     * Whether this field permits learning at all.
     *
     * `InputAttributes.mNoLearning` is the gate the rest of the keyboard already honours - it covers
     * password fields, code fields, and any editor that sets
     * `IME_FLAG_NO_PERSONALIZED_LEARNING`. Touch geometry is content-free, but per-key sample counts
     * still say which letters someone used, and a feature that quietly ignored the established
     * no-learning contract would be wrong regardless of how thin the signal is.
     */
    @JvmStatic
    fun isAllowedInField(settingsValues: SettingsValues?): Boolean {
        val attrs = settingsValues?.mInputAttributes ?: return false
        return !attrs.mNoLearning
    }

    /**
     * @param committedWord what actually landed in the editor
     * @param session the session about to be discarded; its strokes are the raw evidence
     */
    @JvmStatic
    fun onWordFinalized(
        context: Context,
        session: TapSwipeSession,
        committedWord: String,
        settingsValues: SettingsValues?
    ) {
        if (!isEnabled()) return
        // Cheap and idempotent, but load-before-record is load-bearing: recording into an unread
        // model and then saving would replace the file with only this session's samples.
        TapSwipeTouchModel.ensureLoaded(context)
        if (!isAllowedInField(settingsValues)) {
            Counters.blockedByField++
            return
        }

        val word = committedWord.trim()
        if (word.length < MIN_WORD_LENGTH) { Counters.tooShort++; return }

        val strokes = session.strokes
        if (strokes.isEmpty()) return
        // A pure-tap word is peck-adjacent; the value here is in sloppy swiping.
        if (strokes.none { it.kind == TapSwipeSession.Kind.SWIPE }) { Counters.noSwipe++; return }

        val inputs = strokes
            .filter { !it.isEmpty }
            .map {
                TapSwipeStrokeAligner.StrokeInput(it.kind, it.codePoint, it.x, it.y, it.t)
            }
        if (inputs.isEmpty()) return

        // Two thumbs at once: the decoder resolves their interleaving by lexicon, not by time, so
        // "time order equals letter order" - the premise the alignment rests on - does not hold.
        if (TapSwipeStrokeAligner.overlapsInTime(inputs)) {
            Counters.concurrent++
            if (DEBUG) Log.d(TAG, "skipping '$word': concurrent strokes")
            return
        }

        // The decoder must have been confident, and about *this* word - a mismatch means the
        // committed text came from somewhere else (autocorrect, a picked suggestion) and the
        // margin we captured describes a different candidate.
        Counters.lastMarginSeen = SwipeDecoderDictionary.lastDecodeMargin
        if (!SwipeDecoderDictionary.lastDecodeWord.equals(word, ignoreCase = true)) {
            Counters.wordMismatch++
            if (DEBUG) Log.d(TAG, "skipping '$word': decode was " +
                "'${SwipeDecoderDictionary.lastDecodeWord}'")
            return
        }
        if (SwipeDecoderDictionary.lastDecodeMargin < MIN_MARGIN) {
            Counters.lowMargin++
            Counters.lastRejectedWord = word
            if (DEBUG) Log.d(TAG, "skipping '$word': margin " +
                "${SwipeDecoderDictionary.lastDecodeMargin} < $MIN_MARGIN")
            return
        }

        val layoutKey = SwipeDecoderDictionary.currentTouchModelLayoutKey() ?: return
        val extent = SwipeDecoderDictionary.normalizedKeyHalfExtent() ?: return

        // Nominal centres, never personalized ones. If the alignment measured against the shifted
        // positions, every correction would be judged against the previous correction - the model
        // would drift under its own bias and an early noisy sample could reinforce itself forever.
        val aligned = TapSwipeStrokeAligner.align(
            word, inputs
        ) { cp -> SwipeDecoderDictionary.normalizedKeyPosition(cp) }

        // When exact tap positions are off, a tap records the key *centre* - so its offset is
        // identically zero. Those are not observations, they are the absence of one, and folding
        // them in would drag every mean toward zero while inflating confidence.
        val keepTaps = DataStoreHelper.getSetting(TapSwipeRealTapPositionSetting)
        val attributions = if (keepTaps) aligned else aligned.filter { !it.fromTap }

        if (attributions.isEmpty()) {
            Counters.notAligned++
            Counters.lastRejectedWord = word
            return
        }
        if (!TapSwipeStrokeAligner.isPlausible(attributions, extent[0], extent[1])) {
            Counters.implausible++
            Counters.lastRejectedWord = word
            if (DEBUG) Log.d(TAG, "rejected implausible alignment for '$word'")
            return
        }

        commit(context, layoutKey, word, attributions, weightScale = 1f, inputs = inputs)
        Counters.accepted++
    }

    /**
     * Records a batch and remembers it, so a correction moments later can take it back.
     */
    private fun commit(
        context: Context,
        layoutKey: String,
        word: String,
        attributions: List<TapSwipeStrokeAligner.Attribution>,
        weightScale: Float,
        inputs: List<TapSwipeStrokeAligner.StrokeInput>
    ) {
        val now = System.currentTimeMillis()
        for (a in attributions) {
            TapSwipeTouchModel.record(
                layoutKey, a.codePoint, a.dx, a.dy, a.weight * weightScale, now)
        }
        lastBatch = LearnedBatch(layoutKey, word, attributions, weightScale)
        lastLearned = LastLearned(
            word, attributions, replaySegments(inputs), wasCorrection = weightScale != 1f)
        Counters.samples += attributions.size

        if (DEBUG) {
            Log.d(TAG, "learned from '$word'" +
                (if (weightScale != 1f) " (x$weightScale)" else "") + ": " +
                attributions.joinToString {
                    "${it.codePoint.toChar()}${if (it.fromTap) "*" else ""}" +
                        "(${"%.4f".format(it.dx)},${"%.4f".format(it.dy)}" +
                        " w=${"%.2f".format(it.weight)})"
                })
        }

        samplesSinceSave += attributions.size
        if (samplesSinceSave >= SAVE_EVERY_N_SAMPLES) {
            samplesSinceSave = 0
            val app = context.applicationContext
            scope.launch { TapSwipeTouchModel.save(app) }
        }
    }

    /**
     * Tier 2, positive: the user named the word the gesture meant.
     *
     * Called when a suggestion is picked for a word that is still composing, so the strokes are the
     * *original* ones - the same gesture, now with a label we know is right. That makes it better
     * evidence than anything [onWordFinalized] collects, and it is the case where learning has the
     * most to correct, since the decoder demonstrably read this gesture wrong.
     *
     * The confidence gates that guard accepted words are therefore skipped: the decode margin and
     * the decode-matches-committed check both exist to establish that the decoder was probably
     * right, and here the user has overruled it. The plausibility check stays - it guards against
     * someone picking an unrelated next-word prediction, where the stroke never went near the
     * letters and the offsets would be meaningless.
     */
    @JvmStatic
    fun onWordCorrected(
        context: Context,
        session: TapSwipeSession,
        correctedWord: String,
        settingsValues: SettingsValues?
    ) {
        if (!isEnabled()) return
        TapSwipeTouchModel.ensureLoaded(context)
        if (!isAllowedInField(settingsValues)) { Counters.blockedByField++; return }

        val word = correctedWord.trim()
        if (word.length < MIN_WORD_LENGTH) { Counters.tooShort++; return }

        val strokes = session.strokes
        if (strokes.isEmpty()) return
        if (strokes.none { it.kind == TapSwipeSession.Kind.SWIPE }) { Counters.noSwipe++; return }

        val inputs = strokes
            .filter { !it.isEmpty }
            .map { TapSwipeStrokeAligner.StrokeInput(it.kind, it.codePoint, it.x, it.y, it.t) }
        if (inputs.isEmpty()) return
        if (TapSwipeStrokeAligner.overlapsInTime(inputs)) { Counters.concurrent++; return }

        val layoutKey = SwipeDecoderDictionary.currentTouchModelLayoutKey() ?: return
        val extent = SwipeDecoderDictionary.normalizedKeyHalfExtent() ?: return

        val aligned = TapSwipeStrokeAligner.align(word, inputs) { cp ->
            SwipeDecoderDictionary.normalizedKeyPosition(cp)
        }
        val keepTaps = DataStoreHelper.getSetting(TapSwipeRealTapPositionSetting)
        val attributions = if (keepTaps) aligned else aligned.filter { !it.fromTap }

        if (attributions.isEmpty()) { Counters.notAligned++; return }
        if (!TapSwipeStrokeAligner.isPlausible(attributions, extent[0], extent[1])) {
            Counters.implausible++
            if (DEBUG) Log.d(TAG, "rejected implausible correction for '$word'")
            return
        }

        commit(context, layoutKey, word, attributions, CORRECTION_WEIGHT, inputs)
        Counters.corrected++
    }

    /**
     * Tier 2, negative: a word we just learned from turned out to be wrong.
     *
     * Called when a finished word is reopened for editing, which is the user saying the committed
     * text was not what they meant. Whatever geometry was recorded for it describes a misreading,
     * so it is taken back rather than left to drag the affected keys toward a word nobody typed.
     *
     * Only the immediately preceding batch is retractable, and only if it is for this word. Holding
     * a longer history would mean guessing which edit undid which sample.
     */
    @JvmStatic
    fun onWordRejected(word: String) {
        if (!isEnabled()) return
        val batch = lastBatch ?: return
        if (!batch.word.equals(word.trim(), ignoreCase = true)) return
        lastBatch = null

        val now = System.currentTimeMillis()
        for (a in batch.attributions) {
            TapSwipeTouchModel.retract(
                batch.layoutKey, a.codePoint, a.dx, a.dy, a.weight * batch.weightScale, now)
        }
        Counters.retracted++
        if (DEBUG) Log.d(TAG, "retracted what was learned from '${batch.word}'")
    }

    /** Flush on the way out, so a session's last few words are not lost. */
    @JvmStatic
    fun flush(context: Context) {
        val app = context.applicationContext
        samplesSinceSave = 0
        scope.launch { TapSwipeTouchModel.save(app) }
    }

    private const val DEBUG = true
}
