package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.SuggestedWords

/**
 * Keyboard decisions, as recorder events. Each call is one line in `InputLogic` and returns at once
 * unless [TypingTestRecorder.active] is true.
 *
 * These are observations only. Nothing here may change state or call back into the input logic.
 */
object TypingTestEvents {
    private inline fun ifActive(block: () -> Unit) {
        if (TypingTestRecorder.active) block()
    }

    @JvmStatic
    fun swipeAbsorbed(segments: Int, strokes: Int, generation: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("stroke")
            .put("kind", "swipe").put("segments", segments)
            .put("strokes", strokes).put("gen", generation))
    }

    @JvmStatic
    fun tapAdded(codePoint: Int, strokes: Int, generation: Int, hand: String) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("stroke")
            .put("kind", "tap").put("code", codePoint).put("hand", hand)
            .put("char", String(Character.toChars(codePoint)))
            .put("strokes", strokes).put("gen", generation))
    }

    /** A decode the generation guard dropped. */
    @JvmStatic
    fun decodeStale(text: String?, decodeGeneration: Int, sessionGeneration: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("decode_stale")
            .put("text", text).put("gen", decodeGeneration).put("sessionGen", sessionGeneration))
    }

    /**
     * A decode that passed the guard, before it is written. `covered` below `strokes` means it was
     * computed before the newest stroke joined.
     */
    @JvmStatic
    fun decodeArrived(words: SuggestedWords, covered: Int, strokes: Int, generation: Int) = ifActive {
        val n = minOf(words.size(), 5)
        val list = ArrayList<String>(n)
        val scores = IntArray(n)
        for (i in 0 until n) {
            list.add(words.getWord(i))
            scores[i] = words.getInfo(i).mScore
        }
        TypingTestRecorder.emit(TypingTestRecorder.event("decode")
            .put("text", if (n > 0) list[0] else null)
            .putStrings("candidates", list).putInts("scores", scores)
            .put("covered", covered).put("strokes", strokes).put("gen", generation)
            .put("resultStyle", words.mInputStyle).put("resultSeq", words.mSequenceNumber))
    }

    /** The decoder's own score breakdown per candidate, for ranking analysis. */
    @JvmStatic
    fun decodeDetail(words: List<String>, scores: List<Float>, ctc: List<Float>, lm: List<Float>,
                     context: List<String>, alpha: Float, beta: Float, gamma: Float, lambda: Float,
                     leftSegs: Int, rightSegs: Int, orderPenalty: List<Float> = emptyList(),
                     timings: List<TapSwipeTimingOrder.Timing> = emptyList(),
                     rerankMs: Float = 0f, apostrophe: Boolean = false, aposPos: FloatArray? = null,
                     keyHalf: FloatArray? = null, lPos: FloatArray? = null) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("decode_detail")
            .putStrings("words", words)
            .putFloats("score", scores.toFloatArray()).putFloats("ctc", ctc.toFloatArray())
            .putFloats("lm", lm.toFloatArray())
            .putStrings("context", context.takeLast(4))
            .put("alpha", alpha).put("beta", beta).put("gamma", gamma).put("lambda", lambda)
            .put("left", leftSegs).put("right", rightSegs)
            .put("rerankMs", rerankMs)
            .put("apostrophe", apostrophe)
            .putFloats("aposPos", aposPos ?: FloatArray(0)).putFloats("keyHalf", keyHalf ?: FloatArray(0))
            .putFloats("lPos", lPos ?: FloatArray(0))
            .putFloats("orderPenalty", orderPenalty.toFloatArray())
            .putStrings("timings", timings.map {
                (if (it.isTap) "tap:" + String(Character.toChars(it.codePoint)) else "swipe") +
                    (if (it.right) "/R" else "/L") + " ${it.t0.toInt()}..${it.t1.toInt()}"
            }))
    }

    /** A suggestion computation starting, with the stroke count of the TapSwipe input built for it. */
    @JvmStatic
    fun decodeRequest(style: Int, seq: Int, segments: Int, generation: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("decode_request")
            .put("style", style).put("seq", seq).put("segments", segments).put("gen", generation))
    }

    /** A separator ended a word. `verbatim` says which commit path it took. */
    @JvmStatic
    fun finalize(codePoint: Int, verbatim: Boolean, typedWord: String?, autoCorrection: String?,
                 mode: String?) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("finalize")
            .put("code", codePoint).put("verbatim", verbatim)
            .put("typedWord", typedWord).put("autoCorrection", autoCorrection).put("mode", mode))
    }

    @JvmStatic
    fun commit(text: String, commitType: Int, separator: String?) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("commit")
            .put("text", text).put("commitType", commitType).put("separator", separator))
    }

    /** Backspace presses while recording. The typing test counts corrections from this. */
    @JvmField
    val backspaces = java.util.concurrent.atomic.AtomicInteger(0)

    @JvmStatic
    fun backspace(composing: String?, strokes: Int, sessionOpen: Boolean, keyRepeat: Boolean) = ifActive {
        backspaces.incrementAndGet()
        TypingTestRecorder.emit(TypingTestRecorder.event("backspace")
            .put("composing", composing).put("strokes", strokes)
            .put("sessionOpen", sessionOpen).put("repeat", keyRepeat))
    }

    /** Which backspace branch handled the press: `grace_reopen`, `stroke_undo`, or `revert_commit`. */
    @JvmStatic
    fun backspaceTier(tier: String, detail: String?) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("backspace_tier")
            .put("tier", tier).put("detail", detail))
    }

    /**
     * A selection update from the editor. `outcome` is `belated` (matched what the keyboard expected
     * and was dismissed), `kept` (cursor stayed inside the word), or a `reset_*` that ended the word.
     */
    @JvmStatic
    fun selection(oldStart: Int, newStart: Int, composingStart: Int, composingEnd: Int,
                  expectedStart: Int, outcome: String, composing: String?) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("selection")
            .put("old", oldStart).put("new", newStart)
            .put("compStart", composingStart).put("compEnd", composingEnd)
            .put("expected", expectedStart).put("outcome", outcome).put("composing", composing))
    }

    /**
     * Suggestion pipeline steps: `request`, `skip` (a newer request superseded it before it ran),
     * `done`, and `result` / `result_dropped` when the answer reaches the main thread. `style` is a
     * SuggestedWords.INPUT_STYLE_* value: 1 typing, 3 tail batch.
     */
    @JvmStatic
    fun suggest(step: String, style: Int, seq: Int, current: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("suggest")
            .put("step", step).put("style", style).put("seq", seq).put("current", current))
    }

    /** The word session dropped while it held strokes. `reason` names the invariant that fired. */
    @JvmStatic
    fun sessionReset(reason: String, strokes: Int, generation: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("session_reset")
            .put("reason", reason).put("strokes", strokes).put("gen", generation))
    }

    @JvmStatic
    fun pick(word: String, index: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("pick").put("word", word).put("index", index))
    }

    @JvmStatic
    /** The word segmenter's verdict for one stroke list, with every reading it scored. */
    fun segment(best: String, readings: String, generation: Int, strokes: Int, ms: Float) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("segment")
            .put("best", best).put("readings", readings).put("gen", generation).put("strokes", strokes)
            .put("ms", ms))
    }

    /** A tapped word's language score, raw, for calibrating the segmenter. */
    @JvmStatic
    fun tappedWordScore(word: String, context: String, score: Float, lm: Float, found: Boolean) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("tapped_word")
            .put("word", word).put("context", context).put("score", score).put("lm", lm)
            .put("found", found))
    }

    /** The finalizer committed a segmented stroke list as several words. */
    /** The auto space timer: armed, cancelled, fired, or stale (fired on a word that changed). */
    @JvmStatic
    fun autoSpace(what: String, delayMs: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("auto_space").put("what", what).put("delay", delayMs))
    }

    /** The rejoin check: [joined] is the joined word, or empty when the words stay apart. */
    @JvmStatic
    fun rejoinCheck(prefix: String, joined: String, strokes: Int, ms: Float, detail: String) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("rejoin_check").put("prefix", prefix)
            .put("joined", joined).put("strokes", strokes).put("ms", ms).put("detail", detail))
    }

    /** A rejoin was applied: the previous word and this one became [joined]. */
    @JvmStatic
    fun rejoined(prefix: String, joined: String) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("rejoin").put("prefix", prefix).put("joined", joined))
    }

    /** An alternative reading was offered in the suggestion strip. */
    @JvmStatic
    fun alternativeOffered(shown: String, alternative: String) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("alternative_offered").put("shown", shown)
            .put("alternative", alternative))
    }

    /** The user tapped the alternative. */
    @JvmStatic
    fun alternativePicked(shown: String, alternative: String) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("alternative_picked").put("shown", shown)
            .put("alternative", alternative))
    }

    /** The finalizer waited for the segmenter. */
    @JvmStatic
    fun segmentWait(ms: Int, arrived: Boolean) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("segment_wait").put("ms", ms).put("arrived", arrived))
    }

    @JvmStatic
    fun segmentCommitted(words: String, strokes: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("segment_commit")
            .put("words", words).put("strokes", strokes))
    }

    @JvmStatic
    fun split(head: String, at: Int, strokes: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("split")
            .put("head", head).put("at", at).put("strokes", strokes))
    }
}
