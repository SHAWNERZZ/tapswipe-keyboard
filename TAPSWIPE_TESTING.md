# TapSwipe — testing

## Layers

| Layer | Command | Device | Status |
|---|---|---|---|
| JVM unit tests | `./gradlew testUnstableDebugUnitTest` | none | **working** — 18 tests, ~5s |
| Scenario matrix | this document | none | **working** — checklist below |
| Instrumented scenarios | `./gradlew connectedUnstableDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.futo.inputmethod.latin.TapSwipeInputTests` | emulator | **blocked** — see below |

## Why the layers differ in value

Of the eleven bugs fixed so far, nearly all were **sequencing** bugs — the seam between evidence
changing and a derived result being applied — not logic errors in isolated functions.

- Unit tests over `TapSwipeSession` would have caught roughly three: the `hasComposed`/anchor
  coupling behind "ButBy", the missing generation bump on pop, and validate firing before the first
  composing write.
- Instrumented scenarios would have caught roughly nine, because they run the real `InputLogic`
  against a real editor.

So the instrumented layer is where the value is, and it is the one currently blocked.

## Instrumented harness: state and blockers

The AOSP `InputTestsBase` harness survives in this fork (`tests/src/.../InputTestsBase.java`) with
`type()`, `gesture()` and a real `EditText`. **Its existing tests do not pass on this fork either** —
`InputLogicTests` fails identically, so this is upstream rot, not something TapSwipe broke.

Fixed so far:

1. **Lifecycle main-thread assertion.** FUTO made the IME a `LifecycleOwner`; `ServiceTestCase`
   creates the service on the test thread, so `LifecycleRegistry` threw. Added a test-only
   `InputMethodServiceCompose.unsafeLifecycleForTests` flag using `LifecycleRegistry.createUnsafe`.
2. **Fold tracking crashed the process.** `WindowInfoTracker.windowLayoutInfo` needs a real window;
   under test it threw from inside the collector and killed the run after two tests. Now guarded —
   a genuine robustness fix, since a cosmetic feature should never take the process down.
3. **`gesture()` built no segments.** It used `addPointer`, which fills only the flat coordinate
   arrays; every TapSwipe path reads `gestureSegments`. Tests written against it would have passed
   while exercising nothing. Now opens a segment with `onPointerDown` and appends through `append`.

Still blocking: **`MainKeyboardView` inflation**. `MoreKeysKeyboardView` cannot resolve a
`DynamicThemeProviderOwner` from its context. `obtainFromContext` now walks the entire
`ContextWrapper` chain rather than one level (also a genuine improvement), but the context used for
that nested inflate has no owner anywhere in its chain. Next step is to find what context
`MainKeyboardView.java:268` inflates with and give it an owner — likely by having the harness supply
a themed wrapper around the service.

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
