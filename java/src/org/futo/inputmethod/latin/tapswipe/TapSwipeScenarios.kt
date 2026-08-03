package org.futo.inputmethod.latin.tapswipe

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.SwipeDecoderDictionary
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

    /** Key centre in keyboard coordinates, or null if the current layout has no such key. */
    private fun keyXY(cp: Int): Pair<Int, Int>? {
        val kb = SwipeDecoderDictionary.debugCurrentKeyboard() ?: return null
        val key = kb.sortedKeys.firstOrNull {
            Character.toLowerCase(it.code) == Character.toLowerCase(cp)
        } ?: return null
        return (key.x + key.width / 2) to (key.y + key.height / 2)
    }

    private suspend fun tap(ime: LatinIME, cp: Int) {
        val xy = keyXY(cp)
        onMain {
            ime.latinIMELegacy.onCodeInput(
                cp,
                xy?.first ?: Constants.NOT_A_COORDINATE,
                xy?.second ?: Constants.NOT_A_COORDINATE,
                false
            )
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
        /** Skipped when the decoder is unavailable, since it cannot do anything meaningful. */
        val needsSwipe: Boolean = true,
        val run: suspend (LatinIME) -> Unit,
        /** Returns null when the scenario passed, or the reason it did not. */
        val check: (Probe) -> String?
    )

    private fun scenarios(): List<Scenario> = listOf(

        // --- word building --------------------------------------------------------------

        Scenario("two swipes build one word",
            run = { swipe(it, "hel"); settle(); swipe(it, "lo") },
            check = { p ->
                if (p.words.size == 1) null else "expected one word, got '${p.word}'"
            }),

        Scenario("swipe then tap commits what is shown",
            run = { swipe(it, "bu"); settle(); type(it, "t") },
            check = { p -> if (p.lower == "but") null else "expected 'but', got '${p.word}'" }),

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

        Scenario("stroke undo replaces rather than appends",
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

        Scenario("slow tapping engages peck", needsSwipe = false,
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

        Scenario("strokes do not leak into the next word",
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
                run = {
                    swipe(it, "hel"); delay(gap); type(it, " "); delay(gap); swipe(it, "cat")
                },
                check = { p ->
                    if (p.words.size == 2) null else "word lost to the decode race: '${p.word}'"
                })
        }.toTypedArray(),

        Scenario("moving the cursor mid-word drops the session",
            run = {
                swipe(it, "hel"); settle(); type(it, " "); settle()
                swipe(it, "cat"); settle()
                moveCursorToStart(it); settle()
                swipe(it, "dog")
            },
            check = { p ->
                // Reads the whole field: the caret is at the start, so everything the earlier
                // strokes produced sits *after* it. What matters is that the new stroke composed a
                // fresh word instead of rewriting the one the closed session had anchored.
                val words = p.full.trim().split(Regex("\\s+")).filter { w -> w.isNotEmpty() }
                if (words.size >= 3) null
                else "session survived a cursor move, field is '${p.full.trim()}'"
            }),

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
            })
    )

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

        for (s in scenarios()) {
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

            if (outcome == null) {
                passed++
                sb.appendLine("[PASS] ${s.name}")
            } else {
                failed++
                sb.appendLine("[FAIL] ${s.name} - $outcome")
            }
            Log.d(TAG, sb.lines().let { it[it.size - 2] })
        }

        try { clearField(ime) } catch (e: Throwable) { /* leave the field as the run left it */ }

        val summary = "$passed passed, $failed failed" + if (skipped > 0) ", $skipped skipped" else ""
        sb.insert(0, "$summary\n\n")
        Log.d(TAG, "scenarios: $summary")
        return sb.toString()
    }
}
