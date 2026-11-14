# TODO

Read this when a session starts. Update it when you start, finish, or
drop a task. Date each Done entry.

## Now

- Next typing test from the user, on the build with both fixes, to
  measure first-try accuracy against the 90 of 100 baseline. Use
  `sim.py replay-words` and `sim.py stats` on it.
- Phone check of the two fixes from 2026-11-11 (late selection report,
  skipped tail decode). Bug A should be gone: a word built from parts
  should no longer need a backspace to come out right.

## Next

- **New swipe language model** `chosen_planarian`: wait for a FUTO
  swipe library that has its scoring weights. See `docs/decisions.md`,
  2026-11-12, "Merge FUTO upstream".
- **Auto spacing, next steps** (`docs/plans/AUTO_SPACING.md`, "Known
  gaps"): tapped words after an auto space (waiting on the user), the
  rejoin layer for pauses inside a word, the losing reading in the
  suggestion strip. Phone test of the dev APK first.

- **Calibration offsets: not shown to help yet.** Held-out check scored
  77 against 80. Rerun with adaptive geometry off on the emulator.
- **Session offset layer** (proposed 2026-11-11, waiting on the user).
  Ranking errors ("Wang", "van", "cold") come from the right thumb
  landing low, and the amount changes between sessions. A causal
  correction from the last 60 taps fixed them in replay. Design and
  numbers: `docs/plans/TYPING_SIMULATOR.md`, "Calibrated offsets".
- Single swipes of short words are the user's weakest case (77% in the
  second test).
- Word split, open items. Full list in `docs/plans/WORD_SPLIT.md`.
  - Words split with the setting off. Likely fixed on 2026-11-11 (late
    selection report). Confirm on the phone with the debug panel counts.
  - Wire n-gram probability through the `Dictionary` base class.
  - Offer the losing reading in the suggestion strip.
  - Decode the gesture alone for taps-then-gesture words (`Ivar`).
  - Decide: capitalize a split `i`, what to learn, undo granularity.
- `LAST_STROKE_FLIP`: the final tap of a swipe-then-tap word changes a
  right decode into a wrong one. On some seeds swipe-then-tap is near
  78%. Decoder input: a tap is resampled like a whole swipe. Needs a
  design, see `docs/plans/TYPING_SIMULATOR.md`, Results.
- Bug B (two thumbs read better than sequential strokes) is not
  supported by synthetic runs or by the user's first recording (86%
  overlapping vs 92% one at a time). Recheck with more data.
- Weakest real cases: a single swipe of a short word (75% first try),
  and words started with a tap. Look at these next.
- Improve the simulator's two-thumb model: give each thumb the keys on
  its side.
- Recheck the swipe/space race in `TAPSWIPE_TESTING.md`. It may share
  the skipped tail decode cause.
- `CLAUDE.md` is over 190 lines. The shared rule says under 100. Propose
  cuts to the user before editing.
- Delete `MIGRATION.md` and `tapswipe-claude-v2.zip` from the main
  checkout if the user agrees. Both are leftovers from 2026-08-18.
- Install `git-lfs` on the server, so a fresh clone gets the swipe
  models, and `java/assets/futo-swipe` stops showing as modified.

## Done

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
