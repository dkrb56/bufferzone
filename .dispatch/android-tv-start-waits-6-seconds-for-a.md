state: done
needs: nothing
files: android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt
plan: .dispatch/plans/android-tv-start-waits-6-seconds-for-a.md

## What changed
The Android TV app now continues right after the first server address answers. The server check went from about 6 s to about 0.17 s on the Philips TV. Commit `ded84a5`.

## Why
The app checked all addresses at the same time, but a stopped check did not stop its network call. The app then waited for the dead address to reach its 6 s time limit. Now each address check uses a network call that stops when the check stops.

## Files
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`: new private function `identityOk` (async OkHttp `enqueue` inside `suspendCancellableCoroutine`, `call.cancel()` on cancel, same headers, same 6000 ms `callTimeout`). `probe` uses it instead of `request(...)`. The 7000 ms round limit, the order direct-then-relay and `request()` did not change.

## Verification
Measured on the TV 192.168.50.60:5555 with temporary log lines "TMP probe begin" / "TMP probe end" in `probe`. A script did `am force-stop`, `logcat -c`, a `monkey` launch, and read `logcat -d -v time -s BufferZone`. Each start was a cold start.

| Start | Before fix (begin → end) | After fix (begin → end) |
|---|---|---|
| 1 | 18:58:58.772 → 18:59:04.801 = 6.03 s | 19:00:26.885 → 19:00:27.042 = 0.157 s |
| 2 | 18:59:11.760 → 18:59:17.780 = 6.02 s | 19:00:33.537 → 19:00:33.705 = 0.168 s |
| 3 | 18:59:24.470 → 18:59:30.485 = 6.02 s | 19:00:40.091 → 19:00:40.258 = 0.167 s |

- The winning address was `http://plex.v12.appboxmanager.xyz:80` before and after the fix (a direct address, not the relay).
- A screenshot after start 3 showed the home screen with Continue watching content.
- I removed the temporary log lines. `grep TMP` on `Plex.kt` finds nothing. `git diff --stat` before the commit showed only `Plex.kt`.
- `gradle -p android test` (the check as written): exit 127, `gradle: command not found`. Gradle is not on PATH on this machine. The check was not ticked in Checks chosen.
- `android/gradlew.bat -p android test`: `BUILD SUCCESSFUL`, exit 0.
- `./gradlew.bat assembleDebug` in `android/`: `BUILD SUCCESSFUL`, exit 0. I installed this clean build on the TV.

## Follow-ups
- Windows and webOS already continue at the first answer (`Promise.any` in `renderer/js/plex.js`). No change there, as the plan says.
- Version bump, `npm run build:all`, push and GitHub release are for the user.
- The check command `gradle -p android test` in `.dispatch/checks.txt` fails on this machine because `gradle` is not on PATH. `android/gradlew.bat -p android test` works.
- The rest of the 13 s start (about 2–3.5 s from "start" to "probe begin", and the time after the probe) was not measured in this run.

## Not tried
- I did not open the server label in Settings. The log shows the same direct address as before.
- I did not test a start where no direct address answers (relay only), or a server that is offline.
- I did not test the onboarding server list, which also uses this check.
