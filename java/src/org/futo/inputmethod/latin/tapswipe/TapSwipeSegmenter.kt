package org.futo.inputmethod.latin.tapswipe

/**
 * Where the words are in one TapSwipe stroke list, decided once, when the word is over.
 *
 * Short words (one to three letters) are often tapped. Longer words hold a swipe. So the tap runs at
 * the two ends of a stroke list are where a second or third word can hide: tap `I`, swipe `want`,
 * tap `a` is `I want a`, not `Intra`. And a tap run can also finish the swiped word: swipe `cat`, tap
 * `s` is `cats`. This compares every way of cutting the end tap runs into short words, and keeps the
 * reading that scores best.
 *
 * ### One score for every reading
 *
 * Each reading is a list of pieces. A swiped piece (the strokes from the first swipe to the last,
 * plus any taps that stayed attached) gets the decoder's own score for its best word, decoded with
 * the words before it as context. A tapped piece is exact text, so it has no shape to fit. It gets
 * the language part of the decoder's score instead: how likely the context model finds that word
 * next. Every extra word costs [EXTRA_WORD_COST], because a reading with more words always has more
 * chances to fit. On a near tie ([TIE]) the single-word reading wins: a wrong join is cheaper to fix
 * than a wrong split, and it is what the keyboard did before.
 *
 * Pure, so the rule is tested without a device. The decoder and the language model stay with the
 * caller through [Scorer]. See `docs/plans/AUTO_SPACING.md`, "Redesign: decide once, at the pause".
 */
object TapSwipeSegmenter {

    /** The longest tapped word considered. Longer words hold a swipe. */
    const val MAX_TAP_WORD = 3

    /** At most this many tapped words at each end of a stroke list (`this` + `is a`). */
    const val MAX_TAP_WORDS_PER_END = 2

    /**
     * What each word beyond the first costs. Needs calibration against `split_check.py` and the
     * user's recordings: too low splits real words (`cats`), too high never splits.
     */
    @JvmField var EXTRA_WORD_COST = 2.0f

    /**
     * What a one-letter word tapped in front of a swipe costs on top of [EXTRA_WORD_COST].
     *
     * Tapping the first letter and swiping the rest is also how the user types long words:
     * `another`, `actually`, `available`. The single-word reading pays for that tap in its shape
     * score, because the tap is a lone point far from where the swipe starts. The split reading
     * does not. So `a` + `bitter` beat `another` by 4.7 to 5.8 in the user's recordings, while
     * correct splits (`a lot`, `I think`, `I want a`) won by 9.3 or more. Set 2026-11-13.
     */
    @JvmField var LEAD_LETTER_COST = 5.0f

    /**
     * In a split, the swiped part must score above this. Below it the decoder found no good word
     * for the swipe on its own, and splitting only trades one bad word for another plus a stray
     * letter: `a` + `eponymy` (-67) for `appointment` (-110). Correct splits in the split test read
     * their swipe at 4.6 or more. Set 2026-11-13.
     */
    @JvmField var MIN_SPLIT_SWIPE_SCORE = 0f

    /**
     * A split must win by more than this, or the single word stays. Set from the split test on
     * 2026-11-13: correct splits won by 2.8 or more (most by over 9), and the wrong `may be` for
     * `maybe` won by 1.0.
     */
    @JvmField var TIE = 2.0f

    /** One stroke as the segmenter sees it: a swipe, or a tap on a known letter. */
    class StrokeInfo(@JvmField val isSwipe: Boolean, @JvmField val letter: Char)

    /** One word of a reading: strokes [from] until [to], and its text. */
    data class Piece(val from: Int, val to: Int, val text: String, val tapped: Boolean, val score: Float)

    data class Reading(val pieces: List<Piece>, val score: Float) {
        val words: List<String> get() = pieces.map { it.text }
        val isSplit: Boolean get() = pieces.size > 1
        override fun toString(): String =
            pieces.joinToString(" + ") { "${it.text}(${"%.1f".format(it.score)})" } +
                " = ${"%.1f".format(score)}"
    }

    data class Result(val best: Reading, val single: Reading?, val considered: List<Reading>)

    /** The decoder and language model, supplied by the caller so the rule stays pure. */
    interface Scorer {
        /**
         * The best word for strokes [from] until [to], decoded with [context] as the words before
         * it, and the decoder's score for it. Null when no word fits.
         */
        fun decode(from: Int, to: Int, context: List<String>): Pair<String, Float>?

        /**
         * The score of [word] typed exactly, after [context]. Null when [word] may not stand alone
         * as a tapped word (not a word, or a single letter that is not `a` or `I`).
         */
        fun tappedWord(word: String, context: List<String>): Float?
    }

    /**
     * The best reading of [strokes], or null when there is nothing to judge: no swipe, or no taps
     * at either end.
     *
     * @param context the words before this stroke list
     */
    @JvmStatic
    fun segment(strokes: List<StrokeInfo>, context: List<String>, scorer: Scorer): Result? {
        val n = strokes.size
        val first = strokes.indexOfFirst { it.isSwipe }
        if (first < 0) return null
        val last = strokes.indexOfLast { it.isSwipe }
        val leadTaps = first
        val trailTaps = n - 1 - last
        if (leadTaps == 0 && trailTaps == 0) return null

        val considered = ArrayList<Reading>()
        // How many leading taps leave the swiped word, and how many trailing ones.
        for (a in 0..minOf(leadTaps, MAX_TAP_WORD * MAX_TAP_WORDS_PER_END)) {
            for (b in 0..minOf(trailTaps, MAX_TAP_WORD * MAX_TAP_WORDS_PER_END)) {
                for (lead in partitions(strokes, 0, a)) {
                    for (trail in partitions(strokes, n - b, n)) {
                        reading(strokes, a, n - b, lead, trail, context, scorer)?.let { considered.add(it) }
                    }
                }
            }
        }
        if (considered.isEmpty()) return null
        val single = considered.firstOrNull { !it.isSplit }
        var best = considered.maxByOrNull { it.score }!!
        if (single != null && best.isSplit && best.score - single.score <= TIE) best = single
        return Result(best, single, considered)
    }

    /** Ways to cut taps [from] until [to] into one or two words of 1 to [MAX_TAP_WORD] letters. */
    private fun partitions(strokes: List<StrokeInfo>, from: Int, to: Int): List<List<String>> {
        val len = to - from
        if (len == 0) return listOf(emptyList())
        val text = buildString { for (i in from until to) append(strokes[i].letter) }
        val out = ArrayList<List<String>>()
        if (len <= MAX_TAP_WORD) out.add(listOf(text))
        if (MAX_TAP_WORDS_PER_END >= 2) {
            for (cut in 1 until len) {
                if (cut <= MAX_TAP_WORD && len - cut <= MAX_TAP_WORD) {
                    out.add(listOf(text.substring(0, cut), text.substring(cut)))
                }
            }
        }
        return out
    }

    private fun reading(
        strokes: List<StrokeInfo>, midFrom: Int, midTo: Int,
        lead: List<String>, trail: List<String>, context: List<String>, scorer: Scorer
    ): Reading? {
        val pieces = ArrayList<Piece>()
        val ctx = ArrayList(context)
        var at = 0
        for (w in lead) {
            val s = scorer.tappedWord(w, ctx) ?: return null
            pieces.add(Piece(at, at + w.length, w, true, s))
            at += w.length
            ctx.add(contextForm(w))
        }
        val (word, s) = scorer.decode(midFrom, midTo, ctx) ?: return null
        if (word.isEmpty()) return null
        if ((lead.isNotEmpty() || trail.isNotEmpty()) && s <= MIN_SPLIT_SWIPE_SCORE) return null
        pieces.add(Piece(midFrom, midTo, word, false, s))
        ctx.add(word)
        at = midTo
        for (w in trail) {
            val ts = scorer.tappedWord(w, ctx) ?: return null
            pieces.add(Piece(at, at + w.length, w, true, ts))
            at += w.length
            ctx.add(contextForm(w))
        }
        val leadLetter = if (lead.firstOrNull()?.length == 1) LEAD_LETTER_COST else 0f
        val total = pieces.sumOf { it.score.toDouble() }.toFloat() -
            EXTRA_WORD_COST * (pieces.size - 1) - leadLetter
        return Reading(pieces, total)
    }

    /**
     * A tapped word as the language model should see it. A lowercase `i` reads to the model as a
     * different word from the pronoun, and it pulled `think` after it down to `tink`.
     */
    @JvmStatic
    fun contextForm(word: String): String = if (word == "i") "I" else word

    /**
     * Short words that may stand alone when tapped. A single letter must be on this list, because
     * every dictionary holds every letter. Two and three letter words need the caller's dictionary
     * check as well; this list only keeps junk entries (`th`, `wh`) out.
     */
    @JvmField val ONE_LETTER_WORDS = setOf("a", "i")

    /**
     * Common English words of two and three letters. A tapped word on this list may stand alone even
     * when the context model does not rank it among the likely next words; it then gets a low score
     * instead of none. Without this, `we` after `in the` could never be a word of its own.
     */
    @JvmField val COMMON_SHORT_WORDS = setOf(
        "am", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if", "in", "is", "it", "me",
        "my", "no", "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we", "ya", "ye", "yo",
        "all", "and", "any", "are", "ask", "bad", "big", "but", "buy", "can", "car", "day", "did",
        "dog", "eat", "end", "far", "few", "for", "fun", "get", "got", "had", "has", "her", "him",
        "his", "hot", "how", "its", "job", "let", "lot", "man", "may", "men", "new", "nor", "not",
        "now", "off", "old", "one", "our", "out", "own", "pay", "put", "ran", "red", "run", "saw",
        "say", "see", "set", "she", "sit", "six", "son", "ten", "the", "too", "top", "try", "two",
        "use", "via", "war", "was", "way", "who", "why", "win", "yes", "yet", "you")
}
