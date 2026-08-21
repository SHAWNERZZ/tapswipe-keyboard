# TapSwipe testing

## Layers

| Layer            | Entry point                                | Device |
|------------------|--------------------------------------------|--------|
| JVM unit tests   | `./gradlew testUnstableDebugUnitTest`      | no     |
| Scenario runner  | Memory Debug action → **Run scenarios**    | yes    |
| Manual checklist | The checklist below                        | yes    |

Do not record scenario counts here. They drift. Use the runner output.

## When to run what

Choose the smallest layer that can catch the change.

| Change area                                                                                                       | Layer                                        |
|-------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| Pure logic in `TapSwipeSession`, `TapSwipeInputBuilder`, `TapSwipeMode`, `BackspaceSlideMode`, gesture arithmetic  | JVM unit tests                               |
| Anything that touches `InputLogic`, `WordComposer`, `SwipeDecoderDictionary`, `GeneralIME`, `PointerTracker`, `BatchInputArbiter` | Scenario runner                              |
| Rendering, borders, mode indicators, key layout, real finger paths                                                | Manual checklist                             |
| Learned geometry, persistence across install                                                                      | Release checklist                            |

## Why the layers differ in value

Most defects in this fork are sequencing bugs at the seam between
evidence changing and a derived result being applied. Unit tests catch
arithmetic reliably. End-to-end scenarios catch the seams. Pure-function
tests verify the rule, not that the caller honors the answer.

## What the runner has actually found

Most failures so far came from the harness, not from the keyboard. A
synthetic driver that skips a layer reports green on everything that
layer controls. Check the harness before you trust a failure.

| Reported as | Actually was |
|---|---|
| auto-capitalisation broken | harness sent literal lowercase. Real taps send `key.getCode()` from the live keyboard, which is uppercase on a shifted layout |
| manual shift stuck on (`ABC`) | shift was correct. `ABC` is a dictionary entry, so autocorrect uppercased `Abc` |
| session survived a cursor move | it did not. The check read `getTextBeforeCursor` in a scenario that parks the caret at position 0 |
| swipe-then-tap committed the wrong word | the decoder picked a different word for an ambiguous two-key swipe. The check asserted the word instead of the invariant |
| word lost on swipe/space/swipe | real. The window is under 50 ms. See the known limitation below |

## The in-app scenario runner

File: `java/src/org/futo/inputmethod/latin/tapswipe/TapSwipeScenarios.kt`.
Path confirmed on 2026-08-18. The directory is lowercase `tapswipe`.

Steps:

1. Open a scratch text field.
2. Switch to the Memory Debug action.
3. Press **Run scenarios**.

Read the report:

- `PASS` or `FAIL <reason>`. `FAIL` names the expected and actual text.
- `SKIP` means no keyboard layout has resolved yet. Swipe one word, then
  run again.
- `ABORT` means no focused field, or the TapSwipe model is off.

Each scenario clears the field first, so failures do not cascade. IME
calls marshal to the main thread, so a run cannot ANR.

Assertions test the invariant, not the decoder's output. Models are
non-deterministic. Assert "one word not two", "replaced not appended",
"capital preserved", not "the word is `but`".

## Four fidelity rules for the runner

1. Resolve codes from the live keyboard, not from the character asked
   for. Capture the code before the press, as `PointerTracker` does.
2. Send press, code, release. `KeyboardState.onEvent` ignores
   `CODE_SHIFT`.
3. Send coordinates only where the real path does. `PointerTracker`
   passes `NOT_A_COORDINATE` unless the key has proximity correction.
   `onCodeInput` subtracts view padding.
4. Assert the invariant, not the decoder's output.

## Known limitation: swipe/space decode race

Space within about 50 ms of a swipe end loses the word. Measured with a
ladder at 0, 50, 100, 200, 350 ms. Lost at 0 ms, safe from 50 ms up. A
thumb needs more than 150 ms to reach the space bar. The scenario
reports `KNOWN`, not `FAIL`. Revisit if the 50 ms rung ever fails.

## Manual checklist

### Another app or field type

- [ ] Behaves in an app using `ICPatched` (`InputConnectionInternalComposingWrapper`).
- [ ] Switching text fields mid-word does not carry evidence across.

The **Field policy** scenario group covers per-field behavior. Run it
from a password field to test password-field behavior. Open Settings →
Developer → Text Edit Variations, focus a field, then run.

### Looking at the keyboard, not the text

- [ ] Peck borders appear when peck engages.
- [ ] Master Mode dots render. The action-bar button toggles it.
- [ ] No visible mode flicker mid-word.

### State that survives a restart

- [ ] A peck-committed out-of-dictionary word is swipeable after
      restart.
- [ ] Learned geometry survives a keyboard restart.

### Release checklist

- [ ] Learned geometry survives installing a new APK over an old one.
      Note the sample count on the Learned key geometry page, install,
      reopen the page, compare.

This one is worth its own line. It was reported lost across v0.7.0. The
cause was never established. v0.7.1 reduced blast radius and added
logging under `TapSwipeTouchModel`.

### Real finger on real hardware

- [ ] Backspace slide deletes at the granularity Settings → Backspace
      names.
- [ ] Reversing past a full step switches for the rest of the slide.
      Test with base mode set to Words.
- [ ] Reversing a second time does not switch back.
- [ ] A reversal below a full step changes nothing.
- [ ] Swipe straight down from V onto the space bar produces a comma
      (Nintype gestures on).
- [ ] A word swipe starting on V, B, or C still decodes as a word.
- [ ] Each assigned enter-key direction fires. A plain tap is still
      enter.
- [ ] With the period key hidden, Enter is wider and the space bar is
      not.

Test the reversal with base mode Words. The one shipped bug in this
feature was invisible with base mode Characters. `GeneralIME.onMoveDelete
Pointer` used `mBackspaceMode == WORDS || isActiveSlideWordMode()`, so
the first term made reversal into Characters a no-op.

## Adding a scenario

1. Follow the four fidelity rules.
2. Add a `Scenario` to `scenarios()` with a `group`.
3. Wrap in `run { var captured = ""; Scenario(...) }` if the scenario
   needs mid-run state.
4. Set `knownLimitation` for acceptable failures. Do not delete the
   scenario. The boundary must stay measured.

## Not used: AOSP instrumented harness

`tests/src/.../InputTestsBase.java` is retained. Three real robustness
fixes were kept. It still does not run: `MainKeyboardView` inflation
cannot resolve `DynamicThemeProviderOwner` in the test context, and stock
`InputLogicTests` hangs. The harness assumes a threading model this fork
left when it moved to coroutines and Compose. The scenario runner covers
the ground it was intended to cover, and covers it better.

<!-- TODO(claude-code): if you re-enable the AOSP harness, update this
  section. -->
