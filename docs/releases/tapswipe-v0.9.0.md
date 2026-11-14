# TapSwipe v0.9.0

The Nintype way of typing, now with auto spacing. Build each word from taps and swipes, and let the
keyboard put in the spaces.

Install the APK below. It sits next to the official FUTO Keyboard. Turn it on at
**Settings → TapSwipe → TapSwipe input model**. For auto spacing, open **Auto spacing** in the same
screen and turn on **Space after a pause** and **Split tapped words automatically**.

## New

**Auto spacing that knows where your words are.** A space after a pause is the easy part. The hard
parts are what make auto spacing hard to live with, and this release handles both:

- **No waiting after short words.** Short words (one to three letters) are usually tapped, longer
  words swiped. In one quick burst, TapSwipe works out where the words are from the dictionary and
  the decoder's language model: tap I, swipe want, tap a gives "I want a"; swipe cat, tap s gives
  "cats"; tap a, swipe nother gives "another".
- **Hesitations repaired.** If you pause mid-word long enough for the space to go in, keep going:
  when the next part reads as the rest of the same word, the space comes back out. Swipe cele, pause,
  swipe bration gives "celebration". Two real words with a pause stay apart.
- **A wrong call costs one tap.** After any split or join, the other reading is first in the
  suggestion strip.

The timer itself: stop touching the keyboard for a moment (250 ms, adjustable) and the space goes
in; a thin bar at the top of the keyboard shows the time left; one space press after an auto space
ends the sentence with a period.

Also new:

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

Bugs that were already in 0.8.0:

- **Words breaking mid-word.** A word you were still building could suddenly end and the next tap
  start a new word. The keyboard misread its own edit as you moving the cursor.
- **Taps ignored when typing fast.** A tap's re-read was skipped when the decoder was busy, so the
  raw letters went in ("technoy", "understandd").
- **Two-thumb order.** Overlapping thumbs could read in the wrong order: "form" became "from", and
  a second thumb's tap was mixed into the other thumb's swipe ("then" became "the").
- **Contractions.** A swipe through the apostrophe for I'm read as "in". Contractions also broke
  the context for the next word ("He'll" was read as "He" and "ll").
- **The word being typed was used as its own context** by the language model, which skewed what it
  read.
- **A quick tap right after a swipe** could replace the whole word with the tapped letter.
- **Undo** of the last stroke could bring the same word back.
- **A mid-word acronym reading** made the rest of the word upper case ("NATIONAL").
- **A space pressed right after a swipe** could lose the word.
- **A crash** during long swipes.

## Under the hood

- Rebased on FUTO Keyboard 0.1.30 and later upstream changes. FUTO's newer swipe language model is
  not used yet, because the prebuilt swipe library has no tuning for it.

## Notes

- Auto spacing is new and still being tuned. A long pause inside a word can still split it; the
  suggestion strip offers the joined word.
- If a mild word you swipe never appears, turn off FUTO's **Block potentially offensive words**.
- The APK is signed with the public upstream debug keystore, like earlier TapSwipe releases.
