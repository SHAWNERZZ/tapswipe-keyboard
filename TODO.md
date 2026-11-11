# TODO

Read this when a session starts. Update it when you start, finish, or
drop a task. Date each Done entry.

## Now

- Typing simulator. Plan written in `docs/plans/TYPING_SIMULATOR.md`.
  Waiting on two things from the user: agreement on the plan, and an
  export of the app settings (TapSwipe settings, Export).

## Next

- **The Nintype apostrophe layout is not in git.** It exists only as
  uncommitted edits in the `java/assets/layouts` submodule of the main
  checkout: a new `Default/qwerty_nintype.yaml` and three lines in
  `mapping.yaml`. A build from git drops the layout, and 8 tests in
  `QwertyNintypeLayoutTest` fail. Decide where it lives. Options: fork
  the layouts repository, or move the file into a path this repository
  owns and merge it at build time.
- Merge branch `worktree-linux-setup-and-sim-plan` into `dev`.
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
- `CLAUDE.md` is 169 lines. The shared rule says under 100. Propose
  cuts to the user before editing.
- Delete `MIGRATION.md` and `tapswipe-claude-v2.zip` from the main
  checkout if the user agrees. Both are leftovers from 2026-08-18.
- Install `git-lfs` on the server, so a fresh clone gets the swipe
  models.

## Done

- 2026-11-11. Ran the JVM unit tests on Linux. 278 of 278 pass when
  the Nintype layout files are copied in. Without them, 270 pass.
- 2026-11-11. Set up the Linux build. Installed SDK platform 35, NDK
  28.2.13676358, and CMake 3.22.1 in `/opt/android-sdk`.
- 2026-11-11. Committed the uncommitted word-split work from the
  Windows checkout, unchanged, with LF line endings.
- 2026-11-11. Created `TODO.md` and `docs/decisions.md`.
- 2026-08-22. Released `tapswipe-v0.8.0`, the backspace redesign.
