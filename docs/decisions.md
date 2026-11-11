# Decisions

One entry per decision. Log a decision when another option was
seriously considered, or when undoing it would cost real time.

Decisions made before 2026-11-11 are recorded in `docs/history/`,
mainly `TAPSWIPE_DESIGN_RATIONALE.md`. This file starts after the move
to Linux.

## 2026-11-11. Build and test on the Linux server

**Decision.** All builds and tests run on the Unraid server, in the
Ubuntu container. The Windows clone is retired.

**Rejected.** Keep building on Windows and use the server only for
reading. The server has KVM, an x86_64 system image, and enough memory
to run an emulator without anyone attending it. The typing simulator
needs that.

**Consequences.**

- `local.properties` sets `sdk.dir=/opt/android-sdk`.
- The dev APK goes to `/Documents/Notes/tapswipe-dev.apk`. That folder
  is the same folder as `D:\Documents\Notes` on Windows.
- Git needs `safe.directory` for this repository, because the files
  belong to the share user, not root. Set globally for this one path.

## 2026-11-11. The apostrophe layout is a setting, in a fork-owned folder

**Decision.** `qwerty_nintype.yaml` moves to
`java/assets-tapswipe/layouts/TapSwipe/`, a second assets folder this
repository owns. A TapSwipe setting, **Apostrophe key**, swaps it in for
`qwerty`. It is no longer a language layout.

**Rejected.**

- Fork the upstream layouts repository and commit the file there. That
  adds a second repository to maintain, and every upstream layout update
  becomes a merge.
- Leave it in the layouts submodule. That is how it lived until now,
  uncommitted, so a build from git silently dropped it. It also breaks
  the rule against editing submodules.
- Keep it as a language layout, picked in language settings. The user
  asked for a TapSwipe setting.

**Consequence.** The user's stored subtype names `qwerty_nintype`. A
one-time migration turns the setting on for that case, and the swap runs
both ways, so the toggle controls the key either way.

## 2026-11-11. Commit the Windows work in progress unchanged

**Decision.** The word-split work that was uncommitted on Windows went
into one commit, unchanged, before any other edit.

**Rejected.**

- Leave it uncommitted in the main checkout. About 1,750 files there
  show as modified only because of CRLF line endings. The real work was
  hidden, and one careless commit would have mixed the two.
- Finish the feature first, then commit. The work would stay outside
  git for longer, with no record of what it was before new edits.
