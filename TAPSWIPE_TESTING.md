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
