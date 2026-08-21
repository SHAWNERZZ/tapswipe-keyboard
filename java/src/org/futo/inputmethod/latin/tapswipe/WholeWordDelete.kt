package org.futo.inputmethod.latin.tapswipe

import java.util.function.IntPredicate

/**
 * How much text a whole-word backspace should remove.
 *
 * Whole-word delete undoes a word you finished typing. It is not a tool for trimming whatever
 * happens to sit before the cursor, so it declines any position where the thing before the cursor
 * is not part of a word. Punctuation, symbols and closing brackets all fall through to an ordinary
 * character delete, which is the behavior someone expects when they end a sentence and change
 * their mind about the full stop.
 *
 * ### Why the word boundary is not whitespace
 *
 * An earlier version walked back to the nearest whitespace. A full stop is not whitespace, so the
 * walk went straight through it and took the word as well: `Hello world.` lost `world.` when the
 * user meant to lose `.` alone.
 *
 * The boundary is now "part of a word", which the caller supplies. That predicate counts an
 * apostrophe as a word connector, so `don't` still deletes in one press, and it excludes a full
 * stop, so `world.` splits at the stop.
 *
 * Pure, so the rule is checked without a device. Deciding what counts as part of a word needs live
 * settings and a locale, so that stays with the caller.
 */
object WholeWordDelete {

    /**
     * @param before text immediately before the cursor, most recent character last
     * @param isWordCodePoint whether a code point is part of a word
     * @return how many characters to delete, or 0 to leave this to the ordinary delete path
     */
    @JvmStatic
    fun lengthToDelete(before: CharSequence, isWordCodePoint: IntPredicate): Int {
        if (before.isEmpty()) return 0

        var end = before.length

        // At most one trailing space, so "foo  " leaves the earlier spaces alone. The space goes
        // with the word because a word is committed together with its separator, and leaving it
        // behind would take two presses to undo one word.
        if (before[end - 1] == ' ') end--
        if (end == 0) return 0

        // The character before the cursor decides whether a word is there at all. This is the test
        // that keeps a trailing full stop out of the word.
        if (!isWordCodePoint.test(Character.codePointBefore(before, end))) return 0

        val wordEnd = end
        while (end > 0) {
            val cp = Character.codePointBefore(before, end)
            if (!isWordCodePoint.test(cp)) break
            end -= Character.charCount(cp)
        }
        if (end == wordEnd) return 0

        // A phone number or a run of symbols was never a decoded word, so there is nothing here for
        // whole-word delete to undo. Losing a long number because the last digit was mistyped is a
        // bad trade, and digits count as part of a word when the number row is on.
        if (!containsLetter(before, end, wordEnd)) return 0

        return before.length - end
    }

    private fun containsLetter(s: CharSequence, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            val cp = Character.codePointAt(s, i)
            if (Character.isLetter(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
