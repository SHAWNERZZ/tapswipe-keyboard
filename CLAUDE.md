# CLAUDE.md: TapSwipe Keyboard

Shared project instructions for Claude Code. Commit this file. Personal
preferences and machine-local paths live in `CLAUDE.local.md` (gitignored).

## Project summary

TapSwipe is a personal fork of FUTO Keyboard. It adds a Nintype-style
tap+swipe input model on top of FUTO's neural swipe decoder. The public
repository is `SHAWNERZZ/tapswipe-keyboard`. Changes are never contributed
upstream to FUTO.

- Package: `latin.tapswipe`
- Symbols and settings: `TapSwipe*`
- Publication channel: GitHub Releases, tag namespace `tapswipe-vX.Y.Z`
- Release artifact: `assembleUnstableDebug` output
- Non-commercial license terms carry over to every downstream fork (FUTO
  Source First License 1.1-kb)

## Branches

- `master` mirrors upstream FUTO. Never commit to it.
- `dev` is the working branch.
- `tapswipe` is the release branch and the GitHub default.

## Current state

Run these two commands before you plan any work. They answer "where are
we" more reliably than this file can.

```
git log --oneline tapswipe..dev     # written but not released
gh release list --repo SHAWNERZZ/tapswipe-keyboard --limit 3
```

As of 2026-11-11 (evening), the latest release is `tapswipe-v0.8.0`.
`dev` holds unreleased work: the automatic word split (unfinished,
`docs/plans/WORD_SPLIT.md`), the apostrophe key setting, the typing test
and typing simulator (`docs/plans/TYPING_SIMULATOR.md`), and two fixes
the simulator found. Read `TODO.md` for the task list and
`docs/decisions.md` for decisions.

Release when the user asks. Do not release on your own.

Keep this section short and dated. A stale note here is worse than none,
because it invites acting on a state that no longer exists.

## How this project runs

Work arrives one small piece at a time. The pattern repeats.

1. The user describes a behavior they want, or a bug they hit.
2. You build it, add JVM tests for the tunable rule, and build the dev
   APK.
3. The user tests on hardware and reports what the finger felt.
4. You diagnose from that report, fix, and rebuild.
5. The user asks for a release when a feature feels right.

Two habits matter more than the code.

- The user's report is evidence. Trace it to a mechanism before you
  change a threshold. Most reported bugs in this project turned out to
  be structural, not a value that needed tuning.
- Do not widen the request. When the user asks for a specific change,
  build that change. A previous session replaced "make word deletion
  cost the same distance as the letters" with "delay word deletion until
  the slide travels far enough". That was a different feature, and it
  was wrong.

## Source of truth for behavior

Read documents in this order. Stop at the first one that answers the
question.

1. Current code and passing tests in the repository
2. `TAPSWIPE_ARCHITECTURE.md`. Current session model, modes, finalizers,
   and known risks
3. `TAPSWIPE_TESTING.md`. How to run tests and the manual matrix
4. `README.md`. User-facing overview and license notice
5. `docs/history/`. Why the design is what it is, and the fix-by-fix
   record. Historical only; never treat as pending work
6. `docs/plans/`. Agreed and not yet built. Check each item against the
   code before you act on it

Never treat an "Original plan" section or a completed phase checklist as
an open task. Verify claims against the current code first.

Planned work goes in `docs/plans/`, one file per initiative. Move a plan
to `docs/history/` when it ships. Nothing in `docs/history/` is pending,
whatever it appears to propose.

## Working rules

- This is a personal fork. Never suggest upstream contributions.
- Put new settings in the TapSwipe settings menu unless told otherwise.
- Measure before guessing. Ask for logs or a specific on-device test when
  the code cannot answer the question.
- Split tunable logic into pure functions with JVM tests. Put device
  tests only where a finger is required.
- Prefer symbols and file paths over line numbers. Line numbers drift.
- Do not modify submodules. The swipe library ships as a prebuilt `.aar`.

## Signing and build

Verified against `build.gradle` on 2026-08-18.

- The `debug` build type always uses `signingConfigs.debug`. That config
  reads `java/shared.keystore`, which is committed in the repository.
- The `release` build type uses `releaseSigning`. `releaseSigning`
  resolves to a real release config only when `keystore.properties`
  exists. Otherwise it falls back to `signingConfigs.debug`.
- `keystore.properties` is gitignored. The keystore it names lives
  outside the repository. Do not commit either file.
- The three product flavors are `unstable`, `stable`, and `playstore`.
  No flavor sets a signing config. Only the build type decides the key.

Two consequences matter for releases.

1. Published artifacts come from `assembleUnstableDebug`. That is the
   `debug` build type, so every published APK is signed with the shared
   upstream keystore.
2. The shared keystore is public. A matching signature proves only that
   the APK was not altered after signing. It proves nothing about who
   built the APK.

## Build host (Linux, since 2026-11-11)

Builds and tests run on the Unraid server, in the Ubuntu container.

- SDK: `/opt/android-sdk`. `local.properties` holds
  `sdk.dir=/opt/android-sdk`. The file is gitignored, so each new
  worktree needs it.
- Unit tests: `./gradlew testUnstableDebugUnitTest`.
- Dev APK: `/Documents/Notes/tapswipe-dev.apk`. This is
  `D:\Documents\Notes` on Windows.
- `git-lfs` is not installed. A fresh submodule checkout gets pointer
  files for the swipe models in `java/assets/futo-swipe`. Copy the real
  files from the main checkout, or install `git-lfs`.
- Fork-owned assets go in `java/assets-tapswipe/`, never in a
  submodule. The apostrophe layout lives there.
- The version name can be stale. A phone build reported
  `g0733c88ae` while it held two later commits, likely from Gradle's
  configuration cache. Check the recorder's events, not the version
  string, to know which fixes a recording had.
- Git data for every submodule is trusted with `safe.directory`. The
  voice-model submodule's `.git` file was repointed from a Windows
  path on 2026-11-11.
- `java/assets/futo-swipe` always shows as modified. Git stores its
  models as LFS pointers, and the folder holds the real files. Never
  run `git checkout -- .` there. It would replace the models with
  pointer text.

## Communication style

Follow ASD-STE100 (Simplified Technical English).

- Write short sentences. Use no more than 20 words in an instruction.
- Give one instruction per sentence.
- Use the active voice.
- Use the same word for the same thing every time.
- Use simple, common words.
- Do not stack more than three nouns together.
- Do not use the "not X, but Y" construction.
- Do not use em dashes.
- Do not use metaphors or invented terminology.
- Lead with the answer.
- Report assumptions and unresolved items explicitly.

## What the assistant knows and does not know

The user does not know Kotlin or Java in depth. The user understands
control flow and system design.

- Explain mechanisms and data flow. Do not paste code back as an
  explanation.
- Name parts in plain terms first. Give the class name in parentheses.
- When you describe a bug, explain the cause as a sequence of events.
- When you make a design decision, state the alternative you rejected.
- Critique in chat first. Do not edit large sections without a summary.

## Verification

- Run `./gradlew testUnstableDebugUnitTest` after changes to pure logic.
- Run the in-app scenario runner when the change touches `InputLogic`,
  `GeneralIME`, `TapSwipeSession`, `SwipeDecoderDictionary`,
  `PointerTracker`, or `BatchInputArbiter`.
- Run the typing simulator when the change can affect what a word
  becomes: `tools/typing-sim/README.md`. Compare against a run from
  before the change, with `sim.py compare`.
- Flag device-only checks that the user must run.
- See the `verify-tapswipe-change` skill for the full checklist.

## Recurring bug class

Nearly every defect in this fork has lived in the seam between when
evidence changes and when a derived result is applied. Before you land a
change, ask three questions.

1. What is the evidence, and what asynchronous work depends on it?
2. What is the invariant that protects the derived result?
3. What is the transition window in which the invariant is legitimately
   violated? How does the code stay correct in that window?
