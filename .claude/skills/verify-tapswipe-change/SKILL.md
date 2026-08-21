---
name: verify-tapswipe-change
description: Pick the right test layer for a TapSwipe change, run it, and report what still needs a device.
---

# verify-tapswipe-change

Use this after a code change that touches TapSwipe behavior. Do not use
this for pure documentation changes.

## Step 1. Classify the change

Match the change to one of these areas:

- Pure logic (`TapSwipeSession`, `TapSwipeInputBuilder`, `TapSwipeMode`,
  `BackspaceSlideMode`, gesture arithmetic).
- IME plumbing (`InputLogic`, `WordComposer`, `SwipeDecoderDictionary`,
  `GeneralIME`, `PointerTracker`, `BatchInputArbiter`).
- Rendering, layout, key gestures, real finger paths.
- Learned geometry, persistence across install.

## Step 2. Run the smallest sufficient layer

- Pure logic: run `./gradlew testUnstableDebugUnitTest`.
- IME plumbing: request the scenario runner report. Ask the user to
  press **Run scenarios** and paste the report.
- Rendering or real-finger cases: request a manual check from
  `TAPSWIPE_TESTING.md`.
- Learned geometry: list the release-checklist items.

## Step 3. Report

Always include four sections in the report.

1. **What ran and passed.**
2. **What ran and failed.** Copy the exact `FAIL <reason>` lines.
3. **What still needs a device.** List the specific items from
   `TAPSWIPE_TESTING.md`.
4. **What still needs another app or field.** For example, `ICPatched`
   apps, password fields, mid-word field switch.

## Do not

- Claim verification when only the unit tests ran and the change
  touches `InputLogic`.
- Add scenario counts into the report unless copied from live output.
- Skip the manual list when the change touches rendering, layout, real
  finger paths, or persistence across install.

<!-- TODO(claude-code): if the codebase adds a new test layer, list it
  here in the same shape. -->
