---
name: build-dev-apk
description: Build the TapSwipe unstable debug APK and copy it to the user's dev APK path with a single stable filename.
disable-model-invocation: true
---

# build-dev-apk

Use this when the user asks for a dev build.

## Steps

Run both commands from the repository root, in the Bash tool.

1. Build.

   ```
   ./gradlew assembleUnstableDebug
   ```

2. Copy the output over the dev APK.

   ```
   cp build/outputs/apk/unstable/debug/*-unstable-debug.apk \
      /Documents/Notes/tapswipe-dev.apk
   ```

The folder is fixed by the flavor and the build type. The file name
starts with the project folder's name, so it differs between the main
checkout (`tapswipe-keyboard-unstable-debug.apk`) and a worktree. The
glob matches both. Confirm the file timestamp after the copy.

3. Report only the destination path. Do not report paths under a
   worktree.

## Do not

- Rename the dev APK per feature.
- Copy the APK to any folder other than the one in `CLAUDE.local.md`.
- Sign the release keystore into anywhere the repository can pick up.

## After a build

Do not run tests inside this skill. Testing lives in
`verify-tapswipe-change`. The user decides whether to run it.
