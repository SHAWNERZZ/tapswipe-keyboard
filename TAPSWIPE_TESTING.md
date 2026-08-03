# TapSwipe - testing

## Layers

| Layer | How to run | Device | Status |
|---|---|---|---|
| JVM unit tests | `./gradlew testUnstableDebugUnitTest` | none | **working** - 18 tests, ~5s |
| In-app scenario runner | Memory Debug action -> **Run scenarios** | phone | **working** - 23 scenarios |
| Manual matrix | the checklist below | phone | for anything the runner cannot reach |

## Why the layers differ in value

Of the eleven bugs fixed so far, nearly all were **sequencing** bugs - the seam between evidence
changing and a derived result being applied - not logic errors in isolated functions.

- Unit tests over `TapSwipeSession` would have caught roughly three: the `hasComposed`/anchor
  coupling behind "ButBy", the missing generation bump on pop, and validate firing before the first
  composing write.
- End-to-end scenarios would have caught roughly nine, because they run the real `InputLogic`
  against a real editor.

So the end-to-end layer is where the value is.

## The in-app scenario runner

`java/src/org/futo/inputmethod/latin/tapswipe/TapSwipeScenarios.kt`.

Open any scratch text field, switch to the Memory Debug action, and press **Run scenarios**. It
drives `LatinIMELegacy.onCodeInput` / `onStartBatchInput` / `onEndBatchInput` - the same entry points
a finger reaches - and reads the result back through the input connection. **Copy scenario results**
puts the report on the clipboard; it also goes to logcat under `TapSwipeScenarios`.

Each scenario clears the field first, so a failure cannot cascade. Every IME call is marshalled to
the main thread with the waiting done off it, so a run cannot ANR.

Reading a report:

- `PASS` / `FAIL <reason>` - the reason names the expected and actual text.
- `SKIP` - no keyboard layout has been resolved yet. Swipe one word, then run again.
- `ABORT` - no focused field, or the TapSwipe input model is switched off.

Note the checks are deliberately loose about *which* word the decoder produced. The models are
non-deterministic across layouts and personalisation; asserting exact output would produce failures
that mean nothing. What they assert is structure: one word not two, replaced not appended, verbatim
not corrected, capital preserved.

### Why not the AOSP instrumented harness

`tests/src/.../InputTestsBase.java` survives in this fork, and three real blockers in it were fixed
along the way (a `LifecycleRegistry` main-thread assertion, a `WindowInfoTracker` crash that took the
process down, and `gesture()` building no gesture segments at all - so tests written against it
would have passed while exercising nothing). Two of those were genuine robustness fixes and were
kept.

It still does not run: `MainKeyboardView` inflation cannot resolve a `DynamicThemeProviderOwner`
from the test context, and **stock `InputLogicTests` hangs identically**. The harness assumes a
threading model this fork left behind when it moved to coroutines and Compose. Chasing it further
was not worth it against a runner that exercises the real IME.

## What the runner has actually found

Worth recording, because the pattern is consistent: **most failures so far were the harness lying,
not the keyboard breaking.** A synthetic driver that skips a layer reports green on everything that
layer controls.

| Reported as | Actually was |
|---|---|
| auto-capitalisation broken | harness sent literal lowercase; real taps send `key.getCode()` from the live keyboard, which is uppercase on a shifted layout |
| manual shift stuck on (`ABC`) | shift was fine - the trace showed the layout return to base after the first letter. `ABC` is a dictionary entry, so autocorrect uppercased `Abc` |
| session survived a cursor move | it did not; the check read `getTextBeforeCursor` in a scenario that parks the caret at position 0 |
| swipe-then-tap committed the wrong word | the decoder picked a different word for an ambiguous two-key swipe. The check was asserting the word instead of the invariant |
| word lost on swipe/space/swipe | real, but the window is under 50ms - see below |

Three fidelity rules the harness has to keep, each learned by getting it wrong:

1. **Resolve the code from the live keyboard, not from the character asked for.** A shifted layout
   carries uppercase codes, so this is the only way capitalisation is exercised at all.
2. **Send press, code, release.** The shift state machine lives entirely in `onPressKey` /
   `onReleaseKey`; `KeyboardState.onEvent` ignores `CODE_SHIFT`. Capture the code *before* the
   press, as `PointerTracker` does, since the press can flip the layout.
3. **Send coordinates only where the real path does.** `PointerTracker` passes `NOT_A_COORDINATE`
   unless the key has proximity correction, and `onCodeInput` subtracts the view padding - so raw
   key centres land offset, and functional keys otherwise carry a spurious position into the
   session.

And one rule for the assertions: **assert the invariant, not the decoder's output.** The models are
non-deterministic and short gestures are genuinely ambiguous. "The committed word equals what was
displayed" is the property the but/by bug violated; "the committed word is `but`" is a test of the
decoder's taste, and it flakes.

## Known limitation: the swipe/space decode race

Hitting space within ~50ms of finishing a swipe loses the word. The decode runs on a background
thread; space reaches the main thread first, `onUpdateTailBatchInputCompleted` finds the session
closed and drops the result, and since nothing had been composed yet the word never reaches the
editor at all.

Measured with a ladder at 0/50/100/200/350ms: lost at 0ms, safe from 50ms up. A thumb needs 150ms+
just to travel to the space bar, so no finger can reach it. The fix would be holding separators
until the decode lands - new state in the path that produced the ButBy and but/by regressions - and
that is not a trade worth making for a window nobody can hit.

The ladder still runs and reports `KNOWN` rather than `FAIL`. If the 50ms rung ever fails, decoding
got slower and this judgement needs revisiting.

## Scenario matrix

Each row is a bug that shipped, or an edge case identified while tracing. Until the instrumented
layer runs, these are the manual checklist before calling a feature done.

### Word building
- [ ] Two swipes then space build one word, not two
- [ ] Swipe then tap commits what is displayed, not the pre-tap word
- [ ] Tap then swipe keeps the tapped prefix
- [ ] Lifting a finger never commits
- [ ] Space, punctuation and Enter each finalize
- [ ] Sentence start stays capitalised across two strokes
- [ ] Two thumbs swiping at once compose one word

### Modes
- [ ] Slow tapping engages peck: no autocorrect, borders, letters return under Master Mode
- [ ] Fast tapping does not engage peck
- [ ] Five quick taps engage legacy typing; a swipe leaves it again
- [ ] Peck-committed out-of-dictionary word is swipeable afterwards
- [ ] Mode is latched per word — no flicker mid-word
- [ ] Key boosting is suppressed during peck

### Backspace
- [ ] Undo in a swiped word pops one stroke and re-decodes, without appending
- [ ] Undo in a pecked word removes one character
- [ ] Undoing every stroke clears the word and leaves nothing
- [ ] Peck, backspace, then swipe still fuses the earlier taps
- [ ] Whole-word delete (when enabled) takes one trailing space with the word
- [ ] Autocorrect undo, double-space period and inserted text keep priority over whole-word delete
- [ ] Hold-to-delete still honours the existing setting

### Session hygiene — the runaway-word class
- [ ] Strokes never leak into the next word
- [ ] Cursor move mid-word drops the session
- [ ] Rapid swipe / space / swipe produces two distinct words
- [ ] A word never grows past the stroke cap
- [ ] Switching text fields mid-word does not carry evidence across

### Interop
- [ ] Disabling TapSwipe restores stock behaviour exactly
- [ ] Password and no-suggestion fields behave as stock
- [ ] Behaves in an app using the emulated-composing connection (`ICPatched`)
