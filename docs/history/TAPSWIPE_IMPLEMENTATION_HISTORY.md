# TapSwipe — implementation history

This document holds the fix-by-fix record and the completed-phase
checklists. It is read-only reference material. Do not treat entries
here as pending work.

For current behavior, read `TAPSWIPE_ARCHITECTURE.md`.
For rationale that led to the design, read `TAPSWIPE_DESIGN_RATIONALE.md`.

## How to read this document

Each fix records:

- What was reported.
- What the code actually did.
- Why the original design missed it.
- What the fix changed.
- The invariant that would have caught it earlier, if any.

The pattern is nearly always the same. A defect sits in the seam between
when evidence changes and when a derived result is applied. Cite that
pattern when you diagnose new bugs, not the individual fixes.

## Recurring bug class

Nearly every defect below is a validate-on-read failure in a transition
window. Fix 1 exempted the pre-first-write window. Fix 6 exempted a
single call site. Fix 8 added the re-entrancy guard. Fix 10 was the
same pattern in stroke pop. Fix 14 was the same pattern one file
downstream of a correct pure rule.

If you are about to write a new invariant, name the transition window
in which it is legitimately violated. Handle that window explicitly.

## Fix index

| Fix | Summary | Main files |
|-----|---------|------------|
| 1 | second swipe replaced the first word instead of extending it | `InputLogic`, `GeneralIME`, `SwipeDecoderDictionary` |
| 2 | tap-then-swipe was silently losing the tapped prefix | `InputLogic`, `Suggest` |
| 3 | phantom space broke fusion after a swipe | `InputLogic`, `PointerTracker` |
| 4 | swipe + tap committed the pre-tap word | `InputLogic`, `Suggest` |
| 5 | peck-mode key borders never appeared | `TapSwipeSession`, `WordComposer`, `SwipeDecoderDictionary` |
| 6 | "but" still reverting to "by" (the real cause) | `TapSwipeSession` |
| 7 | stale decodes could land after a word was finalized | `TapSwipeSession` |
| 8 | "but"→"by" again: validation firing *inside* the apply window | `TapSwipeSession` |
| 9 | swiped words lost their sentence-start capital | `InputLogic`, `WordComposer`, `Suggest` |
| 10 | stroke undo appended instead of replacing ("But" → "ButBy") | `WordComposer` |
| 11 | the pull never fired at all | `GeneralIME`, `PointerTracker`, `NintypeGestures` |
| 12 | only fired if you stopped exactly on the space bar | `PointerTracker` |
| 13 | missed when reaching V with the right hand | `NintypeGestures` |
| 14 | the reversal did nothing when the base mode was Words | `GeneralIME`, `BackspaceSlideMode` |

## 2. Phased implementation

Each phase is independently testable.

### Phase 0 — Feasibility spikes (do first, cheap)

Answers the unknowns before committing to the design.

**Status: COMPLETE — all four spikes pass on-device. The design is viable as written.**

Run on an en-QWERTY layout with the special decoder and context LM both active (`hasDecoder=true hasLm=true`, 3 tries, weights 1.0/1.0/1.0).

| Spike | Result | Verdict |
|---|---|---|
| S1 | `hel`+`lo` (2 segs) → **hello** rank 0 (6.377) | PASS |
| S2 | L=`saw` R=`hn` → **Shawn** rank 0 (5.710) | **PASS — gating assumption confirmed** |
| S2b | L=`hlo` R=`el` → **hello** rank 0 (5.813) | PASS |
| S3 | 64 segments / ~2048 timesteps, no failure | PASS |
| S4 | taps h,e,l + swipe l→o → **hello** rank 0 (6.199) | PASS |

Four findings that change the plan:

1. **Cross-hand interleaving is lexicon-driven — confirmed.** The controls prove it: `saw` alone → `saw, Shaw, sat, law`; `hn` alone → `in, then, on, hen`. Neither contains "Shawn"; only the two streams *together* produce it. `decode_multi` really does resolve the interleaving through the lexicon, with no timestamps. **Two-thumb overlap needs only correct per-hand ordering.**

2. **Segment boundaries encode double letters.** A single continuous `hello` stroke ranks `help` (4.544) *above* `hello` (4.257) — the classic swipe double-letter ambiguity. Split as `hel`+`lo`, `hello` jumps to rank 0 at 6.377. Re-touching a key starts a new segment, and that re-touch is itself the signal for the repeated letter. TapSwipe's tap-then-swipe style therefore *disambiguates better than a pure swipe*, rather than merely coping. Retires risk 3.7 and is a genuine quality argument for the whole approach.

3. **No practical timestep ceiling.** The `"Too many timesteps specified!"` path never triggered, even at 64 segments per hand (~2048 timesteps) — far beyond any real word. Scores decay roughly linearly (about −2.8/segment on garbage input), so the real limit is confidence and latency, not a hard cap. Risk 3.3 downgraded; keep a generous sanity cap only.

4. **Tap dwell length is irrelevant — use a single raw point.** Scores were *byte-identical* across dwell = 1/2/4/8/16 (`hello` 6.199 every time), because each segment is resampled to exactly 64 points internally, so a 1-point and a 16-point stationary segment collapse to the same input. **No dwell synthesis needed in Phase 3** — the single down-point a swallowed tap already produces is sufficient. Retires risk 3.2 and removes work.

Not covered by these spikes, still open:
- **Latency** of re-decoding an accumulated word on every event (risk 3.4). An `S5` latency spike using `SwipeDecoder.lastTiming()` is added to the harness and will be measured on the Phase 1 build.
- Real touch noise — synthetic paths are perfectly straight and evenly sampled.
- The `count == 1` special case at `SwipeDecoderDictionary.kt:433-435`, which routes a lone segment to `left` regardless of its actual pointer id.

*Ordering correction:* the session-state panel described in §6.4 can only be built once a session exists, so it moves to Phase 1. Phase 0 instead ships a **spike runner**, which is what's actually needed to answer S1–S4.

Implemented:
- `java/src/org/futo/inputmethod/latin/tapSwipe/TapSwipeSpikes.kt` — synthesizes trajectories from the **applied layout's normalized key centers** (`SwipeDecoderDictionary.appliedLayoutInfo.letters/xs/ys`), so the spikes run in exactly the coordinate space the real path uses, with no pixel math and no touch input. Decodes via the live decoder under `BinaryDictionary.sTrieUsageLock` using the live tries and trie weights.
- `SwipeDecoderDictionary.debugGetOrInitDecoder()` — exposes the lazily-created decoder to the harness.
- `MemDebugAction.kt` — "TapSwipe Phase 0 Spikes" section: run button, on-screen report, copy-to-clipboard. Results also go to logcat under tag `TapSwipeSpikes`.

Incidental fix: `tools/make-keyboard-text-py/src/generate.py` opened UTF-8 JSON without an explicit encoding, so the `:updateLocales` codegen task failed on Windows under the cp1252 default. All three `open()` calls now pass `encoding="utf-8"`. Pre-existing upstream issue, unrelated to this work, but it blocked every build on this machine.

**The spikes.** Driven directly against `SwipeDecoder` with synthetic input, no IME plumbing.

- **S1 — Multi-segment accumulation actually decodes.** Hand `recognize()` two synthetic segments for one word (e.g. `hel` + `lo`) and confirm a sane candidate. Establishes that concatenated per-segment emissions work as inferred.
- **S2 — Cross-hand interleaving is lexicon-driven (§0.1).** Feed left = `S,A,W`, right = `H,N` and check `shawn` appears. **This is the highest-risk assumption in the plan.**
- **S3 — Timestep budget.** The native lib contains `"Too many timesteps specified! %zu:%zu"`. At 32 timesteps per segment, find the ceiling empirically by growing segment count until it trips. Determines the cap in §3.6.
- **S4 — Tap-as-segment quality.** A tap is a 1-point segment resampled to 64 near-identical points — off-distribution for a model trained on swipes. Compare: (a) 1 raw point, (b) synthesized dwell of N points at the key centre. Pick the better; if both are poor, fall back to the merged-trajectory variant in §5.1.

### Phase 1 — Defer finalization to space (swipe-only)

Goal: multiple **sequential swipes** accumulate into one word; only a finalizer commits. No taps yet.

**Status: implemented and compiling; NOT yet verified on device.**

Behind `TapSwipeModeSetting` (`__experimental_tapSwipe_mode`, default **off**) — Dev Settings → TapSwipe → "TapSwipe input model". Stock behaviour is one toggle away on the same build.

New files:
- `tapSwipe/TapSwipeSession.kt` — the word-scoped accumulator, implementing the §5.5 contract (generation counter, cursor anchor, `validateOrReset`, `MAX_STROKES` fail-safe, derived `literalText`, deep-copying `addSwipeSegments`).
- `tapSwipe/TapSwipeDecodeInput.kt` — immutable per-decode evidence snapshot, carrying its source generation.
- `tapSwipe/TapSwipeInputBuilder.kt` — unions completed session strokes with the in-progress stroke; also folds a lone right-hand stroke into the left stream, mirroring `SwipeDecoderDictionary.kt:433-435`.

Modified:
- `ComposedData` — optional `mTapSwipeInput` (typed `Object` to keep `common/` free of a swipe-library dependency); new 4-arg constructor, old 3-arg delegates.
- `WordComposer` — `setTapSwipeInput`, passed through `getComposedDataSnapshot`, cleared in `reset`.
- `SwipeDecoderDictionary` — `decodeTapSwipe` path taking precedence over single-batch pointers; `currentNormalizers()` exposing the exact `transformSegment` arithmetic; `TapSwipeModeSetting`; extracted `contextWordsFrom`/`resultsToSuggestions` helpers.
- `BatchInputArbiter` — `getBatchOriginTime()` so per-batch timestamps can be re-based onto one word timeline.
- `InputLogic` — session field, `tapSwipeSession()` validate-on-read accessor, `absorbBatchIntoTapSwipeSession` (once per batch, at finger lift), `buildTapSwipeDecodeInput` (unions the live stroke only at `UPDATE_BATCH`), no `commitCurrentAutoCorrection` on batch start, no `finishComposingText`/auto-space/PHANTOM re-arm while a session is open, `noteComposingWrite` anchoring, unconditional session reset on any finalizer, ANTIPHANTOM swallow bypassed while a session is open, explicit reset on backspace.
- `GeneralIME` — `inputLogicForDebug` accessor.
- `MemDebugAction` — live "TapSwipe Session" panel (polls every 250ms; a leaked session is visible immediately) plus the Phase 0 spike runner.
- `DevSettings` — the TapSwipe toggle.

#### Fix 1 — second swipe replaced the first word instead of extending it

Reported symptom: swipe `H→E` gives "He"; swipe `L→O` replaces it with "Ali" (the best word for that stroke alone).

Two distinct defects, both from the same oversight — treating *"a session exists"* and *"a session has produced text"* as the same state.

1. **The composing-word invariant fired during a legitimate transient window.** At the first stroke's tail decode, `validateOrReset` checks `isComposingWord()` — but the composer does not receive the decoded word until `onUpdateTailBatchInputCompleted`, which runs *afterwards*. So the invariant wiped the stroke that had just been absorbed. The second stroke then decoded alone.

   Fix: the editor-state invariants only apply once the session has actually written a composing region (`hasComposed`). Before that, strokes-without-a-composing-word is normal. The `MAX_STROKES` fail-safe still applies unconditionally.

2. **The first stroke of a word was wrongly treated as a continuation.** `tapSwipeOpen` was true from the very first stroke, so `finishComposingText` and the phantom auto-space were skipped even when starting a brand-new word — which would have run consecutive words together.

   Fix: skip finalization/auto-space only for a *continuation* (`isOpen && hasComposed`). The first stroke behaves exactly like stock.

Validation was also strengthened while fixing this: it now checks that the composing word still equals the text we last wrote (`typedWord != lastComposedText`), catching replacement by recorrection or a suggestion pick, not just cursor movement.

**Lesson for the remaining phases:** validate-on-read is only as good as its notion of identity. An invariant that is correct in steady state can still be wrong during the window between mutating evidence and applying its result. Any new invariant must be checked against that window.

Deliberately still absent (later phases): taps do **not** join the session yet, so tap+swipe fusion doesn't work (Phase 3); autocorrect is not yet gated on `hasSwipe` (Phase 2); backspace resets the whole session rather than popping one stroke (Phase 5).

1. Add `TapSwipeWordSession` + deep-copy of `GestureSegment`s, re-basing `t` per §0.4. Expose the batch origin (a getter for `sGestureFirstDownTime`) so times can be normalized.
2. `InputLogic.onStartBatchInput` (`:763-775`) — **remove the `commitCurrentAutoCorrection` at `:773`**. Keep the `resetEntireInputState` at `:770` for the genuine mid-word-cursor recorrection case only.
3. `InputLogic.onUpdateTailBatchInputCompleted` (`:2637-2661`) — stop finalizing: don't `finishComposingText()` at `:2648` while a session is open. The `distinct` parameter is a ready-made lever (`distinct=false` already exists, exercised by `GeneralIME.requestSuggestionRefresh`, `GeneralIME.kt:742-751`).
4. New `SwipeDecoderDictionary.getSuggestionsForSession(...)` decoding from the session rather than `composedData.mInputPointers`. Keep the existing `getSuggestions` for the legacy path.
5. Finalizer handling — `handleSeparatorEvent` (`:1224-1235`) commits the session candidate, then clears the session. Space, punctuation, and Enter all finalize (§6.2), so the existing separator set is kept and we hook the commit rather than narrowing the trigger.
6. **Fix the ANTIPHANTOM space swallow** (`:1068-1072`): a space arriving while `mSpaceState == ANTIPHANTOM` is currently dropped entirely. Since space is now the sole commit, it must never be silently swallowed while a session is open.
7. Clear the session on: cursor move, `finishInput`, editor action, backspace-to-empty.

### Phase 2 — Peck mode

**Status: implemented and compiling; Phase 1 verified working on device.**

A word is a *peck* word when TapSwipe is on and no swipe contributed to it (`isTapSwipePeckWord()` = `isTapSwipeMode() && !session.hasSwipe`). The session is read without revalidating: a leaked session can only report `hasSwipe=true`, which suppresses peck behaviour rather than applying it wrongly — the conservative direction.

Three gates, each at an existing precedent site:

1. **Commit** — `handleSeparatorEvent` takes `commitTyped` instead of `commitCurrentAutoCorrection` for peck words, so the literal string is committed verbatim.
2. **UI / strip** — `setSuggestedWords` clears `mWillAutoCorrect` for peck words, alongside the existing `textBeforeCursorLooksLikeURL()` clause. Candidates stay listed and tappable (§6.3) but none is highlighted as auto-applying. This single choke point also covers the transformer-LM path, which stamps merged candidates `KIND_WHITELIST` and would otherwise bypass the autocorrect threshold entirely — so gating `isCorrectionEnabled` at the `Suggest` call was not needed.
3. **Learning** — new `forceValidWord` flag plumbed through `WordLearner.addToHistory` → `DictionaryFacilitator.addToUserHistory` → `addWordToUserHistory`, where `isValid = forceValidWord || maxFreq > 0`. Set for peck commits, this escapes the count-0 dead zone (§0.3) so a pecked out-of-dictionary word is swipeable immediately rather than after two commits. The old 5-arg `addToUserHistory` remains and delegates with `false`.

On the typo risk of force-learning: a pecked word is a deliberate "I mean exactly this" signal (no autocorrect was in the way and the user saw the literal text before pressing space), and user history uses a forgetting curve, so a one-off typo decays on its own. It only affects genuinely OOV words — known words already have a real frequency.

#### Fix 2 — tap-then-swipe was silently losing the tapped prefix

A regression introduced in Phase 1, found while reviewing Phase 2 rather than on device. Removing the `commitCurrentAutoCorrection` from `onStartBatchInput` meant that typing `h` and then swiping discarded the `h`: the swipe's `setBatchInputWord` resets the composer and `setComposingTextInternal` overwrites the composing region. Stock produces `h hello`; Phase 1 produced `hello`.

The commit is now skipped only when a session is already **open** (a genuine continuation). With no session open the word so far is pure taps — which do not join the session until Phase 3 — so it is committed exactly as stock does. Phase 3 replaces that branch with real tap absorption.

#### Original Phase 2 checklist

1. Track `session.hasSwipe`.
2. Autocorrect gate at `:1225` — take `commitCurrentAutoCorrection` only when `hasSwipe`; otherwise `commitTyped` (`:1232`), which already commits `mWordComposer.getTypedWord()` verbatim.
3. Also pass `isCorrectionEnabled = false` at `:2877` for peck words. **Necessary, not redundant:** `LanguageModelFacilitator.processAndMergeSuggestions` stamps merged candidates `KIND_WHITELIST` (`xlm/LanguageModelFacilitator.kt:62`), and `AutoCorrectionUtils.suggestionExceedsThreshold` short-circuits `true` for whitelist entries (`utils/AutoCorrectionUtils.java:35-38`) — so threshold tuning alone cannot suppress autocorrect.
4. Gesture suggestions are already suppressed for non-batch input (`SwipeDecoderDictionary.kt:402`). The strip itself stays populated with tappable dictionary candidates (§6.3) — only the *automatic application* of a correction is gated. Manual picks continue through `onPickSuggestionManually` (`:434-528`).
5. **Learning fix** — `DictionaryFacilitatorImpl.java:775`: force `isValid = true` for peck-committed words (or add twice, or call `addUnigramEntry` with a nonzero count) to escape the `count == 0` dead zone in §0.3. Keep respecting `mNoLearning` / password / `isPersonalizationEnabled` (`InputLogic.java:2013-2015`).

### Phase 3 — Tap + swipe fusion

**Status: implemented and compiling; Phases 1 and 2 verified on device.**

Taps now join the session as `TAP` strokes, so `tap H, tap E, tap L, swipe L→O` decodes as one word.

- **Tap location comes from the layout, not the touch event.** `SwipeDecoderDictionary.normalizedKeyPosition(codePoint)` looks the letter up in `appliedLayoutInfo`. This sidesteps two real problems: tap coordinates pass through `MainKeyboardView.getKeyX/getKeyY` (which strips view padding) while gesture points do not — *different coordinate spaces* — and `onCodeInput` reports `NOT_A_COORDINATE` unless the key has proximity correction. Layout key centres are already in the decoder's space. Phase 0 / S4 established a single point suffices.
- **Taps are recorded even in peck mode**, where they stay inert (peck words are never decoded). If a swipe arrives later, the preceding taps are already part of the word.
- **Re-decode on tap once the word contains a swipe**: after writing the literal character, `postUpdateSuggestionStrip(INPUT_STYLE_TAIL_BATCH)` reuses the existing tail-batch machinery to decode the whole word and overwrite the composing region — the same path a finger lift takes. Peck words skip this and keep their literal text.
- `handleNonSeparatorEvent`'s `|| mWordComposer.isBatchMode()` teardown is now suppressed while fusing; a tap on a swiped word is more evidence, not a correction.
- The interim commit from Fix 2 is subsumed: taps open the session, so `onStartBatchInput` skips the commit naturally and the tapped prefix fuses into the swipe.

#### Fix 3 — phantom space broke fusion after a swipe

Found by inspection while wiring the above. A phantom space is armed after every swipe, meaning "the next input starts a new word". Two paths acted on it and both had to be suppressed while a session is open:

- `handleNonSpecialCharacterEvent` pre-commits the composing word (`commitTyped`) when PHANTOM is set — which would have committed the swiped word the instant a tap followed it, making fusion impossible.
- `handleNonSeparatorEvent` then inserts an automatic space, and its validity check `throw new RuntimeException("Should not be composing here")` would have **crashed** once the pre-commit above was skipped, since the composing word legitimately survives into that branch now.

Also fixed here: taps were being timestamped with `System.currentTimeMillis()` while gesture segments derive from `MotionEvent.getEventTime()` on the `SystemClock.uptimeMillis()` clock, putting them on timelines offset by an arbitrary amount. Harmless in practice — each segment is resampled relative to its own first point and the beam search never sees timestamps (§0.1) — but only by accident, so both now use uptime.

#### Original Phase 3 checklist

1. Append a TAP stroke on each letter tap. Coordinates: `mImeHelper.getCodepointCoordinates(codePoints)` already exists and is used at `InputLogic.java:417` — reuse it rather than depending on `onCodeInput`'s x/y, which is `NOT_A_COORDINATE` unless `hasProximityCharsCorrection(code)` (`PointerTracker.java:328-333`).
2. `InputLogic.handleNonSeparatorEvent` (`:1126`) — drop `|| mWordComposer.isBatchMode()` so a tap after a swipe no longer unlearns the word and tears down the composer.
3. Taps that land **while another finger is gesturing** are swallowed at `PointerTracker.java:1171` and contribute only their down point as a 1-point segment. That is *sufficient* — the encoder derives letter identity from position via `layout_keys`, so key identity is not needed for decoding. Key identity is only needed for peck mode, which by definition has no active gesture. **No `PointerTracker` change required here.**
4. Expand tap strokes into a short dwell per the S4 result.

### Phase 3.5 — Tuning pass

Three tweaks after on-device use of Phases 1–3.

1. **Peck mode now needs a tap threshold.** Engaging on the first tap meant short words lost autocorrect, and short words are exactly where it earns its keep ("ti" → "to"). Peck now requires `TapSwipePeckMinTapsSetting` taps (default 4) in a swipe-free word. Below that, a word behaves like stock: autocorrected, not force-learned.

2. **Key borders as a peck-mode indicator.** Borders switch on when peck engages and off when a swipe joins the word or the word finishes.

   Not done by writing `KeyBordersSetting`: that is the user's own preference, and changing it makes `LatinIME` rebuild the entire drawable provider — far too heavy to run twice per word. Instead `BasicThemeProvider` precomputes bordered variants of the only four styles that depend on borders (`Normal`, `Functional`, `StickyOff`, `Spacebar`) and `getKeyStyleDescriptor` swaps to them when the transient [`TapSwipePeckIndicator`] flag is set. Cost is a handful of extra drawables at theme construction; toggling is then a map lookup plus `invalidateAllKeys()`. No effect when the user already has borders on.

3. **Swipe sensitivity is now continuous.** Short swipes (`e`→`r` to finish a word) needed too much travel before being classified as a gesture. `SwipeSensitivitySetting` (default 1.0 = stock) scales the three quantities gating recognition in `GestureStrokeRecognitionPoints`: the fast-move speed test in `detectFastMove`, and the dynamic distance/time thresholds in `isStartOfAGesture`. Higher = registers sooner.

   The pre-existing coarse `mGestureInputSensitive` toggle now multiplies on top (×2) rather than applying its own hardcoded factors. Slight behaviour change for that toggle alone: it previously scaled `deltaTime` by 3 and the thresholds by 2; it is now a uniform ×2. Both sliders live in Dev Settings → TapSwipe.

#### Fix 4 — swipe + tap committed the pre-tap word

Reported: swipe `b`→`u`, tap `t`, display correctly showed "but", but space committed "by".

A tap sets `inputTransaction.setRequiresUpdateSuggestions()`, scheduling an ordinary TYPING-style query. That query runs the **non-batch** pipeline plus the transformer LM over the literal composing string and stores a genuine autocorrection on the word composer. On space, `commitCurrentAutoCorrection` calls `ensureSuggestionStripCompleted`, which force-completes exactly that pending query, then commits *its* answer rather than the decoded word.

Pure swipes were unaffected only because a finger lift never sets that flag, so no typing query is ever scheduled — the bug needed a tap to appear.

Fix: `isTapSwipeVerbatimWord()` — true for peck words **and** any word containing a swipe — now gates both the commit branch and `mWillAutoCorrect`. A swiped word is committed exactly as displayed. This is not a special case: the decoded candidate already came from a lexicon-constrained beam search over the whole word, and stock never autocorrects batch input either (`Suggest.getSuggestedWordsForBatchInput` hardcodes `willAutoCorrect = false`). Letting a second, weaker pipeline overrule it was the mistake.

`isTapSwipePeckWord()` (tap-only, at/above the threshold) is retained and still gates the two genuinely peck-specific behaviours: force-learning and the key-border indicator.

Known remaining rough edge: that redundant TYPING query still runs after a tap in a swiped word, so the suggestion strip may briefly list typed-word/LM candidates rather than swipe candidates. Harmless for the committed text now, but it means a manual strip pick during that window chooses from the wrong candidate set.

#### Fix 5 — peck-mode key borders never appeared

Two independent defects, found in sequence.

**a) Peck detection could never engage on a fresh keyboard.** It counted session *tap strokes*, which are recorded by `absorbTapIntoTapSwipeSession` — and that method early-returns when `SwipeDecoderDictionary.normalizedKeyPosition()` finds no entry, which is the case until `appliedLayoutInfo` has been populated by a decode. The indicator refresh sat *after* that early return, so neither the tap nor the refresh happened.

Peck now keys off `mWordComposer.size()` — the actual composing word length, which is what "the 4th tap of a word" means — and the refresh moved out of `absorbTapIntoTapSwipeSession` so it runs on every tap regardless of whether the tap became usable decode evidence.

**b) The bordered styles were byte-identical to the borderless ones.** `peckKeyStyles` was built from the `keyColor` / `functionalKeyColor` / `onKeyColor` locals — but those are themselves gated on `keyBorders` earlier in the same `init` block (`:344-362`), so with borders off they are already `Color.Transparent`. The precomputed "bordered" styles therefore reproduced the borderless look exactly. Flag, map, and redraw were all working; the two styles just looked the same.

They are now built from the scheme colours directly (`keyboardContainer`, `keyboardContainerVariant`, `onKeyboardContainer`).

Worth noting the redraw was never the problem: with hardware acceleration `KeyboardView.onDraw` goes straight to `onDrawKeyboard`, which redraws **every** key through `getKeyStyleDescriptor` on every frame. The `invalidateAllKeys()` call is retained as belt-and-braces for the software-rendering path.

#### Fix 6 — "but" still reverting to "by" (the real cause)

Fix 4 was correct but treated a symptom. The logs showed the actual sequence:

```
:270  tapSwipe TapSwipeDecodeInput(L=2 R=0 hasSwipe=true) beam=300 -> but(17.15)   correct
:271  TapSwipeSession: reset (2 strokes): editor is no longer composing            session destroyed
:278  Left = [SwipeSeg(...)]                                                      legacy path, swipe only
:279  outputs = Word("by", ...)                                                   overwrites the composer
:732  commitChosenWord() : [by]
```

Not autocorrect at all — a second decode of *stale, partial* evidence overwriting a correct result.

**a) Validate-on-read destroyed a live session mid-word.** `onUpdateTailBatchInputCompleted` called `tapSwipeSession()`, which revalidates. On this device the emulated-composing input connection (`InputConnectionInternalComposingWrapper`, logged as `ICPatched`) implements the composing region with real `commitText`/backspace calls, so a mid-word edit churns the selection and leaves the editor transiently not composing. The invariant fired and discarded a two-stroke session.

That method applies a decode already computed from the session and accumulates no new evidence, so validation buys nothing there. It now reads the session directly.

This is the same lesson as Fix 1, in a new place: **an invariant that is correct in steady state can still be wrong in the window between mutating evidence and applying its result.** The earlier fix exempted the pre-first-write window; this one exempts the apply step itself.

**b) Losing the session silently fell back to a worse decode.** With `mTapSwipeInput` null, `getSuggestions` dropped through to the legacy single-batch path, which decodes `mInputPointers` — still holding only the *last* gesture. So it re-derived the swipe-only word and clobbered the one taps had completed.

While TapSwipe is enabled, that fallback is now declined outright: whatever is already composed beats a decode of partial evidence.

Why it only struck later in a sentence: at position 0 the wrapper takes its simple "Case Begin" path, while mid-text it uses "Case Addition"/"Case Complex" with backspacing — far more selection churn, so far more likely to trip the composing check.

#### Fix 7 — stale decodes could land after a word was finalized

Investigated after a report of similar corruption around double-space-to-period.

`tryPerformDoubleSpacePeriod` is **not** itself implicated: it operates purely on committed text through the input connection (read 3 chars, delete 1, commit ". ") and never touches the session or the word composer. But looking for how its input could already be corrupt exposed a genuine gap.

`onUpdateTailBatchInputCompleted` had **no guard against applying a decode whose word no longer exists**. Decoding is async, so a result can arrive after the user has hit space (finalizing the word) or after the next word has begun. Applying one then calls `finishComposingText()` + `setBatchInputWord()` + `setComposingTextInternal()`, re-inserting a stale word *after* the committed text — and a following double-space-to-period would then operate on that corrupted text, which matches the reported symptom.

This is precisely the generation counter specified in §5.5 (item 3). It existed on `TapSwipeSession` and was carried on every `TapSwipeDecodeInput`, but was **never actually checked at the apply site** — the one place it was designed to protect. Two guards now:

- session no longer open ⇒ drop the result (word already finalized);
- the decode input's generation is not the session's current generation ⇒ drop it (belongs to an earlier word).

Note Fix 6(a) slightly widened this window by removing the `tapSwipeSession()` call from this method, though the hazard predated it: that call could reset the session but never gated the apply.

**Recurring theme across fixes 1, 6 and 7:** every defect so far has been in the seam between *when evidence changes* and *when a result derived from it is applied*. The validate-on-read design handles steady state well; each bug lived in a transition window.

### Phase 4 — Dual-thumb hardening

Only needed for robust *overlapping* two-thumb use. The touch layer already does more than expected — per-pointer stroke buffers, per-pointer segments, per-pointer trails (`GestureTrailsDrawingPreview.java:37`), and a batch already survives single-finger lifts (`BatchInputArbiter.java:191`). What needs fixing:

1. **`sInGesture` is a single global boolean** (`PointerTracker.java:129`) consulted at ~12 sites. Two thumbs cannot hold independent gesture state. Second finger inherits gesture mode without qualifying — currently *accidentally* beneficial, should be made deliberate.
2. **`cancelBatchInput` is a global nuke** (`:596-608`): one finger leaving the valid y-band (`y < -0.25 × keyboardHeight`, `GestureStrokeRecognitionPoints.java:83,305`) calls `cancelAllPointerTrackers()` and `resetForBatch()`, destroying **every** finger's segments. Thumb A rising to the top row is a realistic trigger. Must become per-pointer.
3. **Multi-touch slide-off hack** (`:936-944`) force-ups a pointer when `getActivePointerTrackerCount() > 1` and it crosses a key boundary before gesture mode engages — hostile to the two-thumbs-down opening.
4. `pointerId >= 2` currently dropped — see §1.3.
5. Space and flick keys cannot *originate* a stroke (`:973-1007`, `:1032-1044`, plus `Character.isLetter` at `:804`); with default `SPACEBAR_MODE_CURSOR` a space-down becomes cursor movement. Fine for now — space is our commit key.

### Phase 5 — Backspace, undo, polish

Two-tier backspace (§6.1):

- **Session open** — pop the last `Stroke` off the session and re-decode. Update the composing text from the new candidate. When the last stroke is popped, close the session.
- **Session closed** — delete the whole preceding word. Generalizes today's batch-only behaviour (`:1448-1456`) to every committed word.

Watch `setRejectedBatchModeSuggestion` (`:1451`): it permanently disables autocorrect for the retry (`Suggest.java:240`). That is reasonable when the user rejects a *decoded* word, but must not fire for a stroke-level undo mid-session.

Also: `mInputPointers` is never truncated on delete (`WordComposer.java:210` guards only the add path), so coordinate/character alignment silently degrades after a backspace. Pre-existing bug; matters more once we lean on pointer data.

---

#### Fix 8 — "but"→"by" again: validation firing *inside* the apply window

```
:824  tapSwipe TAIL applied text='but' continuation=true prevTypedWord='byt'
:825  TapSwipeSession reset (2 strokes): composing word changed: expected 'byt', got 'but'
:825  tapswipe on but no session input; declining legacy batch decode
:987  tapSwipe COMMIT verbatim=false hasSwipe=false strokes=0 typedWord='but' autoCorrection='by'
:987  commitChosenWord() : [by]
```

Caused by the invariant added in Fix 6 (composing word must equal the text we last wrote). Inside the apply block, `setBatchInputWord` updates the composer, then `setComposingTextInternal` + `send()` flush the input connection, which triggers a selection update, which triggers a fresh suggestion query, which calls `tapSwipeSession()` — **while the composer already says "but" but `lastComposedText` still says "byt"**, since `noteComposingWrite` only runs at the end of the method. Mismatch, session destroyed mid-apply, `verbatim` false at commit, autocorrect wins.

Fix: `mTapSwipeApplyingDecode`, set for the duration of the write-back; `tapSwipeSession()` skips validation while it is set. The composer and the session's recorded text are *legitimately* out of step during that window, and flushing the IC re-enters this class.

**This is the general form of fixes 1, 6 and 8 — all three were validate-on-read firing in a transition window.** Fix 1 exempted the pre-first-write window, Fix 6 exempted a single call site, and this exempts the whole apply block. The re-entrancy guard is what Fix 6 should have been.

#### Fix 9 — swiped words lost their sentence-start capital

`Suggest.getSuggestedWordsForBatchInput` gates *all* batch capitalization on one predicate (`Suggest.java:358`):

```java
final boolean isFirstCharCapitalized = wordComposer.wasShiftedNoLock();
```

which reads `mCapitalizedMode`. `onStartBatchInput` ended with an **unconditional** `setCapitalizedModeAtStartComposingTime(getActualCapsMode(...))`, and `getActualCapsMode` returns any non-`AUTO_SHIFTED` mode verbatim — i.e. `CAPS_MODE_OFF` once the keyboard un-shifts.

Under TapSwipe a word spans multiple strokes, and applying the first stroke's decode puts text before the cursor, so `requestUpdatingShiftState` un-shifts the keyboard. The *next* stroke's `onStartBatchInput` then recorded `CAPS_MODE_OFF`, clearing `wasShiftedNoLock()` — so the whole-word re-decode came back lowercase. Stock never hit this because a word there is exactly one batch.

Fix: skip that write while a TapSwipe session is open. Capitalization is decided when the **word** starts, not when each stroke starts.

Not changed: `Suggest.java:358` itself. Switching it to `isOrWillBeOnlyFirstCharCapitalized()` would arguably be more robust but alters stock behaviour for every gesture, which this fork shouldn't do casually.

### Phase 6 — Settings home, pro mode, sensitivity default

1. **TapSwipe gets its own settings section**, out of Dev Settings: mode toggle, peck threshold, peck cadence, swipe sensitivity, pro mode, key personalization.
2. **Master Mode — dots instead of letters.** *(done, dev)* Named after the original Nintype mode. Off by default and disabled unless the input model is on. Draw-time label substitution at `AdvancedThemeCustomizer.kt`, the same override seam the peck borders use — only single-character letter labels are replaced, so space/shift/backspace/enter/digits/punctuation stay readable, and the hint is dropped alongside the letter. Peck mode reveals the letters again (`TapSwipeMasterMode.shouldHideLetters()`), which is the first piece of the Phase 7 state machine landing early. State is a transient flag kept in sync by a settings collector in `LatinIME` that repaints via `invalidateAllKeys()`; no theme rebuild, and no DataStore lookup per key per frame.

   Original note: **Pro mode — dots instead of letters.** Draw-time label substitution at `AdvancedThemeCustomizer.kt:163` (`key.labelOverride ?: key.label`), the same override seam the peck borders use. Precedent exists: `HiddenKeysSetting` ("Touch typing mode") already blanks labels by making the foreground transparent, so dots sit between that and normal.
3. **Default swipe sensitivity 3.0×**, noted as tuned for short swipes under TapSwipe.
4. **Surface the personalization dependency.** `mUsePersonalizedDicts` gates `performAdditionToUserHistoryDictionary` *before* our `forceValidWord` flag is read, so with it off the peck→learn→swipe loop silently does nothing and learned words never reach the swipe tries. Warn next to the TapSwipe toggle.

### Phase 7 — Mode state machine (normal / peck / legacy tap) — *implemented, dev*

Shipped as `TapSwipeMode` { SWIPE, UNDECIDED, PECK, LEGACY_TAP } resolved by `InputLogic.tapSwipeMode()`.

- **Cadence gate.** `TapSwipeSession.medianTapGapMs()` over per-tap timestamps recorded *unconditionally* (`noteTapTime`), not from TAP strokes — those are skipped when the layout cannot place a code point, which would misclassify any word containing one. Median rather than mean, so a single pause does not make fluent typing look deliberate. Threshold is `TapSwipePeckCadenceSetting`, default 250ms.
- **Latched per word.** Once classified the mode is held for the rest of the word, or the keys would flip between dots and letters, and borders on and off, as the median drifted mid-word. A swipe is never latched and always forces back to SWIPE — that is how legacy-tap mode is left.
- **Peck is decided on cadence alone.** The tap-count gate was removed after testing: slowness turned out to be the reliable signal on its own, and requiring length meant short words genuinely being spelled out never qualified. Minimum of two taps is implicit — that is what a cadence needs.
- **Legacy tap depends on Master Mode.** With the letters already visible, legacy tap is indistinguishable from ordinary typing, so it is gated on Master Mode and its threshold can be set to 0 to disable it outright.
- **`MasterModeAction`** puts Master Mode on the action bar, since it is worth flipping mid-sentence rather than walking into settings. It writes the same setting, so the two never disagree.
- **LEGACY_TAP is a cross-word state, not a per-word class.** First cut classified it per word, which made short words like "to" flip the display for one word and flip back — noise rather than information. It is really a judgement about *the person typing*: someone unfamiliar with the keyboard just taps, and should get letters and ordinary autocorrect without discovering anything. So it is a run of quick taps counted across words (`TapSwipeLegacyTapRunSetting`, default 5), reset by any swipe. A deliberate pause does not clear the run — real typing pauses at word boundaries, so clearing on every slow tap would mean it never builds. PECK stays per-word and latched, since that genuinely is about one word.
- **LEGACY_TAP restores stock behaviour**: excluded from `isTapSwipeVerbatimWord()`, so fluent typing keeps autocorrect. This is the behavioural change from the old length-only rule, where any long swipe-free word was committed verbatim.
- **Morph suppression**: `updateBoostedCodePoints` now also requires `!isTapSwipePeckWord()`, so dictionary key boosting stops fighting a word being deliberately spelled out. Personal-preference morphing does not exist yet (Phase 8).
- `TapSwipePeckIndicator` became `TapSwipeUiState`, exposing `showBorders()` (peck only) and `showLetters()` (peck or legacy tap), which Master Mode consults.

#### Original plan

Today peck is a single predicate on word length. It becomes an explicit three-state machine, classified per word:

| Mode | Entered when | Letters | Borders | Morphing | Autocorrect |
|---|---|---|---|---|---|
| **Swipe (normal)** | any swipe in the word | dots (pro mode) | no | on | no — verbatim decode |
| **Peck** | swipe-free, ≥ threshold taps, **slow cadence** | **letters** | yes | **off** | no — verbatim + force-learn |
| **Legacy tap** | swipe-free, ≥ threshold taps, **fast cadence** | letters | no | on | yes (stock) |

- **Cadence gate.** Fast typing should not trigger peck. Inter-tap intervals are already available — the session stores a timestamp per stroke — so a median inter-tap interval needs no new plumbing. Worth testing whether *slowness alone* is a more reliable peck signal than tap count, using a low count threshold.
- **Latch per word.** Classify once at the crossing and hold for the rest of the word; re-evaluating every tap would flicker the letters/dots and borders mid-word. A swipe always forces back to Swipe mode.
- **Morph suppression.** Peck disables both personal-preference morphing and dictionary key boosting. Key boosting in particular *contradicts* peck: it biases hitboxes toward letters that continue known words, which is exactly wrong when the point is entering a word the dictionary doesn't have. It is currently gated only on the setting, `mAutoCorrectionEnabledPerTextFieldSettings`, a preceding word codepoint, and accessibility — nothing TapSwipe-aware.

### Phase 8 — Key personalization (adaptive frame), trained before applied

Learn where the user's hand actually sits and shift the decode frame to match — distinct from key boosting, which adapts to the *lexicon* rather than to the *hand*.

**Signal.** Every tap yields a residual: actual touch point minus the resolved key's centre. A decaying average of those residuals is the hand offset. The affine hook already exists — `appliedLayoutInfo.ox/oy` (and potentially `sx/sy`) are applied to every swipe coordinate.

**Train before applying** (the user's requirement, and correct):
- Collect residuals continuously, apply nothing.
- Apply only past a sample-count and variance threshold, with readiness visible in the debug panel and ideally the settings screen.
- Ramp the correction in rather than snapping it on.

**Two hazards specific to this codebase:**
1. **Key boosting corrupts the training signal.** A boosted key can capture a touch up to half a key outside its own bounds (`KeyDetector.java:110-130`, distance-to-edge with `0.5 * min(w,h)` slack). The residual then reflects a dictionary correction, not hand drift. Residual collection must **exclude boosting-resolved taps** — or run with boosting off, which is what "disable letter prediction changes until trained" achieves.
2. **Taps currently record key centres, not touch points** (`normalizedKeyPosition`). That is right for decode reliability — taps are exact anchors — but it discards the residual. The touch point must be captured separately for training while decode keeps using centres.

### Phase 5 (revisited) — stroke-level backspace — *implemented, dev*

Two tiers.

**Tier 1 — word in progress: pop the last stroke.** A tap of backspace removes the last tap or swipe and rewrites what remains, so a mis-swipe can be redone without losing the word. Auto-repeat is deliberately excluded: holding backspace is a bulk gesture already governed by `mBackspaceModeHold`, and per-stroke undo under repeat would let the tiers oscillate, because `restartSuggestionsOnWordTouchedByCursor` can put a word back into composing between ticks.

Popping to empty clears the composing region with the existing `commitText("", 1)` idiom. If a swipe remains the word is re-decoded; if only taps remain the composing text is the literal and one code point is deleted instead — deliberately *not* rewritten from `session.literalText`, because a code point the layout cannot place never became a stroke and that derived string can be missing characters the composer legitimately holds.

**Tier 2 — finished word: delete it whole.** Behind `TapSwipeWholeWordBackspaceSetting`, **off by default**: a backspace tap eating a whole word departs sharply from every other keyboard, and it would override `mBackspaceModeHold = CHARACTERS` for the first press. Takes one trailing space with the word, since words are committed together with their separator and leaving the space would mean two presses to undo one word.

Placed *after* the revert branches (autocorrect, double-space period, punctuation swap, prefix space, inserted text). Those are one-press undos of the previous keystroke and must keep priority or the corresponding settings appear broken.

#### Fix 10 — stroke undo appended instead of replacing ("But" → "ButBy")

Backspacing after `swipe b-to-u, tap t` produced "ButBy" rather than "By".

Self-inflicted by the "fail closed" clause added to `popLastStroke` in the same phase. `hasComposed` is derived from `anchorSelStart != NO_ANCHOR`, and the apply path reads it to decide whether it is *continuing* a word or *starting* one. Clearing the anchor on pop therefore made the rewrite treat the word as new: it called `finishComposingText()`, finalising "But", then wrote the re-decoded "By" after it.

Clearing was wrong in principle too. A pop changes the **evidence**, not the editor — the composing region still holds the pre-pop text, so the anchor remains an accurate description of it until the replacement is written. Only the generation bump is kept.

Fixed alongside: the literal (no-swipe-left) branch used to return false and fall through to the ordinary delete, which changed the composing word without telling the session. The next validate-on-read would have seen a word that no longer matched `lastComposedText` and discarded the session, losing the taps still belonging to that word. It now performs the delete itself and re-anchors.

**Supporting changes.**
- `popLastStroke` now bumps `generation` on *every* pop and clears the anchor, so a decode already in flight is rejected rather than applied against strokes that no longer exist, and a caller that forgets to write a new candidate fails closed.
- The composing write was factored out of `onUpdateTailBatchInputCompleted` into `applyTapSwipeCandidate`, shared by both paths. Duplicating that sequence — re-entrancy guard, batch edit, `setBatchInputWord`, `setComposingTextInternal`, `noteComposingWrite` — is how this feature has bled before.
- Branch A1 (wipe the whole batch word, set `setRejectedBatchModeSuggestion`) is now unreachable while TapSwipe is on. It disabled autocorrect for the retry, and tier 1 owns that case.

**Found while tracing:** `revertCommit` is already dead for every TapSwipe-committed word. `canRevertCommit()` requires `mActive && !didCommitTypedWord()`, and `commitTyped` produces `COMMIT_TYPE_USER_TYPED_WORD`, which `WordComposer.commitWord` deactivates immediately *and* whose typed and committed words are equal by construction. So "Undo autocorrect on backspace" silently does nothing under TapSwipe — a pre-existing regression from the verbatim-commit work, not addressed here.

#### Original plan

Not a bug: **never implemented.** The current code resets the whole session on backspace, which was deliberate as an anti-leak interim (Phase 1) with stroke-level undo deferred.

Now it needs doing properly, and the hard part is not the session — popping a `Stroke` is trivial — it is keeping `WordComposer` in step. A swiped word lives in the composer as a batch word (`setBatchInputWord`), so removing a stroke means re-decoding the remainder and rewriting the composing region, not deleting characters. Given every bug so far has lived in the seam between changing evidence and applying a result, this must re-decode and rewrite atomically, and must not fire `setRejectedBatchModeSuggestion` (which would permanently disable autocorrect for the retry).

### Phase 9 — Whole-stroke shortcuts and key-level gestures — *implemented, dev*

The first work in this fork that lives entirely in the touch layer rather than the word session. Everything here is a shape claimed *before* the decoder sees it, or a key that does more than one thing.

**Nintype gestures — the comma pull.** A straight pull down from V onto the space bar produces a comma; the comma key comes off the bottom row and the space key, being `Grow`, takes its width. One setting covers both halves deliberately: splitting them ships either a dead key or a gesture competing with the key it supersedes.

Not a `flick` key, which looked like the obvious fit. `PointerTracker`'s flick branch returns *before* gesture detection, so making V a flick key would stop any swipe starting on V from becoming a word at all — silently breaking swipe-typing through a letter, a far worse trade than not having the shortcut.

Matching is strict because the failure is asymmetric: a miss costs one repeated gesture, a false positive eats a word you meant to type. The rule asserts a start key, an end key, a direction steeper than 45°, and a drift bound.

#### Fix 11 — the pull never fired at all

Logcat during failed attempts showed **zero** `NintypeGestures` lines: the matcher was never reached. Only `GeneralIME.onEndBatchInput` was checked, and a short deliberate pull routinely fails `detectFastMove`/`isStartOfAGesture` — thresholds calibrated for long fast word swipes — so no batch gesture ever starts and that callback never fires.

Fixed by checking in `PointerTracker` as well, sharing one rule between both paths so they cannot drift apart. The rule was split into a pure-arithmetic half and a half that resolves keys, so the tunable part is JVM-testable.

#### Fix 12 — only fired if you stopped exactly on the space bar

Reported as: works, but only when carefully stopping on the bar; a natural flick past it fails. That is backwards from how a flick should behave, and the cause was structural rather than a threshold.

The second check ran at *release*. Flicking past the space bar leaves the valid gesture area, which calls `cancelBatchInput()` → `cancelAllPointerTrackers()` → `cancelTrackingForAction()` on **every** tracker including the one performing the gesture, so `onUpEventInternal` returns at its `mIsTrackingForActionDisabled` guard long before any release-time check. The harder you flicked, the more certainly nothing happened.

Moved to fire mid-stroke, the moment the pull becomes unambiguous — which is also what a flick *is*: it commits when it crosses its threshold, not when the finger happens to lift. Leaving the keyboard downward is now read as reaching the space bar rather than as abandoning the gesture.

#### Fix 13 — missed when reaching V with the right hand

The start band is anchored to V's *centre* with half a key of slack either side, not to whichever key reported the touch. Keying off the reported key would make tolerance lopsided and dependent on where key boundaries happen to fall; anchoring gives even slack regardless. B and C are permitted as reporting keys only so the anchor lookup is skipped for strokes that could never qualify — distance from V decides.

**Enter key swipes.** Eight assignable directions, using the existing `FlickKey` type — safe here precisely because it was not safe for V: the flick branch bypasses the gesture pipeline, which only matters for keys that participate in spelling, and enter never does.

`FlickKey.computeData` forces `longPressEnabled = false` and empties `moreKeys`, so enter's hold menu is destroyed — shift+enter, field navigation, the emoji action. Rather than accept the loss, those became assignable options, so the capability moves onto a direction instead of disappearing.

Slots store an option *id*, not the text produced: not every option is a character. Parsing is deliberately forgiving — an unrecognised id costs only its own direction — because this is settings data that outlives the build that wrote it, and rejecting a whole string for one bad slot would wipe seven good assignments.

**`KeyWidth.WideFunctionalKey`.** Hiding the period key needed a new width token. Widths resolve per token, not per key, so two `FunctionalKey`s in a row are always equal — no way to widen enter without also widening symbols. `Grow` splits leftover space evenly, which would make enter as wide as the spacebar. The new token is functional width plus one regular width: enter absorbs exactly the period's 10%, and the spacebar is untouched at 40%.

**Backspace slide — reversal-latched granularity.** Replaced tap-then-slide entirely. The slide starts at whatever granularity the setting names; reversing past a full step switches, for the rest of that gesture, to the other one. A one-way latch, not a toggle — a second reversal is another nudge, not a request to return to coarse deletion.

Tap-then-slide was removed rather than kept alongside. It had shipped working, but its arming window was generous enough (900ms after any backspace tap) that ordinary slides following a tap silently changed granularity, which reads as the slide behaving inconsistently rather than as a feature engaging.

#### Fix 14 — the reversal did nothing when the base mode was Words

`GeneralIME.onMoveDeletePointer` computed `stepOverWords` as `mBackspaceMode == WORDS || isActiveSlideWordMode()`. That was correct for tap-then-slide, which only ever needed to *add* word mode on top of an always-Characters setting.

The new mechanic switches both ways, and the `||` made it one-directional: with the base on Words the first term is unconditionally true, so a reversal into character mode had no effect downstream — the exact case the feature existed for. `isActiveSlideWordMode()` is now authoritative, safe because it is written from the same `BackspaceSlideMode` result as the steps being delivered, immediately before the call.

Worth recording as the current best example of the fork's recurring bug class: the pure state machine was tested and correct throughout, and the defect sat one file downstream in code with no unit coverage. Testing a rule does not test that anyone honours the answer.
