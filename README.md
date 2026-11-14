# TapSwipe Keyboard

> ## ⚠️ This is a modified version of FUTO Keyboard
>
> **This software has been modified.** It is an unofficial personal fork of
> [FUTO Keyboard](https://github.com/futo-org/android-keyboard), maintained by
> [@SHAWNERZZ](https://github.com/SHAWNERZZ). It is **not** affiliated with, endorsed by, or
> supported by FUTO Holdings, Inc. Please do not report issues with this fork to FUTO.
>
> Changes are not submitted upstream. Bugs you find here are most likely mine, not theirs.

TapSwipe brings back the way the old **Nintype** keyboard worked, on top of FUTO Keyboard's
offline neural swipe decoder. You build each word from taps and swipes in any mix, with one thumb
or two, and the keyboard reads the whole word at once. With auto spacing on, you never need the
space bar between words.

Everything is off by default. Without TapSwipe turned on, it is FUTO Keyboard.

## Download and setup

1. Get the APK from the [Releases page](https://github.com/SHAWNERZZ/tapswipe-keyboard/releases).
   It installs next to the official FUTO Keyboard and does not replace it.
2. Enable it as a keyboard, as you would FUTO Keyboard.
3. Open **Settings → TapSwipe** and turn on **TapSwipe input model**.
4. Optional, recommended for the full Nintype feel:
   - **Auto spacing → Space after a pause** and **Split tapped words automatically**.
   - **Pin Peck mode to the toolbar**.
   - **Master Mode** (letters shown as dots) once you type by shape.

APKs are signed with the shared keystore committed to the upstream repository, as FUTO's own builds
are. That keystore is public, so a signature proves only that the APK was not changed after
signing, not who built it.

## How to type

- **Swipe** most words. **Tap** short words such as a, I, is and to.
- **Mix them in one word.** Tap h, tap e, swipe l to o: "hello". Swipe cat, tap s: "cats".
  Lifting your finger does not end the word.
- **Use both thumbs at once** if you like. The decoder sorts out the order.
- **End a word** with space, punctuation, or Enter. With auto spacing, just pause: a thin bar at
  the top of the keyboard runs down, and the space goes in when it ends. Touch again before then to
  keep adding to the word.
- **Several words in one go:** tap I, swipe want, tap a gives "I want a".
- **End a sentence:** after an auto space, press space once for a period.
- **Spell out a new word** slowly, letter by letter, or press the **Peck mode** button first. The
  word is kept exactly as typed and learned, so you can swipe it next time.
- **Backspace** removes the last stroke, not the last letter. After a space it removes the space
  first, then the word.
- **Wrong split or join?** The other reading is first in the suggestion strip. Tap it.

## Features

### Typing

**Taps and swipes make one word.** Every new stroke re-decodes the whole word. Both thumbs can
swipe at once.

**Auto spacing** (optional, work in progress). A word ends when you stop touching the keyboard for
a moment (250 ms by default, adjustable), and the space goes in by itself. Short tapped words get a
space too. Short tapped words next to a swiped word become words of their own, while a tapped
ending stays with the word (cats). If a pause cut a word in two, the next stroke joins it back
when the decoder reads it as one word. After any split or join, the other reading sits first in the
suggestion strip. A backspace holds the timer until you type again. A space you press out of habit
right after an auto space turns into a period instead of a second space.

**Peck mode.** Spell a word out slowly and it skips autocorrect, is committed exactly as typed, and
is learned. Key borders and the space bar show when it is on. With auto spacing on, a word of taps
that is not a short word turns into a peck word 100 ms after the pause. The optional **Peck mode**
button (a crosshair, left of voice input) does it on demand for one word.

**Tap-only mode.** Type a few long words letter by letter and the keyboard behaves like plain FUTO
Keyboard, with autocorrect and no auto spacing, until your next swipe.

**Apostrophe key** (optional, QWERTY only). A thin key in the gap right of L. Swipe through it for
contractions: I'm, don't, we'll. It uses space QWERTY already gives to L's tap area, so no other
key moves. Cost: FUTO's English/QWERTY decoder refinement expects 26 keys, so a 27th turns it off,
about half a point of top-1 accuracy by [their paper](https://arxiv.org/abs/2606.25247).

### Editing

**Backspace undoes the last stroke, not the last letter.** Re-swipe a bad gesture without losing
the taps before it, even just after the word is committed. Optional whole-word delete covers older
words and never removes a run of digits or symbols.

**Backspace slide changes gear mid-gesture.** Slide across backspace to delete as you drag. Reverse
past a full step and the rest of the slide switches between words and characters.

### Gestures and layout

**Nintype gestures** (optional). Swipe straight down from V onto the space bar for a comma. The
comma key comes off the bottom row and the space bar gets its width.

**Enter key swipes** (optional). Eight assignable directions off Enter: punctuation by default (up
?, down !, left (, right )), and editor actions such as paste, clipboard history, undo, cut, select
all and arrows. Optionally drops the period key and gives its width to Enter.

### Appearance

**Master Mode.** Letter keys show as dots, since typing by shape makes the letters noise. Peck mode
brings them back while you spell something out. There is a toolbar toggle.

**Show the word's gestures.** Every tap and swipe of the word being typed is drawn on the keys.

**Typing speed** (optional). Words per minute on the space bar.

### Learning

**Adaptive key geometry** (opt-in). Learns where your fingers actually land and tells the decoder.
It works with or without the TapSwipe input model. **Learned key geometry** shows the model over
your layout and replays the last word it learned from.

**Typing test.** Word lists and three short real-text passages, scored with punctuation. Every
touch is recorded on the phone, so a problem can be replayed and fixed.

## What's new in 0.9.0

- Auto spacing: space after a pause, a countdown bar at the top of the keyboard, short tapped words,
  a period from one space press, rejoining a word cut by a pause.
- Word splitting decided once, when the word ends: "I want a", "a start is", "this is a" now come
  out right.
- The other reading of a split or join in the suggestion strip.
- Peck mode toolbar button; peck mode by pause; tap-only mode turns auto spacing off.
- Backspace after a space removes the space first.
- Fixes: contractions such as I'm, capitals after a split or a mid-word acronym, a quick tap right
  after a swipe, undo bringing back the same word, a crash during long swipes.
- Rebased on FUTO Keyboard 0.1.30 and later upstream changes.

## Known limits

- Auto spacing is new. A long pause inside a word can still split it.
- FUTO's newer swipe language model is not used yet: the prebuilt swipe library has no tuning for
  it, so this build keeps the previous one.
- Turn off **Block potentially offensive words** (FUTO's setting) if a mild word you swipe never
  appears.

## Licensing

This fork is distributed under the same [FUTO Source First License 1.1-kb](LICENSE.md) as the
original, free of charge and for non-commercial use only. Those terms carry over to this fork and
to anything derived from it — they are **not** an open-source licence. The swipe model weights are
covered separately by the FUTO Model Weights License 1.0 and are referenced as a submodule rather
than redistributed here.

---

*The original FUTO Keyboard README follows, unmodified.*

---

# FUTO Keyboard

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Nightly builds

Usually, a build close to the current master is available at [https://dl.keyboard.futo.org/nightly.apk](https://dl.keyboard.futo.org/nightly.apk). Read more about it here: https://docs.keyboard.futo.tech/improvements/nightly

The "Check for updates manually" button in the nightly build will check against the latest nightly, allowing you to get updated this way. It's recommended to check it once immediately after first installation in case an older version got downloaded due to CDN cache.

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
