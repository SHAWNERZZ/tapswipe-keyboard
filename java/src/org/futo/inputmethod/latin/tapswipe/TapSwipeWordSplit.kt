package org.futo.inputmethod.latin.tapswipe

import kotlin.math.abs

/**
 * Whether the strokes of one TapSwipe word are really two words.
 *
 * A word runs from one finalizer to the next, so a short tapped word beside a swiped one folds into
 * that word. Tapping `i` after swiping `hello` gives one mangled word. Tapping `i` before swiping
 * `think` gives the same. This decides where the boundary belongs.
 *
 * ### The tap must never re-read the swipe
 *
 * The failure this exists to stop: swipe across a, c, a and the decoder shows `ava`, a saved name.
 * Tap `i` and the result is `acai`. That happens because the tap is appended to the path as one more
 * point, so the decoder reads the whole shape again, and a, c, a, i is exactly the shape of `acai`.
 * The decoder answered the question it was asked.
 *
 * So the caller must never hand the taps to the path decoder to build a fusion. It decodes the swipe
 * strokes alone, takes the tapped letters as exact text, and joins the two by string. The tapped
 * letters are known: [TapSwipeSession.Stroke] stores each tap's code point.
 *
 * ### Two layers
 *
 * Layer 1 asks whether the strokes can read as one word at all. Two dictionary lookups settle most
 * words. Layer 2 runs only when both readings are real words, and it asks which one wins.
 *
 * Pure, so the rule is checked without a device. Deciding what is a word needs live dictionaries and
 * a locale, so that stays with the caller through [Lexicon].
 *
 * See `docs/plans/WORD_SPLIT.md` for the reasoning and the open decisions.
 */
object TapSwipeWordSplit {

    enum class Verdict {
        /** One word. Today's behavior, and the answer on any tie. */
        FUSE,

        /** Two words. Commit the head, then keep the tail as the open word. */
        SPLIT,

        /**
         * Undecided. No single word fits yet, and the tapped side is not a word on its own. Another
         * tap may complete either reading, so behave as [FUSE] and ask again next stroke.
         *
         * This is what makes a backward-moving boundary possible. `see` plus a tap on `m` reads as
         * `seem`, and only the following `e` shows the boundary belonged before the `m`. Nothing has
         * committed at that point, so the answer is free to change.
         */
        WAIT
    }

    /** What the dictionary returns for a word it does not hold. Matches `Dictionary`. */
    const val UNKNOWN_FREQUENCY = -1

    /**
     * How far ahead one reading must be before it overrules the other.
     *
     * Frequencies and n-gram probabilities share a 0 to 255 scale, and a unigram is not directly
     * comparable with a pair. This margin absorbs that. It needs calibration against real strokes on
     * a device, using the candidate and score logging already in `SwipeDecoderDictionary`.
     */
    const val CLEAR_WIN = 24

    /** Where the two readings are close enough that how the user typed decides instead. */
    const val TIE_BAND = 12

    /**
     * How many letters another reading of the same swipe may differ by.
     *
     * One path produces words of close to one length, so a candidate whose leading part is far
     * shorter than the head is not a reading of that swipe at all. Without this, `this` counted as a
     * single-word reading of `this` plus `is`, because it ends in those letters. That left `th` as
     * the supposed reading of a four-letter gesture, and the boundary was judged against it.
     */
    const val MAX_READING_LENGTH_DRIFT = 1

    /**
     * A fused word this rare loses to two common words when no pair entry exists.
     *
     * The dictionaries hold n-gram entries unevenly, and the personal dictionary starts with none,
     * so "how often does this tail follow this head" is often unanswerable. In that state the fused
     * word's own rarity is the only evidence available. `acai` and `fora` sit below this. `area` and
     * `about` sit far above it. Needs the same device calibration as [CLEAR_WIN].
     */
    const val RARE_FUSION = 48

    /**
     * A pause this long between the two parts reads as a word boundary.
     *
     * Only consulted inside [TIE_BAND]. Someone typing `area` as a swipe and a tap does it as one
     * action. Someone typing `are a` crosses a word boundary, and people pause at those. Also needs
     * device calibration; pass a negative gap to leave it out entirely.
     */
    const val DELIBERATE_GAP_MS = 400

    /** The dictionary answers this needs. Supplied by the caller so the rule stays pure. */
    interface Lexicon {
        fun isWord(text: String): Boolean

        /** 0 to 255, or [UNKNOWN_FREQUENCY]. */
        fun frequency(text: String): Int

        /** Probability of [second] directly following [first], or [UNKNOWN_FREQUENCY]. */
        fun pairFrequency(first: String, second: String): Int
    }

    /**
     * One way the strokes could read as a single word.
     *
     * @param text the whole word, built by joining the two sides as strings
     * @param fromBestReading true when the swiped side used the decoder's top candidate. False means
     *   this fusion only exists because a lower-ranked reading of the swipe was substituted, which
     *   is the `ava` to `aca` demotion that produced `acai`.
     */
    class Fusion(
        @JvmField val text: String,
        @JvmField val fromBestReading: Boolean
    )

    /**
     * One candidate boundary, which is always a place where the stroke kind changes between tap and
     * swipe.
     *
     * @param head text of the leading part
     * @param tail text of the trailing part
     * @param tapRunLeads true when the taps came first, so [head] is exact typed text
     * @param fusions single-word readings of the whole thing, best first
     * @param gapMs milliseconds between the two parts, or negative when not measured
     */
    class Boundary @JvmOverloads constructor(
        @JvmField val head: String,
        @JvmField val tail: String,
        @JvmField val tapRunLeads: Boolean,
        @JvmField val fusions: List<Fusion>,
        @JvmField val gapMs: Int = -1
    )

    /**
     * The boundary a decoder candidate list describes, or null when there is nothing to judge.
     *
     * Kept here rather than at the call site so the whole path from decoder output to verdict can be
     * tested without a device. Everything this needs is a string.
     *
     * @param swipeReading what the swiped strokes read as on their own. Ignored when the taps lead,
     *   because no reading of the swipe alone exists until after a split re-decodes it.
     * @param tapText the exact letters of the tap run
     * @param tapRunLeads true when the taps came first
     * @param candidates the decoder's readings of every stroke, best first
     * @param gapMs milliseconds between the two parts, or negative when not measured
     */
    @JvmStatic
    fun boundaryFor(
        swipeReading: String,
        tapText: String,
        tapRunLeads: Boolean,
        candidates: List<String>,
        gapMs: Int
    ): Boundary? {
        if (tapText.isEmpty()) return null

        if (tapRunLeads) {
            // The taps are exact text, so a reading that honored them begins with them. One that
            // does not has thrown a keypress away, and is no fusion of these two parts.
            val fusions = ArrayList<Fusion>()
            var tail = ""
            for (candidate in candidates) {
                if (candidate.length <= tapText.length) continue
                if (!candidate.regionMatches(0, tapText, 0, tapText.length, ignoreCase = true)) {
                    continue
                }
                fusions.add(Fusion(candidate, true))
                if (tail.isEmpty()) tail = candidate.substring(tapText.length)
            }
            if (tail.isEmpty()) {
                // No reading kept the taps, so the swiped side has none of its own here. A split
                // re-decodes it. This stands in for the frequency comparison only.
                tail = candidates.firstOrNull { it.isNotEmpty() } ?: return null
            }
            return Boundary(tapText, tail, true, fusions, gapMs)
        }

        if (swipeReading.isEmpty()) return null
        val fusions = ArrayList<Fusion>()
        for (candidate in candidates) {
            if (candidate.length <= tapText.length) continue
            val at = candidate.length - tapText.length
            if (!candidate.regionMatches(at, tapText, 0, tapText.length, ignoreCase = true)) continue
            val leading = candidate.substring(0, at)
            if (!couldBeSameSwipe(leading, swipeReading)) continue
            fusions.add(Fusion(candidate, leading.equals(swipeReading, ignoreCase = true)))
        }
        return Boundary(swipeReading, tapText, false, fusions, gapMs)
    }

    /**
     * Whether [leading] could be the same gesture read a different way.
     *
     * Two readings of one path start at the same key and have close to the same number of letters.
     * Both tests are needed. Length alone would accept `axa` for `ava`, which is fine, and reject
     * nothing useful. The first-letter test alone would accept `th` for `this`.
     */
    private fun couldBeSameSwipe(leading: String, swipeReading: String): Boolean {
        if (leading.isEmpty()) return false
        if (leading.equals(swipeReading, ignoreCase = true)) return true
        if (abs(leading.length - swipeReading.length) > MAX_READING_LENGTH_DRIFT) return false
        return leading[0].lowercaseChar() == swipeReading[0].lowercaseChar()
    }

    /** One place the word could break, with the stroke index it sits at. */
    class Option(
        @JvmField val at: Int,
        @JvmField val boundary: Boundary
    )

    /**
     * The boundary to act on, or null when none of them splits.
     *
     * More than one boundary can split the same word, and picking the wrong one is what produced
     * `to sis`. That word was a gesture over t, h, i plus tapped `s`, `i`, `s`. Two boundaries
     * split it: after the gesture, giving `to` and `sis`, and after the first tap, giving `this` and
     * `is`. Only the second is what the user typed.
     *
     * **Later boundaries win.** Let the gestured word absorb as many of the taps that follow it as
     * it can, and start the new word with whatever is left. That is the order fingers work in: the
     * taps that finish a gestured word come before the taps that begin the next one.
     */
    @JvmStatic
    fun chooseSplit(options: List<Option>, lexicon: Lexicon): Option? {
        var best: Option? = null
        for (option in options) {
            if (best != null && option.at <= best.at) continue
            if (decide(option.boundary, lexicon) == Verdict.SPLIT) best = option
        }
        return best
    }

    /** [boundaryFor] then [decide], for callers and tests that want the whole answer at once. */
    @JvmStatic
    fun decideFrom(
        swipeReading: String,
        tapText: String,
        tapRunLeads: Boolean,
        candidates: List<String>,
        gapMs: Int,
        lexicon: Lexicon
    ): Verdict {
        val boundary = boundaryFor(swipeReading, tapText, tapRunLeads, candidates, gapMs)
            ?: return Verdict.FUSE
        return decide(boundary, lexicon)
    }

    @JvmStatic
    fun decide(boundary: Boundary, lexicon: Lexicon): Verdict {
        // Both sides of a split have to carry text.
        if (boundary.head.isEmpty() || boundary.tail.isEmpty()) return Verdict.FUSE

        // Only the tapped side is tested. The swiped side came out of a lexicon-constrained beam
        // search, so it is already a word, and re-testing it against a different dictionary set
        // would decline valid words for no reason.
        val tapRun = if (boundary.tapRunLeads) boundary.head else boundary.tail
        val tapRunIsWord = lexicon.isWord(tapRun)

        // ---- Layer 1. Can these strokes read as one word at all?
        //
        // Prefer a reading that keeps the swipe's best candidate. The layer 2 test below asks
        // whether fusing *had* to demote it, and "had to" means no such reading exists. Taking the
        // highest-ranked valid fusion instead would split `cat` plus `s` whenever some unrelated
        // word outranked `cats`.
        val valid = boundary.fusions.filter { it.text.isNotEmpty() && lexicon.isWord(it.text) }
        val fusion = valid.firstOrNull { it.fromBestReading } ?: valid.firstOrNull()

        if (fusion == null) {
            // No single word fits. Split once the tapped side stands on its own.
            return if (tapRunIsWord) Verdict.SPLIT else Verdict.WAIT
        }

        // A real word fits and the tapped side is not a word by itself, so the tap was a suffix.
        // This is `cats` and `worked`.
        if (!tapRunIsWord) return Verdict.FUSE

        // ---- Layer 2. Both readings are real words.

        // Fusing had to demote the swipe's best reading, so the tap rewrote strokes it did not make.
        // A tap may extend a word. It may never re-read the one before it.
        if (!fusion.fromBestReading) return Verdict.SPLIT

        return compare(boundary, fusion.text, lexicon)
    }

    private fun compare(boundary: Boundary, fused: String, lexicon: Lexicon): Verdict {
        val fusedFrequency = lexicon.frequency(fused)

        // How often this tail follows this head is the right question. It is also the one the
        // dictionaries often cannot answer.
        val pair = lexicon.pairFrequency(boundary.head, boundary.tail)

        if (pair == UNKNOWN_FREQUENCY) {
            // With no pair entry, the fused word being in a dictionary at all is the only hard
            // evidence there is. Keep it unless it is rare enough that two common words are the
            // better reading. An unknown frequency is not evidence of rarity, so that fuses too.
            //
            // Deliberately does not consult the gap. Inside the comparison below a pause separates
            // two readings already known to be close. Here nothing is known to be close, and
            // "any pause splits any word" is far too much.
            val rare = fusedFrequency != UNKNOWN_FREQUENCY && fusedFrequency <= RARE_FUSION
            return if (rare) Verdict.SPLIT else Verdict.FUSE
        }

        val advantage = atLeastZero(pair) - atLeastZero(fusedFrequency)
        if (advantage >= CLEAR_WIN) return Verdict.SPLIT
        if (advantage <= -CLEAR_WIN) return Verdict.FUSE

        // Inside the tie band the words cannot separate themselves, so the way the user typed
        // decides. Lifting the finger and tapping a letter that the swipe could have covered is a
        // choice, and the pause that comes with it is the part this can measure.
        if (boundary.gapMs >= DELIBERATE_GAP_MS && abs(advantage) <= TIE_BAND) return Verdict.SPLIT

        // Fuse on a tie. A wrong fuse leaves the composing region wrong, and that region is replaced
        // for free. A wrong split has already committed the head word. Fusing is also what happens
        // today, so a tie cannot introduce a regression.
        return Verdict.FUSE
    }

    private fun atLeastZero(value: Int): Int = if (value < 0) 0 else value
}
