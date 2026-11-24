state: done
needs: nothing
files: android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt, android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt, android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
plan: .dispatch/plans/on-android-tv-app-keeps-playing-even-if.md

## What changed
- `PlayerScreen.kt`: a lifecycle observer pauses the player on `ON_STOP`. On the next `ON_START` it opens the controls (Decision 1 Default).
- `MultiView.kt`: a lifecycle observer calls `pauseAll()` on `ON_STOP` and `resumeAll()` on the next `ON_START`.
- `Playback.kt`: the main player (not tiles) sets audio attributes with audio focus on (Decision 2 Default).
- Commit 56270f0 on `main`.

## Why
Home only stops the activity. The composables stay alive, so the player was never paused. ExoPlayer also did not handle audio focus.

## Files
- The three files above. Nothing else was changed or committed.

## Verification
- `cd android && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug` ended with BUILD SUCCESSFUL.
- `grep -c "ON_STOP"` printed 1 for `PlayerScreen.kt` and 1 for `MultiView.kt`.
- `grep -c "handleAudioFocus\|setAudioAttributes"` on `Playback.kt` printed 1.
- `git diff --stat HEAD` for renderer, webos, main.js, preload.js, package.json and MainActivity.kt printed nothing.
- Check "android/gradle tests" was not chosen and was not run.
- Not tried: `adb devices` lists the device 192.168.50.60:5555 as `unauthorized`. So I did not run the reproduction or the `dumpsys audio` proof, before or after the fix.
- Not tried: the manual tests on a TV (Home, YouTube, Multi-view, voice key), a TV where Home shows only an overlay, and a pause of more than 4 minutes.

## Follow-ups
- Deviation: none. The observer sits after `var controls`, not right after the `pb.release()` line, because it sets `controls`.
- Other uncommitted changes (`build.gradle.kts`, `SearchScreen.kt`, `.dispatch/` files) came from other tasks and stay as they were.
- Release: no version bump, no build of all platforms, no push.
- The background player still sends its ping (30 s) and timeline (10 s), so the server keeps the stream while the app waits.
- LG TV was not checked.
- The user must authorize the TV for adb and run the manual tests.
