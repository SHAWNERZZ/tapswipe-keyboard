# CLAUDE.md — TapSwipe Keyboard

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

## Source of truth for behavior

Read documents in this order. Stop at the first one that answers the
question.

1. Current code and passing tests in the repository
2. `TAPSWIPE_ARCHITECTURE.md` — current session model, modes, finalizers,
   and known risks
3. `TAPSWIPE_TESTING.md` — how to run tests and the manual matrix
4. `README.md` — user-facing overview and license notice
5. `TAPSWIPE_PLAN.md` — historical rationale only; do not treat as pending
   work
6. `docs/history/TAPSWIPE_IMPLEMENTATION_HISTORY.md` — completed phases
   and fixes, kept for context

Never treat an "Original plan" section or a completed phase checklist as
an open task. Verify claims against the current code first.

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
