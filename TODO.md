# TODO

Read this when a session starts. Update it when you start, finish, or
drop a task. Date each Done entry.

## Now

- Phone use of the current dev APK (auto space, tapped short words, peck
  by pause, backspace space-first, capitalization fixes). Report what
  feels off; a typing test with a `text-*` list gives numbers.

## Next

Ordered by what the user is likely to hit most.

- **Stroke undo after a commit re-decodes to the same word.** `cats`,
  two taps of backspace (space, then the last stroke), gives `cats`
  again, and `think` the same. Likely cause: the commit learned the word,
  and the swipe decoder still favors it after the unlearn. Seen in
  `edge_check.py`, "second backspace pops last stroke". Check before
  changing.
- **A pause inside a word splits it** (auto space). About 1 in 40 long
  words in the user's recordings (`celebration`). Planned fix: the rejoin
  layer in `docs/plans/AUTO_SPACING.md`.
- **The losing reading in the suggestion strip** after a split or a
  join, so one tap fixes a wrong call (`about` read as `a bit`).
- **Single swipes of short words** are the user's weakest case (75 to
  77% first try in earlier tests). Ranking errors (`Wang`, `van`, `can`)
  come from the right thumb landing low. Two candidate fixes, both
  measured in replay, neither built:
  - Session offset layer (waiting on the user): a correction from the
    last 60 taps. `docs/plans/TYPING_SIMULATOR.md`, "Calibrated offsets".
  - Calibration offsets: held-out check scored 77 against 80, so not
    shown to help yet. Rerun with adaptive geometry off.
- **`LAST_STROKE_FLIP`**: the final tap of a swipe-then-tap word can turn
  a right decode into a wrong one. Needs a design,
  `docs/plans/TYPING_SIMULATOR.md`, Results.
- **New swipe language model** `chosen_planarian`: blocked until FUTO
  ships a swipe library with its scoring weights. `docs/decisions.md`,
  2026-11-12, "Merge FUTO upstream".

Smaller and housekeeping:

- Wire n-gram probability through the `Dictionary` base class (a second
  opinion for the segmenter; the context model already scores pairs).
- Two-thumb overlap: the user's first recording read 86% overlapping vs
  92% one at a time. Recheck with more data. Improve the simulator's
  two-thumb model (each thumb on its own side).
- Recheck the swipe/space race in `TAPSWIPE_TESTING.md`.
- `CLAUDE.md` is over 190 lines; the shared rule says under 100. Propose
  cuts to the user before editing.
- Delete `MIGRATION.md` and `tapswipe-claude-v2.zip` from the main
  checkout if the user agrees. Leftovers from 2026-08-18.
- Install `git-lfs` on the server, so a fresh clone gets the swipe
  models and `java/assets/futo-swipe` stops showing as modified.
- Emulator leaks host memory per app start; worked around in
  `simlib/emulator.py`. A newer emulator build may fix it.

## Done

- 2026-11-14. TODO cleaned: removed items finished or made moot by the
  segmenter (eager split bugs, `Ivar`, capitalizing a split `i`, tapped
  words after an auto space, the 90-of-100 test, the 2026-11-11 phone
  checks).

- 2026-11-13. Fixed: a mid-word acronym reading made the rest of the word
  upper case. Capitalization audit, 13 edge cases.

- 2026-11-13. Split words keep their case ("If not"). Typing test scores
  end punctuation, and capitals in real-text lists.

- 2026-11-13. Backspace after a space removes the space first, then the
  word. Typing test field stays three lines high.

- 2026-11-13. Edge cases: one space after an auto space makes a period;
  apostrophe and Enter take the auto space back. Three real-text typing
  tests (`text-chat`, `text-email`, `text-story`). Simulator:
  `edge_check.py`, `text_check.py`.

- 2026-11-13. Modes with auto space: tapped short words get the auto
  space, peck after pause + 100 ms, swipe ends peck, tap-only mode turns
  auto space off (no Master Mode needed), Peck mode toolbar action with a
  pin button, peck threshold 350 ms. `modes_check.py` 8/8.

- 2026-11-13. Phone test feedback: backspace holds the auto space timer,
  250 ms default, countdown bar at the top of the keyboard, stricter
  split floor (4). "crap" was the offensive word filter (setting).

- 2026-11-13. Auto spacing: word segmenter (decides at word end, scores
  every reading with the decoder and context model) and the space after
  a pause, with a countdown bar. Simulator: emulator memory guard, warm
  sessions, event waits (split test 15 min to 4 min). Results in
  `docs/plans/AUTO_SPACING.md`, "Built".

- 2026-11-12. Merged FUTO upstream `7d61ab413` into `dev`. Kept the old
  swipe language model, because the swipe library has no weights for the
  new one. Kept our apostrophe work. Added `apostrophe_check.py`.

- 2026-11-12. TapSwipe settings in sections. Auto spacing moved to a
  work-in-progress sub-menu. Exact tap positions removed (always on).
  Learned key geometry now learns with the input model off. Checked on
  the emulator: 23 of 25 swiped words gave samples, stock mode.

- 2026-11-12. "I'm" no longer reads "in": deliberate apostrophe visits
  decode without the language model and penalize non-apostrophe words.
  Context keeps contractions whole. Fixed a keyboard crash
  (ConcurrentModificationException on live gesture segments).
- 2026-11-12. Timing re-rank: letter times from the swipe path; search
  pruned (long two-thumb words no longer lag).
- 2026-11-11. Soft time order across the two thumbs
  (`TapSwipeTimingOrder`): two-thumb "form" no longer reads "from". The
  language model no longer sees the word being typed as context.
- 2026-11-11. Fixed, from the user's second typing test: a second-finger
  tap now joins its own decoder stream ("then" no longer reads "the").
  Results are judged against their own decode input, not the newest.
  The decode thread no longer validates the session. The stroke list is
  locked. Added `sim.py analyze` for per-word typing test reports.
- 2026-11-11. Typing simulator built and working: recorder, typing
  test screen, emulator `tapswipe-sim`, touch player, host with
  scoring, report, `stats`, `compare`, `replay-recording`.
- 2026-11-11. Fixed: a late selection report of the keyboard's own
  write dropped the word session mid-word (bug A's mechanism).
- 2026-11-11. Fixed: a tap's re-decode skipped itself when the lock was
  busy, and the literal was committed.
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
