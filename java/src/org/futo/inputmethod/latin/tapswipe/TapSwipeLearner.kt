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
import org.futo.inputmethod.latin.uix.DataStoreHelper

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Save is debounced by sample count rather than time - writes are tiny and infrequent anyway. */
    private const val SAVE_EVERY_N_SAMPLES = 12
    private var samplesSinceSave = 0

    @JvmStatic
    fun isEnabled(): Boolean =
        DataStoreHelper.getSetting(TapSwipeAdaptiveGeometrySetting)

    /**
     * @param committedWord what actually landed in the editor
     * @param session the session about to be discarded; its strokes are the raw evidence
     */
    @JvmStatic
    fun onWordFinalized(context: Context, session: TapSwipeSession, committedWord: String) {
        if (!isEnabled()) return

        val word = committedWord.trim()
        if (word.length < MIN_WORD_LENGTH) return

        val strokes = session.strokes
        if (strokes.isEmpty()) return
        // A pure-tap word is peck-adjacent; the value here is in sloppy swiping.
        if (strokes.none { it.kind == TapSwipeSession.Kind.SWIPE }) return

        val inputs = strokes
            .filter { !it.isEmpty }
            .map {
                TapSwipeStrokeAligner.StrokeInput(it.kind, it.codePoint, it.x, it.y, it.t)
            }
        if (inputs.isEmpty()) return

        // Two thumbs at once: the decoder resolves their interleaving by lexicon, not by time, so
        // "time order equals letter order" - the premise the alignment rests on - does not hold.
        if (TapSwipeStrokeAligner.overlapsInTime(inputs)) {
            if (DEBUG) Log.d(TAG, "skipping '$word': concurrent strokes")
            return
        }

        // The decoder must have been confident, and about *this* word - a mismatch means the
        // committed text came from somewhere else (autocorrect, a picked suggestion) and the
        // margin we captured describes a different candidate.
        if (!SwipeDecoderDictionary.lastDecodeWord.equals(word, ignoreCase = true)) return
        if (SwipeDecoderDictionary.lastDecodeMargin < MIN_MARGIN) return

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

        if (attributions.isEmpty()) return
        if (!TapSwipeStrokeAligner.isPlausible(attributions, extent[0], extent[1])) {
            if (DEBUG) Log.d(TAG, "rejected implausible alignment for '$word'")
            return
        }

        val now = System.currentTimeMillis()
        for (a in attributions) {
            TapSwipeTouchModel.record(layoutKey, a.codePoint, a.dx, a.dy, a.weight, now)
        }

        if (DEBUG) {
            Log.d(TAG, "learned from '$word': " + attributions.joinToString {
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

    /** Flush on the way out, so a session's last few words are not lost. */
    @JvmStatic
    fun flush(context: Context) {
        val app = context.applicationContext
        samplesSinceSave = 0
        scope.launch { TapSwipeTouchModel.save(app) }
    }

    private const val DEBUG = true
}
