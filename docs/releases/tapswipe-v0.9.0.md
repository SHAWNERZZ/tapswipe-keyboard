# TapSwipe v0.9.0

The Nintype way of typing, now with auto spacing. Build each word from taps and swipes, and let the
keyboard put in the spaces.

Install the APK below. It sits next to the official FUTO Keyboard. Turn it on at
**Settings → TapSwipe → TapSwipe input model**. For auto spacing, open **Auto spacing** in the same
screen and turn on **Space after a pause** and **Split tapped words automatically**.

## New

- **Auto spacing.** Stop touching the keyboard for a moment after a word and the space goes in. A
  thin bar at the top of the keyboard shows the time left (250 ms, adjustable). Touch again to keep
  adding to the word.
- **Short tapped words** (a, I, is, to) get their space too, and stand alone next to a swiped word:
  tap I, swipe want, tap a gives "I want a". A tapped ending stays with its word: swipe cat, tap s
  gives "cats".
- **One space press after an auto space ends the sentence** with a period.
- **A word cut in two by a pause joins back** when the decoder reads it as one word.
- **The other reading** of any split or join is first in the suggestion strip. One tap swaps it.
- **Peck mode button**: pin it from TapSwipe settings (a crosshair, left of voice input). It puts
  the next word in peck mode: typed exactly, then learned.
- **Peck mode by pause**: a word of taps that is not a short word becomes a peck word 100 ms after
  the auto space pause.
- **Backspace after a space** removes the space first, then the word. A backspace also holds the
  auto space timer until you type again.
- **Reorganized TapSwipe settings**, in sections. Adaptive key geometry now learns with or without
  the TapSwipe input model.
- **Typing test** with real-text passages, scored with punctuation.

## Fixed

- Contractions: a swiped I'm no longer reads as "in".
- Capitals: a split at the start of a sentence keeps its capital; a mid-word acronym reading no
  longer makes the rest of the word upper case.
- A quick tap right after a swipe could replace the whole word with the tapped letter.
- Undoing the last stroke could bring the same word back.
- A crash during long swipes.
- Lag on long two-thumb words.

## Under the hood

- Rebased on FUTO Keyboard 0.1.30 and later upstream changes. FUTO's newer swipe language model is
  not used yet, because the prebuilt swipe library has no tuning for it.

## Notes

- Auto spacing is new and still being tuned. A long pause inside a word can still split it; the
  suggestion strip offers the joined word.
- If a mild word you swipe never appears, turn off FUTO's **Block potentially offensive words**.
- The APK is signed with the public upstream debug keystore, like earlier TapSwipe releases.
