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
default - enable it at **Settings -> TapSwipe**.

APKs are signed with the shared keystore committed to the upstream repository, as FUTO's own builds
are. That keystore is public, so a signature here proves nothing about who built the APK - only that
it was not tampered with after signing.

## What's different

A reimplementation of the input model from the old **Nintype** keyboard, built on top of FUTO's
neural swipe decoder. Two behaviours differ fundamentally from the upstream keyboard:

- **Taps and swipes compose into one word.** `tap H, tap E, tap L, swipe L->O` decodes as "hello".
  Both hands can swipe at once; the decoder resolves the interleaving through the lexicon.
- **Only a separator finalizes a word.** Lifting a finger never commits. Space, punctuation, or
  Enter ends the word; until then every new tap or swipe re-decodes the whole thing.

Plus a **peck mode** for words that contain no swipe: tap a word out slowly and deliberately and it
skips autocorrect, is committed exactly as typed, and is learned so you can swipe it next time. This
is how you enter a word the dictionary doesn't know. Key borders mark it while it's active.

**Master Mode** renders letter keys as dots - once you're typing by shape rather than reading keys,
the letters are just noise. Peck mode brings them back while you spell something out, and if you
simply tap away for a few words the keyboard concludes you want a normal keyboard and restores the
letters until you swipe again. There's an action-bar button to toggle it.

**Backspace edits the thing you just did.** While a word is being built it removes the last tap or
swipe rather than the last character, so a mis-swipe can be redone without losing what came before
it. That holds for a moment after the word finishes too, which is usually when you notice it came
out wrong. Whole-word delete is available as a setting for older words, and never applies to a run
of digits or symbols with no letters - deleting an entire phone number because the last digit was
mistyped is not a trade worth making.

**Adaptive key geometry** (opt-in) learns where your fingers actually land and tells the decoder,
which takes key positions as a runtime input. It aims at fast, sloppy swiping - the case that needs
help - so a deliberately spelled word teaches it nothing. Once a word is accepted, its stroke is
aligned against the letters it produced to work out which part of the path was aiming at which key,
which reaches the middle of a word where corner-cutting actually happens. Corrections teach it more
than accepted words do: picking a suggestion supplies the right answer for a gesture the decoder
demonstrably misread, and backspacing into a word takes back what was learned from it. Shifts are
capped, confidence-gated, suppressed on keys you hit inconsistently, and faded on elapsed time.
Settings -> TapSwipe -> **Learned key geometry** draws the model over your layout and can replay
the last word it learned from, at the timing you actually typed it.

An optional **QWERTY (Nintype apostrophe)** layout puts a thin apostrophe key in the gap right of L,
so redirecting a swipe along the right edge keeps "its" and "it's" apart. The gap it uses already
exists on stock QWERTY, folded invisibly into L's tap area, so no other key moves. It does turn off
FUTO's English/QWERTY decoder refinement, which is fixed at 26 keys - about half a point of top-1
accuracy by [their paper's numbers](https://arxiv.org/abs/2606.25247), the same as already happens
on Dvorak and AZERTY.

Everything is behind a setting (Settings -> TapSwipe -> "TapSwipe input model"), off by default,
so the stock behaviour is one toggle away. Design notes, the reasoning behind each decision, and a
full record of the bugs found along the way are in [TAPSWIPE_PLAN.md](TAPSWIPE_PLAN.md); the testing
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
