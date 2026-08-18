# TapSwipe — historical design record

> **Read this first.**
>
> This document is a historical design record. It contains superseded
> plans, original checklists, and implementation history. Do not treat
> any "Original plan" or completed-phase checklist here as pending work.
>
> For current behavior, read `TAPSWIPE_ARCHITECTURE.md`.
> For testing, read `TAPSWIPE_TESTING.md`.
> For fix-by-fix history, read `docs/history/TAPSWIPE_IMPLEMENTATION_HISTORY.md`.

## Why keep this document

The original plan captures the reasoning that led to the current
architecture. Later work resolved some decisions differently. Use this
document to answer "why did we choose this" questions. Use the
architecture doc to answer "what does it do now" questions.

## Superseded or resolved decisions

Read these before quoting the plan.

- **Finalizers.** The plan opens with "space is the only finalizer". The
  resolved decision is space, the existing separator punctuation, and
  Enter. See `TAPSWIPE_ARCHITECTURE.md` §5.
- **Peck mode entry.** The plan uses a length threshold. The current
  code uses a cadence gate, decided per word and latched. See
  `TAPSWIPE_ARCHITECTURE.md` §4.
- **Tap dwell.** The plan spike S4 concluded a single raw point is
  enough. Do not synthesize dwell.
- **Cross-hand ordering.** The plan spike S2 confirmed the beam search
  resolves interleaving through the lexicon, not by timestamps.
- **Backspace.** Now two-tier, per `TAPSWIPE_ARCHITECTURE.md` §7.
- **Peck-mode strip.** Suggestions remain tappable. Only autocorrect is
  gated.

## Reading the plan

The remainder of this document (below the `<!-- BEGIN HISTORICAL PLAN -->

# TapSwipe — implementation plan

TapSwipe reimplements the input model of the discontinued **Nintype** keyboard on top of FUTO's
neural swipe decoder. "Nintype" below refers to that original keyboard; "TapSwipe" refers to this
implementation.

Personal fork, published at https://github.com/SHAWNERZZ/tapswipe-keyboard.
Changes are not submitted upstream to FUTO.

Target behaviour (from the original Nintype keyboard):

1. **Peck mode** — a word in which no swipe occurred. No autocorrect, no gesture suggestions. The literal tapped string is committed verbatim even if out-of-dictionary, then learned so it becomes swipeable later.
2. **Swipe mode** — a word built from *interleaved taps and swipes*, including two thumbs operating with temporal overlap. `tap H, tap E, tap L, swipe L→O` ⇒ `hello`. Thumb A `S→A→W` overlapping thumb B `H→N` ⇒ `shawn`.
3. **Space is the only finalizer.** Lifting a finger never commits. Further taps/swipes keep accumulating and re-decode into a new candidate. Modes are implicit — never surfaced to the user.
4. **Gestures that are not words.** Shapes claimed before the decoder sees them (a pull from V to the space bar for a comma), directional shortcuts off keys that never spell (punctuation and actions off enter), and a slide-to-delete that changes granularity when it reverses. Nintype's own wiki calls these "edge slide shortcuts" and treats them as a general system rather than a fixed list — see Phase 9.

---

## 0. Key findings that shape the design

### 0.1 The decoder already supports this natively

From symbols in `libs/futo-swipe-release.aar` → `jni/*/libswipe_jni.so`:

```
SwipeEngine::recognize_multi(vector<SwipeSeg> left, vector<SwipeSeg> right,
                             vector<string> context, int topK, int beamWidth,
                             vector<float> trieWeights)
SwipeEngine::Impl::predict_segment(SwipeSeg const&)
TrieBeamSearch::decode_multi(ITrie const*, float const* emisL, float const* lamL, size_t lenL,
                                          float const* emisR, float const* lamR, size_t lenR, int)
```

Each hand takes a **vector of segments**. Each segment is encoded independently (64 resampled points → 32 emission timesteps, `java/assets/futo-swipe/honorable_sturgeon/metadata.json`), per-hand emissions are concatenated, and one joint lexicon-constrained beam search runs over the two streams.

**Critical inference:** `decode_multi` receives *no timestamps* — only two emission streams and their lengths. Therefore cross-hand interleaving is resolved by the **lexicon**, not by wall-clock time. Order *within* a hand is fixed; the interleaving *between* hands is whatever spells a real word.

For `shawn`: left stream = `S,A,W`, right stream = `H,N`. The beam finds the interleaving `S,H,A,W,N`. **We do not need precise cross-hand timing — only correct per-hand ordering.** This substantially simplifies the work. *(High-confidence inference from the symbol signature; confirm in Phase 0.)*

Timestamps still matter *within* a segment, for resampling (`SwipeDecoder.Timing.resampleUs`).

### 0.2 Nothing commits on finger-lift today — the docs lie

`InputLogic.onUpdateTailBatchInputCompleted` (`java/src/org/futo/inputmethod/latin/inputlogic/InputLogic.java:2637-2661`) is documented *"This commits the word to the editor"* but the code calls `setComposingTextInternal(batchInputText, 1)` (`:2654`) — it leaves the word **composing**. Verified.

So requirement 3 is much closer than expected. The real commit triggers are:

- `handleSeparatorEvent` (`:1224-1235`) — space and other separators. **This is the one we keep.**
- `onStartBatchInput` (`:773`) — `commitCurrentAutoCorrection`. **This is the one that must go.**

### 0.3 Peck-mode learning has a two-commit dead zone

`DictionaryFacilitatorImpl.addWordToUserHistory` (`java/src/org/futo/inputmethod/latin/DictionaryFacilitatorImpl.java:774-775`):

```java
// We demote unrecognized words (frequency < 0, below) by specifying them as "invalid".
final boolean isValid = maxFreq > 0;
```

A novel word has `maxFreq == NOT_A_PROBABILITY (-1)` ⇒ `isValid = false` ⇒ native `ver4_patricia_trie_policy.cpp:382-395` early-returns with `count = 0` ⇒ `language_model_dict_content.cpp:58-62` treats it as invalid ⇒ `ITrie_GetLogFrequency` returns `-1`, violating the documented 1..255 contract in `native/jni/src/suggest/core/dictionary/itrie.h:63-67`.

Net effect: **a pecked new word needs two commits before it is genuinely swipeable.** Must be fixed for requirement 1.

Good news: the ITrie is a **live lazy view** over the dictionary policy (`native/jni/src/suggest/core/dictionary/dictionary.cpp:227-233`), not a snapshot — so once the frequency is right, a learned word is swipeable on the *next* `recognize()` with no `setMode` or restart.

### 0.4 Timestamps are batch-relative, not word-relative

`BatchInputArbiter.getElapsedTimeSinceFirstDown` (`java/src/org/futo/inputmethod/keyboard/internal/BatchInputArbiter.java:62-64`) subtracts the static `sGestureFirstDownTime`, which is reset to `-1` when the last finger lifts (`:195`). Each batch therefore has its **own** time origin, so segment times are not comparable across batches. Our accumulator must re-base.

Also a real bug to work around: `sGestureFirstDownTime` is set on *every* armed finger-down (`:77-78`) but cleared only on gesture end/cancel — both gated on `sInGesture`. **A tap that never becomes a gesture leaves the origin set forever**, and empty `GestureSegment`s pile up. Currently masked by `.filter { it.x.length > 0 }` (`SwipeDecoderDictionary.kt:405`) and by re-basing on `segments[0]` (`:419`).

### 0.5 `InputPointers.set()` is a shallow copy

`common/src/org/futo/inputmethod/latin/common/InputPointers.java:104-105` does `mGestureSegments.addAll(...)` — copying *references*. The `WordComposer`'s copy aliases the live `sAggregatedPointers` segments, which the UI thread keeps mutating while `InputLogicHandler` reads them on another thread. The class is explicitly `// TODO: This class is not thread-safe.` (`:31`).

**Our accumulator must deep-copy.**

---

## 1. Architecture

### 1.1 The word session

Introduce a word-scoped accumulator — the unit of composition. Opened by the first letter input, closed **only** by space (or explicit cancel).

```
TapSwipeWordSession
  strokes: List<Stroke>            // chronological, whole word
  hasSwipe: Boolean                // ⇒ swipe mode vs peck mode
  literalText: String              // tap code points, in order (peck mode output)

Stroke
  kind: TAP | SWIPE
  hand: LEFT | RIGHT               // from pointerId 0/1
  points: (x, y, t)[]              // deep-copied, session-relative t
  keyCode: Int?                    // taps only, when known
```

**Home: the IME layer** (`InputLogic`), not the touch layer. Word lifetime is only knowable where space is observed. The touch layer keeps producing per-gesture batches exactly as today; `InputLogic` deep-copies each batch's segments into the session as they arrive. This keeps the touch-layer diff near zero for Phases 1–3.

### 1.2 Division of responsibility

| Concern | Owner |
|---|---|
| Input evidence for the whole word | `TapSwipeWordSession` (new) |
| Current candidate *text* + composing region | `WordComposer` + `setComposingTextInternal` (unchanged mechanism) |
| Decode over accumulated evidence | new `SwipeDecoderDictionary.getSuggestionsForSession(...)` |
| Commit / learn | existing `commitChosenWord` funnel (`InputLogic.java:2764`) |

`WordComposer` stays the holder of the *current best interpretation*; the session holds the *evidence*. This avoids fighting the many `isBatchMode()` forks (see §4) — we simply stop letting them tear the word down.

### 1.3 Hand assignment

Keep the existing rule from `SwipeDecoderDictionary.kt:436-437`: `pointerId == 0` → left, `== 1` → right.

This is already correct for our purposes. Android reuses freed pointer ids, so **sequential** single-finger taps and swipes all arrive as id 0 → one stream, in order. Two ids only coexist when strokes genuinely **overlap** in time, which is exactly when the two-stream split is wanted. A third simultaneous finger reuses a hand slot rather than being dropped (today `pointerId >= 2` is silently discarded).

### 1.4 The re-decode loop

On every input event (tap, mid-swipe update, swipe end):

1. Append to the session.
2. If `session.hasSwipe` → decode all strokes both hands → top candidate → `setComposingTextInternal(candidate)`, top-4 to the strip.
3. Else → composing text = `session.literalText`; normal dictionary suggestions may show, but **autocorrect off**.

On **space**: commit the current candidate, learn it, clear the session, emit the space.

Composing text is therefore always "current best interpretation", replaced wholesale on each re-decode — which is what `setComposingTextInternal` already does.

---

## 3. Risks

| # | Risk | Mitigation |
|---|---|---|
| 3.1 | ~~S2 fails — no lexicon-driven cross-hand interleaving~~ | **RETIRED.** S2 passed: L=`saw` + R=`hn` → `Shawn` rank 0, with single-hand controls confirming neither stream alone reaches it. |
| 3.2 | ~~Tap-as-segment decodes poorly (off-distribution)~~ | **RETIRED.** S4 passed at rank 0, and dwell length made zero difference (internal 64-point resample collapses it). Use a single raw point. |
| 3.3 | Timestep budget exhausted on long words. | **DOWNGRADED.** S3 reached 64 segments/hand (~2048 timesteps) with no failure. Keep a generous sanity cap; the real limits are confidence decay and latency. |
| 3.4 | Latency — re-decoding the whole word on every event. Beam 300 at tail today. | Use low beam mid-stroke, high beam on finger-lift; `SwipeDecoder.lastTiming()` gives per-stage numbers. `useHighBeam` is currently just `inputStyle == TAIL_BATCH` (`DictionaryFacilitatorImpl.java:887`) — that equivalence must be broken since "tail" no longer means "word over". |
| 3.5 | Thread-safety — `InputPointers` is not thread-safe and is read on `InputLogicHandler`'s thread while mutated on the UI thread (§0.5). | Deep-copy at a single well-defined point; never retain references to live segments. |
| 3.6 | Very long-lived composing regions. `InputConnectionInternalComposingWrapper` (`:160-193`) emulates composing for buggy editors. | Test in a few real apps early. |
| 3.7 | ~~Double letters lost~~ | **RETIRED — inverted into an advantage.** S1 showed a single continuous `hello` stroke ranks `help` above `hello`, while `hel`+`lo` puts `hello` at rank 0. A re-touch starts a new segment and that boundary *is* the repeated-letter signal, so TapSwipe's style disambiguates better than a pure swipe. |
| 3.8 | Latent: `static TrieId CHILD_ID` shared across all three ITrie wrappers (`dictionary_itrie.cpp:75`), reset by any trie's `end_search`. Pre-existing correctness bug. | Watch for it; fix if peck-mode learning exposes it. |
| 3.9 | Contacts dictionary is absent from the swipe tries (only MAIN/USER/USER_HISTORY, `DictionaryFacilitatorImpl.java:1137-1141`). | Out of scope; note that contact names are never swipeable. |

---

## 4. The `isBatchMode()` fork inventory

`mIsBatchMode` is a single boolean assuming a word is *either* typed *or* swiped, never both. We do **not** try to eliminate it — instead the session becomes authoritative and we stop these sites from destroying the word. Sites that matter:

| Site | Behaviour | Action |
|---|---|---|
| `WordComposer.java:215` | tap coords not recorded once batch mode on | superseded by session |
| `WordComposer.java:294-297` | `setBatchInputPointers` replaces the pointer array | superseded by session |
| `WordComposer.java:300` | `setBatchInputWord` starts with `reset(true)` | harmless to the session; keep |
| `WordComposer.java:504` | `commitWord` clears `mIsBatchMode` — "did this word have a swipe?" unrecoverable after commit | session tracks `hasSwipe` |
| `InputLogic.java:773` | swipe start commits the tapped word | **remove** (Phase 1) |
| `InputLogic.java:1126` | tap after swipe unlearns + tears down | **remove the `isBatchMode()` clause** (Phase 3) |
| `InputLogic.java:1448-1456` | backspace wipes whole batch word | Phase 5 |
| `InputLogic.java:2648,2653` | swipe end finalizes previous composing region | **gate on open session** (Phase 1) |
| `Suggest.java:106-113` | two mutually exclusive pipelines | route by `hasSwipe` |
| `Suggest.java:396-402` | batch hardcodes `typedWordValid=true, willAutoCorrect=false`; no `KIND_TYPED` entry | keep for swipe mode |
| `SwipeDecoderDictionary.kt:402` | swipe decoder disabled for non-batch | bypassed by the session entry point |
| `DictionaryFacilitatorImpl.java:880-901` | swipe results **early-return**, skipping contacts/user/user-history/main/emoji dicts and the native shortcut path | revisit if peck words must merge with swipe candidates |

---

## 5. Alternatives considered

### 5.1 Merged synthetic trajectory (fallback for 3.2)

Instead of one segment per tap, splice the whole word into **one** synthetic trajectory per hand: dwell at each tapped key centre, real stroke points where swiped, straight interpolation across finger lifts. Closer to the training distribution (a continuous path through the right keys) and uses one 32-timestep budget instead of one per tap, which also relieves 3.3.

Cost: loses per-stroke detail, since the whole word shares one 64-point resample. Adopt only if S4 shows tap-as-segment decodes badly.

### 5.2 Rebuilding the swipe library

Beam search, CTC, and lexicon traversal are inside the prebuilt `.aar`. Source is at `gitlab.futo.org/keyboard/swipe-library` (`libs/README.md:4`) but we don't have it. The open native surface we *do* control is the ITrie bridge — `native/jni/src/suggest/core/dictionary/dictionary_itrie.cpp` (`ITrie_GetLogFrequency:155`, `ITrie_IsWord:124`, `ITrie_GetChildCount:95`, `isOmissible:71`) — which is enough to change lexicon, frequencies, and pruning from inside the beam search. Not needed for this plan beyond the §0.3 learning fix.

---

## 5.5 Statelessness and invalidation — the runaway-word bug class

Known failure mode from prior experience with this design: **accumulated stroke state outliving the word it belongs to.** Symptom — swipe a short word, delete part of it, swipe again, and the new stroke appends to state that should have been discarded, so the word grows without bound.

The root cause is architectural, not a missing line: *invalidate-on-write* requires enumerating every event that should clear the accumulator (commit, cursor move, delete, focus change, app-side edit, recorrection, orientation change, …) and it only takes one missed path to leak. Agent mapping found ~30 distinct composing-teardown sites in `InputLogic` alone.

**So the accumulator is built validate-on-read instead.** The session carries an *identity*, and every access re-verifies that identity against live editor state. Anything we failed to anticipate causes the session to be dropped automatically rather than silently reused. Self-healing by construction.

Five mechanisms, in order of how much they carry:

1. **Primary invariant: no composing word ⇒ no session.** Session validity derives from `WordComposer.isComposingWord()`. Every one of those ~30 existing teardown sites already clears the composer, so deriving validity from it **inherits all of that correctness for free** instead of hooking each site individually. This alone kills most of the bug class.

2. **Cursor anchor.** After each composing write, store the resulting `mConnection.getExpectedSelectionStart()`. On next access, a mismatch means something moved the cursor or edited the text underneath us ⇒ reset. Catches cursor moves, external edits, and app-driven changes without needing a callback for each.

3. **Generation counter.** Every `reset()` bumps it. Async decode results carry the generation they were computed for and are **dropped** if stale. Decodes run on the `InputLogicHandler` thread while resets happen on the UI thread, so an in-flight decode resurrecting a cleared session is a real race, not a theoretical one.

4. **Pure recomputation, never incremental mutation.** The candidate text is always a pure function `decode(strokes, context)`. Deleting a stroke is `pop` + full recompute — never a partial undo of previously applied text. Any inconsistency therefore self-corrects on the very next event instead of compounding.

5. **Sanity cap that fails safe.** Past a plausible word (> 12 strokes), log loudly and reset. This turns the runaway-growth symptom into a bounded, visible bug rather than an unbounded one — a canary for a leak we haven't found, not a substitute for fixing it.

Two supporting rules:

- **One atomic `reset()`.** No partial resets anywhere; a single code path clears strokes, anchor, and derived state together. Partial resets are how the flat/segment desync already present in `BatchInputArbiter.resetNonBatch()` (`:126`) happens.
- **Derive, don't duplicate.** The peck-mode literal string is *derived* from the tap strokes rather than accumulated alongside them, so the two cannot disagree.

## 6. Resolved decisions

1. **Backspace is two-tier.**
   - *Session open (word being built):* pop the **last stroke** and re-decode the remaining evidence.
   - *Session closed (word already finalized):* delete the **whole word**.

   Note the second tier is a change from standard behaviour, where backspace after a committed word deletes one character. It generalizes today's batch-word behaviour (`InputLogic.java:1448-1456`) to every committed word. Implementation lands in Phase 5; the stroke-level undo needs the session to keep strokes individually poppable, which the `List<Stroke>` model already allows.

2. **Finalizers: space + punctuation + Enter.** The existing separator set in `handleSeparatorEvent` (`:1224-1235`) is kept as-is, and Enter finalizes too. Substantially less code to change than space-only, and no risk of a word being left uncommitted when a message is sent. The ANTIPHANTOM swallow fix (§2, Phase 1.6) still applies — it must not silently drop a finalizing space.

3. **Peck mode keeps a tappable suggestion strip.** Dictionary candidates still display and can be picked manually; nothing is ever auto-applied. So peck mode means *"never autocorrected"*, not *"no suggestions"*. Manual picks go through the existing `onPickSuggestionManually` path (`:434-528`).

4. **Debug surface: both.** Extend the live `MemDebugAction` panel (`:319-338`) with session state — stroke list, per-hand streams, `hasSwipe`, current candidates — *and* keep the 5-minute logcat trace (`DevSettings.kt:206-225`) for trajectory/timing forensics. The panel carries day-to-day work; logcat is for the dual-thumb cases where exact per-hand ordering matters.

### Consequences for the phases

- **Phase 0** builds both debug surfaces before the spikes, so S1–S4 are observable on-device.
- **Phase 1** treats space, punctuation, and Enter uniformly as finalizers.
- **Phase 2** leaves the strip populated in peck mode; only the autocorrect *application* is gated.
- **Phase 5** implements the two-tier backspace.
