# TapSwipe Keyboard

> ## ⚠️ This is a modified version of FUTO Keyboard
>
> **This software has been modified.** It is an unofficial personal fork of
> [FUTO Keyboard](https://github.com/futo-org/android-keyboard), maintained by
> [@SHAWNERZZ](https://github.com/SHAWNERZZ). It is **not** affiliated with, endorsed by, or
> supported by FUTO Holdings, Inc. Please do not report issues with this fork to FUTO.
>
> Changes are not submitted upstream. Bugs you encounter here are most likely mine, not theirs.

## Download

Builds are published on the [Releases page](https://github.com/SHAWNERZZ/tapswipe-keyboard/releases).
It installs alongside the official FUTO Keyboard rather than replacing it, and everything is off by
default — enable it at **Settings → TapSwipe**.

APKs are signed with the shared keystore committed to the upstream repository, as FUTO's own builds
are. That keystore is public, so a signature here proves nothing about who built the APK — only that
it was not tampered with after signing.

## What's different

A reimplementation of the old **Nintype** input model on FUTO's neural swipe decoder. All of it sits
behind one setting (Settings → TapSwipe), off by default.

**Taps and swipes make one word.** `tap H, tap E, tap L, swipe L→O` → "hello". Both thumbs can swipe
at once; the lexicon resolves the interleaving.

**Only a separator commits.** Lifting a finger never does. Until you hit space, every new stroke
re-decodes the whole word.

**Apostrophe key** — optional *QWERTY (Nintype apostrophe)* layout. A thin key in the gap right of L;
redirect a swipe along the right edge to keep "its" and "it's" apart. It claims the margin stock
QWERTY already hides inside L's tap area, so no other key moves. Cost: FUTO's English/QWERTY decoder
refinement is fixed at 26 keys, so a 27th disables it — roughly half a point of top-1 accuracy by
[their paper](https://arxiv.org/abs/2606.25247), the same tax Dvorak and AZERTY already pay. The
layout-agnostic encoder does the real work and handles the extra key natively.

**Backspace undoes the last stroke, not the last letter.** Re-swipe a bad gesture without losing the
taps before it — including for a moment after the word commits, which is usually when you notice.
Optional whole-word delete covers older words, and never touches a run of digits or symbols: losing
a whole phone number to one mistyped digit isn't a trade worth making.

**Backspace slide changes gear mid-gesture.** Sliding across backspace deletes as you drag, starting
at whatever granularity the swipe-backspace setting names. Reverse past a full step and the rest of
that gesture switches to the other granularity — sweep back over three words, bounce right, then
continue left character by character. The switch is one-way: a second reversal is another nudge, not
a request to go back to coarse deletion.

**Peck mode.** Tap a word out slowly and it skips autocorrect, commits exactly as typed, and is
learned so you can swipe it next time — that's how you enter a word the dictionary lacks. Key borders
mark it, the space bar says so, and it ticks when it engages — it can engage on its own after a
pause, so it has to announce itself.

**Nintype gestures** (optional). Swipe straight down from V onto the space bar for a comma, as the
original did. The comma key then comes off the bottom row and the space bar takes its width — the
gesture buys back space rather than leaving a hole. The pull is matched strictly, and anchored to V's
centre with half a key of slack either side, because a false positive eats a word you meant to type.

**Enter key swipes** (optional). Eight assignable directions off the enter key: punctuation by
default (up `?`, down `!`, left `(`, right `)`), and editor actions — paste, clipboard history,
undo, cut, select all, arrows — available for any direction. Optionally drops the period key, handing
its width to enter rather than to the space bar. A plain tap is still enter. Assigning it is a keypad
grid rather than eight dropdowns, since "where is my question mark" is a spatial question. Cost: a
flickable key can't also hold a long-press menu, so enter's is displaced — shift+enter and field
navigation are in the assignable list instead, to be put back on a direction of your choosing.

**Typing speed** (optional). Words per minute on the space bar, measured per text field. Pauses over
ten seconds aren't counted against you.

**Master Mode.** Letter keys render as dots, since typing by shape makes the letters noise. Peck mode
brings them back while you spell something out, and tapping away for a few words restores them until
you swipe again. Action-bar toggle.

**Adaptive key geometry** (opt-in). Learns where your fingers actually land and tells the decoder,
which takes key positions as a runtime input. Aimed at fast, sloppy swiping — deliberate spelling
teaches it nothing. Accepted words get their stroke aligned against the letters it produced, so it
learns mid-word too, where corner-cutting happens. Corrections teach it more: picking a suggestion
labels a gesture the decoder misread, and backspacing into a word takes back what it taught. Shifts
are capped, confidence-gated, suppressed on keys you hit inconsistently, and faded by age.
**Learned key geometry** draws the model over your layout and replays the last word it learned from,
at the timing you typed it.

Design notes and the full bug history are in [docs/history/](docs/history/); the testing
approach is in [TAPSWIPE_TESTING.md](TAPSWIPE_TESTING.md).

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

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

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
