package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.tapswipe.TapSwipeSegmenter.StrokeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rules of [TapSwipeSegmenter], with a fake decoder. Scores are made up in the decoder's range;
 * what the tests check is which reading wins and why, not calibration.
 */
class TapSwipeSegmenterTest {

    private var savedCost = 0f
    private var savedTie = 0f
    private var savedLead = 0f
    private var savedMin = 0f

    @Before fun pin() {
        savedCost = TapSwipeSegmenter.EXTRA_WORD_COST
        savedTie = TapSwipeSegmenter.TIE
        savedLead = TapSwipeSegmenter.LEAD_LETTER_COST
        savedMin = TapSwipeSegmenter.MIN_SPLIT_SWIPE_SCORE
        TapSwipeSegmenter.MIN_SPLIT_SWIPE_SCORE = -1000f
        TapSwipeSegmenter.EXTRA_WORD_COST = 2.0f
        TapSwipeSegmenter.TIE = 0.5f
        TapSwipeSegmenter.LEAD_LETTER_COST = 0f
    }

    @After fun restore() {
        TapSwipeSegmenter.EXTRA_WORD_COST = savedCost
        TapSwipeSegmenter.TIE = savedTie
        TapSwipeSegmenter.LEAD_LETTER_COST = savedLead
        TapSwipeSegmenter.MIN_SPLIT_SWIPE_SCORE = savedMin
    }

    private fun swipe() = StrokeInfo(true, '?')
    private fun tap(c: Char) = StrokeInfo(false, c)

    /** Decodes keyed by "from-to", tapped-word scores keyed by word. Records every context seen. */
    private class Fake(
        val decodes: Map<String, Pair<String, Float>>,
        val tapped: Map<String, Float>,
        val contextBonus: Map<Pair<String, String>, Float> = emptyMap()
    ) : TapSwipeSegmenter.Scorer {
        val contexts = ArrayList<Pair<String, List<String>>>()
        override fun decode(from: Int, to: Int, context: List<String>): Pair<String, Float>? {
            contexts.add("$from-$to" to context.toList())
            val d = decodes["$from-$to"] ?: return null
            val bonus = contextBonus[(context.lastOrNull() ?: "") to d.first] ?: 0f
            return d.first to d.second + bonus
        }
        override fun tappedWord(word: String, context: List<String>): Float? {
            val base = tapped[word.lowercase()] ?: return null
            return base + (contextBonus[(context.lastOrNull() ?: "") to word.lowercase()] ?: 0f)
        }
    }

    private fun words(strokes: List<StrokeInfo>, fake: Fake, context: List<String> = emptyList()) =
        TapSwipeSegmenter.segment(strokes, context, fake)!!.best.words

    @Test fun nothingToJudgeWithoutEndTaps() {
        assertNull(TapSwipeSegmenter.segment(listOf(swipe()), emptyList(), Fake(emptyMap(), emptyMap())))
        assertNull(TapSwipeSegmenter.segment(listOf(swipe(), tap('x'), swipe()), emptyList(),
            Fake(emptyMap(), emptyMap())))
        assertNull(TapSwipeSegmenter.segment(listOf(tap('a'), tap('b')), emptyList(),
            Fake(emptyMap(), emptyMap())))
    }

    @Test fun iWantA_splitsAtBothEnds() {
        // The user's case. The whole stroke list reads as the rare word "intra".
        val fake = Fake(
            decodes = mapOf("0-3" to ("intra" to 5f), "1-2" to ("want" to 6f), "1-3" to ("wants" to 3f),
                "0-2" to ("iwan" to 1f)),
            tapped = mapOf("i" to 2f, "a" to 2f))
        assertEquals(listOf("i", "want", "a"), words(listOf(tap('i'), swipe(), tap('a')), fake))
    }

    @Test fun theSwipedPartIsDecodedWithTheTappedWordsBeforeIt() {
        val fake = Fake(mapOf("0-3" to ("intra" to 5f), "1-2" to ("want" to 6f)), mapOf("i" to 2f, "a" to 2f))
        TapSwipeSegmenter.segment(listOf(tap('i'), swipe(), tap('a')), listOf("so"), fake)
        // The pronoun goes in as "I": a lowercase "i" pulled "think" after it down to "tink".
        assertTrue(fake.contexts.contains("1-2" to listOf("so", "I")))
        assertTrue(fake.contexts.contains("0-3" to listOf("so")))
    }

    @Test fun aSuffixThatIsNotAWordStaysJoined() {
        // "s" is not a standalone word, so "cat s" is never a reading.
        val fake = Fake(mapOf("0-2" to ("cats" to 7f), "0-1" to ("cat" to 9f)), mapOf("a" to 2f, "i" to 2f))
        assertEquals(listOf("cats"), words(listOf(swipe(), tap('s')), fake))
    }

    @Test fun aNearTieKeepsOneWord() {
        // area against are + a: 5.5 + 2 - 2 = 5.5, within TIE of 6.0.
        val fake = Fake(mapOf("0-2" to ("area" to 6f), "0-1" to ("are" to 5.5f)), mapOf("a" to 2f))
        val r = TapSwipeSegmenter.segment(listOf(swipe(), tap('a')), emptyList(), fake)!!
        assertEquals(listOf("area"), r.best.words)
        assertEquals(2, r.considered.size)
    }

    @Test fun aClearWinSplits() {
        // hello + i: the whole list only reads as a weak word.
        val fake = Fake(mapOf("0-2" to ("helio" to 2f), "0-1" to ("hello" to 8f)), mapOf("i" to 2f))
        assertEquals(listOf("hello", "i"), words(listOf(swipe(), tap('i')), fake))
    }

    @Test fun aTapRunCanHoldTwoWords() {
        // this + is + a. "isa" itself is not a tapped word, and "sa" is not either.
        val fake = Fake(
            decodes = mapOf("0-4" to ("thisa" to 1f), "0-1" to ("this" to 8f), "0-2" to ("thi" to 1f),
                "0-3" to ("this" to 3f)),
            tapped = mapOf("is" to 2.5f, "a" to 2f, "i" to 1f))
        assertEquals(listOf("this", "is", "a"),
            words(listOf(swipe(), tap('i'), tap('s'), tap('a')), fake))
    }

    @Test fun theFirstTapsCanFinishTheSwipedWord() {
        // A gesture over t,h,i then taps s,i,s: "this is", not "to sis".
        val fake = Fake(
            decodes = mapOf("0-4" to ("thisis" to 0f), "0-1" to ("to" to 4f), "0-2" to ("this" to 8f),
                "0-3" to ("this" to 1f)),
            tapped = mapOf("is" to 2.5f, "sis" to 0.5f, "i" to 1f))
        assertEquals(listOf("this", "is"), words(listOf(swipe(), tap('s'), tap('i'), tap('s')), fake))
    }

    @Test fun contextDecides() {
        // "we have" against "weave": the same strokes, and only context separates them.
        val strokes = listOf(tap('w'), tap('e'), swipe())
        val decodes = mapOf("0-3" to ("weave" to 6f), "2-3" to ("have" to 5f), "1-3" to ("eave" to 0f))
        val plain = Fake(decodes, mapOf("we" to 2f))
        assertEquals(listOf("weave"), words(strokes, plain))
        val withPair = Fake(decodes, mapOf("we" to 2f), contextBonus = mapOf(("we" to "have") to 3f))
        assertEquals(listOf("we", "have"), words(strokes, withPair))
    }

    @Test fun aHigherWordCostKeepsWordsJoined() {
        val fake = Fake(mapOf("0-3" to ("intra" to 5f), "1-2" to ("want" to 6f)), mapOf("i" to 2f, "a" to 2f))
        TapSwipeSegmenter.EXTRA_WORD_COST = 4f
        assertEquals(listOf("intra"), words(listOf(tap('i'), swipe(), tap('a')), fake))
    }

    @Test fun anUnreadableWholeStillSplits() {
        // The decoder finds no word for all strokes together: strong evidence of two words.
        val fake = Fake(mapOf("1-2" to ("think" to 7f)), mapOf("i" to 2f))
        val r = TapSwipeSegmenter.segment(listOf(tap('i'), swipe()), emptyList(), fake)!!
        assertEquals(listOf("i", "think"), r.best.words)
        assertNull(r.single)
    }

    @Test fun aTappedFirstLetterOfALongWordStaysJoined() {
        // The user's "another": tap a, swipe the rest. The swipe alone reads as "bitter". Numbers
        // from the replay of the user's long-words recording.
        TapSwipeSegmenter.LEAD_LETTER_COST = 5f
        TapSwipeSegmenter.TIE = 2f
        val fake = Fake(mapOf("0-2" to ("another" to 5.5f), "1-2" to ("bitter" to 9.2f)), mapOf("a" to 3.1f))
        assertEquals(listOf("another"), words(listOf(tap('a'), swipe()), fake))
    }

    @Test fun aClearOneLetterWordStillSplits() {
        // "a lot", with the same costs: the swipe alone reads strongly, the whole reads weakly.
        TapSwipeSegmenter.LEAD_LETTER_COST = 5f
        TapSwipeSegmenter.TIE = 2f
        val fake = Fake(mapOf("0-2" to ("aloft" to 6.0f), "1-2" to ("lot" to 14.3f)), mapOf("a" to 3.1f))
        assertEquals(listOf("a", "lot"), words(listOf(tap('a'), swipe()), fake))
    }

    @Test fun aPoorReadOfTheSwipeDoesNotSplit() {
        // The user's "available": both readings are poor. A split would trade one bad word for
        // another plus a stray letter.
        TapSwipeSegmenter.MIN_SPLIT_SWIPE_SCORE = 0f
        TapSwipeSegmenter.LEAD_LETTER_COST = 5f
        TapSwipeSegmenter.TIE = 2f
        val fake = Fake(mapOf("0-2" to ("available" to -45.9f), "1-2" to ("bailable" to -31.4f)),
            mapOf("a" to 3.1f))
        assertEquals(listOf("available"), words(listOf(tap('a'), swipe()), fake))
    }
}
