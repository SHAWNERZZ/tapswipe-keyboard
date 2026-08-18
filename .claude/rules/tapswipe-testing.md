---
paths:
  - "tests/**"
  - "**/TapSwipeScenarios.kt"
  - "**/TapSwipe*Test*.kt"
  - "**/*Test*.java"
  - "java/src/org/futo/inputmethod/latin/tapswipe/**"
  - "java/src/org/futo/inputmethod/latin/inputlogic/InputLogic.java"
  - "java/src/org/futo/inputmethod/engine/general/GeneralIME.kt"
  - "java/src/org/futo/inputmethod/keyboard/PointerTracker.java"
  - "java/src/org/futo/inputmethod/keyboard/internal/BatchInputArbiter.java"
  - "TAPSWIPE_TESTING.md"
---

# TapSwipe testing rule

## Layer selection

Choose the smallest layer that can catch the change.

| Change area                                              | Layer            |
|----------------------------------------------------------|------------------|
| Pure logic and arithmetic                                | JVM unit tests   |
| `InputLogic`, `WordComposer`, `SwipeDecoderDictionary`, `GeneralIME`, `PointerTracker`, `BatchInputArbiter` | Scenario runner  |
| Rendering, layout, real finger paths                     | Manual checklist |
| Learned geometry across install                          | Release checklist|

## Fidelity rules for the scenario runner

1. Resolve codes from the live keyboard, not from the character asked.
   Capture the code before the press.
2. Send press, code, release. `KeyboardState.onEvent` ignores
   `CODE_SHIFT`.
3. Send coordinates only where the real path does. `PointerTracker`
   passes `NOT_A_COORDINATE` unless the key has proximity correction.
   `onCodeInput` subtracts view padding.
4. Assert the invariant, not the decoder's output.

## What the runner cannot test

- Real `MotionEvent`s. The runner drives `LatinIMELegacy` method calls
  directly.
- Backspace slide granularity across a real finger.
- The comma pull from V onto the space bar.
- Enter-key direction assignments as actual flicks.
- Peck borders, Master Mode dots, and other rendering.

Flag device-only checks in your response. Do not claim the change is
verified when only the runner ran.

## Adding a scenario

- Follow the four fidelity rules.
- Add the scenario to `scenarios()` with a `group`.
- Wrap in `run { var captured = ""; Scenario(...) }` for mid-run state.
- Use `knownLimitation` for acceptable failures. Do not delete
  scenarios.

## Do not

- Write scenario counts into the introduction of `TAPSWIPE_TESTING.md`.
  Counts drift.
- Add unit tests for code that requires a live `InputConnection`.
  Instead, add a scenario.
- Silently disable a scenario that starts to fail. Diagnose first.
