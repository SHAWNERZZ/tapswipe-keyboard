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
   cp build/outputs/apk/unstable/debug/futo-keyboard-unstable-debug.apk \
      "C:/Users/Shawn/Documents/Claude/tapswipe-dev.apk"
   ```

The build output path is fixed by the flavor and the build type. Flavor
`unstable` plus build type `debug` always writes
`build/outputs/apk/unstable/debug/futo-keyboard-unstable-debug.apk`.
Confirm the file timestamp after the copy.

3. Report only the destination path. Do not report paths under
   `AppData\Local\Temp` or under a worktree.

## Do not

- Rename the dev APK per feature.
- Copy the APK to any folder other than the one in `CLAUDE.local.md`.
- Sign the release keystore into anywhere the repository can pick up.

## After a build

Do not run tests inside this skill. Testing lives in
`verify-tapswipe-change`. The user decides whether to run it.
