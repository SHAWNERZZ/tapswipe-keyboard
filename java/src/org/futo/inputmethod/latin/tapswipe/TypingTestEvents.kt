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
    fun tapAdded(codePoint: Int, strokes: Int, generation: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("stroke")
            .put("kind", "tap").put("code", codePoint)
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
            .put("covered", covered).put("strokes", strokes).put("gen", generation))
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

    @JvmStatic
    fun backspace(composing: String?, strokes: Int, sessionOpen: Boolean, keyRepeat: Boolean) = ifActive {
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
    fun split(head: String, at: Int, strokes: Int) = ifActive {
        TypingTestRecorder.emit(TypingTestRecorder.event("split")
            .put("head", head).put("at", at).put("strokes", strokes))
    }
}
