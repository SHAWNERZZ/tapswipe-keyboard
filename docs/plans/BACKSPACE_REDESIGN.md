# Backspace redesign

Agreed on 2026-08-21. Not yet built.

Check each item against the code before you act on it. An item stays here
until it ships, then it moves to `docs/history/`.

## Why

Typing on this keyboard works on intent. You aim near the letters and the
lexicon resolves what you meant. Precision is optional.

Deleting works on mechanics. You aim at a distance, the unit is decided
in advance, and your thumb covers the text you are judging. Precision is
required.

The two halves use opposite models. That gap is what makes deleting feel
clunky next to typing. Every backspace defect so far came from the
mechanical half: travel per step, debt, dead zones, timer races.

The plan moves backspace toward the input model. Gestures state intent.
The keyboard removes what you actually made.

## The target behavior

| Gesture | Result |
|---|---|
| Tap | Remove the last gesture, while it is still available |
| Tap, then slide | Delete whole words |
| Plain slide | Delete characters |
| Swipe up | Delete the last word |
| Hold | Repeat, per the upstream setting |

Granularity is no longer a setting. The gesture states the intent. A
plain slide always gives characters. A tap-then-slide always gives words.

`pref_backspace_mode` is upstream and stays in the Typing menu. Only its
`Off` value still applies. The difference between `Characters` and
`Words` is ignored, because the gesture now decides.

## Items, in build order

### 1. Whole-word delete eats trailing punctuation

A bug. Type `Hello world.` and press backspace. You lose `world.` and
expect to lose `.`.

`InputLogic.deleteLastCommittedWord` walks back from the cursor and stops
only at whitespace. A period is not whitespace, so the walk passes
through it and takes the word as well.

Two-part fix.

1. Engage whole-word delete only when the character before the cursor is
   part of a word. A period is not, so backspace falls through to plain
   character delete.
2. When the walk runs, stop it at any code point that is not part of a
   word, rather than at whitespace alone.

`SettingsValues.isWordCodePoint` already draws that line. It counts an
apostrophe as a word connector, so `don't` stays whole. The existing
letter check keeps digit runs such as `3.14` protected.

### 2. Swipe up to delete the last word

The delete key ignores vertical movement today. The slide branch in
`PointerTracker.onMoveEventInternal` reads the horizontal position only,
then returns.

Three constraints.

- The built-in flick mechanism cannot be used. The delete key sets the
  sliding-cursor flag, and that flag disables flicks.
- The gesture needs vertical dominance and a distance threshold, then a
  lockout once the horizontal slide has committed a step. Without the
  lockout, a long slide that drifts upward fires a word delete during a
  careful selection.
- It must cancel the auto-repeat timers. They are cancelled by horizontal
  movement today, so an upward swipe would leave repeat running.

### 3. Restore tap-then-slide for words

This shipped in v0.6.0 and was removed in v0.7.0 to test a reversal
model. The reversal model is worse in use. Restore the earlier one.

The priming tap does its own work first, then the slide deletes words.
That stacking is intended and was confirmed in use.

Do not restore the granularity setting. See the target behavior above.

Bounce-to-refine is dropped. Character precision now comes from not
tapping first.

### 4. Gesture trails for the whole word

Trails today are per-finger and fade on a clock. A point waits 100 ms,
then fades over 800 ms.

Add a second layer that draws every gesture in the current word and
clears when the word commits. Keep the existing trail as live feedback
while a finger is down.

Brightness counts backwards from the newest gesture. It does not follow
elapsed time. Two reasons.

- Brightness then means "how many backspace presses away", which is the
  information needed to aim a tap.
- Alpha changes only when a gesture is added or removed, so the layer
  repaints on events instead of every 20 ms.

Taps draw nothing today and need their own marker. A ring or dot reads
differently from a line, which is what separates the two gesture types at
a glance.

Coordinates need a decision. The session stores points in the decoder's
normalized space. Drawing needs view pixels. Prefer capturing a second
copy in view space over inverting the transform at draw time. It keeps
the renderer independent of the decoder's space. Both copies must follow
the same lifecycle events.

Risk: trails sit on top of the keys. Master Mode reduces keys to dots
already, so old gestures need a low floor and the layer needs an opacity
ceiling.

### 5. Tap removes the last gesture

Bounded to the last word, by decision. Older text deletes by whole word.

| State | One tap removes |
|---|---|
| Word still open | The last gesture. This exists today |
| Last committed word | The last gesture of that word. New |
| Anything older | The whole word. Exists, needs item 1 |

The new tier needs the committed word's gestures kept in memory, which
`TapSwipeLearner` already does for learning. It also needs that word
returned to composing, with evidence and editor state restored together.

This is the riskiest item. Re-opening a word means the session, the
composing region, the cursor anchor and the generation counter must all
agree at the instant the tap lands. That seam has produced most of this
project's defects. Build it behind its own setting.

Ship it with item 4. The undo is hard to aim without the markers, and the
markers have little purpose without the undo.

## Settings

A `Backspace` sub-screen under TapSwipe, in the style of the Enter key
swipes screen.

```
BACKSPACE

TAP
  One tap removes        [Last gesture | Whole word | One character]

SWIPE UP
  Delete the last word   [on/off]

GESTURE TRAILS
  Keep trails for the word   [on/off]
  Show taps                  [on/off]

A plain slide deletes characters. Tap, then slide, to delete words.
Turn slide deletion off in Settings > Typing > Backspace.
```

Tap-then-slide has no toggle. It is the only route to word deletion, so a
switch would remove a capability with no replacement.

Upstream settings stay in the Typing menu. The closing line is a pointer,
not a control.

## Open questions

- Backspace would carry five gestures. Accidental triggering is the main
  risk. Every gesture needs a dominance test or a lockout so it cannot
  fire during another one.
- Item 5 needs a rule for words this keyboard did not type, such as text
  already in the field when it opened. Those have no gestures. Whole-word
  delete is the fallback.
