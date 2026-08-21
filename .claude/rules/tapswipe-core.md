---
paths:
  - "java/src/org/futo/inputmethod/latin/tapswipe/**"
  - "java/src/org/futo/inputmethod/latin/inputlogic/InputLogic.java"
  - "java/src/org/futo/inputmethod/latin/WordComposer.java"
  - "java/src/org/futo/inputmethod/latin/Suggest.java"
  - "java/src/org/futo/inputmethod/latin/LatinIMELegacy.java"
  - "java/src/org/futo/inputmethod/engine/general/GeneralIME.kt"
  - "java/src/org/futo/inputmethod/keyboard/PointerTracker.java"
  - "java/src/org/futo/inputmethod/keyboard/internal/BatchInputArbiter.java"
  - "java/src/org/futo/inputmethod/keyboard/internal/BackspaceSlideMode.kt"
  - "java/src/org/futo/inputmethod/latin/DictionaryFacilitatorImpl.java"
  - "**/SwipeDecoderDictionary.kt"
---

# TapSwipe core rule

## Design invariants

1. `TapSwipeSession` owns evidence. `WordComposer` owns the current
   candidate text. Do not merge these roles.
2. Every decode carries the session generation it was computed for.
   Apply drops any result whose generation is stale, or whose session
   has closed.
3. Deep copy `GestureSegment` values at the session boundary.
   `InputPointers` and its segments are not thread safe.
4. A finalizer (space, existing separator punctuation, Enter) always
   commits, learns, and clears the session. The ANTIPHANTOM space
   swallow must not drop a finalizer while a session is open.
5. Backspace has two tiers. Session open pops the last stroke and
   re-decodes. Session closed deletes the whole previous word only when
   `TapSwipeWholeWordBackspaceSetting` is on, after every one-press
   revert branch.
6. Modes latch per word. Only a swipe forces a word back to `SWIPE`
   mode. `LEGACY_TAP` is a run counted across words.
7. Do not modify submodules. Do not modify the swipe library `.aar`.

## The recurring bug class

Nearly every defect in this fork lives in the seam between when
evidence changes and when a derived result is applied.

Before you add or change an invariant:

1. Name the evidence and the derived result.
2. Name the invariant that connects them.
3. Name the transition window in which the invariant is legitimately
   violated. Explain how the code stays correct in that window.

Do not paste new invariants into `tapSwipeSession()` without applying
this checklist.

## Preferred coordinate conventions

- Tap position for decode: `SwipeDecoderDictionary.normalizedKeyPosition`
  from the applied layout, not from touch coordinates.
- Tap position for personalization training: raw touch point, captured
  separately from decode input.
- Swipe segments: `MotionEvent.getEventTime()` on the
  `SystemClock.uptimeMillis()` clock. Do not mix `System.currentTimeMillis`.

## Do not

- Reintroduce `commitCurrentAutoCorrection` in `onStartBatchInput` when
  a session is open.
- Call `finishComposingText` inside `onUpdateTailBatchInputCompleted`
  while a session is open.
- Read the session with validation from inside the apply path. Use the
  re-entrancy guard.
- Store cursor anchors past the current session lifetime.
- Change stock behavior for non-TapSwipe users without an explicit note.

## Symbols to prefer over line numbers

Use these anchors when referencing code. Line numbers drift.

Checked on 2026-08-18. Every symbol below exists.

- `InputLogic.handleSeparatorEvent`
- `InputLogic.handleNonSeparatorEvent`
- `InputLogic.onStartBatchInput`
- `InputLogic.applyTapSwipeCandidate`
- `InputLogic.commitChosenWord`
- `WordComposer.setBatchInputWord`
- `WordComposer.setComposingTextInternal`
- `SwipeDecoderDictionary.decodeTapSwipe`
- `SwipeDecoderDictionary.normalizedKeyPosition`
- `TapSwipeSession.popLastStroke`
- `TapSwipeSession.validateOrReset`
- `TapSwipeSession.noteComposingWrite`
- `BatchInputArbiter.getBatchOriginTime`
- `BackspaceSlideMode.next`
- `BackspaceSlideMode.chargeWord`
- `GeneralIME.cursorStepped`

Two symbols are easy to place on the wrong class.

- `onUpdateTailBatchInputCompleted` lives on `GeneralIME`. It does not
  live on `InputLogic`.
- `PointerTracker` has no public `onCodeInput`. The private method is
  `callListenerOnCodeInput`. Reference that name, or reference
  `PointerTracker.onMoveEventInternal` for touch-path work.
