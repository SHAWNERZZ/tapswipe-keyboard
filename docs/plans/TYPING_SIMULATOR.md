# Typing simulator

Agreed 2026-11-11. Partly built. How to run it: `tools/typing-sim/README.md`.

| Step | State |
|---|---|
| 1. Typing test mode and recorder | Built. Needs a device check. |
| 2. Emulator on the server | Built: `tapswipe-sim`, port 5580 |
| 3. Debug hooks: layout and trace | Built, as the recorder's events |
| 4. Player | Built, as `app_process` injection. No separate driver app. See `docs/decisions.md`. |
| 5. Host: generator, styles, scoring, report | Built. Finger model not yet calibrated. |
| 6. Settings import | Built: settings datastore and touch model |
| 7. Decoder bench | Not built. See below. |

Not built yet: replay of a phone recording on the emulator, finger model
calibration from recordings, and baseline comparison between runs.

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
