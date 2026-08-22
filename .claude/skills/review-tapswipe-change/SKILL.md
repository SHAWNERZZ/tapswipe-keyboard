---
name: review-tapswipe-change
description: Review a TapSwipe change against the architecture, the recurring bug class, and the current invariants.
---

# review-tapswipe-change

Use this before merging or accepting a TapSwipe change.

## Checklist

1. **Read the diff.** Understand what evidence changes and what derived
   result is affected.

2. **Compare with `TAPSWIPE_ARCHITECTURE.md`.** Behavior claims must
   match the architecture doc, or the doc must be updated in the same
   change.

3. **Do not use `docs/history/` as a source of truth.** Use it only
   for historical rationale. Never treat an original checklist or a
   completed phase as pending work.

4. **Apply the seam checklist.**
   - Name the evidence and the derived result.
   - Name the invariant that connects them.
   - Name the transition window in which the invariant is legitimately
     violated. Explain how the code stays correct in that window.

5. **Check the guards.**
   - Generation counter bumped on reset and on stroke pop.
   - Cursor anchor updated only after a successful composing write.
   - Re-entrancy flag set during apply.
   - Deep copy of `GestureSegment` at the session boundary.

6. **Check the finalizers.** Space, existing separator punctuation, and
   Enter all commit and clear the session. ANTIPHANTOM must not drop a
   finalizer.

7. **Check modes.** A swipe forces `SWIPE`. Peck is per-word and
   latched. `LEGACY_TAP` is a run counted across words.

8. **Check verification.** Run `verify-tapswipe-change`. Do not accept
   the change without the report.

## Report format

Return findings by severity. Include file paths and symbols. Do not
edit files.

- **Blocking.** invariant broken, seam unhandled, or ships a change to
  stock behavior for non-TapSwipe users without a note.
- **High.** missing verification for a change area that has device-only
  coverage. Documentation contradicts code.
- **Nit.** style, naming, or comment.

## Do not

- Approve based on the pure-logic tests alone if the change touches
  `InputLogic` or the touch layer.
- Trust a validate-on-read call inside an apply path.
- Merge without updating `TAPSWIPE_ARCHITECTURE.md` when behavior
  changes.

<!-- TODO(claude-code): if new symbol names appear in the code, add them
  to the invariants checklist. -->
