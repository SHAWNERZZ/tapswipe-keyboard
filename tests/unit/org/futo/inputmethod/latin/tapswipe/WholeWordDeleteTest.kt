package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.function.IntPredicate

/**
 * JVM tests for how much text a whole-word backspace removes.
 *
 * A return of 0 means "decline", which sends the press to the ordinary character delete. Most of
 * these cases assert a decline, because the failure this rule guards against is taking too much.
 * Deleting a word the user wanted to keep costs a re-type. Deleting one character too few costs
 * one more press.
 */
class WholeWordDeleteTest {

    /**
     * Stands in for `SettingsValues.isWordCodePoint` with an English locale and the number row on.
     * Letters, digits and the apostrophe connector count. Everything else does not.
     */
    private val isWordCodePoint = IntPredicate { cp ->
        Character.isLetter(cp) || Character.isDigit(cp) || cp == '\''.code
    }

    private fun len(before: String) = WholeWordDelete.lengthToDelete(before, isWordCodePoint)

    // ---------------------------------------------------------------- the reported bug

    /**
     * Type a sentence, end it, then change your mind. The stop goes and the word stays.
     */
    @Test
    fun `a trailing full stop is not part of the word`() {
        assertEquals(0, len("Hello world."))
    }

    @Test
    fun `every trailing punctuation mark declines`() {
        for (mark in listOf(".", ",", "!", "?", ":", ";", ")", "]", "\"", "-")) {
            assertEquals("'$mark' should decline", 0, len("Hello world$mark"))
        }
    }

    /** One press removes the stop. The next press then sees a plain word and takes it. */
    @Test
    fun `the word goes on the press after the stop is gone`() {
        assertEquals(0, len("Hello world."))
        assertEquals(5, len("Hello world"))
    }

    // ---------------------------------------------------------------- what it still takes

    /** The word only. The space in front of it belongs to the word before. */
    @Test
    fun `a plain word goes whole`() {
        assertEquals(5, len("world"))
        assertEquals(5, len("Hello world"))
    }

    @Test
    fun `a word takes one trailing space with it`() {
        assertEquals(6, len("Hello world "))
    }

    /**
     * Two trailing spaces decline. The cursor is sitting after a space, so there is no word to
     * take, and a character delete removes one space. The press after that sees a word.
     */
    @Test
    fun `a second trailing space declines`() {
        assertEquals(0, len("Hello world  "))
        assertEquals(6, len("Hello world "))
    }

    /**
     * The reason the boundary is "part of a word" rather than "a letter". An apostrophe connects,
     * so a contraction is one word and goes in one press.
     */
    @Test
    fun `a contraction goes in one press`() {
        assertEquals(5, len("I don't"))
    }

    @Test
    fun `a word at the very start of the text goes whole`() {
        assertEquals(5, len("world"))
    }

    // ---------------------------------------------------------------- what it declines

    @Test
    fun `nothing before the cursor declines`() {
        assertEquals(0, len(""))
    }

    @Test
    fun `whitespace before the cursor declines`() {
        assertEquals(0, len("Hello  "))
        assertEquals(0, len(" "))
    }

    /**
     * Digits count as part of a word when the number row is on, so the walk collects them. The
     * letter check is what stops a mistyped digit from costing the whole number.
     */
    @Test
    fun `a run of digits declines`() {
        assertEquals(0, len("5551234"))
        assertEquals(0, len("3.14"))
    }

    @Test
    fun `a word containing digits still goes`() {
        assertEquals(5, len("wifi2"))
    }

    // ---------------------------------------------------------------- boundaries

    /** The walk stops at the stop, so the earlier word is safe. */
    @Test
    fun `a word after a stop takes only itself`() {
        assertEquals(3, len("Hi. Yes"))
    }

    @Test
    fun `an opening bracket is not part of the word after it`() {
        assertEquals(5, len("see (below"))
    }

    /**
     * Text outside the Basic Multilingual Plane is two chars per code point. The count returned is
     * in chars, because that is what the delete call takes.
     */
    @Test
    fun `a surrogate pair counts as its char length`() {
        // A letter outside the BMP: Deseret capital long I.
        val wide = "𐐀"
        assertEquals(2, len(wide))
        assertEquals(3, len("a$wide"))
    }

    // ------------------------------------------------- the swipe-up variant

    private fun swipe(before: String) = WholeWordDelete.lengthToWhitespace(before)

    /**
     * The gesture is a statement, so it takes what a tap declines. Someone who swipes for a word
     * delete after `world.` wants the stop gone with the word.
     */
    @Test
    fun `a swipe takes the trailing punctuation with the word`() {
        assertEquals(6, swipe("Hello world."))
        assertEquals(8, swipe("Hello world!?!"))
    }

    @Test
    fun `a swipe takes a digit run that a tap would decline`() {
        assertEquals(7, swipe("5551234"))
        assertEquals(4, swipe("3.14"))
    }

    @Test
    fun `a swipe takes one trailing space, as a tap does`() {
        assertEquals(6, swipe("Hello world "))
    }

    @Test
    fun `a swipe over plain words matches a tap`() {
        assertEquals(5, swipe("Hello world"))
        assertEquals(5, swipe("I don't"))
    }

    @Test
    fun `a swipe with nothing to take declines`() {
        assertEquals(0, swipe(""))
        assertEquals(0, swipe("  "))
        assertEquals(0, swipe("Hello  "))
    }
}
