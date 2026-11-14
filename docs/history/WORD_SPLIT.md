# Automatic word split

**Superseded 2026-11-13.** The eager split described here was retired.
The segmenter now decides once, when the word ends; see
`docs/plans/AUTO_SPACING.md`. Kept as history.

Agreed 2026-08-23. Not yet complete. Behind
`TapSwipeAutoSplitSetting`, off by default.

Current behavior lives in `TAPSWIPE_ARCHITECTURE.md`. Move this file to
`docs/history/` when the feature ships.

## The problem

A word is every stroke between two finalizers. So a short tapped word
next to a swiped word fuses into one word.

Two orders fail today.

- Swipe `hello`, then tap `i`. The result is one mangled word.
- Tap `i`, then swipe `think`. The same.

Both are common. `I`, `a`, `be`, and `me` are faster to tap than to
swipe, and they sit next to longer words all the time.

## The motivating case

The user swipes across a, c, a. The decoder shows `ava`, a name saved in
the personal dictionary. The user taps `i`. The keyboard produces `acai`.

The mechanism, as a sequence of events.

1. The swipe becomes one stroke. The decoder reads it as `ava`.
2. The tap becomes a second stroke.
3. `TapSwipeInputBuilder.build` rebuilds the decode input from every
   stroke. The tap becomes a one-point segment on the path.
4. `SwipeDecoderDictionary.decodeTapSwipe` reads the whole path again.
5. `acai` is a real word, and a, c, a, i is its exact shape. It wins.

The decoder answered the question it was asked. This is structural. No
threshold change fixes it.

The root cause is step 3. A tap is allowed to re-read the swipe that
came before it. The swipe's shape did not change when the user tapped.
Its meaning must not change either.

## The rule

Never give the taps to the path decoder when testing a split.

1. Decode the swipe strokes alone. That yields a candidate.
2. Take the tapped letters as exact text. `TapSwipeSession.Stroke` already
   stores each tap's code point.
3. Join the two by string, in stroke order, and test the result in the
   dictionary.

One rule then serves both orders.

### Layer 1

Two dictionary lookups. Settles most words.

| Fused form is a word | Tap run is a word | Verdict |
|---|---|---|
| no | no | Wait. More taps may arrive. |
| no | yes | **Split.** |
| yes | no | **Fuse.** This is `cats` and `worked`. |
| yes | yes | Ambiguous. Go to layer 2. |

### Layer 2

First question: does the fused word still begin with the head?

If it does not, the tap rewrote the strokes before it. Split. This is the
`ava` case, because `acai` does not begin with `ava`.

When taps lead, the head is exact text rather than a guess, so this test
is proof rather than evidence.

If it does begin with the head, compare numbers.

- Cost of fusing: the frequency of the fused word.
- Cost of splitting: the probability of the tail following the head.

`BinaryDictionary.getNgramProbability` and `isValidNgram` provide the
second number. The personal dictionary learns n-gram entries, so this
improves with use.

### On a tie

Fuse, and put the split reading in the suggestion strip.

Repair cost decides this. A wrong fuse leaves the composing region wrong,
and that region gets replaced for free. A wrong split has already
committed the head word. Fusing on a tie also matches today's behavior,
so a tie can never cause a regression.

## Why the tap itself is evidence

A tap is currently reduced to a position. The decision to lift the finger
is discarded.

`are` and `a` sit close together, so a user who wants `area` can swipe
the whole word. Tapping the `a` separately is therefore a choice, and the
choice carries intent.

The signal varies in strength. `usa` is the strong case, because `s` and
`a` are adjacent and swiping `usa` is one easy motion. `area` is the weak
case, because the path doubles back over its own start and that is the
shape the decoder handles worst.

Two measurements support this, and both come from data the session
already holds.

- The gap between the swipe lift and the tap.
- The distance from the swipe's last point to the tapped key.

The direction of the second measurement is unknown. A short distance may
mean the tap was deliberate, because including it would have been easy.
It may instead mean the keys are adjacent, which is where swiping is
least reliable. Measure before choosing.

## Direction asymmetry

The two orders are not equally risky.

Swipe-then-tap mostly resolves at layer 1. Tap-then-swipe reaches layer 2
constantly, because English is full of short words that begin longer
words: `be`/`because`, `to`/`today`, `no`/`nothing`, `so`/`some`,
`a`/`about`.

Layer 2 settles almost all of those on frequency, since `be cause` is
close to nonsense. The residual set is the open and closed compound
pairs: `a way` and `away`, `in to` and `into`, `may be` and `maybe`.
Those are ambiguous for human writers. Leave them to the strip.

## Build order

1. The setting, and its toggle on the TapSwipe page.
2. `TapSwipeWordSplit`, a pure function, with JVM tests. **Done.**
3. The dictionary accessors. Word frequency is **done**:
   `DictionaryFacilitator.getWordFrequency` was added because a tapped
   `i` before a gesture over h, e, l reads as `Irk`, and only frequency
   says which of `Irk` and `i hell` someone meant. N-gram probability is
   still outstanding: it lives on `BinaryDictionary` rather than the
   `Dictionary` base class, so reaching it means a method on the base
   class and an override in each wrapper.
4. Wire the verdict into the decode-apply path. Commit the head word
   through `InputLogic.commitChosenWord`, then rebuild the session from
   the tail strokes.
5. Offer the losing reading in the suggestion strip.
6. Scenario runner coverage.
7. Device calibration of the score comparison.

## The seam

- **Evidence:** the stroke list. **Derived result:** the boundary, and
  committed text.
- **Invariant:** a split may only act on the session generation it was
  computed from.
- **The dangerous window:** the fused candidate arrives from a background
  thread, and a split commits text. The generation guard in
  `onUpdateTailBatchInputCompleted` runs when a decode is applied. A
  commit needs that check before it runs. Committing on a stale decode
  writes text that no later correction can replace.

Splitting the session needs no new lifecycle. Capture the tail strokes,
call `TapSwipeSession.reset`, then call `restoreStrokes` with the tail.
Both already exist, and both already bump the generation.

Capture the grace record at the split, as a normal finalize does. That
gives one backspace to reopen the head word.

## Fixed after the first hardware test

**Space after a split committed the wrong word.** Reported: swipe `ava`,
tap `i`, press space, get `ava ava`.

The sequence of events.

1. The tap fires two suggestion queries. One decodes the strokes.
2. The other is an ordinary typing query over the literal in the editor
   at that moment, which is `avai`.
3. The decode lands first. The split commits `ava` and reopens `i`.
4. The typing query finishes. `ava` is one deletion from `avai`, so it
   produces `ava` as an autocorrection, and stamps it on the composer.
   The composer now holds `i`.
5. Space took the autocorrect branch and committed the stamped answer.

The recurring bug class exactly: a derived result computed for evidence
that no longer exists, applied to what replaced it.

The fix makes a split word verbatim, so space commits the composer's own
text rather than a stamped result. Racing the stale query would not have
been a fix. The split already required both sides to be dictionary words,
so autocorrect had nothing to contribute anyway.

The flag clears as soon as another stroke joins, so only the word the
split actually decided is protected.

Still outstanding from the same cause: the suggestion strip lists
candidates for the pre-split word for a moment. Picking one in that
window would apply the wrong word. Left alone until it is seen on a
device, rather than adding another query that races the first.

## The boundary is not the kind change

Reported: swipe `this`, tap `i` and `s`, get `to sis`. The word was typed
as a gesture over t, h, i **plus a tapped `s`**, which is what made it
diagnosable.

So the strokes were one gesture and three taps: `s`, `i`, `s`. The first
tap finishes the gestured word. The last two are the next word.

The old search looked only at the kind change, so it treated the whole
tap run as the second word. The gesture alone read as `to`, and every tap
after it became `sis`.

The fix builds a boundary at every position in the tap run and picks
between them. Later boundaries win: let the gestured word absorb as many
following taps as it can, and start the new word with what is left. In
the reported case boundary 1 gives `to` and `sis`, boundary 2 gives
`this` and `is`, and boundary 2 is later.

Each boundary needs the reading of the strokes in front of it. That is
not recomputed. Every stroke already triggers a decode, so the reading of
each shorter prefix was produced one stroke ago and is kept in
`mTapSwipeReadingByStrokes`, indexed by stroke count.

Taps before a gesture keep the single kind-change boundary. A boundary
inside the leading taps would leave a tail holding both taps and a
gesture, and a tail has to be describable on its own.

## Two more defects, found from the same report

Both were found while diagnosing `to sis`.

**A suffix match is not a fusion.** `this` ends with the letters `is`, so
the old code counted `this` itself as a one-word reading of `this` plus
`is`. That left `th` standing as the reading of a four-letter gesture,
and the boundary was judged against it.

The fix asks whether the leading part could be the same gesture read
another way. Two readings of one path start on the same key and have
close to the same number of letters. `aca` for `ava` passes both. `th`
for `this` fails on length. Constant `MAX_READING_LENGTH_DRIFT`.

**A partial decode was treated as a full one.** Adding a stroke does not
bump the session generation, so a decode dispatched before the newest
stroke still passes the staleness guard and arrives describing fewer
strokes than the session holds. Two consequences.

1. The gesture's own decode is asynchronous, so a fast tap arrives
   before it lands and nothing describes the head. The split declined
   every time the user typed quickly. Now a decode covering exactly the
   strokes before the boundary is recorded as the head reading, which is
   what it is.
2. Worse, the split ran against a gesture-only candidate list. Such a
   list cannot contain a reading of the whole word, so its silence looked
   like proof that none exists. That split `are` from `a` on every fast
   tap. The split now requires the decode to cover every stroke.

The second one is the same shape as the first fix. A derived result
computed from part of the evidence, judged as though it described all of
it.

## Found by the scenario runner on hardware

**Prefix readings outlived their word.** Reported as a gesture over t, h,
i plus tapped `s`, `i`, `s` producing `Cats is`. `Cats` came from the
previous scenario.

The readings were held in `InputLogic` and cleared in
`resetTapSwipeSession`. But `TapSwipeSession.validateOrReset` calls its
own `reset` from five places, and none of those pass through the caller's
teardown. So the readings survived into the next word and a boundary was
judged against a head from the word before it.

This is the exact failure mode `TapSwipeSession` was built to prevent for
strokes, which is why it validates on read. Anything derived from the
strokes has to live under the same protection. The readings now live in
the session and die with it.

Also changed: a missing reading now declines the whole word rather than
judging only the boundaries that happen to have one. A missing reading
means a decode did not apply, and the boundary it would have described
could be the right one. Splitting at an earlier position then commits a
shorter head than the user typed.

**Taps before a gesture cannot be judged properly yet.** Reported as a
tapped `i` before a gesture over c, a, t producing `Ivar`.

The tail of a one-word reading is not the tail of the split. `Ivar`
implies a tail of `var`. The gesture on its own reads `cat`. So the
comparison weighs `Ivar` against a tail no gesture produced, and the real
alternative, `I` and `cat`, is never considered.

A gesture followed by taps has no such problem, because the head's
reading comes from the decode covering exactly those strokes. There is no
equivalent for a gesture at the end of a word: no prefix decode covers it
alone.

The fix is to decode the gesture by itself when this shape appears. One
extra decode, in one shape, once per word. Raising the rarity floor
instead would only change which names break. Recorded as a
`knownLimitation` on the scenario until then.

## A decode that read nothing skipped the split

The runner reported `Thisis` and `He'lli`, both of which are the literal
text rather than any decoded word. The recorded decision named one
boundary for a four-stroke word, which is the decision from two strokes
earlier still standing.

`onUpdateTailBatchInputCompleted` returned immediately when the decode
produced no word. That return skipped the split decision along with the
write.

Nothing fitting the strokes as one word is the strongest evidence there
is that they are two words. It is also common: the beam search finds no
lexicon word for a gesture with unrelated taps stuck on the end, which is
exactly what one word followed by another looks like. So the case the
split most wants to see was the one case it never saw.

Now only the write is skipped.

Two related changes came with it.

- A boundary with no reading is skipped again rather than declining the
  whole word. Declining was a guard against readings left over from an
  earlier word, and that is now prevented at the source by keeping them
  on the session. A decode that produced nothing is common, and it must
  not stop the boundaries either side of it being judged.
- Every reason the split is skipped is recorded, not just the decisions
  it makes. A stale line read as a decision that had been made, and cost
  a diagnosis.

## The session timeline outlived its word

The same recorded line showed `gap=128673` between two strokes made half
a second apart.

`mTapSwipeSessionOriginMs` is cleared in `resetTapSwipeSession`, and the
absorb paths only re-established it when it was negative. A session that
dropped itself from inside `validateOrReset` left the value behind, so a
new word opened on a timeline belonging to a word from minutes earlier.

The first stroke of a word now always establishes the timeline, tested
against the session rather than against the stored value. Same shape as
the readings defect: derived state that has to be tied to the session's
identity rather than to one teardown path.

## Words splitting with the setting off

Reported 2026-08-29: with the toggle off, words still seemed to split
occasionally.

The split cannot run in that state. `performTapSwipeSplit` is called from
one place, inside a gate that includes the setting, and it now re-checks
the setting itself. `TapSwipeSplitDebug.performedCount` counts splits
actually performed and is shown in the Memory Debug panel, so the
question can be settled rather than argued.

**The likely cause is the phantom space, which predates this feature.**
The sequence:

1. A gesture lands and its word is composed. `onUpdateTailBatchInputCompleted`
   arms `SpaceState.PHANTOM`, because the first stroke of a word behaves
   like stock.
2. Something ends the composing state before the next tap. On the
   emulated-composing input connection this happens transiently, because
   that wrapper implements a composing region with real `commitText`
   calls.
3. The session validates on the next read and drops itself, so
   `tapSwipeFusing` in `handleNonSeparatorEvent` is false.
4. The tap therefore takes the auto-space branch and writes the owed
   space.

The result is the gestured word, a space, then the tap beginning a new
word. Indistinguishable from an automatic split by looking at the text.

Confirmed 2026-08-29: the symptom was reproduced with
`splitsPerformed` still at zero. So the automatic split is not the cause,
and the phantom space is the remaining candidate.

Not fixed yet, because the fix depends on which invariant drops the
session, and that is not knowable from here. The panel now reports:

- `autoSpaces`, counting automatic spaces written by the ordinary typing
  path while TapSwipe was on, with the code point and the session's reset
  history at the moment it happened
- `resets` and `lastReset` on the session, naming the invariant that
  fired

A count that rises alongside the symptom confirms the path. The reset
reason then says which of the three validate-on-read invariants to look
at.

**Do not simply stop arming the phantom.** It was armed on the first
stroke of a word, and removing it trades a spurious space for words
running together, because the guard that inserts the separating space at
the start of the next word reads the same phantom. The fix has to address
why the session is dropping, not the space that follows from it.

## Likely cause found, 2026-11-11

The typing simulator caught the session dropping mid-word with the
reason `editor is no longer composing`, in 11 of 25 swipe-then-tap
words, with the split off. The cause was a late selection report of the
keyboard's own tap write, arriving after the decode rewrote the word.
`onUpdateSelection` read it as a cursor move and reset the input state.
The next tap then began a new word, which is the symptom above.

Fixed by `TapSwipeOwnSelections`. See `TAPSWIPE_ARCHITECTURE.md`,
section 8. Confirm on the phone: the `autoSpaces` and `resets` counts in
the debug panel should stop rising with the symptom.

## Decisions still open

- **Capitalization of `i`.** A tap-only word commits literally in peck
  mode, with autocorrect off. So `i think` keeps a lowercase `i`. Decide
  whether a split tap word goes through the capitalization pass.
- **What gets learned.** Peck force-learns the literal, which would bump
  the personal frequency of `i`, `a`, `me`, and `be`. Proposed rule: do
  not force-learn a word the main dictionary already holds.
- **Undo granularity.** After a split, one backspace pops a stroke in the
  tail word, and a second reopens the head word. Decide whether the split
  itself should be one undo step.

## Consequences to expect

Eager splitting keeps the session short. Each confident boundary commits
at once, so the session holds one word plus a pending tap run and never
more. The stroke cap of twelve stays comfortable.

This stops being true if commits are ever deferred to the finalizer,
which is a further reason to commit at the boundary.

Users will also press space less once this works, so runs get longer and
one session sees more boundaries.
