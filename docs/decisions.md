# Decisions

One entry per decision. Log a decision when another option was
seriously considered, or when undoing it would cost real time.

Decisions made before 2026-11-11 are recorded in `docs/history/`,
mainly `TAPSWIPE_DESIGN_RATIONALE.md`. This file starts after the move
to Linux.

## 2026-11-11. Build and test on a Linux build machine

**Decision.** All builds and tests run on a Linux build machine, in an
Ubuntu container. The Windows clone is retired.

**Rejected.** Keep building on Windows and use the server only for
reading. The server has KVM, an x86_64 system image, and enough memory
to run an emulator without anyone attending it. The typing simulator
needs that.

**Consequences.**

- `local.properties` sets `sdk.dir=/opt/android-sdk`.
- The dev APK goes to a shared folder outside the repository, as
  `tapswipe-dev.apk`.
- Git needs `safe.directory` for this repository, because the files
  belong to the share user, not root. Set globally for this one path.

## 2026-11-11. A tap joins the decoder stream of its pointer

**Decision.** A tap goes to the stream of its pointer number: pointer 1
right, anything else left. This matches the rule swipes already follow
(design rationale 1.3).

**Rejected.**

- Keep every tap in the left stream, and move only taps that start
  within some milliseconds of a swipe's lift. That adds a timing
  threshold to tune, and Android already marks a second finger by its
  pointer number.
- Assign a stream by screen side. Sequential one-thumb typing would
  split across streams and lose its fixed order.

**Evidence.** The user's "then" (tap T, swipe H to N, other thumb taps
E) read "the" in 7 of 8 replays before, "then" in 12 of 12 after.
Long-words regression: 98.0% before, 98.9% after.

## 2026-11-11. Tail decodes are never skipped in TapSwipe mode

**Decision.** In TapSwipe mode, a tail-batch suggestion job always runs,
even when a newer request has been made. The session generation guard
decides whether its result still applies.

**Rejected.**

- Stop the tap from requesting a typing update. The typing update feeds
  the suggestion strip, and other code relies on it.
- Change the rule for every user. Stock swipe has its own timing, and
  the project rule is not to change stock behavior without need. The
  swipe/space race in `TAPSWIPE_TESTING.md` may share this cause. Measure
  it before widening the change.

## 2026-11-11. Dismiss late reports of the keyboard's own cursor writes

**Decision.** While a TapSwipe word is open, a selection report that
names a cursor position from one of the keyboard's own recent writes
(within one second) is dismissed, like a belated update.
`TapSwipeOwnSelections` holds the positions.

**Rejected.**

- Stop resetting the session when the editor is not composing. That
  hides the symptom, and a real cursor move by the user would then
  extend the old word.
- Delay the next tap until the editor confirms the decode's write. That
  adds input latency, and the editor gives no reliable confirmation.
- Fix it inside `RichInputConnection.isBelatedExpectedUpdate`. That
  changes the check for every user, including stock typing. The bug only
  exists where a decode rewrites text after a literal write.

## 2026-11-11. The simulator injects touches through app_process

**Decision.** A small Java program runs on the emulator as the shell
user through `app_process`, as scrcpy does. It injects multi-pointer
touch events through the hidden `InputManagerGlobal.injectInputEvent`.
The text field is `TypingSimTargetActivity`, inside the app, not
exported. The host reads the recorder's file through `adb root`.

**Rejected.**

- A separate driver app with an instrumentation test that injects
  through `UiAutomation`. Same input path, but a second app to build,
  sign, and keep in step, for no gain in fidelity.
- `adb shell input motionevent`. One pointer only and coarse timing.
  It cannot make two thumbs overlap.
- Dispatching `MotionEvent`s straight into `MainKeyboardView`. That
  skips the system input pipeline, which batches and resamples real
  touches.

**Consequence.** The target activity ships in every APK, including
published ones. It is not exported, so no other app can open it, and
it records nothing unless it is open.

## 2026-11-11. The apostrophe layout is a setting, in a fork-owned folder

**Decision.** `qwerty_nintype.yaml` moves to
`java/assets-tapswipe/layouts/TapSwipe/`, a second assets folder this
repository owns. A TapSwipe setting, **Apostrophe key**, swaps it in for
`qwerty`. It is no longer a language layout.

**Rejected.**

- Fork the upstream layouts repository and commit the file there. That
  adds a second repository to maintain, and every upstream layout update
  becomes a merge.
- Leave it in the layouts submodule. That is how it lived until now,
  uncommitted, so a build from git silently dropped it. It also breaks
  the rule against editing submodules.
- Keep it as a language layout, picked in language settings. The user
  asked for a TapSwipe setting.

**Consequence.** The user's stored subtype names `qwerty_nintype`. A
one-time migration turns the setting on for that case, and the swap runs
both ways, so the toggle controls the key either way.

## 2026-11-11. Commit the Windows work in progress unchanged

**Decision.** The word-split work that was uncommitted on Windows went
into one commit, unchanged, before any other edit.

**Rejected.**

- Leave it uncommitted in the main checkout. About 1,750 files there
  show as modified only because of CRLF line endings. The real work was
  hidden, and one careless commit would have mixed the two.
- Finish the feature first, then commit. The work would stay outside
  git for longer, with no record of what it was before new edits.

## 2026-11-12. Remove the exact tap positions setting

**Decision.** A tap always gives the decoder its real touch point. The
setting is gone.

**Rejected.**

- Keep the toggle. It was on by default, and nothing showed a reason to
  turn it off. Only the TapSwipe session read it, so with the input
  model off the keyboard already behaved as stock FUTO either way.

## 2026-11-12. Learn key geometry without the input model

**Decision.** With the input model off, `InputLogic` records taps and
gestures into a separate session used only for learning. The learner
runs at each commit, with the same checks as the input model path.

**Rejected.**

- Feed the learner from the stock `WordComposer`. It keeps only key
  codes and coordinates for taps, and nothing about a gesture's points
  in our normalized frame. The learner would need a second input format.
- Turn on the TapSwipe word session in stock mode. That session also
  drives decoding and finalizers, so stock typing would change.
- Learn only from gestures in stock mode, and ignore taps. Mixed words
  are rare in stock typing, and the learner already skips pure-tap
  words. Recording taps costs little and keeps both paths alike.

**Consequence.** Stock typing output does not change. The stock decode
path now reports its first choice and margin to the learner.

## 2026-11-12. Merge FUTO upstream, keep the old swipe language model

**Decision.** `dev` merges FUTO upstream at `7d61ab413` (0.1.30 and 31
later commits). The `java/assets/futo-swipe` submodule stays at
`9393187`, and the decoder keeps the `hungry_jellyfish` context model.
Our apostrophe work stays.

**Why.** The prebuilt swipe library (`libs`, `be4362c`) has its scoring
weights compiled in. It knows only `hungry_jellyfish`. With the new
`chosen_planarian` model it logs "Failed to find scoring" and decodes
with no language model at all. Upstream ships the same library, so
upstream has the same fault. FUTO has published no newer library.

Simulator results (emulator, the user's settings):

| Build | I'm / don't / we'll | Contraction list | Long words | Common-100 |
|---|---|---|---|---|
| A. Before the merge | 14/15, 15/15, 15/15 | 79.8% | 34/40 | 81/91 |
| B. Merge, new model as shipped | 15/15, 15/15, 15/15 | 80.7% | 31/40 | 79/91 |
| C. As B, apostrophe re-decode off | I'm wrong in 8 of 11 | not run | 31/40 | 79/91 |
| D. Merge, old model | not run | not run | 34/40 | 81/91 |
| E. Merge, new model under the old name | 15/15, 15/15, 14/15 | 83.3% | 33/40 | 81/91 |
| F. Merge as committed (old model) | 15/15, 15/15, 15/15 | 80.7% | 34/40 | 80/91 |

**Rejected.**

- Take the new model as upstream ships it (B). The language model does
  not run. Words like "house" became "Jodie" and "right" became "Riki".
- Ship the new model under the old model's name (E). It runs with
  weights tuned for the other model. It scored about the same as the old
  model. It would add a 6 MB binary to this repository.
- Drop our apostrophe re-decode. Without it (C), the new model still
  reads a swiped "I'm" as "in" in most tries.
- Rebuild the swipe library from FUTO's source with the new weights.
  The project rule forbids changing the library.

**Revisit** when FUTO updates `libs/futo-swipe-release.aar`. Check its
strings for `chosen_planarian`, then move the submodule to `f5ebe55c`
and point `ENGLISH_LM_ASSET` at it.

## 2026-11-13. Decide word splits once, when the word ends

**Decision.** The word segmenter (`TapSwipeSegmenter`) judges the whole
stroke list after each full decode, and the finalizer acts on its
verdict. The eager split, which committed a word after any stroke, is
retired. Every reading is scored in one unit: the decoder's score for
swiped parts, and the context model's score for tapped words.

**Rejected.**

- Keep the eager split and extend it to leading taps. It committed text
  mid-word, which caused most of the defects in `WORD_SPLIT.md`, and it
  could not see a word that starts and ends with taps.
- Dictionary frequencies and word-pair counts. FUTO's main dictionaries
  hold few pairs, so `we have` could not beat `weave`. The swipe
  decoder's context model already scores words in context.
- A pure timer with no segmenter. The user's cases (`I want a`) arrive
  as one fast stroke list with no pause.

## 2026-11-13. Space after a pause, for words with a swipe only

**Decision.** A timer presses space 300 ms after the last lift, for a
word that holds a swipe. A touch-down cancels it. A space pressed within
1.5 s after it does nothing.

**Rejected.**

- An auto space after tap-only words. The user decided against it.
  Kinetica, which has it, records that 1 in 5 such spaces came early.
- Counting the pause from when the decode lands. Kinetica measured that
  counting from the lift works better; the fire step waits for the
  decode instead.
- Letting a habitual space press add a second space. Two quick spaces
  also trigger the double-space period.

## 2026-11-13. Modes with auto space: short tapped words, peck by pause

**Decision.** A word of taps alone gets the auto space when it is a
common short word. If no space came, the word turns peck 100 ms after the
pause. Peck threshold 350 ms. A Peck mode toolbar action forces peck for
one word. Tap-only mode turns auto space off and no longer needs Master
Mode; it counts quick taps only from a word's fourth letter.

**Rejected.**

- Taking the space back within 600 ms of a slow next tap. The user found
  600 ms far too long.
- The Nintype way (no slow peck at all). A slow speller would get a word
  cut into pieces.
- Counting every quick tap toward tap-only mode. `it is a` typed with
  auto space would switch auto space off mid-sentence.

## 2026-11-13. Backspace after a space removes the space first

**Decision.** A backspace tap right after one space that follows a word
removes only the space. The next tap removes the word, or its last
stroke, as the backspace tap setting says. This holds for typed spaces
and auto spaces.

**Rejected.**

- Taking the space with the word (the old rule). The user found it
  deleted words they only meant to touch; Nintype and the other
  keyboards they use remove the space first.
- Removing the space and reopening the word for more strokes in one
  tap (Kinetica). The user asked for the space alone.

