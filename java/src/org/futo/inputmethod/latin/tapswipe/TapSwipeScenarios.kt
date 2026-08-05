package org.futo.inputmethod.latin.tapswipe

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.DictionaryFacilitatorImpl
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.inputmethod.latin.TapSwipeAdaptiveGeometrySetting
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.common.ResizableIntArray
import org.futo.inputmethod.latin.uix.DataStoreHelper

/**
 * Scripted end-to-end scenarios, run inside the live keyboard.
 *
 * The AOSP instrumented harness (`InputTestsBase`) assumes a threading model this fork left behind
 * when it moved to coroutines and Compose - its own `InputLogicTests` hang too, so the problem is
 * upstream rot rather than something TapSwipe broke. This drives the same entry points a finger
 * does, against the real IME and a real editor, and reads the result back out.
 *
 * Run it from the Memory Debug action with a scratch text field focused. Every IME call is
 * marshalled to the main thread while the waiting happens off it, so a run cannot ANR.
 *
 * Results also go to logcat under [TAG].
 */
object TapSwipeScenarios {
    const val TAG = "TapSwipeScenarios"

    /** Long enough for the decode round-trip (background inference, back to the main thread). */
    private const val SETTLE_MS = 400L

    /** Comfortably above the peck cadence threshold, so these taps read as deliberate spelling. */
    private const val SLOW_TAP_MS = 400L

    /** Comfortably below it. */
    private const val FAST_TAP_MS = 60L

    /** Gaps to probe the swipe/space decode race at, shortest first. */
    private val SPACE_RACE_GAPS = listOf(0L, 50L, 100L, 200L, 350L)

    private const val STEPS_PER_LEG = 6
    private const val MS_PER_STEP = 16

    // ---------------------------------------------------------------- driving input

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /**
     * The keyboard as it is *right now*, shift state included.
     *
     * This matters more than it looks. `PointerTracker` sends `key.getCode()` from the live
     * keyboard, and a shifted alphabet layout carries uppercase codes ([BaseKey] resolves
     * `ELEMENT_ALPHABET_AUTOMATIC_SHIFTED` to the shifted key). So a real tap on "h" after ". "
     * delivers 'H', not 'h' plus a flag. A harness that always sent lowercase would report
     * auto-capitalisation as working no matter what the shift machinery did.
     */
    private fun liveKeyboard(): Keyboard? =
        KeyboardSwitcher.getInstance().keyboard ?: SwipeDecoderDictionary.debugCurrentKeyboard()

    private fun findKey(cp: Int): Key? = liveKeyboard()?.sortedKeys?.firstOrNull {
        Character.toLowerCase(it.code) == Character.toLowerCase(cp)
    }

    /** Key centre in keyboard coordinates, or null if the current layout has no such key. */
    private fun keyXY(cp: Int): Pair<Int, Int>? {
        val key = findKey(cp) ?: return null
        return (key.x + key.width / 2) to (key.y + key.height / 2)
    }

    /**
     * Taps the key bearing [cp], reproducing what `PointerTracker` does on a real touch.
     *
     * Three details matter, and getting any of them wrong makes the harness silently untruthful:
     *
     * - **press, code, release.** The shift state machine lives entirely in `onPressKey` /
     *   `onReleaseKey`; `KeyboardState.onEvent` ignores `CODE_SHIFT` outright. Sending only the
     *   code means shift never toggles, so a manual-shift test would be asserting on nothing.
     * - **the code is captured before the press.** `onPressKey` can flip the layout
     *   (`unshiftOnPressed`), and `PointerTracker` resolves the key once on touch-down and reuses
     *   that code throughout. That ordering is what makes a shifted letter come out uppercase.
     * - **coordinates only where the real path sends them.** `PointerTracker` passes
     *   `NOT_A_COORDINATE` unless the key has proximity correction, so functional keys carry no
     *   position. And `onCodeInput` runs coordinates through `getKeyX`/`getKeyY`, which subtract
     *   the view padding to reach the keyboard frame - key centres are already in that frame, so
     *   the transform is probed at the origin and cancelled.
     */
    private suspend fun tap(ime: LatinIME, cp: Int) {
        val key = findKey(cp)
        if (key == null) {
            onMain {
                ime.latinIMELegacy.onCodeInput(
                    cp, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            return
        }
        val code = key.code
        val withProximity = liveKeyboard()?.hasProximityCharsCorrection(code) ?: false
        onMain {
            val view = KeyboardSwitcher.getInstance().mainKeyboardView
            val dx = view?.getKeyX(0) ?: 0
            val dy = view?.getKeyY(0) ?: 0

            ime.latinIMELegacy.onPressKey(code, 0, true)
            if (withProximity) {
                ime.latinIMELegacy.onCodeInput(
                    code, key.x + key.width / 2 - dx, key.y + key.height / 2 - dy, false)
            } else {
                ime.latinIMELegacy.onCodeInput(
                    code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            ime.latinIMELegacy.onReleaseKey(code, false)
        }
    }

    /**
     * Taps a fraction of the way across the key rather than dead centre, to exercise the real-tap
     * position path. (0.5, 0.5) is the centre; (0.15, 0.5) is near the left edge.
     */
    private suspend fun tapOffCentre(ime: LatinIME, cp: Int, fracX: Float, fracY: Float) {
        val key = findKey(cp) ?: return
        val code = key.code
        onMain {
            val view = KeyboardSwitcher.getInstance().mainKeyboardView
            val dx = view?.getKeyX(0) ?: 0
            val dy = view?.getKeyY(0) ?: 0
            ime.latinIMELegacy.onPressKey(code, 0, true)
            ime.latinIMELegacy.onCodeInput(
                code,
                key.x + (key.width * fracX).toInt() - dx,
                key.y + (key.height * fracY).toInt() - dy,
                false
            )
            ime.latinIMELegacy.onReleaseKey(code, false)
        }
    }

    /** Types [s] at the given inter-tap gap, which is what decides peck vs fluent typing. */
    private suspend fun type(ime: LatinIME, s: String, gapMs: Long = FAST_TAP_MS) {
        for (c in s) {
            tap(ime, c.code)
            delay(gapMs)
        }
    }

    private fun appendPoint(p: InputPointers, pointerId: Int, x: Int, y: Int, t: Int) {
        val xs = ResizableIntArray(1)
        val ys = ResizableIntArray(1)
        val ts = ResizableIntArray(1)
        xs.add(x); ys.add(y); ts.add(t)
        // append() fills the flat arrays *and* the open segment; addPointer() would fill only the
        // flat arrays, and every TapSwipe path reads segments.
        p.append(pointerId, ts, xs, ys, 0, 1)
    }

    /** Interpolates one pointer's path through the centres of [letters]. */
    private fun traceLeg(p: InputPointers, pointerId: Int, pts: List<Pair<Int, Int>>) {
        appendPoint(p, pointerId, pts[0].first, pts[0].second, 0)
        var t = 0
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            for (step in 1..STEPS_PER_LEG) {
                t += MS_PER_STEP
                appendPoint(
                    p, pointerId,
                    ax + (bx - ax) * step / STEPS_PER_LEG,
                    ay + (by - ay) * step / STEPS_PER_LEG,
                    t
                )
            }
        }
    }

    private fun centres(letters: String): List<Pair<Int, Int>>? =
        letters.map { keyXY(it.code) ?: return null }

    /** Synthesises one continuous swipe through [letters]. */
    private suspend fun swipe(ime: LatinIME, letters: String): Boolean {
        if (letters.length < 2) return false
        val pts = centres(letters) ?: return false
        return onMain {
            val p = InputPointers(Constants.DEFAULT_GESTURE_POINTS_CAPACITY)
            ime.latinIMELegacy.onStartBatchInput()
            p.onPointerDown(0)
            traceLeg(p, 0, pts)
            ime.latinIMELegacy.onUpdateBatchInput(p)
            ime.latinIMELegacy.onEndBatchInput(p)
            true
        }
    }

    /**
     * Two overlapping strokes in one batch, as two thumbs produce. Hand is taken from the pointer
     * id, so this is the shape [TapSwipeSession.addSwipeSegments] routes to the right-hand stream.
     */
    private suspend fun swipeBothThumbs(ime: LatinIME, left: String, right: String): Boolean {
        val lp = centres(left) ?: return false
        val rp = centres(right) ?: return false
        return onMain {
            val p = InputPointers(Constants.DEFAULT_GESTURE_POINTS_CAPACITY)
            ime.latinIMELegacy.onStartBatchInput()
            p.onPointerDown(0)
            p.onPointerDown(1)
            traceLeg(p, 0, lp)
            traceLeg(p, 1, rp)
            ime.latinIMELegacy.onUpdateBatchInput(p)
            ime.latinIMELegacy.onEndBatchInput(p)
            true
        }
    }

    private suspend fun settle(ms: Long = SETTLE_MS) = delay(ms)

    // ---------------------------------------------------------------- editor access

    private suspend fun textBeforeCursor(ime: LatinIME): String = onMain {
        ime.currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: ""
    }

    /**
     * The whole field, not just what precedes the caret. A scenario that moves the cursor leaves
     * most of the text *after* it, so asserting on [textBeforeCursor] alone would read as data loss
     * when nothing was lost.
     */
    private suspend fun wholeField(ime: LatinIME): String = onMain {
        val ic = ime.currentInputConnection ?: return@onMain ""
        (ic.getTextBeforeCursor(500, 0)?.toString() ?: "") +
                (ic.getTextAfterCursor(500, 0)?.toString() ?: "")
    }

    /** Moves the caret, which must drop any open session. */
    private suspend fun moveCursorToStart(ime: LatinIME) = onMain {
        ime.currentInputConnection?.setSelection(0, 0)
        Unit
    }

    /** Clears the field so each scenario starts from a known state. */
    private suspend fun clearField(ime: LatinIME) {
        onMain {
            val ic = ime.currentInputConnection
            if (ic != null) {
                ic.finishComposingText()
                ic.setSelection(Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
                val existing = ic.getTextBeforeCursor(1000, 0)?.length ?: 0
                if (existing > 0) ic.deleteSurroundingText(existing, 0)
                val after = ic.getTextAfterCursor(1000, 0)?.length ?: 0
                if (after > 0) ic.deleteSurroundingText(0, after)
            }
        }
        settle(150)
    }

    // ---------------------------------------------------------------- the scenarios

    /** What a scenario gets to assert on: the editor's text plus the mode the word ended in. */
    private class Probe(val text: String, val full: String, val mode: TapSwipeMode) {
        val word: String get() = text.trim()
        val lower: String get() = word.lowercase()
        val words: List<String> get() = word.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private class Scenario(
        val name: String,
        /** Printed as a header so a long report stays readable. */
        val group: String = "",
        /**
         * Set when a case is expected to fail and the failure has been judged acceptable. It still
         * runs, and still reports - as KNOWN rather than FAIL - so a change in the boundary shows
         * up instead of hiding behind a deleted test.
         */
        val knownLimitation: String = "",
        /** Skipped when the decoder is unavailable, since it cannot do anything meaningful. */
        val needsSwipe: Boolean = true,
        val run: suspend (LatinIME) -> Unit,
        /** Returns null when the scenario passed, or the reason it did not. */
        val check: (Probe) -> String?
    )

    /** Shift is a code input like any other; the live keyboard reflects it on the next lookup. */
    private suspend fun shift(ime: LatinIME) = tap(ime, Constants.CODE_SHIFT)

    /** True when the live keyboard is in any shifted state - what a real finger would see. */
    private fun keyboardIsShifted(): Boolean =
        findKey('a'.code)?.code?.let { Character.isUpperCase(it) } ?: false

    private fun capitalised(w: String) = w.isNotEmpty() && w[0].isUpperCase()

    /**
     * Shift state plus the auto-caps mode driving it.
     *
     * These two together say which branch of `KeyboardState.updateAlphabetShiftState` ran: it
     * re-shifts whenever auto-caps is anything but OFF, and only unshifts when it is OFF. Reading
     * the resulting text alone cannot tell "the unshift never fired" from "it fired and auto-caps
     * immediately shifted again", and those are different bugs.
     */
    private fun shiftLabel(ime: LatinIME): String {
        val shifted = if (keyboardIsShifted()) "SHIFT" else "base"
        return shifted + "/caps=" + ime.debugAutoCapsState()
    }

    private fun scenarios(): List<Scenario> = listOf(

        // --- word building --------------------------------------------------------------

        Scenario("two swipes build one word", group = "Word building",
            run = { swipe(it, "hel"); settle(); swipe(it, "lo") },
            check = { p ->
                if (p.words.size == 1) null else "expected one word, got '${p.word}'"
            }),

        run {
            // The original bug: the display read "But", space was pressed, and "By" was committed -
            // a tap schedules a TYPING suggestion query whose autocorrection wins at commit time.
            //
            // The check compares the committed word against what was actually on screen rather than
            // against a hard-coded "but". Asserting the word made this flaky for a reason that means
            // nothing: a two-key swipe is a genuinely ambiguous gesture, and one run decoded it as
            // "By", giving "Byt". That is the decoder choosing a different word, not the commit
            // path substituting one - which is the only thing this case is about.
            var shown = ""
            Scenario("swipe then tap commits what is shown",
                group = "Word building",
                run = { ime ->
                    swipe(ime, "bu"); settle(); tap(ime, 't'.code); settle()
                    shown = textBeforeCursor(ime).trim()
                    type(ime, " ")
                },
                check = { p ->
                    val committed = p.words.lastOrNull() ?: ""
                    when {
                        shown.isEmpty() -> "nothing was composed to compare against"
                        committed.equals(shown, ignoreCase = false) -> null
                        else -> "displayed '$shown' but committed '$committed'"
                    }
                })
        },

        Scenario("an off-centre tapped tail still extends the word",
            group = "Word building",
            // Exercises the real-tap-position path: the tap lands near the edge of the key rather
            // than its centre, so the position fed to the decoder is one the key-centre lookup
            // could never have produced.
            run = { swipe(it, "bu"); settle(); tapOffCentre(it, 't'.code, 0.2f, 0.5f); settle() },
            check = { p ->
                if (p.word.length >= 3) null
                else "off-centre tap did not extend the word: '${p.word}'"
            }),

        Scenario("an off-centre pecked word is still committed verbatim",
            group = "Word building", needsSwipe = false,
            run = {
                tapOffCentre(it, 'z'.code, 0.2f, 0.4f); delay(SLOW_TAP_MS)
                tapOffCentre(it, 'b'.code, 0.8f, 0.6f); delay(SLOW_TAP_MS)
                tapOffCentre(it, 'q'.code, 0.3f, 0.7f); settle(); type(it, " ")
            },
            check = { p ->
                // Peck words bypass the decoder entirely, so position must not affect them at all.
                if (p.lower == "zbq") null else "peck word altered by tap position: '${p.word}'"
            }),

        Scenario("a tapped tail extends the swiped word rather than replacing it",
            group = "Word building",
            run = { swipe(it, "bu"); settle(); tap(it, 't'.code); settle() },
            check = { p ->
                // Whatever the decoder picks, the tap must lengthen the word, not restart it.
                if (p.word.length >= 3) null else "tap did not extend the word: '${p.word}'"
            }),

        Scenario("tap then swipe keeps the tapped prefix",
            run = { type(it, "s"); settle(); swipe(it, "hawn") },
            check = { p ->
                if (p.lower.startsWith("s")) null else "prefix lost, got '${p.word}'"
            }),

        Scenario("finger lift does not commit",
            run = { swipe(it, "hel") },
            check = { p ->
                if (p.word.isNotEmpty() && !p.text.endsWith(" ")) null
                else "lift committed or produced nothing: '${p.text}'"
            }),

        Scenario("space finalizes",
            run = { swipe(it, "hel"); settle(); type(it, " ") },
            check = { p -> if (p.text.endsWith(" ")) null else "space did not commit: '${p.text}'" }),

        Scenario("punctuation finalizes",
            run = { swipe(it, "hel"); settle(); type(it, ".") },
            check = { p ->
                if (p.word.endsWith(".") && p.word.length > 1) null
                else "period did not finalize: '${p.word}'"
            }),

        Scenario("two thumbs compose one word",
            run = { swipeBothThumbs(it, "saw", "hn") },
            check = { p ->
                if (p.words.size == 1 && p.word.length >= 3) null
                else "expected one fused word, got '${p.word}'"
            }),

        Scenario("sentence start stays capitalised across two strokes",
            run = { swipe(it, "hel"); settle(); swipe(it, "lo"); settle(); type(it, " ") },
            check = { p ->
                if (p.word.isNotEmpty() && p.word[0].isUpperCase()) null
                else "not capitalised: '${p.word}'"
            }),

        // --- backspace ------------------------------------------------------------------

        Scenario("stroke undo replaces rather than appends", group = "Backspace",
            run = {
                swipe(it, "bu"); settle(); type(it, "t"); settle()
                tap(it, Constants.CODE_DELETE)
            },
            check = { p ->
                // The "ButBy" bug: the re-decode was appended after the old composing text.
                if (p.word.length <= 3) null else "undo appended instead of replacing: '${p.word}'"
            }),

        Scenario("stroke undo keeps the rest of the word",
            run = {
                swipe(it, "bu"); settle(); type(it, "t"); settle()
                tap(it, Constants.CODE_DELETE)
            },
            check = { p -> if (p.word.isNotEmpty()) null else "whole word was wiped" }),

        Scenario("undoing every stroke clears the word",
            run = {
                swipe(it, "hel"); settle()
                repeat(3) { _ -> tap(it, Constants.CODE_DELETE); settle(200) }
            },
            check = { p -> if (p.word.isEmpty()) null else "residue left: '${p.word}'" }),

        Scenario("peck backspace removes one character", needsSwipe = false,
            run = {
                type(it, "cat", SLOW_TAP_MS); settle()
                tap(it, Constants.CODE_DELETE)
            },
            check = { p -> if (p.lower == "ca") null else "expected 'ca', got '${p.word}'" }),

        Scenario("peck backspace then swipe still fuses",
            run = {
                type(it, "ca", SLOW_TAP_MS); settle()
                tap(it, Constants.CODE_DELETE); settle()
                swipe(it, "at")
            },
            check = { p ->
                if (p.words.size == 1 && p.word.isNotEmpty()) null
                else "word lost or split after peck-backspace-swipe: '${p.word}'"
            }),

        // --- modes ----------------------------------------------------------------------

        Scenario("slow tapping engages peck", group = "Modes", needsSwipe = false,
            run = { type(it, "cat", SLOW_TAP_MS) },
            check = { p ->
                if (p.mode == TapSwipeMode.PECK) null else "mode was ${p.mode}, expected PECK"
            }),

        Scenario("fast tapping does not engage peck", needsSwipe = false,
            run = { type(it, "hello", FAST_TAP_MS) },
            check = { p ->
                if (p.mode != TapSwipeMode.PECK) null else "peck engaged on fluent typing"
            }),

        Scenario("peck commits an out-of-dictionary word verbatim", needsSwipe = false,
            run = { type(it, "zblq", SLOW_TAP_MS); settle(); type(it, " ") },
            check = { p ->
                if (p.lower == "zblq") null else "peck word was corrected to '${p.word}'"
            }),

        Scenario("peck latches for the rest of the word", needsSwipe = false,
            run = {
                type(it, "ca", SLOW_TAP_MS)
                type(it, "t", FAST_TAP_MS)   // one fast tap must not unlatch it
            },
            check = { p ->
                if (p.mode == TapSwipeMode.PECK) null
                else "peck unlatched mid-word, mode was ${p.mode}"
            }),

        Scenario("a swipe returns the mode to SWIPE",
            run = {
                type(it, "cat", SLOW_TAP_MS); settle(); type(it, " "); settle()
                swipe(it, "hel")
            },
            check = { p ->
                if (p.mode == TapSwipeMode.SWIPE) null else "expected SWIPE, was ${p.mode}"
            }),

        // --- session hygiene: the runaway-word class ------------------------------------

        Scenario("strokes do not leak into the next word", group = "Session hygiene",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                swipe(it, "cat")
            },
            check = { p ->
                if (p.words.size == 2 && p.words[1].length <= 6) null
                else "second word grew from the first: '${p.word}'"
            }),

        // A ladder rather than one case. Hitting space before the decode lands loses the word
        // outright, so what matters is not "does it fail" but *how much slack* a finger has. The
        // first gap that passes is the real-world exposure window; below it the word vanishes.
        *SPACE_RACE_GAPS.map { gap ->
            Scenario("swipe, space, swipe survives a ${gap}ms gap",
                // Measured: the word is lost at 0ms and safe from 50ms up, so the exposure window
                // is under 50ms. A thumb needs 150ms+ just to travel to the space bar, so no real
                // finger can reach it. Fixing it would mean holding separators until the decode
                // lands - new state in the path that produced the ButBy and but/by regressions -
                // which is not a trade worth making for a window nobody can hit. Kept as a live
                // measurement: if this starts failing at 50ms, decoding got slower and the
                // judgement needs revisiting.
                knownLimitation = if (gap == 0L) "decode race, window is under 50ms" else "",
                run = {
                    swipe(it, "hel"); delay(gap); type(it, " "); delay(gap); swipe(it, "cat")
                },
                check = { p ->
                    if (p.words.size == 2) null else "word lost to the decode race: '${p.word}'"
                })
        }.toTypedArray(),

        run {
            // Captured mid-scenario so the check can compare against what was actually there.
            var before = ""
            Scenario("moving the cursor mid-word drops the session",
                run = {
                    swipe(it, "hel"); settle(); type(it, " "); settle()
                    swipe(it, "cat"); settle()
                    before = wholeField(it).trim()
                    moveCursorToStart(it); settle()
                    swipe(it, "dog")
                },
                check = { p ->
                    // The question is only whether the closed session rewrote the earlier text.
                    // It does not matter that the new word abuts it with no space - a word typed
                    // at position 0 in front of existing text runs into it in stock too, since
                    // auto-space is inserted before a word and never after.
                    if (before.isNotEmpty() && p.full.trim().endsWith(before)) null
                    else "earlier text was rewritten: had '$before', field is '${p.full.trim()}'"
                })
        },

        Scenario("a word never grows past the stroke cap",
            run = { repeat(TapSwipeSession.MAX_STROKES + 4) { _ -> swipe(it, "el"); settle(120) } },
            check = { p ->
                if (p.words.size <= 1 && p.word.length < 40) null
                else "runaway word: '${p.word}'"
            }),

        Scenario("double space still produces a sentence end", needsSwipe = false,
            run = { type(it, "hi", SLOW_TAP_MS); settle(); type(it, "  ") },
            check = { p ->
                if (p.lower.startsWith("hi") && p.lower.length <= 4) null
                else "double space garbled the word: '${p.text}'"
            }),

        // --- auto-capitalisation ---------------------------------------------------------
        // These read the code the *live* keyboard hands back, so they fail if the shift machinery
        // stops switching layouts - which a harness sending fixed lowercase could not have noticed.

        Scenario("keyboard is shifted at the start of an empty field",
            group = "Auto-capitalisation", needsSwipe = false,
            run = { },
            check = { _ ->
                if (keyboardIsShifted()) null else "layout was not auto-shifted on an empty field"
            }),

        Scenario("first tapped word is capitalised", needsSwipe = false,
            run = { type(it, "hello", FAST_TAP_MS); settle(); type(it, " ") },
            check = { p -> if (capitalised(p.word)) null else "got '${p.word}'" }),

        Scenario("keyboard re-shifts after a period and space", needsSwipe = false,
            run = { type(it, "hi", FAST_TAP_MS); settle(); type(it, ". ") },
            check = { _ ->
                if (keyboardIsShifted()) null else "layout did not re-shift after a period"
            }),

        Scenario("word after a period is capitalised", needsSwipe = false,
            run = {
                type(it, "hi", FAST_TAP_MS); settle(); type(it, ". "); settle()
                type(it, "there", FAST_TAP_MS); settle(); type(it, " ")
            },
            check = { p ->
                val w = p.words.lastOrNull() ?: ""
                if (capitalised(w)) null else "second sentence not capitalised: '${p.word}'"
            }),

        Scenario("word after a question mark is capitalised", needsSwipe = false,
            run = {
                type(it, "hi", FAST_TAP_MS); settle(); type(it, "? "); settle()
                type(it, "yes", FAST_TAP_MS); settle(); type(it, " ")
            },
            check = { p ->
                val w = p.words.lastOrNull() ?: ""
                if (capitalised(w)) null else "not capitalised after a question mark: '${p.word}'"
            }),

        Scenario("mid-sentence words are not capitalised", needsSwipe = false,
            run = {
                type(it, "hi", FAST_TAP_MS); settle(); type(it, " "); settle()
                type(it, "there", FAST_TAP_MS); settle(); type(it, " ")
            },
            check = { p ->
                val w = p.words.lastOrNull() ?: ""
                if (!capitalised(w)) null else "over-capitalised mid-sentence: '${p.word}'"
            }),

        Scenario("word after a period is capitalised when swiped",
            group = "Auto-capitalisation",
            run = {
                type(it, "hi", FAST_TAP_MS); settle(); type(it, ". "); settle()
                swipe(it, "hel"); settle(); type(it, " ")
            },
            check = { p ->
                val w = p.words.lastOrNull() ?: ""
                if (capitalised(w)) null else "swiped sentence start not capitalised: '${p.word}'"
            }),

        run {
            // Traced step by step: the text alone cannot distinguish an unshift that never fired
            // from one that fired and was immediately undone by auto-caps.
            var trace = ""
            Scenario("manual shift capitalises exactly one letter",
                group = "Auto-capitalisation", needsSwipe = false,
                // "cat", not "abc": ABC is a dictionary entry, so autocorrect uppercased the
                // whole word and the case looked like stuck shift when shift was fine. The trace
                // is what showed it - the layout returned to base right after the first letter.
                run = { ime ->
                    type(ime, "hi", FAST_TAP_MS); settle(); type(ime, " "); settle()
                    trace = "beforeShift=" + shiftLabel(ime)
                    shift(ime); delay(FAST_TAP_MS)
                    trace += " afterShift=" + shiftLabel(ime)
                    tap(ime, 'c'.code); delay(FAST_TAP_MS)
                    trace += " afterC=" + shiftLabel(ime)
                    tap(ime, 'a'.code); delay(FAST_TAP_MS)
                    trace += " afterA=" + shiftLabel(ime)
                    tap(ime, 't'.code); settle(); type(ime, " ")
                },
                check = { p ->
                    val w = p.words.lastOrNull() ?: ""
                    when {
                        !capitalised(w) -> "shift did not capitalise: '$w' [$trace]"
                        w.drop(1).any { c -> c.isUpperCase() } -> "shift stuck on: '$w' [$trace]"
                        else -> null
                    }
                })
        },

        Scenario("shift releases after one letter at a sentence start", needsSwipe = false,
            // Same mechanism, but where auto-caps would shift anyway. If the traced case above
            // fails and this one passes, the unshift works and something is holding auto-caps on.
            run = {
                type(it, "hi", FAST_TAP_MS); settle(); type(it, ". "); settle()
                type(it, "cat", FAST_TAP_MS); settle(); type(it, " ")
            },
            check = { p ->
                val w = p.words.lastOrNull() ?: ""
                if (!w.drop(1).any { c -> c.isUpperCase() }) null
                else "auto-shift stuck on: '$w'"
            }),

        // --- punctuation and spacing -----------------------------------------------------

        Scenario("a comma attaches to the word with no space before it",
            group = "Punctuation", needsSwipe = false,
            run = { type(it, "hi", FAST_TAP_MS); settle(); type(it, ",") },
            check = { p ->
                if (p.word.endsWith(",") && !p.word.contains(" ,")) null
                else "comma spacing wrong: '${p.word}'"
            }),

        Scenario("a swipe after punctuation gets its own space",
            group = "Punctuation",
            run = {
                swipe(it, "hel"); settle(); type(it, ". "); settle()
                swipe(it, "cat"); settle(); type(it, " ")
            },
            check = { p ->
                if (p.words.size == 2 && !p.text.contains("  ")) null
                else "spacing after punctuation wrong: '${p.text}'"
            }),

        Scenario("no doubled space between two swiped words",
            group = "Punctuation",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                swipe(it, "cat"); settle(); type(it, " ")
            },
            check = { p -> if (!p.text.contains("  ")) null else "doubled space: '${p.text}'" }),

        Scenario("an apostrophe stays inside the word",
            group = "Punctuation", needsSwipe = false,
            run = { type(it, "dont", SLOW_TAP_MS); settle(); type(it, " ") },
            check = { p -> if (p.words.size == 1) null else "word split apart: '${p.word}'" }),

        // --- everyday sentences ----------------------------------------------------------

        Scenario("a mixed tap-and-swipe sentence keeps its word count",
            group = "Everyday typing",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                type(it, "cat", FAST_TAP_MS); settle(); type(it, " "); settle()
                swipe(it, "dog"); settle(); type(it, " ")
            },
            check = { p ->
                if (p.words.size == 3) null else "expected 3 words, got ${p.words.size}: '${p.word}'"
            }),

        Scenario("the same word twice produces two words",
            group = "Everyday typing",
            run = {
                swipe(it, "cat"); settle(); type(it, " "); settle()
                swipe(it, "cat"); settle(); type(it, " ")
            },
            check = { p ->
                if (p.words.size == 2) null else "expected 2 words, got '${p.word}'"
            }),

        Scenario("a long swipe stays one word",
            group = "Everyday typing",
            run = { swipe(it, "keyboard"); settle(); type(it, " ") },
            check = { p -> if (p.words.size == 1) null else "long swipe split: '${p.word}'" }),

        Scenario("digits are typed verbatim",
            group = "Everyday typing", needsSwipe = false,
            run = { type(it, "2024", SLOW_TAP_MS); settle(); type(it, " ") },
            check = { p -> if (p.lower == "2024") null else "digits mangled: '${p.word}'" }),

        Scenario("backspace after a committed word edits it rather than the next one",
            group = "Everyday typing",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                tap(it, Constants.CODE_DELETE); settle()
                tap(it, Constants.CODE_DELETE); settle()
            },
            check = { p ->
                if (p.words.size <= 1) null else "backspace left two words: '${p.word}'"
            }),

        // --- adaptive geometry ------------------------------------------------------------

        run {
            // Proves the learned shift actually reaches the decoder, without waiting weeks for real
            // data to accumulate. Anecdotal on-device impressions cannot separate "the mechanism
            // works" from "the mechanism is inert" - this can.
            //
            // A large synthetic offset is written for one key, the layout is reinstalled so the new
            // positions cross into the model, and the same stroke is decoded before and after. If
            // the output never changes, the shift is not reaching `layout_keys` and every result
            // built on top of it is meaningless.
            var detail = ""
            Scenario("a synthetic key shift changes what the decoder produces",
                group = "Adaptive geometry",
                run = { ime ->
                    detail = probeAdaptiveShift(ime)
                },
                check = { _ ->
                    when {
                        detail.startsWith("SKIP") -> null
                        detail.startsWith("OK") -> null
                        else -> detail
                    }
                })
        },

        Scenario("typing continues cleanly after a backspace mid-sentence",
            group = "Everyday typing",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                swipe(it, "cat"); settle()
                tap(it, Constants.CODE_DELETE); settle()
                swipe(it, "dog"); settle(); type(it, " ")
            },
            check = { p ->
                if (p.words.size == 2) null else "expected 2 words, got '${p.word}'"
            })
    )

    /**
     * Writes a deliberately large offset for one key, reloads the layout, and checks the decode
     * moves. Restores the model afterwards so a diagnostic never leaves learned state behind.
     */
    private suspend fun probeAdaptiveShift(ime: LatinIME): String {
        if (!DataStoreHelper.getSetting(TapSwipeAdaptiveGeometrySetting)) {
            return "SKIP: adaptive key geometry is off"
        }
        val layoutKey = SwipeDecoderDictionary.currentTouchModelLayoutKey()
            ?: return "SKIP: no layout applied"
        val extent = SwipeDecoderDictionary.normalizedKeyHalfExtent()
            ?: return "SKIP: no key extent"

        // Snapshot, so the probe is non-destructive.
        val hadSamples = TapSwipeTouchModel.totalSamples()

        clearField(ime)
        swipe(ime, "hel"); settle()
        val before = textBeforeCursor(ime).trim()

        // Enough weight to clear the confidence ramp outright, and a shift at the cap.
        val shove = extent[0] * 2f * TapSwipeTouchModel.MAX_SHIFT_FRACTION * 4f
        val now = System.currentTimeMillis()
        repeat(40) {
            TapSwipeTouchModel.record(layoutKey, 'l'.code, shove, 0f, 1f, now)
        }
        onMain<Unit> { DictionaryFacilitatorImpl.swipeDecoderDictionary?.debugReinstallLayout() }
        settle(300)

        clearField(ime)
        swipe(ime, "hel"); settle()
        val after = textBeforeCursor(ime).trim()

        // Undo the probe regardless of outcome.
        TapSwipeTouchModel.reset()
        onMain<Unit> { DictionaryFacilitatorImpl.swipeDecoderDictionary?.debugReinstallLayout() }
        settle(200)
        clearField(ime)

        val applied = TapSwipeTouchModel.shiftFor(layoutKey, 'l'.code, extent[0], extent[1], now)
        return when {
            before.isEmpty() && after.isEmpty() -> "decoder produced nothing either way"
            before != after -> "OK: '$before' -> '$after' (had $hadSamples samples)"
            else -> "shift did not change the decode: still '$before'" +
                    " (applied=" + (applied?.let { "%.4f".format(it[0]) } ?: "null") + ")"
        }
    }

    // ---------------------------------------------------------------- runner

    suspend fun run(ime: LatinIME): String {
        val sb = StringBuilder()

        if (onMain { ime.currentInputConnection } == null) {
            return "ABORT: no focused text field. Focus a scratch field, then run again.\n"
        }
        if (!DataStoreHelper.getSetting(TapSwipeModeSetting)) {
            return "ABORT: TapSwipe input model is off (Settings -> TapSwipe).\n"
        }

        val swipeReady = SwipeDecoderDictionary.debugCurrentKeyboard() != null &&
                keyXY('h'.code) != null
        if (!swipeReady) {
            sb.appendLine("NOTE: no keyboard layout resolved yet - swipe scenarios skipped.")
            sb.appendLine("Swipe one word on the keyboard first, then run again.")
            sb.appendLine()
        }

        var passed = 0
        var failed = 0
        var skipped = 0
        var known = 0

        var group = ""
        for (s in scenarios()) {
            if (s.group.isNotEmpty() && s.group != group) {
                group = s.group
                sb.appendLine()
                sb.appendLine("-- " + group)
            }
            if (s.needsSwipe && !swipeReady) {
                skipped++
                sb.appendLine("[SKIP] ${s.name}")
                continue
            }

            val outcome = try {
                clearField(ime)
                s.run(ime)
                settle(SETTLE_MS + 100)
                s.check(Probe(textBeforeCursor(ime), wholeField(ime), TapSwipeUiState.mode))
            } catch (e: Throwable) {
                "threw: $e"
            }

            when {
                outcome == null && s.knownLimitation.isNotEmpty() -> {
                    passed++
                    sb.appendLine("[PASS] ${s.name} (known limitation no longer reproduces)")
                }
                outcome == null -> {
                    passed++
                    sb.appendLine("[PASS] ${s.name}")
                }
                s.knownLimitation.isNotEmpty() -> {
                    known++
                    sb.appendLine("[KNOWN] ${s.name} - ${s.knownLimitation}")
                }
                else -> {
                    failed++
                    sb.appendLine("[FAIL] ${s.name} - $outcome")
                }
            }
            Log.d(TAG, sb.lines().let { it[it.size - 2] })
        }

        try { clearField(ime) } catch (e: Throwable) { /* leave the field as the run left it */ }

        val summary = "$passed passed, $failed failed" +
                (if (known > 0) ", $known known" else "") +
                (if (skipped > 0) ", $skipped skipped" else "")
        sb.insert(0, "$summary\n\n")
        Log.d(TAG, "scenarios: $summary")
        return sb.toString()
    }
}
