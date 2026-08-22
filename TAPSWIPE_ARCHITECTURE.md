# TapSwipe architecture (current behavior)

This document describes what the code does **today**. Historical rationale
lives in `TAPSWIPE_PLAN.md`. Completed phases and fix history live in
`docs/history/TAPSWIPE_IMPLEMENTATION_HISTORY.md`.

If this document conflicts with the code, the code wins. Fix this
document, then continue.

Verified against the tree on 2026-08-18. Lines that the code could not
confirm carry an `UNCONFIRMED` marker.

## 1. Input model

TapSwipe reimplements the Nintype input model on top of the FUTO neural
swipe decoder. A word is any accumulation of taps and swipes between two
finalizers. Cross-hand order inside a word is resolved by the lexicon,
not by wall-clock time.

## 2. The word session

`TapSwipeSession` owns the evidence for one word.

- Strokes are chronological. Each stroke is `TAP` or `SWIPE`, tagged with
  hand (`LEFT`/`RIGHT`), and carries deep-copied points.
- The session opens on the first letter input.
- The session closes only on a finalizer or an explicit reset.
- `hasSwipe` is true once any swipe joins the session.
- `literalText` is derived from tap strokes, not stored separately.

Home file: `java/src/org/futo/inputmethod/latin/tapswipe/TapSwipeSession.kt`.

The package directory is lowercase `tapswipe`. No `tapSwipe` directory
exists. Use the lowercase form in every path and glob.

## 3. Ownership

| Concern                              | Owner                          |
|--------------------------------------|--------------------------------|
| Input evidence for the whole word    | `TapSwipeSession`              |
| Current candidate text and composing | `WordComposer` + `setComposingTextInternal` |
| Decode over accumulated evidence     | `SwipeDecoderDictionary.decodeTapSwipe` path |
| Commit and learn                     | `InputLogic.commitChosenWord` funnel |

All four symbols exist. `decodeTapSwipe` lives in
`java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`. The
other three live in
`java/src/org/futo/inputmethod/latin/inputlogic/InputLogic.java`.

`WordComposer` holds the current best interpretation. `TapSwipeSession`
holds the evidence.

## 4. Modes

Mode is decided per word and latched once decided. A swipe forces the
word back to `SWIPE` mode.

| Mode        | Trigger                                       | Behavior                                                     |
|-------------|-----------------------------------------------|--------------------------------------------------------------|
| `SWIPE`     | any swipe in the word                         | Decoded verbatim. Autocorrect disabled. Morphing on.         |
| `UNDECIDED` | swipe-free and below the cadence threshold    | Behaves like stock while classification is pending.          |
| `PECK`      | swipe-free, slow inter-tap cadence            | Literal commit. Force-learn. Borders visible. Morphing off.  |
| `LEGACY_TAP`| swipe-free run of quick taps across words     | Stock behavior including autocorrect. Requires Master Mode.  |

The enum lives in
`java/src/org/futo/inputmethod/latin/tapswipe/TapSwipeUiState.kt`, not in
a file of its own.

Cadence uses the median inter-tap interval, threshold
`TapSwipePeckCadenceSetting` (default 250 ms). `LEGACY_TAP` is a run
counted across words (`TapSwipeLegacyTapRunSetting`, default 5) and is
reset by any swipe.

Peck mode also engages when one tap idles for about two seconds. A lone
tap followed by a pause cannot produce an inter-tap interval, so cadence
alone would never classify it.

## 5. Finalizers

A finalizer commits the current candidate, learns the word, then clears
the session.

- Space
- Punctuation covered by the existing separator set
- Enter

The ANTIPHANTOM space swallow must not drop a finalizer while a session
is open.

## 6. Decode scheduling

- On every input event, the session appends evidence.
- If `hasSwipe`, decode runs and writes the composing region.
- If not, the composing region is the literal string, and autocorrect is
  off.
- Decode runs on `InputLogicHandler`. Results are applied on the main
  thread by `applyTapSwipeCandidate`.

Every decode carries the session generation it was computed for. Apply
drops any result that does not match the current generation or arrives
after the session has closed.

## 7. Backspace

Four gestures share the key. Each states its own intent, so nothing is
inferred from movement alone.

### Tap

Three tiers, chosen by `TapSwipeBackspaceTapSetting`.

| Tier | State | Result |
|---|---|---|
| Open word | A word is composing | Pop the last stroke and re-decode |
| Last word | The word just committed | Reopen it, then pop the last stroke |
| Older | Anything before that | Delete the whole word |

The middle tier reconstructs the pre-commit state and then calls the same
code as the first, so there is one implementation of "pop a stroke". It
has no time limit. The record is replaced whenever another word commits,
so it only ever describes the last word, and two checks prove that word
is still in front of the cursor: the cursor position, and the text
itself.

Whole-word delete declines when the character before the cursor is not
part of a word, so a full stop is removed on its own. The rule lives in
`WholeWordDelete.lengthToDelete`.

`setRejectedBatchModeSuggestion` is not reached for stroke-level undo.
Whole-word backspace runs after all one-press revert branches.

### Slide

The entry gesture picks the unit, fixed for the whole slide.

- A plain slide deletes characters.
- A slide within 900 ms of a tap on the same key deletes words.

The upstream `pref_backspace_mode` values `Characters` and `Words` are
not read. Only `Off` still applies. See section 9 for the distance rules.

### Swipe up

Deletes the last word in one motion, behind
`TapSwipeBackspaceSwipeUpSetting`. Takes punctuation and digit runs with
the word, which a tap declines, because a gesture is unambiguous where a
tap is not. Rule in `WholeWordDelete.lengthToWhitespace`.

### Hold

Unchanged. Repeats per the upstream `pref_backspace_mode_hold`.

## 8. Re-entrancy guards

The design is validate-on-read. Each access checks that the session
still matches the editor. Five mechanisms protect it.

1. No composing word means no session.
2. Cursor anchor stored after each composing write. A mismatch resets
   the session.
3. Generation counter, bumped on every reset and pop.
4. Pure recomputation. Never incremental mutation of composing text.
5. Stroke cap. Log and reset past a plausible word length.

A re-entrancy flag (`mTapSwipeApplyingDecode`) suspends validation for
the duration of an apply. The composer and the session are legitimately
out of step during that window.

## 9. Key gestures and shortcuts

- **Comma pull.** A straight pull from V onto the space bar produces a
  comma. Matching lives in `PointerTracker` and `GeneralIME` and uses one
  shared rule.
- **Enter-key swipes.** Eight assignable directions. Each direction
  stores an option id. Unknown ids cost only their own direction.
- **Wide functional key.** `KeyWidth.WideFunctionalKey` gives Enter the
  hidden period's slot without stretching the spacebar.
- **Backspace slide.** Travel is denominated in characters at every
  granularity. A word costs the slide what its letters would have cost.
  Word mode is available from the first movement. Rules live in
  `java/src/org/futo/inputmethod/keyboard/internal/BackspaceSlideMode.kt`.
- **Word cost is charged after the fact.** A word's length is known only
  once the editor has consumed it. `GeneralIME.cursorStepped` reports the
  character count. `PointerTracker` then calls `chargeWord`. The slide
  runs one word ahead of what it has paid for.
- **Debt is separate from position.** An unpaid word leaves a debt. The
  anchor never moves past the finger. An anchor ahead of the finger would
  read as travel in the opposite direction, which is the same signal as a
  reversal.
- **Reversing steps the other way.** In word mode a turn takes a word
  back. Granularity does not change, because the entry gesture set it.
- **Auto-repeat is cancelled early.** The finger moving past a small slop
  stops the repeat timer. Repeat fires on a clock while the slide waits
  for distance. A slide can reselect, and it cannot undo a delete that
  repeat already performed.

## 10. Gesture trails for the word

Behind `TapSwipeWordTrailsSetting`. Draws every stroke of the word being
typed on the keys, so a backspace that removes one stroke has something
to aim at.

Distinct from the stock gesture trail, which shows the stroke under a
finger right now and fades about a second after it lifts. Both are drawn.
Home files: `WordGestureTrail` holds what to draw, and
`WordGestureTrailPreview` draws it.

**A projection, not a second list.** `InputLogic.publishGestureTrail`
replaces the whole drawing from the session's strokes after every change.
Nothing is added or removed one at a time.

That is the design, and it was arrived at by getting it wrong. An earlier
version collected points from touch events into its own list. It ordered
two-thumb swipes by which finger lifted, while the session orders them by
which finger went down, so a backspace erased one path and removed a
different stroke. It also used a different test for whether a stroke had
happened, so the lists could differ in length and stay wrong for the rest
of the word.

**Strokes carry their own view coordinates.** `TapSwipeSession.Stroke`
holds the points twice: normalized for the decoder, and in view pixels
for drawing. Both are taken from one segment at one moment. A stroke with
no view points is not drawn.

**Brightness counts strokes, not seconds.** The newest is brightest, and
older ones step down to a floor that stays behind the key labels. So
brightness answers how many backspace presses away a stroke is. It also
means alpha changes only when the strokes change, so the layer repaints
on those events rather than continuously.

**Tap or path is decided by shape.** A stroke that stayed within one key
width is drawn as a dot. This overrides the session's own label on
purpose. A tap made while another finger is mid-swipe reaches the session
through the swipe path, because the in-gesture flag is shared by every
finger, and arrives labelled a swipe. Only the picture is corrected. What
the decoder receives is unchanged.

Known limit: a real swipe between two adjacent keys also travels less
than a key width and draws as a dot.

## 11. Adaptive touch model

Home file:
`java/src/org/futo/inputmethod/latin/tapswipe/TapSwipeTouchModel.kt`.
Off by default, behind `TapSwipeAdaptiveGeometrySetting`.

- The model stores decay-weighted running moments per key, not an EMA.
  Sums allow one multiply to decay a whole accumulator, and allow
  weighted samples.
- Decay runs on wall-clock time, with a 45 day half-life
  (`HALF_LIFE_DAYS`). Decay does not run on update count. A key that
  someone stopped using fades on its own.
- A shift ramps in with confidence (`CONFIDENCE_HALF_WEIGHT`, 8.0).
- A shift is capped at `MAX_SHIFT_FRACTION` (0.25) of key size.
- Wide scatter suppresses a key (`MAX_USEFUL_SPREAD_FRACTION`, 0.60).
  Scatter means there is no habit to learn.
- Evidence is bucketed by layout key, which folds in the letter set and
  the orientation. Switching layout files evidence elsewhere. Nothing is
  deleted by a switch.

Persistence rules matter more than the arithmetic. See section 12.

- A failed read blocks writes for the rest of the process.
- An empty model never overwrites existing data unless `reset` asked.
- A known-good copy is snapshotted on load, and restored when the model
  is missing or unreadable.
- Teardown flushes on the calling thread.

Decay is by wall-clock time only. There is no moving window and no
per-sample age horizon. Add one only after a fresh decision.

## 12. Known risks and limitations

- **Swipe/space race under 50 ms.** Hitting space within about 50 ms of
  ending a swipe loses the word. No finger can reach the space bar in
  that window. The scenario runner reports `KNOWN`, not `FAIL`. Revisit
  if the 50 ms rung ever fails.
- **`sInGesture` is a single global boolean.** Two thumbs cannot hold
  independent gesture state. Overlapping two-thumb use works today only
  because a second finger inherits gesture mode.
- **`cancelBatchInput` is global.** One finger leaving the valid y-band
  cancels every finger's segments.
- **`pointerId >= 2` is dropped** in the touch layer today.
- **`InputPointers` is not thread-safe.** Deep copy at the session
  boundary is mandatory.
- **`InputConnectionInternalComposingWrapper`** emulates composing for
  some apps. Long composing regions must be tested there.
- **Learned geometry loss across install.** Reported once at v0.7.0.
  Cause never established. v0.7.1 added blast-radius mitigations and a
  logcat tag `TapSwipeTouchModel`. Recheck on every release.

## 13. Settings home

Screen file:
`java/src/org/futo/inputmethod/latin/uix/settings/pages/TapSwipe.kt`.
Keys are declared in
`java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`.

DataStore keys, verified on 2026-08-18.

| Setting                      | Symbol                             | Key                          | Default |
|------------------------------|------------------------------------|------------------------------|---------|
| TapSwipe input model         | `TapSwipeModeSetting`              | `tapswipe_mode`              | off     |
| Master Mode                  | `TapSwipeMasterModeSetting`        | `tapswipe_master_mode`       | off     |
| Legacy tap run               | `TapSwipeLegacyTapRunSetting`      | `tapswipe_legacy_tap_run`    | 5       |
| Adaptive key geometry        | `TapSwipeAdaptiveGeometrySetting`  | `tapswipe_adaptive_geometry` | off     |
| Exact tap positions          | `TapSwipeRealTapPositionSetting`   | `tapswipe_real_tap_position` | on      |
| What a backspace tap removes | `TapSwipeBackspaceTapSetting`      | `tapswipe_backspace_tap`     | last gesture |
| Swipe up deletes a word      | `TapSwipeBackspaceSwipeUpSetting`  | `tapswipe_backspace_swipe_up`| off     |
| Show the word's gestures     | `TapSwipeWordTrailsSetting`        | `tapswipe_word_trails`       | off     |
| Nintype gestures, comma pull | `TapSwipeNintypeGesturesSetting`   | `tapswipe_nintype_gestures`  | off     |
| Enter key swipes             | `TapSwipeEnterFlicksSetting`       | `tapswipe_enter_flicks`      | off     |
| Enter direction assignments  | `TapSwipeEnterFlickMapSetting`     | `tapswipe_enter_flick_map`   | four cardinals |
| Hide the period key          | `TapSwipeHidePeriodKeySetting`     | `tapswipe_hide_period_key`   | off     |
| Typing speed on the spacebar | `TapSwipeWpmSetting`               | `tapswipe_wpm`               | off     |
| Peck cadence                 | `TapSwipePeckCadenceSetting`       | `tapswipe_peck_cadence_ms`   | 250     |
| Swipe sensitivity            | `SwipeSensitivitySetting`          | `swipe_sensitivity`          | 3.0     |

Two related settings live outside DataStore, in SharedPreferences, and
are owned by upstream code.

| Setting                  | Key                        | Notes                          |
|--------------------------|----------------------------|--------------------------------|
| Swipe backspace mode     | `pref_backspace_mode`      | Off, characters, or words      |
| Hold backspace mode      | `pref_backspace_mode_hold` | Defaults to `pref_backspace_mode` |

Notes.

- `SwipeSensitivitySetting` has no `TapSwipe` prefix. It predates the
  fork's naming rule.
- The screen also carries a footnote about personalized dictionaries.
  Peck learning depends on that upstream setting.
- Three sub-screens hang off this menu: `tapswipeGeometry` for learned
  key geometry, `enterKeyFlicks` for the direction grid, and
  `backspaceGestures` for the backspace key.
