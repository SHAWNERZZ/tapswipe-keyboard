# TapSwipe - testing

## Layers

| Layer | How to run | Device | Status |
|---|---|---|---|
| JVM unit tests | `./gradlew testUnstableDebugUnitTest` | none | **working** - 123 tests, seconds |
| In-app scenario runner | Memory Debug action -> **Run scenarios** | phone | **working** - 65 scenarios, ~3 min |
| Manual matrix | the checklist below | phone | for anything the runner cannot reach |

## Why the layers differ in value

Of the eleven bugs fixed so far, nearly all were **sequencing** bugs - the seam between evidence
changing and a derived result being applied - not logic errors in isolated functions.

- Unit tests over `TapSwipeSession` would have caught roughly three: the `hasComposed`/anchor
  coupling behind "ButBy", the missing generation bump on pop, and validate firing before the first
  composing write.
- End-to-end scenarios would have caught roughly nine, because they run the real `InputLogic`
  against a real editor.

So the end-to-end layer is where the value is — for the *session*. That was the whole feature set at
the time.

The gesture and layout work since then inverted it. Stroke geometry, the enter-key assignment and its
storage, the bottom-row shape, and the backspace-slide state machine are all arithmetic over inputs
that can be written down, so they are unit-tested, and those tests have caught real mistakes cheaply.
The split is deliberate and shows up in the code: each of them separates a pure rule from the part
that needs a live `Keyboard` or native proximity code, purely so the risky half runs without a device.

The limit is worth recording, because the one bug that shipped in that work landed exactly on it.
`BackspaceSlideMode`'s tests were correct and stayed correct; the defect was a stale boolean one file
downstream in `GeneralIME`, which needs a live `InputConnection` to instantiate and so has no unit
coverage at all. Pure-function tests verify the rule, not that anyone honours the answer — still the
seam between evidence changing and a derived result being applied, which is where nearly every bug in
this fork has lived.

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

## Manual checklist

The runner now covers 46 cases across word building, backspace, modes, session hygiene,
auto-capitalisation, punctuation and everyday sentences. Those are no longer listed here - run it
instead of reading them.

What is left needs a human, because it needs a second app, a settings change, or an eye on the
keyboard rather than the text:

### Needs another app or field type
- [ ] Behaves in an app using the emulated-composing connection (`ICPatched`)
- [ ] Switching text fields mid-word does not carry evidence across

The **Field policy** group covers the rest, and covers it wherever it runs: those cases read the live
`EditorInfo` and assert the behaviour correct *for that field*, reporting which branch they took. So
running the suite from a password field is itself the password-field test - open the keyboard's
Text Edit Variations page (Settings -> Developer), focus a field, and run.

### Needs looking at the keyboard, not the text
- [ ] Peck borders appear when peck engages
- [ ] Master Mode dots render, and the quick-action button toggles it
- [ ] No visible mode flicker mid-word

Master Mode's letter hiding and peck's reveal are now automated; only the actual pixels are manual.

### Needs state that survives a restart
- [ ] A peck-committed out-of-dictionary word is swipeable afterwards
- [ ] Learned geometry survives a keyboard restart

### Check on every release, before publishing

- [ ] **Learned geometry survives installing the new APK over the old one.** Note the sample count
      on the Learned key geometry page, install, reopen the page, compare.

This one is worth a line of its own because it was reported lost across the v0.7.0 update and the
cause was never established. The investigation ruled out the layout key, the signing key, and any
change to the persistence code — the diff was additive — so what remains is either a mechanism not
yet identified or something outside the app. Either way it is unproven, so it gets re-checked every
time rather than assumed fixed.

What v0.7.1 changed is the *blast radius*: a failed read can no longer overwrite the file, an empty
model cannot replace a real one unasked, there is a known-good backup to fall back on, and teardown
flushes synchronously instead of posting to a scope about to be cancelled. If it recurs, the logcat
tag `TapSwipeTouchModel` now reports sample counts and bucket names on every load, and says
explicitly when it refuses a write — which is the evidence the first investigation did not have.

### Needs a real finger on real hardware
- [ ] Backspace slide deletes at the granularity swipe-to-delete is set to (Settings -> Backspace)
- [ ] Reversing past a full step switches to the other granularity for the rest of that slide -
      **test with the setting on Words**, see the note below
- [ ] Reversing a second time does not switch back
- [ ] A reversal too small to cross a full step changes nothing
- [ ] Swipe straight down from V onto the space bar produces a comma, with Nintype gestures on
- [ ] A word swipe starting on V, B or C still decodes as a word
- [ ] Each assigned enter-key direction fires; a plain tap is still enter
- [ ] With the period key hidden, the enter key is wider and the space bar is not

These are out of the runner's reach on principle, not just for now: they live in `PointerTracker`'s
raw touch state machine, and the runner drives the keyboard through `LatinIMELegacy` method calls
directly, never through real `MotionEvent`s. There is nothing to synthesize a touch-down/slide
sequence against.

**Test the reversal with the base set to Words specifically.** The one bug this feature shipped with
was invisible in the Characters case: `GeneralIME.onMoveDeletePointer` computed word-mode as
`mBackspaceMode == WORDS || isActiveSlideWordMode()`, so with the base on Words the first term was
always true and a reversal *into* character mode had no effect. The mirror case worked fine. A
setting that makes one direction of a two-way switch untestable is worth naming in the checklist.

## Adding a scenario

Follow the four rules above - resolve codes from the live keyboard, send press/code/release,
send coordinates only where the real path does, and assert the invariant rather than the decoder's
output. Then add a `Scenario` to `scenarios()` with a `group`, and if it needs to compare against
mid-run state, wrap it in `run { var captured = ""; Scenario(...) }`.

If a case is expected to fail for a reason that has been judged acceptable, set `knownLimitation`
rather than deleting it, so the boundary stays measured.
