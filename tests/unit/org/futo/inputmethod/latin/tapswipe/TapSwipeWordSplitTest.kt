package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.tapswipe.TapSwipeWordSplit.Boundary
import org.futo.inputmethod.latin.tapswipe.TapSwipeWordSplit.Fusion
import org.futo.inputmethod.latin.tapswipe.TapSwipeWordSplit.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for where a word boundary belongs when taps sit beside a swipe.
 *
 * The bias under test is toward [Verdict.FUSE]. Fusing is what the keyboard does today, so a wrong
 * fuse costs nothing new and gets replaced when the composing region is rewritten. A wrong split has
 * already committed text. So a case that cannot be decided must come back FUSE, and the tests say so
 * explicitly rather than leaving it implied.
 */
class TapSwipeWordSplitTest {

    /**
     * A small English dictionary. Frequencies are relative, on the 0 to 255 scale the real
     * dictionaries use, and only their order matters to these tests.
     */
    private val words = mapOf(
        "a" to 250, "i" to 250, "be" to 200, "me" to 180, "to" to 245, "no" to 200,
        "so" to 190, "us" to 170, "are" to 240, "for" to 245, "am" to 190, "see" to 200,
        "cat" to 150, "cats" to 140, "work" to 170, "worked" to 150, "seem" to 150,
        "think" to 190, "stop" to 175, "top" to 160, "some" to 210, "because" to 220,
        "today" to 200, "nothing" to 195, "about" to 225, "away" to 200, "way" to 195,
        "area" to 190, "usa" to 120, "fora" to 20, "ami" to 15, "acai" to 30,
        "hello" to 180, "ava" to 90, "aca" to 5, "card" to 160, "car" to 175,
        "mea" to 10, "cots" to 60
    )

    /**
     * Pairs the dictionaries would hold as n-gram entries. `are a` is deliberately close to `area`,
     * because that pair is the one case the numbers cannot separate.
     */
    private val pairs = mapOf(
        "are" to mapOf("a" to 200),
        "for" to mapOf("a" to 210),
        "am" to mapOf("i" to 205),
        "us" to mapOf("a" to 190),
        "hello" to mapOf("i" to 120),
        "ava" to mapOf("i" to 60),
        "see" to mapOf("me" to 150),
        "be" to mapOf("cause" to 0),
        "a" to mapOf("way" to 185, "bout" to 0)
    )

    private val lexicon = object : TapSwipeWordSplit.Lexicon {
        override fun isWord(text: String) = words.containsKey(text)

        override fun frequency(text: String) =
            words[text] ?: TapSwipeWordSplit.UNKNOWN_FREQUENCY

        override fun pairFrequency(first: String, second: String) =
            pairs[first]?.get(second) ?: TapSwipeWordSplit.UNKNOWN_FREQUENCY
    }

    // ------------------------------------------------------------------ helpers

    /** A swipe, then taps. The fusion is the two joined, using the swipe's best reading. */
    private fun swipeThenTap(head: String, tail: String, gapMs: Int = -1) = TapSwipeWordSplit.decide(
        Boundary(head, tail, false, listOf(Fusion(head + tail, true)), gapMs), lexicon
    )

    /** Taps, then a swipe. */
    private fun tapThenSwipe(head: String, tail: String, gapMs: Int = -1) = TapSwipeWordSplit.decide(
        Boundary(head, tail, true, listOf(Fusion(head + tail, true)), gapMs), lexicon
    )

    // ------------------------------------------------------- the motivating case

    /**
     * Swipe across a, c, a and the decoder shows `ava`. Tap `i`. Today this gives `acai`, because
     * the tap joins the path and a, c, a, i is exactly that word's shape.
     *
     * `acai` only exists as a reading if `ava` is demoted to `aca`, so the tap rewrote strokes it
     * did not make. That is what makes this decidable.
     */
    @Test
    fun `a tap that re-reads the swipe splits`() {
        val verdict = TapSwipeWordSplit.decide(
            Boundary(
                "ava", "i", false,
                listOf(Fusion("avai", true), Fusion("acai", false))
            ),
            lexicon
        )
        assertEquals(Verdict.SPLIT, verdict)
    }

    /** With no competing reading at all, layer 1 alone settles it. */
    @Test
    fun `a tapped word after a swipe splits when nothing fuses`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "i"))
        assertEquals(Verdict.SPLIT, swipeThenTap("hello", "a"))
    }

    // ------------------------------------------------------------ suffixes fuse

    /**
     * The case a naive "any tap means a new word" rule breaks. `t` and `s` are far apart, so tapping
     * the `s` is a reasonable choice, and it is still one word.
     */
    @Test
    fun `a suffix tap fuses`() {
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "s"))
        assertEquals(Verdict.FUSE, swipeThenTap("work", "ed"))
    }

    /**
     * Halfway through a suffix, neither reading holds. Waiting is what lets the boundary move
     * backward on the next stroke instead of committing to a guess now.
     */
    @Test
    fun `an incomplete suffix waits`() {
        assertEquals(Verdict.WAIT, swipeThenTap("work", "e"))
        assertEquals(Verdict.WAIT, swipeThenTap("hello", "x"))
    }

    /**
     * The boundary moves backward. `see` plus `m` is `seem`, a real word, so it fuses. The following
     * `e` makes `seeme`, which is not a word, and `me` is. Nothing committed at the first step, so
     * the second is free to disagree with it.
     */
    @Test
    fun `the boundary can move backward once more evidence arrives`() {
        assertEquals(Verdict.FUSE, swipeThenTap("see", "m"))
        assertEquals(Verdict.SPLIT, swipeThenTap("see", "me"))
    }

    // -------------------------------------------------------- taps before a swipe

    /** The reverse order, which is the more common one. Tap `i`, swipe `think`. */
    @Test
    fun `a tapped word before a swipe splits`() {
        assertEquals(Verdict.SPLIT, tapThenSwipe("i", "think"))
        assertEquals(Verdict.SPLIT, tapThenSwipe("me", "hello"))
    }

    /** Tap `s`, swipe `top`. The tapped letter is not a word, so it belongs to the swipe. */
    @Test
    fun `a leading letter that is not a word fuses`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("s", "top"))
    }

    /**
     * English is full of short words that begin longer words, so this order reaches layer 2 often.
     * Frequency settles nearly all of it, because `be cause` is close to nonsense.
     */
    @Test
    fun `a short word that begins a longer word fuses`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("be", "cause"))
        assertEquals(Verdict.FUSE, tapThenSwipe("to", "day"))
        assertEquals(Verdict.FUSE, tapThenSwipe("no", "thing"))
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "me"))
        assertEquals(Verdict.FUSE, tapThenSwipe("a", "bout"))
    }

    // ------------------------------------------------- both readings are real words

    /**
     * The fused word is rare and the pair is common, so the split wins on the numbers alone.
     */
    @Test
    fun `a rare fused word loses to a common pair`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("for", "a"))
        assertEquals(Verdict.SPLIT, swipeThenTap("am", "i"))
        assertEquals(Verdict.SPLIT, swipeThenTap("us", "a"))
    }

    /**
     * `area` against `are a`. Both are common, so the numbers are close and the tie rule applies.
     * Without a measured gap this has to fuse.
     */
    @Test
    fun `a genuine tie fuses`() {
        assertEquals(Verdict.FUSE, swipeThenTap("are", "a"))
        assertEquals(Verdict.FUSE, tapThenSwipe("a", "way"))
    }

    /** The same tie, with a pause between the two parts. The pause is the deciding evidence. */
    @Test
    fun `a pause breaks a tie toward splitting`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("are", "a", gapMs = 600))
    }

    /** A short gap is how one continuous action looks, so it must not tip anything. */
    @Test
    fun `a short pause changes nothing`() {
        assertEquals(Verdict.FUSE, swipeThenTap("are", "a", gapMs = 120))
    }

    /** A pause must not overturn a decided case. Only ties consult it. */
    @Test
    fun `a pause does not overturn a clear fusion`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("be", "cause", gapMs = 4000))
        assertEquals(Verdict.FUSE, swipeThenTap("cat", "s", gapMs = 4000))
    }

    // ------------------------------------------------------------------ fallbacks

    /**
     * No pair entry, which is the state until the personal dictionary has learned any. A rare fused
     * word then loses. `mea` is a word, and `me a` is what someone typing English meant.
     */
    @Test
    fun `a rare fused word loses when no pair is known`() {
        assertEquals(Verdict.SPLIT, swipeThenTap("me", "a"))
    }

    /**
     * The same missing pair entry, with a common fused word. Nothing here says the split is better,
     * so the fused word stands. This is the state the feature ships in, so it has to be the safe one.
     */
    @Test
    fun `a common fused word survives when no pair is known`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "me"))
        assertEquals(Verdict.FUSE, tapThenSwipe("to", "day"))
    }

    /** With no pair entry there is no tie to break, so a pause must not act. */
    @Test
    fun `a pause does not split a common word with no pair entry`() {
        assertEquals(Verdict.FUSE, tapThenSwipe("so", "me", gapMs = 4000))
    }

    /**
     * A lower-ranked reading of the swipe completes a real word, and the tapped letter is not a word
     * on its own. The demotion is allowed here, because there is no competing split to prefer.
     */
    @Test
    fun `a demoted reading still fuses when the tap is not a word`() {
        val verdict = TapSwipeWordSplit.decide(
            Boundary("cat", "d", false, listOf(Fusion("catd", true), Fusion("card", false))),
            lexicon
        )
        assertEquals(Verdict.FUSE, verdict)
    }

    /**
     * A reading that keeps the swipe's best candidate wins over a higher-ranked one that does not,
     * because layer 2 asks whether fusing *had* to demote the head. Without this, `cats` would split
     * whenever an unrelated word outranked it.
     */
    @Test
    fun `a fusion keeping the best reading beats a higher-ranked one that does not`() {
        val verdict = TapSwipeWordSplit.decide(
            Boundary(
                "cat", "s", false,
                listOf(Fusion("cots", false), Fusion("cats", true))
            ),
            lexicon
        )
        assertEquals(Verdict.FUSE, verdict)
    }

    // ------------------------------------------------------------------ guards

    @Test
    fun `an empty side never splits`() {
        assertEquals(Verdict.FUSE, swipeThenTap("hello", ""))
        assertEquals(Verdict.FUSE, swipeThenTap("", "i"))
    }

    @Test
    fun `no fusion candidates at all still decides on the tapped side`() {
        assertEquals(
            Verdict.SPLIT,
            TapSwipeWordSplit.decide(Boundary("hello", "i", false, emptyList()), lexicon)
        )
        assertEquals(
            Verdict.WAIT,
            TapSwipeWordSplit.decide(Boundary("hello", "x", false, emptyList()), lexicon)
        )
    }
}
