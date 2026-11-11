package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.tapswipe.TapSwipeWordSplit.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario tests for the automatic word split, written the way the keyboard actually sees it.
 *
 * The other file, `TapSwipeWordSplitTest`, tests [TapSwipeWordSplit.decide] against a hand-built
 * boundary. This one starts from what the decoder returns, because that is where the surprises live:
 * a candidate list is not a tidy `head + tail`, and reading it wrongly is what produced `to sis` on
 * hardware.
 *
 * Each test names the finger motion, then the decoder output, then the answer wanted. Read the
 * `swipe`/`tap` helper calls as the sequence of events.
 */
class TapSwipeWordSplitScenariosTest {

    // ------------------------------------------------------------------ the dictionary

    /**
     * Relative frequencies on the 0 to 255 scale the real dictionaries use. Only the order matters.
     * `ava` is here as a saved personal name, which is what the reported case depends on.
     */
    private val words = mapOf(
        "a" to 250, "i" to 250, "is" to 245, "it" to 245, "in" to 240, "to" to 245,
        "be" to 200, "me" to 180, "we" to 195, "he" to 200, "no" to 200, "so" to 190,
        "us" to 170, "my" to 200, "of" to 245, "or" to 210, "if" to 205, "at" to 220,
        "as" to 225, "am" to 190, "an" to 230, "do" to 210, "go" to 195, "up" to 200,
        "this" to 240, "that" to 240, "these" to 200, "are" to 240, "for" to 245,
        "see" to 200, "seem" to 150, "seems" to 130, "cat" to 150, "cats" to 140,
        "car" to 175, "cars" to 150, "card" to 160, "cot" to 60, "cots" to 55,
        "work" to 170, "worked" to 150, "working" to 155, "think" to 190, "thing" to 190,
        "top" to 160, "stop" to 175, "some" to 210, "because" to 220, "today" to 200,
        "nothing" to 195, "about" to 225, "away" to 200, "way" to 195, "into" to 215,
        "myself" to 170, "maybe" to 205, "may" to 210, "sometime" to 120,
        "area" to 190, "usa" to 120, "fora" to 20, "ami" to 15, "acai" to 30,
        "mea" to 10, "ora" to 12, "nota" to 14, "not" to 240,
        "hello" to 180, "ava" to 90, "aca" to 5, "running" to 165, "run" to 190,
        "thai" to 40, "thin" to 150, "thus" to 140, "sis" to 25, "tis" to 10
    )

    /** N-gram entries. `are a` is deliberately close to `area`, the one pair numbers cannot split. */
    private val pairs = mapOf(
        "are" to mapOf("a" to 200),
        "for" to mapOf("a" to 210),
        "am" to mapOf("i" to 205),
        "us" to mapOf("a" to 190),
        "not" to mapOf("a" to 215),
        "this" to mapOf("is" to 210),
        "hello" to mapOf("i" to 120),
        "ava" to mapOf("i" to 60),
        "see" to mapOf("me" to 150),
        "be" to mapOf("cause" to 0),
        "may" to mapOf("be" to 150),
        "a" to mapOf("way" to 185, "bout" to 0),
        "in" to mapOf("to" to 120)
    )

    private val lexicon = object : TapSwipeWordSplit.Lexicon {
        override fun isWord(text: String) = words.containsKey(text.lowercase())

        override fun frequency(text: String) =
            words[text.lowercase()] ?: TapSwipeWordSplit.UNKNOWN_FREQUENCY

        override fun pairFrequency(first: String, second: String) =
            pairs[first.lowercase()]?.get(second.lowercase())
                ?: TapSwipeWordSplit.UNKNOWN_FREQUENCY
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A gesture that read as [reading], then taps spelling [taps].
     *
     * @param candidates what the decoder returns for the whole thing, best first
     */
    private fun swipeThenTap(
        reading: String,
        taps: String,
        vararg candidates: String,
        gapMs: Int = -1
    ) = TapSwipeWordSplit.decideFrom(
        reading, taps, false, candidates.toList(), gapMs, lexicon
    )

    /** Taps spelling [taps], then a gesture. [candidates] are read from every stroke together. */
    private fun tapThenSwipe(
        taps: String,
        vararg candidates: String,
        gapMs: Int = -1
    ) = TapSwipeWordSplit.decideFrom("", taps, true, candidates.toList(), gapMs, lexicon)

    private fun boundary(reading: String, taps: String, vararg candidates: String) =
        TapSwipeWordSplit.boundaryFor(reading, taps, false, candidates.toList(), -1)

    // ============================================================ reported from hardware

    /**
     * Swipe across a, c, a for a saved name. The decoder shows `ava`. Tap `i`.
     *
     * The decoder's best reading of the whole path is `acai`, a real word whose shape is exactly
     * a, c, a, i. It only exists if `ava` is demoted to `aca`, so the tap rewrote strokes it did not
     * make. That is what makes this decidable rather than a coin toss.
     */
    @Test
    fun `ava then a tapped i does not become acai`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("ava", "i", "acai", "avai"))
    }

    /**
     * The second report. Swipe `this`, then tap `i` and `s`.
     *
     * `this` ends with the letters `is`, so a plain suffix test counted `this` itself as a one-word
     * reading of `this` plus `is`. That left `th` standing as the reading of a four-letter gesture,
     * and the boundary was then judged against `th`. Nothing in the rule rejected it.
     */
    @Test
    fun `this then a tapped is stays two words`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("this", "is", "this", "thin", "thus"))
    }

    /**
     * The same motion, one tap at a time, which is how it really arrives.
     *
     * The first tap already splits, because no reading of the gesture plus `i` is a word and `i` is
     * one on its own. So `this` commits there and the tail carries on as an ordinary tapped word,
     * reaching `is` on the next tap. The second call here is the same question asked again, and it
     * has to agree, or a word would split twice.
     */
    @Test
    fun `this then is splits at the first tap`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("this", "i", "this", "thin"))
        assertEquals(Verdict.SPLIT, swipeThenTap("this", "is", "this", "thin"))
    }

    /**
     * The contrast that shows why the first tap is allowed to decide. `cat` plus `s` has a real
     * one-word reading, so it waits instead, and the tap that would prove a boundary never comes.
     */
    @Test
    fun `a suffix does not split at the first tap`() {
        assertEquals(Verdict.WAIT, swipeThenTap("work", "e", "worke"))
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "s", "cats"))
    }

    /** The plainest case, and the one the feature exists for. */
    @Test
    fun `a swiped word then a tapped I splits`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "i", "hello"))
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "a", "hello"))
    }

    // ============================================================ the rejection rule

    /**
     * The guard that fixes `this is`. A leading part far shorter than the gesture's own reading is
     * not another reading of that gesture, whatever letters it ends in.
     */
    @Test
    fun `a candidate whose leading part is too short is not a fusion`() {
        val b = boundary("this", "is", "this")
        assertEquals(0, b!!.fusions.size)
    }

    /** Same length is the normal shape of a competing reading, and it is kept. */
    @Test
    fun `a candidate whose leading part is the same length is a fusion`() {
        val b = boundary("ava", "i", "acai")
        assertEquals(1, b!!.fusions.size)
        assertEquals("acai", b.fusions[0].text)
        assertFalse("aca is not ava, so the head was demoted", b.fusions[0].fromBestReading)
    }

    /** One letter of drift is allowed, because a path can read as words of near-equal length. */
    @Test
    fun `a leading part one letter off is still a fusion`() {
        val b = boundary("car", "s", "cats")
        assertEquals(1, b!!.fusions.size)
        assertFalse(b.fusions[0].fromBestReading)
    }

    /** A different starting key means a different gesture, so it is not a competing reading. */
    @Test
    fun `a leading part starting on another key is not a fusion`() {
        val b = boundary("ava", "s", "cats")
        assertEquals(0, b!!.fusions.size)
    }

    /** An exact match is marked, because the whole layer 2 test turns on it. */
    @Test
    fun `an unchanged leading part is marked as the best reading`() {
        val b = boundary("cat", "s", "cats")
        assertEquals(1, b!!.fusions.size)
        assertTrue(b.fusions[0].fromBestReading)
    }

    // ============================================================ suffixes must fuse

    /**
     * The case a rule of "any tap starts a new word" would break. `t` and `s` are far apart, so
     * tapping the `s` is a reasonable choice, and it is still one word.
     */
    @Test
    fun `a suffix tap fuses`() {
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "s", "cats"))
        assertEquals(Verdict.FUSE, swipeThenTap("car", "s", "cars"))
        assertEquals(Verdict.FUSE, swipeThenTap("work", "ed", "worked"))
        assertEquals(Verdict.FUSE, swipeThenTap("run", "ning", "running"))
    }

    /** Halfway through a suffix nothing holds, and waiting is what keeps the boundary movable. */
    @Test
    fun `an incomplete suffix waits`() {
        assertEquals(Verdict.WAIT, swipeThenTap("work", "e", "worke"))
        assertEquals(Verdict.WAIT, swipeThenTap("run", "n", "runn"))
        assertEquals(Verdict.WAIT, swipeThenTap("hello", "x", "hellox"))
    }

    /**
     * The boundary moves backward. `see` plus `m` is `seem`, so it fuses. The following `e` makes
     * `seeme`, which is nothing, and `me` is a word. Nothing committed at the first step, so the
     * second is free to disagree with it.
     */
    @Test
    fun `the boundary moves backward when the next tap disproves it`() {
        assertEquals(Verdict.FUSE, swipeThenTap("see", "m", "seem"))
        assertEquals(Verdict.SPLIT, swipeThenTap("see", "me", "seeme", "seems"))
    }

    /** A demoted reading still fuses when the tapped side is not a word. Nothing to prefer. */
    @Test
    fun `a demoted reading fuses when the tap is not a word`() {
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "d", "catd", "card"))
    }

    /**
     * A reading that keeps the gesture's own answer beats a higher-ranked one that does not, because
     * layer 2 asks whether fusing *had* to demote it.
     */
    @Test
    fun `keeping the best reading beats a higher-ranked demotion`() {
        assertEquals(Verdict.FUSE, swipeThenTap("cot", "s", "cats", "cots"))
    }

    // ============================================================ taps before a gesture

    /** The order you hit more often. Tap `I`, then swipe. */
    @Test
    fun `a tapped word before a gesture splits`() {
        assertEquals(Verdict.SPLIT, tapThenSwipe("i", "think"))
        assertEquals(Verdict.SPLIT, tapThenSwipe("i", "thing", "think"))
        assertEquals(Verdict.SPLIT, tapThenSwipe("we", "work"))
        assertEquals(Verdict.SPLIT, tapThenSwipe("this", "work"))
    }

    /** A leading letter that is no word belongs to the gesture. Tap `s`, swipe `top`. */
    @Test
    fun `a leading letter that is not a word fuses`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("s", "stop"))
        assertEquals(Verdict.FUSE, tapThenSwipe("c", "cats"))
    }

    /**
     * English is full of short words that begin longer ones, so this order reaches layer 2 often.
     * Frequency settles nearly all of it, because `be cause` is close to nonsense.
     */
    @Test
    fun `a short word that begins a longer word fuses`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("be", "because"))
        assertEquals(Verdict.FUSE, tapThenSwipe("to", "today"))
        assertEquals(Verdict.FUSE, tapThenSwipe("no", "nothing"))
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "some"))
        assertEquals(Verdict.FUSE, tapThenSwipe("a", "about"))
        assertEquals(Verdict.FUSE, tapThenSwipe("my", "myself"))
        assertEquals(Verdict.FUSE, tapThenSwipe("in", "into"))
    }

    /** A candidate that dropped the tapped letters read them as nothing, so it is no fusion. */
    @Test
    fun `a candidate not starting with the taps is not a fusion`() {
        val b = TapSwipeWordSplit.boundaryFor("", "i", true, listOf("think", "thing"), -1)
        assertEquals(0, b!!.fusions.size)
        assertEquals("i", b.head)
    }

    /** With no fusion at all the tail is only an estimate, and a split re-decodes it properly. */
    @Test
    fun `the tail is estimated when nothing kept the taps`() {
        val b = TapSwipeWordSplit.boundaryFor("", "i", true, listOf("think"), -1)
        assertEquals("think", b!!.tail)
    }

    // ============================================================ both readings are words

    /** The fused word is rare and the pair is common, so the numbers settle it. */
    @Test
    fun `a rare fused word loses to a common pair`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("for", "a", "fora"))
        assertEquals(Verdict.SPLIT, swipeThenTap("am", "i", "ami"))
        assertEquals(Verdict.SPLIT, swipeThenTap("us", "a", "usa"))
        assertEquals(Verdict.SPLIT, swipeThenTap("not", "a", "nota"))
    }

    /** `area` against `are a`. Both common, so this is the residual case, and it fuses. */
    @Test
    fun `a genuine tie fuses`() {
        assertEquals(Verdict.FUSE, swipeThenTap("are", "a", "area"))
        assertEquals(Verdict.FUSE, tapThenSwipe("a", "away"))
        assertEquals(Verdict.FUSE, tapThenSwipe("may", "maybe"))
    }

    /** A rare fused word with no pair entry still loses. `mea` is a word, `me a` is the sentence. */
    @Test
    fun `a rare fused word loses with no pair entry`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("me", "a", "mea"))
        assertEquals(Verdict.SPLIT, swipeThenTap("or", "a", "ora"))
    }

    /** A common fused word with no pair entry survives. This is the state the feature ships in. */
    @Test
    fun `a common fused word survives with no pair entry`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "some"))
        assertEquals(Verdict.FUSE, tapThenSwipe("to", "today"))
    }

    // ============================================================ how the user typed

    /** A pause is a word boundary, and inside a tie it is the only evidence left. */
    @Test
    fun `a pause breaks a tie toward splitting`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("are", "a", "area", gapMs = 600))
    }

    @Test
    fun `a short pause changes nothing`() {
        assertEquals(Verdict.FUSE, swipeThenTap("are", "a", "area", gapMs = 120))
    }

    /** Only ties consult the pause. A decided case must not be overturned by one. */
    @Test
    fun `a pause does not overturn a decided case`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("be", "because", gapMs = 5000))
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "s", "cats", gapMs = 5000))
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "some", gapMs = 5000))
    }

    // ============================================================ letter case

    /** A shifted tap arrives upper case while the decoder answers lower case. Both must work. */
    @Test
    fun `a shifted tap is still recognised as a word`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "I", "hello"))
        assertEquals(Verdict.SPLIT, tapThenSwipe("I", "think"))
    }

    /** Case must not hide a fusion either, or a capitalised word would split wrongly. */
    @Test
    fun `case does not hide a fusion`() {
        assertEquals(Verdict.FUSE, swipeThenTap("Cat", "s", "Cats"))
        assertEquals(Verdict.FUSE, tapThenSwipe("S", "stop"))
    }

    // ============================================================ degenerate input

    @Test
    fun `no taps means no boundary`() {
        assertEquals(null, TapSwipeWordSplit.boundaryFor("hello", "", false, listOf("hello"), -1))
    }

    @Test
    fun `no gesture reading means no boundary`() {
        assertEquals(null, TapSwipeWordSplit.boundaryFor("", "i", false, listOf("helloi"), -1))
    }

    @Test
    fun `no candidates at all still decides on the tapped side`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "i"))
        assertEquals(Verdict.WAIT, swipeThenTap("hello", "x"))
    }

    /** A candidate no longer than the taps cannot contain both parts. */
    @Test
    fun `a candidate shorter than the taps is ignored`() {
        val b = boundary("hello", "is", "is", "s")
        assertEquals(0, b!!.fusions.size)
    }

    /** Taps leading with no usable candidate at all has no tail to work with. */
    @Test
    fun `taps leading with no candidates has no boundary`() {
        assertEquals(null, TapSwipeWordSplit.boundaryFor("", "i", true, emptyList(), -1))
    }

    // ============================================================ realistic candidate lists

    /**
     * Four candidates is what the end-of-stroke decode actually returns. The rule has to pick from
     * a list, not from one word, and the order it prefers is what these check.
     */
    @Test
    fun `a full candidate list still splits the reported case`() {
        assertEquals(
            Verdict.SPLIT,
            swipeThenTap("ava", "i", "acai", "avai", "aval", "acid")
        )
    }

    @Test
    fun `a full candidate list still fuses a suffix`() {
        assertEquals(
            Verdict.FUSE,
            swipeThenTap("cat", "s", "cats", "cots", "cars", "carts")
        )
    }

    @Test
    fun `a full candidate list still splits this is`() {
        assertEquals(
            Verdict.SPLIT,
            swipeThenTap("this", "is", "this", "thus", "thin", "thai")
        )
    }

    /** An unrelated candidate list must not invent a fusion out of nothing. */
    @Test
    fun `unrelated candidates produce no fusion`() {
        val b = boundary("hello", "i", "goodbye", "farewell", "hi")
        assertEquals(0, b!!.fusions.size)
    }

    // ============================================================ choosing between boundaries

    /**
     * Several boundaries can split one word, and picking the wrong one is what produced `to sis`.
     *
     * `this` was a gesture over t, h, i plus a tapped `s`, so the taps after the gesture are `s`,
     * `i`, `s`. The first finishes the gestured word. The last two are the next word.
     *
     * @param readings what each decode read, by how many strokes it covered
     */
    private fun choose(
        readings: Map<Int, String>,
        taps: String,
        candidates: List<String>,
        leadingSwipes: Int = 1,
        gapMs: Int = -1
    ): Int? {
        val strokeCount = leadingSwipes + taps.length
        val options = ArrayList<TapSwipeWordSplit.Option>()
        for (at in leadingSwipes until strokeCount) {
            val head = readings[at] ?: continue
            val tail = taps.substring(at - leadingSwipes)
            val b = TapSwipeWordSplit.boundaryFor(head, tail, false, candidates, gapMs)
            if (b != null) options.add(TapSwipeWordSplit.Option(at, b))
        }
        return TapSwipeWordSplit.chooseSplit(options, lexicon)?.at
    }

    /**
     * The reported case, end to end. Two boundaries split it. The gesture alone read as `to`, so
     * boundary 1 gives `to` and `sis`. The gesture plus the first tap read as `this`, so boundary 2
     * gives `this` and `is`. The later one is right.
     */
    @Test
    fun `the later boundary wins so this is beats to sis`() {
        val readings = mapOf(1 to "to", 2 to "this", 3 to "thin")
        assertEquals(2, choose(readings, "sis", listOf("this", "thin", "thus")))
    }

    /** Each boundary on its own. Both split, which is why choosing between them is needed. */
    @Test
    fun `both boundaries of the reported case split on their own`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("to", "sis", "this", "thin"))
        assertEquals(Verdict.SPLIT, swipeThenTap("this", "is", "this", "thin"))
    }

    /** A gesture plus a completing tap plus a tapped word. `cats i`, not `cat si`. */
    @Test
    fun `a completing tap stays with the gestured word`() {
        val readings = mapOf(1 to "cat", 2 to "cats")
        assertEquals(2, choose(readings, "si", listOf("cats", "cots")))
    }

    /** Nothing splits, so nothing is chosen. `worked` is one word however it was typed. */
    @Test
    fun `a word with no boundary that splits chooses nothing`() {
        val readings = mapOf(1 to "work", 2 to "worke")
        assertEquals(null, choose(readings, "ed", listOf("worked", "working")))
    }

    /** A single boundary still works, which is the ordinary case. */
    @Test
    fun `one boundary is chosen when it splits`() {
        assertEquals(1, choose(mapOf(1 to "hello"), "i", listOf("hello")))
    }

    /** A missing reading skips that boundary rather than guessing at it. */
    @Test
    fun `a boundary with no recorded reading is skipped`() {
        // Only the reading for one stroke is known, so only that boundary can be judged.
        assertEquals(1, choose(mapOf(1 to "to"), "sis", listOf("this")))
        // With neither known, nothing can be judged.
        assertEquals(null, choose(emptyMap(), "sis", listOf("this")))
    }

    /** An empty option list is not an error. It is the answer for most words. */
    @Test
    fun `no options chooses nothing`() {
        assertEquals(null, TapSwipeWordSplit.chooseSplit(emptyList(), lexicon))
    }

    /** `hello` typed as a gesture plus two taps stays one word at every boundary. */
    @Test
    fun `a gestured word finished by taps stays one word`() {
        val readings = mapOf(1 to "hel", 2 to "hell")
        assertEquals(null, choose(readings, "lo", listOf("hello", "hell")))
    }
}
