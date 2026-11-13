# Auto spacing

Exploration, 2026-11-12. A proposal only. Nothing here is built.
Supersedes nothing yet: `WORD_SPLIT.md` stays the plan for the split
judge, and becomes one layer of this design.

## The goal

The user should not need the space bar between words. The keyboard
decides where a word ends, writes the space, and makes a wrong decision
cheap to undo.

## What the other keyboards do

Read from source on 2026-11-12: Kinetica 2.1.3, LeanType v4.3.2-beta7,
and the user's LeanType fork (June 2026).

**Neither keyboard finds word boundaries from language in English.**
Both use time. LeanType's only text splitter is for Thai.

### Kinetica

- A word is a buffer of strokes, taps and swipes mixed.
- After the last lift, with every finger up, a timer starts. Defaults:
  300 ms after a swiped word, 300 ms after a tapped word, at least 300 ms
  after a one-letter word.
- Any touch-down cancels the timer. A stroke that starts in time joins
  the word. No scoring is involved.
- When the timer fires, it re-checks every condition against the editor
  as it is then. It commits the word and writes a real space, marked as
  the keyboard's own.
- A tapped word gets a space only if it is a dictionary word, or a
  one-letter word on a short list (a, I, ...). Off by default. The code
  records that 1 in 5 such spaces came too early.
- **Retract.** A letter tapped within 600 ms after a tapped word's space
  removes the space, if the joined text is still a dictionary prefix.
  The earlier word reopens as taps at key centres. A space after a
  swiped word is never retracted.
- The keyboard's own space is removed before `. , ! ? ; : ) ] }`. A
  space the user typed is never removed.
- No space in email, URL and password fields.
- Backspace after an auto space deletes it and reopens the word.
- A band on the suggestion bar shows the time left.

### LeanType (public)

- Uses the AOSP space states, the same machine FUTO has: a space can be
  owed (`PHANTOM`) and written only when the next character needs it.
- Owed space after a gesture, after a picked suggestion, before a
  gesture, and (optional) after punctuation and emoji.
- Data tables say which characters take a space before, after, or
  neither. Quote parity decides whether `"` opens or closes.
- One guard function checks field type, per-app quirks, and URL or email
  text before any space is written.
- One backspace reverts the word and its space together.

### LeanType (the user's fork)

- Nintype-style two-thumb typing. Three spacing modes: normal, manual
  (space bar only), and auto space after a delay (500 ms when chosen).
- Each tap or swipe restarts the timer. A tap adds 250 ms more.
- Fragments of one word are joined by merging their touch paths into
  one gesture. The fork found that joining decoded strings gave wrong
  words. TapSwipe already decodes all strokes together.
- Toolbar keys "Join next" and "Force next space" fix single cases.
- The gesture-end owed space was turned off while the timer runs. The
  two mechanisms raced and wrote "tech " before a fast tap of "y".

## The user's own pauses

Measured from three typing tests (2026-11-11 and 2026-11-12), 209 words.
A typing test shows one word at a time, so the pause after the space
includes reading the next word. Normal writing will be faster.

| Pause | Median | 90th pct | 99th pct |
|---|---|---|---|
| Between strokes inside a word, taps only | 50 ms | 150 ms | 392 ms |
| Between strokes inside a word, with a swipe | 42 ms | 150 ms | 2423 ms |
| Last lift of a word to the space touch | 246 ms | 872 ms | 1690 ms |

A pure timer splits a word wherever a pause inside it is longer than the
delay:

| Delay | Pauses inside words that are longer |
|---|---|
| 300 ms | 4.6% |
| 400 ms | 2.9% |
| 600 ms | 2.1% |
| 800 ms | 0.8% |

No delay separates the two groups cleanly. Inside-word pauses run long
when the user hesitates over a long word, which is when a wrong split
hurts most. So a timer alone is not enough. The design needs a cheap,
reliable rejoin.

Measure: `python3 tools/typing-sim/pause_stats.py <recordings...>`.

## Proposed design

Three layers. Each one is useful alone, and each later layer fixes the
errors of the one before.

### Layer 1. A pause ends the word

- Arm a timer when all of these are true: every finger is up, the decode
  for the current session generation has been applied, and the field
  allows auto spaces.
- Start counting at the last lift. Kinetica found this better than
  counting from when the decode lands.
- Delays: one for words with a swipe, a longer one for tap-only words,
  and a floor for one-letter words.
- **A touch-down cancels the timer.** A lift does not. A second thumb
  that is still down holds the word open.
- When the timer fires, check again: same session generation, no finger
  down, editor still composing our word. Then run the normal finalizer
  path (commit, learn, reset the session). Write a real space, and mark
  it as the keyboard's own.
- Keep the committed word's strokes in the grace record, as a space
  finalizer already does.
- Show the time left on the space bar.

### Layer 2. The next stroke can rejoin

This is where TapSwipe can do better than both keyboards. Kinetica
reloads a word as taps at key centres, and never rejoins after a swipe.
TapSwipe still holds the real strokes in the grace record.

- A stroke that starts within a short window after the keyboard's own
  space, with nothing typed since, is a rejoin candidate.
- Decode the old word's strokes plus the new stroke together. This is
  the same decode a word gets today.
- Rejoin when the joined reading is a word (or a live dictionary prefix
  while strokes may follow) and fits better than the two words apart.
  Otherwise the new stroke starts the next word.
- A rejoin removes the space and restores the session from the grace
  record, then adds the new stroke. `TapSwipeSession.restoreStrokes`
  exists for this.
- The losing reading goes in the suggestion strip, so one tap undoes the
  choice either way.

The judge here is the `WORD_SPLIT.md` judge run backwards: there it asks
"are these strokes two words", here it asks "are these two words one".
Both use dictionary lookups and n-gram probability. Build one function
for both.

### Layer 3. Split before the pause

For fast typing with no pause, the existing split judge
(`TapSwipeWordSplit`, the "Split tapped words" setting) commits a word
early when the dictionary is confident. Leave it as is until layers 1
and 2 work. Then re-measure whether it still earns its place.

### Spaces and punctuation

FUTO already has the AOSP space states. Reuse them.

- Remove the keyboard's own space before `. , ! ? ; : ) ] }`. Never
  remove a space the user typed.
- After `. , ! ? ; : ) ] }`, owe a space (`PHANTOM`), so the next word
  gets one. Kinetica lacks this; LeanType has it as an option.
- Apostrophes and hyphens stay inside a word. Our apostrophe key already
  works this way.
- No auto space in password, email and URL fields, or when the text
  before the cursor looks like a URL or email.
- A cursor move by the user cancels any owed space and any armed timer.

### Undo

- Backspace right after the keyboard's own space removes the space and
  reopens the word with its strokes. The next backspace works on the
  word as it does today.
- The suggestion strip after an auto space keeps the word's alternatives
  for a moment, then turns to next-word predictions. The user's fork
  found this useful.

## The seam (recurring bug class)

- **Evidence:** the strokes, and which fingers are down.
- **Derived result:** the armed timer, then the committed word and space.
- **Invariant:** a timer may only commit the session generation it was
  armed for, with no finger down at the moment it fires.
- **The window:** a touch-down on the touch thread while the timer's
  runnable is already queued on the main thread. The down event must
  bump a counter that the runnable checks before it commits. The
  user's fork turned off the gesture-end owed space for the same reason:
  two mechanisms deciding one boundary will race.

## Settings

In the "Auto spacing (work in progress)" sub-menu. All off by default.

- Auto space after a pause (on or off)
- Delay after a swiped word (start at 300 ms)
- Delay after a tapped word (start at 400 ms)
- Rejoin window (start at 600 ms)
- Space after punctuation
- Split tapped words (exists today)

## Build order

1. Simulator support for pauses between words with no space tap.
2. Layer 1 with the space-bar countdown. JVM tests for the arm and fire
   rules as pure functions with a named refusal reason.
3. Undo by backspace.
4. Layer 2, sharing one judge with `WORD_SPLIT.md`.
5. Punctuation rules.
6. Phone test, then tune delays from a new recording.

## The user's answers, 2026-11-12

1. A word made only of taps gets no auto space. The exception is a short
   tapped word next to a swiped word: the split below decides that case.
2. Short words (one to three letters) are often tapped. Anything longer
   should hold a swipe. So a tap run at either end of a word is the
   place to look for a second word.
3. Normal spacing does not change. No new space after punctuation. The
   work is only to put spaces between words.
4. Yes to a countdown: a progress bar on the space bar that shrinks.

## Redesign: decide once, at the pause

This replaces layers 1 and 3 above. Layer 2 (rejoin) waits until the
redesign has been measured.

### What changes

Today the split judge runs after every stroke, while the word is still
being typed, and commits the head word at once. Most defects in
`WORD_SPLIT.md` came from that: a decode that described fewer strokes
than the session held, readings that outlived their word, a split that
raced a typing query, and the `WAIT` state for a boundary that moved.

The redesign makes one decision, at the moment the word is over:

- The 300 ms pause timer fires (only for a word that holds a swipe), or
- the user presses a finalizer (space, punctuation, Enter).

At that moment every stroke is known and every finger is up. Nothing has
been committed yet. The judge picks the best reading of the whole
stroke list, then the keyboard writes one, two or three words.

### The readings

For a word with tap runs at its ends, build these readings. `T` is a
tapped letter, `S` is a swipe, and `L` is the tap run length limit (3).

| Reading | Example strokes | Text |
|---|---|---|
| One word | `S T` | decode of all strokes: `cats` |
| Trailing taps are a word | `S T` | decode of `S` alone, then the taps as typed: `hello I` |
| Leading taps are a word | `T S` | the taps as typed, then decode of `S` alone: `I think` |
| Both ends | `T S T` | `a` + decode of `S` + `I` (rare, allowed) |

- A tap run may give only part of itself to the next word. For `S T T T`
  try every cut: `this` + `is` and `to` + `sis` are both readings. This
  is the `to sis` lesson from `WORD_SPLIT.md`.
- One stroke list can hold several words. The user's examples:
  `I want a` (tap, swipe, tap) and `a start is` (tap, swipe, two taps).
  So both ends are judged in one pass, and a tap run may hold two short
  words (`is a` in `this is a`). The judge tries every cut of each tap
  run into zero, one or two short words.
- A fast typist may chain two swiped words with no pause. A cut between
  two swipes is not part of this design yet. The test list will show if
  it is needed.
- A tapped side must be a short standalone word. The dictionary holds
  every single letter, so one-letter words come from a short list (`a`,
  `I`, and the user's own). Two and three letters need a dictionary
  word of real frequency. Kinetica found the same and uses a list.
- The swiped side is decoded without the split-off taps. A tap must
  never re-read the swipe (the `ava` + `i` = `acai` lesson). The
  decodes can start at the last lift, while the timer runs, so they are
  ready when it fires.

### The score

Each reading gets a score from the evidence the decoder already uses:

- **Shape fit.** The decoder's score for each swiped part.
- **Language fit.** How likely the words are in this context. The swipe
  decoder's context model scores each candidate given the words before
  it. For a split reading, the second word is scored with the first word
  added to the context. The n-gram dictionaries give a second opinion.
- **Typing evidence.** The pause between the swipe and the tap run. A
  long pause leans toward two words. Used only to break a near tie.

Pick the best reading. On a near tie, keep one word and offer the split
reading in the suggestion strip. A wrong join is cheaper to fix than a
wrong split, and it matches today's behavior.

### Then the space

- One word with a swipe: commit, write the space, show the countdown on
  the next word as usual.
- A split: commit each word with a space between. After the last word,
  the normal rule applies: a space if it holds a swipe. If the last word
  is a tapped short word (`hello I`), the space follows too, because the
  timer fired on the whole stroke list.
- The space written by the timer is marked as the keyboard's own.
  Backspace right after it removes the space and reopens the word with
  all its strokes, as one undo step.

### The seam

- **Evidence:** the stroke list and the finger state.
- **Derived result:** the readings, the verdict, the committed text.
- **Invariant:** the verdict describes exactly the stroke list of the
  session generation it was computed for.
- **The window:** a new touch-down after the readings start. The
  touch-down cancels the timer and bumps the generation, so any reading
  in flight is dropped. No text is committed before the verdict, so
  there is nothing to undo.

## Test plan

Four layers, smallest first. Each later layer needs the one before it to
pass.

### 1. JVM tests of the judge

A table of cases, each with strokes as text, a small fixed lexicon, and
the expected words. The judge is a pure function, so these run in
seconds with no device.

| Group | Cases |
|---|---|
| Must join | `cat`+s, `work`+ed, `are`+a = area, `go`+es, `the`+y |
| Must split, trailing | `hello`+I, `this`+is, `want`+to, `think`+so |
| Must split, leading | I+`think`, a+`lot`, to+`go`, in+`the` |
| Ambiguous, join and offer | `away`/a way, `into`/in to, `maybe`/may be |
| Partial tap runs | `thi`+s,i,s = this is |
| Never re-read | `ava`+i must not become `acai` |

### 2. Simulator: a split corpus

A new list of short phrases, each typed as one stroke list with no space
key: a swiped word with tapped short words before or after it. Each
phrase is typed two ways: as one fast burst (inside-word gaps of about
50 ms, from the user's data) and with a pause between words.

- **Split recall:** of phrases that are two words, how many come out
  right.
- **Join precision:** of single words with a tapped ending, how many stay
  one word.
- Run with the setting off and on, and compare with `sim.py compare`.

### 3. Real recordings must not change

Replay the user's recordings with `sim.py replay-words`. Every word in
them is one word. With the setting on, the first-try count must not
drop. Any split there is a false split, and each one gets a JVM case.

The phrase list is `tools/typing-sim/lists/split-phrases.txt`, run by
`tools/typing-sim/split_check.py`. It includes the user's cases
(`I want a`, `a start is`) and tap runs that hold two words.

Baseline, 2026-11-12, build `175609f0c`, the user's settings (34 cases,
first number fast burst, second with pauses):

| Group | Split off | Split on (today's judge) |
|---|---|---|
| Must split, trailing taps (6) | 0, 0 | 5, 5 |
| Must split, leading taps (6) | 0, 0 | 0, 0 |
| Must split, both ends (4) | 0, 0 | 0, 0 |
| Must split, tap run of two words (3) | 0, 0 | 0, 0 |
| Partial tap run (2) | 0, 0 | 2, 2 |
| Must join, tapped ending (6) | 5, 6 | 6, 5 |
| Must join, tapped start (3) | 2, 2 | 2, 2 |
| Ambiguous, one word expected (4) | 4, 4 | 4, 4 |
| **Total (34)** | **11, 12** | **19, 18** |

Today's judge handles trailing taps. It never splits leading taps, both
ends, or a tap run that holds two words. Those are the user's cases.
Examples: `I want a` became `Intra`, `a start is` became `Asterisk`,
`this is a` became `This isa`. Two other faults to look at: `hello I`
became `Help i` (shape), and `a lot` became `A 🅰️` (an emoji replaced
the swiped word).

### 4. A new phone test

Add a typing-test list of short phrases. The user types them with no
space between the words. The recorder already captures every stroke.
Replay the recording on each new build. This is the real calibration
data the score thresholds need.

## Built, 2026-11-13

Commits `4919d2046` (segmenter) and `9ecd54450` (timer). Both settings are
in the Auto spacing sub-menu and off by default.

### What was built

- `TapSwipeSegmenter` (pure, JVM tested). Tries every cut of the tap runs
  at both ends into zero, one or two words of up to 3 letters. A swiped
  part gets the decoder's score, decoded with the tapped words before it
  as context. A tapped word gets the context model's score for it
  (`predictNext`, top 400), plus the decoder's length bonus. A common
  short word not in the top 400 gets a floor score. Costs: 2 per extra
  word, 5 for a one-letter word in front of a swipe, and a split must win
  by 2. The swiped part of a split must score above 0.
- It runs on the decode thread after each full decode. The verdict is
  stored with its generation and stroke count. The finalizer commits all
  but the last word, and waits up to 250 ms if the space came first.
- The eager split (after every stroke) is retired, behind
  `EAGER_SPLIT = false` in `InputLogic`.
- `TapSwipeAutoSpace`: the pause timer. Touch-down cancels; the last
  lift arms it; it fires through the normal space path. A space pressed
  within 1.5 s after an auto space does nothing. A bar on the space key
  shrinks as the pause runs out.

### Results (emulator, the user's settings)

| Check | Before | Now |
|---|---|---|
| Split test, space key, fast / paused (34) | 19 / 18 | 34 / 34 |
| Split test, timer only, fast / paused (37) | n/a | 37 / 35 |
| Long words recording, timer off / on | 34 | 34 / 33 |
| Common-100 recording, timer off / on | 80 to 81 | 80 / 80 |

Each tuned number came from these runs. The reasoning is in the comments
on `TIE`, `LEAD_LETTER_COST` and `MIN_SPLIT_SWIPE_SCORE`.

### Known gaps

- **Tapped words after an auto space.** With a pause after the swipe,
  `this is a` gives `this isa`: the timer ends `this`, then `is`, a
  pause, and `a` are taps only. Tap-only words get no auto space, and
  the segmenter needs a swipe. Waiting on the user's decision.
- **A pause inside a word.** The timer fired inside `celebration` once
  in 40 long words (`celebre Aaron`). The rejoin layer (layer 2 above)
  is the planned fix.
- **`about` typed as tap `a` + swipe `bout`** reads as `a bit`. Real
  ambiguity; context sometimes settles it.
- The simulator's finger model misreads `hello` and `there` even as
  single swipes. Those phrases were swapped in the test list.
- Not yet: the losing reading in the suggestion strip, and one undo for
  a whole split.

## Modes with auto space (proposal, 2026-11-13)

The user asked for auto space after short tapped words (`is a `), and a
clear rule for the three modes. **Built on 2026-11-13** with the user's
changes, listed under "Decided" below. The current rules are in
`TAPSWIPE_ARCHITECTURE.md`, section 4.

### Decided (2026-11-13)

- Option A, plus a Peck mode toolbar action and a button to pin it.
- Rule 4 replaced: no 600 ms window. After the 250 ms pause, a short word
  gets its space; if no space came, 100 ms later the word turns peck. A
  swipe still ends peck mode and the word decodes as a swipe word.
- Single letters (`a`, `I`) get the auto space.
- Tap-only mode turns all auto space off, without needing Master Mode.

Checked with `tools/typing-sim/modes_check.py` (8 of 8) and the split
test with no space key (37 of 37 in both tempos).

### The modes today

| Mode | Trigger | What it does |
|---|---|---|
| Swipe word | any swipe in the word | Decoded. Auto space after the pause. Segmenter. |
| Undecided | taps only, fewer than 2 taps or quick taps | Stock typing for that word. |
| Peck | taps only, median gap between taps 250 ms or more; a lone tap idle 2 s; any password field | Commits the letters as typed, learns the word, shows letters and key borders. |
| Tap only (`LEGACY_TAP`) | 5 quick taps in a row across words, with no swipe; Master Mode only | Stock FUTO typing: autocorrect, letters shown. A swipe ends it. |

### The conflict

The peck threshold (250 ms between taps) equals the auto space pause
(250 ms). With auto space after tapped words, a slow speller pauses long
enough for peck mode, and the space has already been written.

### Proposed rules

1. **Swipe word.** No change.
2. **Short tapped word.** The timer runs after a word of taps only when
   the letters so far are a common short word of 1 to 3 letters (`a`,
   `I`, `is`, `to`, `the`), typed at normal speed (gaps under 250 ms).
   Longer tap runs, and runs that are not words, never get an auto space.
3. **Peck.** Raise the peck threshold to 350 ms. A word whose taps come
   350 ms or more apart is peck: no auto space; it ends with a manual
   space and is learned. A lone tap stays undecided until a second tap
   or the 2 s idle.
4. **Take the space back.** If the timer wrote a space after a tapped
   word and the next tap arrives within 600 ms, at peck pace (350 ms or
   more after the previous tap), the space is removed and the word
   continues in peck mode. This is the user's two-threshold idea: 250 ms
   gives the space, a slow next tap turns it back into a spelled word.
5. **Tap only.** Turns all auto space off, for swipe words too, until a
   swipe ends the mode.

Between 250 and 350 ms is a grey zone. A gap there counts as a word
boundary after a short word, and as normal typing inside a word.

### Options considered

- **A. Cadence decides (the rules above).** Recommended. Keeps peck mode
  as it is, with one raised threshold.
- **B. The Nintype way.** Taps faster than the pause build one word. A
  tap-only word that is not in the dictionary is learned. No slow peck
  at all. Simpler, but a slow speller gets a word split into pieces.
- **C. An explicit peck switch.** For example, hold the first letter.
  No guessing, but one more thing to learn.

### Open questions

1. Rule 4: is 600 ms after the space the right window, and must the
   joined letters also start a known word (Kinetica's rule)?
2. Rule 5: should tap-only mode still need Master Mode? Today it exists
   only to bring the letters back.
3. Should a single tapped `a` or `I` get the auto space, or only words of
   2 to 3 letters?

## Open questions for the user (answered above)

1. Should a word made only of taps get an auto space? Kinetica keeps
   this off by default, because tapped words end early often.
2. Should one-letter words (a, I) get an auto space, and after what
   delay?
3. Should the keyboard add a space after `.` and `,`?
4. Countdown on the space bar: wanted, or a distraction?
