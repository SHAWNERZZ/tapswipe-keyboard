# Typing simulator

Agreed 2026-11-11. Partly built. How to run it: `tools/typing-sim/README.md`.

| Step | State |
|---|---|
| 1. Typing test mode and recorder | Built. Checked on the emulator. Needs a phone check. |
| 2. Emulator on the server | Built: `tapswipe-sim`, port 5580 |
| 3. Debug hooks: layout and trace | Built, as the recorder's events |
| 4. Player | Built, as `app_process` injection. No separate driver app. See `docs/decisions.md`. |
| 5. Host: generator, styles, scoring, report | Built, with `stats`, `compare`, and `replay-recording` |
| 6. Settings import | Built: settings datastore and touch model |
| 7. Decoder bench | Not built. Answered end to end instead. See "Results" below. |

First phone recording received 2026-11-11 (Pixel 10 Pro Fold,
common-100). Calibration in `tools/typing-sim/params-user-20261111.json`.

## Results, 2026-11-11

Two bugs found and fixed, both in the recurring bug class:

1. **Late selection report dropped the word session.** Bug A's
   mechanism. See `TapSwipeOwnSelections` and `TAPSWIPE_ARCHITECTURE.md`
   section 8. Swipe-then-tap on 25 long words: 76% right with 11
   mid-word resets before, 88% with none after.
2. **A tap's re-decode skipped itself.** The tap's own typing update
   superseded it while the previous job held the lock. The literal was
   committed. See `TAPSWIPE_ARCHITECTURE.md` section 6.

Baseline after the first fix, user's settings, 50 long words, 7 styles:
343 of 350 right (98%). S 96%, T 100%, S+T 100%, T+S 100%, S+S 100%,
S/S 92%, mix 98%.

**Bug B is not reproduced.** Two sequential swipes (one decoder
stream) scored 100%. The same splits as two overlapping thumbs (two
streams) scored 92%. So the hypothesis that several strokes in one
stream decode worse is not supported, with synthetic paths. The
simulator's two-thumb model is crude: the second thumb starts at a fixed
fraction of the first swipe, whatever side its keys are on. The user's
impression may also come from fix 1, which only affected sequential
strokes. A typing test recording of real two-thumb typing will settle
it. The planned decoder bench would not add evidence beyond this.

**Still failing: `LAST_STROKE_FLIP`.** On some seeds, swipe-then-tap is
near 78%. The decode was right before the final tap, and the tap
changed it. This is decoder input, not state: a tap is a one-point
stroke that the decoder resamples like a whole swipe. Not addressed.

## Goal

Type hundreds of words on the real keyboard, with mixes of taps and
swipes that a real user makes. Score every word. For each failure, name
the mechanism that caused it.

The simulator runs on the Linux server, in an Android emulator, with no
one attending it. It must catch problems before a release, not after
the user meets them on the phone.

## Why the current layers are not enough

| Layer | What it misses |
|---|---|
| JVM unit tests | Everything outside pure functions |
| Scenario runner | Real touch events, `PointerTracker`, pointer numbers, real timing. It asserts invariants, not accuracy. A person must press **Run**. |
| Manual checklist | Slow. Nobody remembers exactly which strokes they made. |

Both bugs the user reports live in these gaps. One is in the touch
path. The other is in the timing between decodes and commits.

## The bugs this must catch

### Bug A. A word built from parts commits wrong

Reported: the user builds a long word from swipes and taps. A different
word appears. One backspace brings back exactly the intended word.

Three mechanisms can produce that report. None is confirmed.

1. **The last stroke changed the decode.** With the backspace tap mode
   set to `LAST_GESTURE`, one backspace after a word reopens the word
   and removes its last stroke (`InputLogic.handleTapSwipeGraceReopen`).
   The decode of the shorter stroke list is the intended word. So the
   final stroke made the decoder change its mind. A plausible cause: a
   tap is a stroke of one point, and the decoder resamples every stroke
   to the same size (64 points, 32 output steps). One tap carries as
   much weight as a whole swipe.
2. **The commit wrote a different word than the screen showed.** This
   is the `ava ava` defect from the word split. A typing query over the
   literal stamps an autocorrection on the composer, and space commits
   it. `isTapSwipeVerbatimWord` blocks this for `SWIPE` and `PECK`
   words, and for a split word. With the stock backspace mode, one
   backspace reverts an autocorrection, which matches the report.
3. **A stale partial decode overwrote the word.** Adding a stroke does
   not bump the session generation. `onUpdateTailBatchInputCompleted`
   keeps a decode that covers fewer strokes out of the split decision,
   but still writes it to the screen. Decodes run in order on one
   thread, so this is probably rare.

The settings export tells which backspace mode the user runs. That
narrows the list before any test runs.

### Bug B. Sequential strokes decode worse than a two-finger swipe

Reported: swiping with two fingers at once reads better than a swipe
then a swipe, or a tap with a swipe.

Facts from the code:

- The decoder takes two streams, left hand and right hand
  (`SwipeEngine::recognize_multi`).
- Each stroke goes to a stream by pointer number. Pointer 0 is left.
  Pointer 1 is right (`TapSwipeInputBuilder.build`).
- Android reuses pointer 0 for each new touch. So sequential strokes
  all go to the left stream, even when the user alternates thumbs.
- Taps always go to the left stream (`InputLogic`, the `addTap` call).
- Two fingers down together get pointers 0 and 1. That gives one swipe
  per stream, which is the input the decoder was built for.

Hypothesis: a stream that holds several short strokes is far from what
the model learned from. Two streams with one stroke each is close to
it. The decoder bench below measures this directly. If it holds, one
possible fix is to assign the stream by screen side or by alternation,
not by pointer number. That fix is out of scope for this plan.

## Parts

### 1. Debug hooks in the app

Debug builds only. Off until the simulator turns them on.

- **Layout dump.** Every key's rectangle and code, and the keyboard's
  position on screen. The path generator needs real geometry.
- **Trace.** One JSON line per event on logcat tag `TapSwipeTrace`.
  - stroke added: kind, hand, pointer number, key
  - decode sent: generation, stroke count
  - decode applied: text, strokes covered, top candidates and scores
  - autocorrection stamped on the composer
  - commit: path taken (verbatim or autocorrect), text
  - backspace: which tier handled it
  - split: decision and reason

### 2. Driver app and player (on the emulator)

A small separate app with one text field. Its instrumentation test is
the player. The player reads a script of touch events and injects them
as real `MotionEvent`s through `UiAutomation.injectInputEvent`, with
exact timing and up to two pointers at once. The events go into the
real keyboard, through `PointerTracker`, as a finger's would. After
each word, the player records the field's text and the trace lines.

### 3. Decoder bench (on the emulator)

An instrumentation test that calls the swipe decoder directly, with no
keyboard and no text field. It is fast, so it can run thousands of
decodes. It answers "is this the decoder or the plumbing", and it
measures bug B by feeding the same paths with different stream
assignments.

### 4. Host orchestrator (Python, pipenv, on the server)

- Starts the emulator. Installs the keyboard and the driver.
- Imports the user's settings.
- Generates touch scripts from the layout dump and a word list.
- Runs the player. Collects text and trace.
- Scores, and writes a report.

## Finger model

All paths come from the layout dump.

- **Tap.** A point drawn from a 2D normal distribution around the key
  center. The spread starts from key size. The user's learned touch
  model replaces it when the export contains one
  (`tapswipe-touch-model.json`).
- **Swipe.** A smooth curve through the letters' key centers, with
  scatter at each key, rounded corners, and a speed profile that slows
  at the ends. Duration grows with distance.
- **Gaps.** Time between strokes, and thumb travel time between keys.
- **Two thumbs.** Keys are assigned to a thumb by screen side. Strokes
  overlap in time when two thumbs would overlap.

Every run takes a seed. The same seed gives the same events, so any
failure replays exactly.

## Typing styles

Each word is typed in several styles. The scatter stays the same where
it can, so style is the only thing that changes.

| Code | Style |
|---|---|
| `S` | One swipe |
| `T` | All taps |
| `S+T` | Swipe the start, tap the last 1 to 3 letters |
| `T+S` | Tap the first 1 to 2 letters, swipe the rest |
| `S+S` | Two swipes, one after the other |
| `S/S` | Two swipes, two thumbs, overlapping in time |
| `mix` | A random split into up to 4 strokes |

## Word list

- About 300 words, spread across frequency bands and lengths.
- The names and words in the user's personal dictionary, from the
  export.
- Short phrases for the split and for spacing: `i think`, `a lot`,
  `see me`, `this is`.
- Every bug already fixed, as a fixed regression case: `but` and `by`,
  `ava i`, `this is`, `are` and `area`.

## Scoring

For each attempt, record the intended word, the composing text just
before the finalizer, and the committed text.

| Verdict | Meaning |
|---|---|
| `OK` | Committed text is the intended word |
| `WRONG_DECODE` | The composing text was already wrong |
| `WRONG_COMMIT` | The composing text was right, the commit was wrong |
| `SPACE` | Right word, wrong spacing |
| `SPLIT` | One word became two, or two became one |
| `LOST` | Nothing committed |

Two probes run after a failure:

- **Backspace probe.** Press backspace once. Record what comes back. If
  it is the intended word, mark the failure `BACKSPACE_RECOVERS`. This
  is the exact signature of bug A.
- **Last-stroke flip.** If the decode of every stroke but the last was
  the intended word, mark `LAST_STROKE_FLIP`.

If the intended word was a candidate, record its rank and its score gap
to the winner.

The report totals accuracy by style, by word length, and by frequency
band. The **style penalty** is the accuracy of `S` minus the accuracy
of another style, on the same words. Each run compares against a saved
baseline, so a build can be checked against the last one.

The decoder may give different answers on the same input. If it does,
each case repeats a set number of times, and the report gives rates.
Measure this before choosing the count.

## Report

An HTML page per run. It holds the totals, the style table, every
failure with its mechanism label, and a replay command for each one.
Runs live in `tools/typing-sim/runs/` (gitignored). A run can be
published as a private Artifact page on request.

## Settings import

The app's own exporter (`SettingsExporter.exportSettings`) writes a zip
with the settings, the personal dictionary, the touch model, and the
clipboard history. The simulator imports it through the same class.

The zip is private. Keep it outside the repository. Strip the clipboard
history before import, because the tests never need it.

## Typing test mode (the user's idea, 2026-11-11)

A screen in the app, similar to a typing-speed test. It shows a list of
about 100 words. The user types them the way they normally type. Every
touch is recorded, with the word the user meant to type.

This is the most valuable data the simulator can get: real thumbs, real
habits, and a known intended word for every stroke.

### What the user sees

- A line of prompt words. The current word is marked. Each finished
  word turns green if it matches the prompt, red if not.
- A text field under it. The keyboard is the normal TapSwipe keyboard,
  with the user's normal settings.
- At the end: words per minute, accuracy, backspace count, and the list
  of words that needed a correction.
- A **Save** button that writes the recording to a file the user can
  sync to the server.

### Word lists

Plain text files, one word per line, in
`java/assets-tapswipe/typing-tests/`. The screen lists every file there.
The lists change with what is being tested. First lists:

- `common-100`: frequent words of every length.
- `long-words`: words that people build from parts.
- `short-pairs`: short words next to long ones, for the split
  (`i think`, `a lot`, `see me`).
- `regressions`: words from fixed bugs (`but`, `ava`, `this`, `area`).

A list can also be loaded from a file on the phone, so a new list does
not need a new build.

### What is recorded

Only in the typing test field. The screen marks its field through
`EditorInfo.privateImeOptions`. The keyboard records nothing in any
other field.

One JSON line per event, with `SystemClock.uptimeMillis` time:

- test start: list name, words, app version, every TapSwipe setting
- raw touch: down, move, and up for each pointer, with x, y, and the
  pointer number. Positions are relative to the keyboard view.
- key layout: every key's rectangle, once per keyboard rebuild
- stroke added to the session: tap or swipe, hand, key
- decode applied: text, strokes covered, top candidates
- commit: text, and whether it went verbatim or through autocorrect
- backspace: which tier handled it, and what it removed
- suggestion picked from the strip
- field text after each change
- prompt position: which prompt word the user is on

### How the simulator uses it

1. **Calibrate the finger model.** Tap spread per key, swipe speed and
   curve, gaps between strokes, and the user's real habits for
   splitting a word into strokes.
2. **Replay real failures.** Every word that needed a backspace is a
   test case. The player replays the raw touches on the emulator,
   against any build.
3. **Score real typing.** Each recording is a real-world accuracy
   score, comparable across builds.

The user may type a word other than the prompt, by a slip. The scorer
aligns typed words to prompt words, and marks a word as a slip when the
typed letters, not the decoder, differ.

### The seam

- **Evidence:** touch events. **Derived result:** the recording.
- **Invariant:** recording must not change what the keyboard does.
- **The risk:** the touch path runs on the main thread. A file write
  there adds delay at the moment timing matters most. Events go into an
  in-memory buffer. A background thread writes the buffer to the file.

## Rejected alternatives

- **`adb shell input motionevent`.** One pointer only, and coarse
  timing. It cannot make two thumbs overlap.
- **Call the IME methods directly, as the scenario runner does.** That
  skips `PointerTracker` and pointer numbers, which is where bug B
  lives.
- **Run the decoder on the server's JVM.** The library's native code is
  built for Android's C library. It does not load on the server JVM.
- **Revive the AOSP harness in `tests/`.** It does not run. See
  `TAPSWIPE_TESTING.md`.
- **Drive the real phone over adb.** Possible later. The emulator comes
  first, because it runs with no one attending it.

## Limits

- Injected events have no digitizer noise and no palm contact. The
  finger model supplies the noise.
- A score is not a feel. Synthetic paths are not the user's paths. The
  record and replay phase closes this gap.

## Build order

1. Typing test mode, with the recorder. It runs on the phone, so the
   user can collect real data while the rest is built.
2. Create an emulator image on the server. Install a dev build. Inject
   one tap through `UiAutomation` and read the field.
3. Debug hooks: layout dump and trace. The recorder's events are the
   same events, so most of this comes from step 1.
4. Driver app and player. Replay one recorded word on the emulator.
5. Host orchestrator: generator, styles, scoring, report. Finger model
   calibrated from the recordings.
6. Settings import. Run with the user's setup.
7. Decoder bench. Measure bug B.

Steps 2 and 4 answer whether injection reaches the keyboard window. If
it does not, stop and revise this plan.

## The user's setup

From the export of 2026-11-11. The file stays in `/Documents/Notes`,
outside the repository.

- TapSwipe on, Master Mode on (letters hidden), adaptive geometry on.
- Backspace tap mode: last gesture. Swipe up on backspace: on.
- Word trails on. Enter flicks on, period key hidden. Nintype gestures
  on. Typing speed on the space bar on. Automatic split: off.
- Layout: `qwerty_nintype`, picked as a language layout. Becomes the
  apostrophe key setting after the 2026-11-11 migration.

The backspace mode makes mechanism 1 of bug A the first one to test:
one backspace after a word removes its last stroke.

## The seam

- **Evidence:** the injected touch events. **Derived result:** the
  trace and the committed text.
- **Invariant:** the trace must not change the timing it observes.
- **The risk:** a trace line written on the main thread, inside the
  apply path, widens the very windows the simulator hunts for. Buffer
  trace lines and write them off the main thread. Run once with the
  trace off and compare the scores.


## The user's first typing test, 2026-11-11

Build: had fix 1 (late selection report), not fix 2 (skipped tail
decode). The version string in the file was stale.

- 100 of 100 words right in the end, 38.8 WPM, 37 backspace presses.
  First try right: 90 of 100.
- No mid-word session drops. Fix 1 caught one late report on the phone.
- The user builds most short words from parts. First-attempt patterns:
  swipe then taps, taps then swipe, and tap-swipe-tap are the most
  common. One single swipe: 12 of 16 right (75%), the weakest group.
- Overlapping thumbs: 32 of 37 right (86%). One finger at a time: 58 of
  63 (92%). Bug B is not supported by real data either, with small
  numbers.
- Taps are offset per key by the thumb's angle (W a quarter key right;
  L, B, N a third of a key low). Scatter after those offsets: 0.18 of a
  key. The finger model now applies both.

Replay of that recording word by word (`replay-words`): 83 of 89 words
matched the phone. Good enough to use real recordings as regression
tests, with a few words of noise.

Calibrated simulator run, 50 common words, 4 styles: 177 of 181 right.
Tap then swipe is the weakest style (94%), as on the phone.

Harness lessons from that day:

- Newer Android writes `ACTION_POINTER_DOWN(0)`. Strip the suffix.
- A whole-session replay drifts once one word differs. Replay per word.
- A tap that opens the emoji panel leaves the keyboard thinking a finger
  is down. Every later touch then types nothing. Sessions now start
  with a fresh keyboard process, and letter taps never land on function
  keys.


## Ranking, 2026-11-11

Question: why does the decoder pick "Wang" for "what", "van" for "can",
"woke" for "work"?

Score breakdown for the user's "what" (two thumbs, `probe-word`):
Wang total 4.58, shape -2.15; what 3.45, shape -5.91; want 2.74, shape
-6.56. The dictionary part already favors "what" by about 2.6. Shape
decides it.

Key paths from the recording: left thumb W to A, right thumb B to H to
G. For "what" the right thumb should run H to T. It started and ended
one row low. "work": the left thumb stopped on E, short of R.

Tap offsets by screen half: right-half taps land 0.36 (test 1) and 0.12
(test 2) of a key low; left-half taps land near the center. The learned
touch model has hundreds of samples per key, but its learned shift for
H is about 0.05 key up. The learner skips every two-thumb word, and the
user types mostly with two thumbs.

Offline re-ranking over 117 replayed words (`rerank_experiment.py`):
first choice right for 108, intended in the top 4 for 115. Amplifying
the dictionary part fixes 1 and breaks 0 at best. Amplifying the
language model breaks words quickly. Penalizing capitalized names fixes
none and would hurt real names such as "Ava". Ranking is not the lever.


## Calibrated offsets, 2026-11-11

Question from the user: would a typing test designed to learn offsets
beat the dynamic learner?

Experiment (`tools/typing-sim/offset_experiment.py`): move every touch
by minus the nearest key's offset, as a keyboard that learned those
offsets would see it, then replay word by word.

| Offsets fitted on | Test 1 (89 words) | Test 2 (90 words) |
|---|---|---|
| none | 80 | 72 |
| the other test, full strength | 77 | 70 |
| the other test, half strength | 80 | 73 |
| the same test, all of it (hindsight) | | 75 |
| the same test, last 60 taps only (causal) | 76 | 75 |

- Offsets from another session do not transfer. The user's right half
  landed 0.36 key low in test 1 and 0.12 in test 2, on the same screen.
- Offsets from the current session fix exactly the misranked words:
  test 2 causal fixed "what" and "want" (were "Wang") and "can" (was
  "van"), and broke none. Test 1 causal fixed 4 and showed 8 breaks, some
  of which look like replay artifacts.

Conclusion: a one-time calibration goes stale. The useful design is a
short-term layer that learns from the last ~60 taps of exact identity,
including taps in two-thumb words, on top of the long-term model. The
calibration word list (`calibration.txt`) stays useful to measure and to
seed it. Not built yet; waiting on the user.

Harness fix found on the way: a layout read mid-slide put every touch
126 px low. `wait_for_layout` now waits for a stable position.


### Calibration test, revised after the user's review

The first offset experiment fitted from `common-100`, where many keys
had 1 to 4 taps. The user pointed out that a test built for calibration,
with each word typed several times, would measure habits instead of
single attempts. Per-key data from both tests supports a split model:

- The per-key pattern is stable across sessions. W lands a quarter key
  right in both tests. L, N, O, Y land low in both. F lands high in both.
- The whole keyboard shifts by session: +0.23 key low in test 1, +0.04
  in test 2.

So: learn the per-key pattern from a calibration test, and track only
the whole-keyboard shift during normal typing.

`calibration.txt` now holds 42 words, 4 times each (168 words, fixed
order): every word the user's tests got wrong, plus coverage of every
letter (15+ each, rare letters 4+), and of first and last letters.

`sim.py calibrate <recording>...` measures per-key offsets. With several recordings it removes each session's whole-keyboard shift first, then pools the per-key pattern and reports a standard error per key, so repeated runs refine it. It from taps and
from swipes aligned to the known word (`simlib/align.py`): every
assignment of letters to the two thumbs' streams is tried, and each
stream's letters are split across its strokes by dynamic time warping.
Only taps and swipe endpoints are kept; letters in the middle of a swipe
are often never drawn. Swipe endpoints agree with taps where both exist
(test 1, key O: taps +0.53, swipe starts +0.58, swipe ends +0.27). About
a third of words do not align well enough and are left out.


### First calibration run, 2026-11-11 evening

The user typed the calibration test, then `common-100`, in one sitting.

- Pooled with the earlier tests, 21 of 26 keys are measured to about
  0.07 key or better. P, J, K, Z, X are thin; Q has nothing.
- The session shift moved within the sitting: +0.01 key in the
  calibration run, +0.10 in the `common-100` right after.
- Held-out check: `common-100` corrected with offsets from the
  calibration run scored 77, against 80 uncorrected (fixed "want",
  "its"; broke 5). Fixed offsets do not help yet. A likely confound: the
  emulator already applies the user's imported learned key model, so the
  correction stacks on it. Next experiment: switch adaptive geometry off
  on the emulator for both arms.

## Order across thumbs, 2026-11-11

Reported: "form" always read "from". The user taps F, swipes O to M, and
taps R with the other thumb during the swipe. Since a second-finger tap
joined its own stream, the decoder interleaved the streams freely and
chose the more common word.

`TapSwipeTimingOrder` re-ranks the decoder's top candidates by timing.
Each letter gets a time window (a tap's touch-down; a swipe's start for
its first letter; up to 150 ms before the lift for its last letter, since
a swipe often reaches its last letter early). Neighboring letters that
run backwards in time cost 0.06 points per ms beyond 50 ms. Each swipe's
first and last letters must be near its start and end points, and a
barely moving segment counts as a tap on a nearby key.

Results: the two-thumb "form" attempts read "form" in every replay.
Across both new tests (85 + 20 aligned words) the re-rank changed no
other word. A first version broke "house"; the swipe-end window fixed
it. Same-finger "form" (R tapped after the swipe ended) still reads
"from": the decoder's order within one stream is strict.

Also found: TapSwipe decodes passed the word being typed to the language
model as the previous word (117 of 180 decodes). Fixed by
`TapSwipeContext.dropComposing`.


### Letter times from the swipe path, 2026-11-12

The user described their two techniques. "form": one thumb touches F,
the other touches O; the F thumb slides to R and lifts while the O thumb
slides to M. "from": one thumb drags F to O to M; the other taps R on the
way to O. The first version could not tell these apart: it treated a
swipe's middle letters as "any time during the swipe", and its last
letter as up to 150 ms before the lift.

Now each letter's moment comes from the timed path: the touch for a
swipe's first letter and for a tap; the moment the path came closest to
the key for middle letters; closest approach through the lift for the
last letter. An attribution whose letters the path never came near
(mean distance over 1.2 keys) is impossible, not late.

Simulated techniques, 5 each with natural scatter: "form" read "form" 4
times (the fifth landed nothing as the session's first word, a known
simulator start-up artifact); "from" read "from" 5 times. On the user's
recordings, the re-rank changed one word, a "from" the decoder read as
"form", and broke none. The user's recorded two-thumb "form" attempts
still read "form".


### Lag on long two-thumb words, 2026-11-12

The user's long-words test (44 of 50 right on the first try) showed long
words taking up to 0.8 s to appear after the last lift. Decode compute
for 8+ letter two-thumb words: median 41 ms, worst 63 ms before the
timing rule; median 87 ms, worst 712 ms after. One-stream words were
unchanged. Cause: the order search tried every split of the letters
between the thumbs (4096 for 12 letters) and re-ran the path warping for
each.

Fix: a letter may only go to a thumb whose tap is that key or whose
swipe passed within 1.2 keys of it, so only ambiguous letters are
searched; path fits are cached per stroke and letter run. Results are
the same. Replaying the slowest words: re-rank at most 45 ms, whole
decode at most 174 ms on the emulator. `decode_detail` now records
`rerankMs`.
