state: done
needs: nothing
files: android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt, android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt
plan: .dispatch/plans/i-keep-seeing-loading-spinner-on-android.md

## What changed
- `App` has a new flag `started`, set in `start()`. `start()` now logs `start`.
- `MainActivity.onCreate` calls `App.start()` when `savedInstanceState == null` or when `App.started` is false.

## Why
After Android kills the background process, the activity is restored with a saved state. `App` is new and shows `Screen.Loading`, but nothing called `start()`. The spinner never ended.

## Files
- MainActivity.kt
- AppState.kt

## Verification
- `gradle -p android test` as written: failed, `gradle` is not installed on this machine (command not found, in bash and PowerShell).
- I ran `./gradlew.bat test` in `android/` instead (deviation). It ended with `BUILD SUCCESSFUL`. The changed code compiles and the existing unit tests pass.
- `adb devices` showed only 192.168.50.60:5555, state `unauthorized`. It is probably the user's own TV. I did not use it.

## Follow-ups
- Nobody tried the device test (kill the process in the background, reopen, check the log and screenshot). No emulator was available. Someone must test it on an emulator or a test device.
- The check command `gradle -p android test` does not work here. Dispatch may count that check as not run.
