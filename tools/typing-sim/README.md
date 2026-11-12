# Typing simulator

Types words on the real keyboard in an emulator, scores each word, and
names the mechanism behind each failure. Design and reasons:
`docs/plans/TYPING_SIMULATOR.md`.

## Parts

| Part | Where | What it does |
|---|---|---|
| Recorder | `TypingTestRecorder`, `TypingTestEvents` in the app | Writes touches and keyboard decisions to `files/recordings/<id>.jsonl`, only for a field with the marker |
| Target field | `TypingSimTargetActivity` in the app | One text field with the marker. Not exported. |
| Touch player | `player/TouchPlayer.java` | Runs on the emulator through `app_process`. Injects multi-pointer touch events with real timing. |
| Host | `sim.py`, `simlib/` | Builds finger paths, plays them, reads the recording, scores, and writes a report |

## Run it

Run these commands from `tools/typing-sim`. The emulator is
`tapswipe-sim`, on port 5580.

1. Start the emulator, if it is not running.

   ```
   /opt/android-sdk/emulator/emulator -avd tapswipe-sim -port 5580 \
     -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
   ```

2. Build the player once.

   ```
   python3 sim.py build-player
   ```

3. Install the dev build, select the keyboard, and import the user's
   settings.

   ```
   python3 sim.py setup --apk ../../build/outputs/apk/unstable/debug/tapswipe-keyboard-unstable-debug.apk \
     --settings /Documents/Notes/FUTOKeyboardSettings_2026-11-11_09-14-06.backup
   ```

4. Run a word list in some styles.

   ```
   python3 sim.py run --list long-words --styles S,T,S+T,T+S,S+S,S/S,mix --seed 11
   ```

   The report goes to `runs/<run id>/report.html`, with `results.json`
   and the full `recording.jsonl` beside it. `runs/` is gitignored.

5. Type one failed word again, with the same seed.

   ```
   python3 sim.py replay-word runs/<run id> <attempt index>
   ```

Unit tests for the host: `python3 -m unittest discover -s tests`.

## Styles

| Code | Style |
|---|---|
| `S` | One swipe |
| `T` | All taps |
| `S+T` | Swipe the start, tap the last 1 to 3 letters |
| `T+S` | Tap the first 1 to 2 letters, swipe the rest |
| `S+S` | Two swipes, one after the other |
| `S/S` | Two swipes, two thumbs, overlapping in time |
| `mix` | A random split into up to 4 strokes |

## Verdicts and flags

| Verdict | Meaning |
|---|---|
| `OK` | Committed the intended word |
| `WRONG_DECODE` | The composing text was already wrong before space |
| `WRONG_COMMIT` | The composing text was right, and space committed something else |
| `SPLIT` | One word became two |
| `LOST` | Nothing committed |

| Flag | Meaning |
|---|---|
| `BACKSPACE_RECOVERS` | One backspace after the commit brought back the intended word. Bug A's signature. |
| `LAST_STROKE_FLIP` | The decode of every stroke but the last was the intended word |

## Gotchas

- Force-stopping the app makes Android switch to another keyboard.
  `setup` and every run select TapSwipe again.
- The keyboard slides in. The first layout position is wrong. The host
  waits for the settled `layout` event with a non-zero `viewW`.
- Do not touch the emulator during a run. Any other input lands in the
  recording.
- Finger model defaults are guesses. Calibrate them from typing test
  recordings: `python3 sim.py stats <recording> --out params.json`, then
  `run --params params.json`.
- Results vary between runs with the same seed. The touches are the
  same, but decode timing is not. Measured on 2026-11-11: across two
  runs of the same 50 swipe-then-tap words, 2 words flipped between
  right and wrong for no code reason (a third change came from a fix
  between the runs). Treat a
  difference of a few words as noise. Repeat a run before you trust a
  small change.

## More commands

| Command | Does |
|---|---|
| `stats <recording> [--out params.json]` | Measures tap scatter, tap hold, swipe speed, gaps, and word styles from a recording |
| `compare <run dir> <run dir>` | Accuracy per style, and words whose result changed |
| `replay-words <recording>` | Replays each word's first attempt from a phone typing test on its own, and compares first-try results. The fair comparison. |
| `python3 apostrophe_check.py [label]` | Swipes contractions after several lead-ins and runs `lists/contractions.txt`. Compare builds on apostrophe handling. |
| `replay-recording <recording>` | Replays the whole recording, corrections included. Drifts after the first different word, because the user's fixes reacted to the phone's text. |
| `replay-word <run dir> <index>` | Types one attempt again, same seed |

## Calibration from the user

`params-user-20261111.json` comes from the user's first typing test
(Pixel 10 Pro Fold, common-100). Use it with `run --params`. Their taps
scatter more than the default model: 0.24 of a key, not 0.15.

Per-word replay of that recording matched the phone on 83 of 89 words.
