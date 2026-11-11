# TODO

Read this when a session starts. Update it when you start, finish, or
drop a task. Date each Done entry.

## Now

- Typing simulator, `docs/plans/TYPING_SIMULATOR.md`. Agreed in outline
  2026-11-11, with the user's addition of a typing test mode. Step 1 is
  the typing test mode and its recorder.
- The user's settings export is at
  `/Documents/Notes/FUTOKeyboardSettings_2026-11-11_09-14-06.backup`.
  Private. Never copy it into the repository.

## Next

- Word split, open items. Full list in `docs/plans/WORD_SPLIT.md`.
  - Words split with the setting off. Suspect: the phantom space after
    the session drops. Needs the `autoSpaces` and `lastReset` counts
    from the debug panel, read on a device after the symptom.
  - Wire n-gram probability through the `Dictionary` base class.
  - Offer the losing reading in the suggestion strip.
  - Decode the gesture alone for taps-then-gesture words (`Ivar`).
  - Decide: capitalize a split `i`, what to learn, undo granularity.
- Reported bug A: a word built from parts commits wrong, and one
  backspace returns the intended word. Diagnose with the simulator.
- Reported bug B: sequential strokes decode worse than a two-finger
  swipe. Measure with the decoder bench.
- `CLAUDE.md` is 190 lines. The shared rule says under 100. Propose
  cuts to the user before editing.
- Delete `MIGRATION.md` and `tapswipe-claude-v2.zip` from the main
  checkout if the user agrees. Both are leftovers from 2026-08-18.
- Install `git-lfs` on the server, so a fresh clone gets the swipe
  models, and `java/assets/futo-swipe` stops showing as modified.

## Done

- 2026-11-11. Cleaned the main checkout. Backed it up first to
  `/Documents/code-claude/mobile/tapswipe-keyboard-backup-2026-11-11.zip`.
  Discarded line-ending churn in the repository and in five
  submodules. Undid the layouts submodule edits. Repointed the
  voice-model submodule. Fast-forwarded `dev`. Set `local.properties`
  to `/opt/android-sdk`.
- 2026-11-11. Made the apostrophe layout a TapSwipe setting
  (`TapSwipeApostropheKeySetting`). Moved the file out of the layouts
  submodule into `java/assets-tapswipe/`. One-time migration for users
  who picked it as a language layout. Needs a device check.
- 2026-11-11. Ran the JVM unit tests on Linux. All 284 pass.
- 2026-11-11. Set up the Linux build. Installed SDK platform 35, NDK
  28.2.13676358, and CMake 3.22.1 in `/opt/android-sdk`.
- 2026-11-11. Committed the uncommitted word-split work from the
  Windows checkout, unchanged, with LF line endings.
- 2026-11-11. Created `TODO.md` and `docs/decisions.md`.
- 2026-08-22. Released `tapswipe-v0.8.0`, the backspace redesign.
