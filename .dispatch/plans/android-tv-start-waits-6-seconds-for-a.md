# Plan: android-tv-start-waits-6-seconds-for-a

## Summary
The Android server check stops the slow address checks for real, so it no longer waits up to 6 s for a dead address.
1 file changes (Plex.kt). Windows and webOS already continue at the first answer.
1 decision, no HOLD.

## Goal
On Android TV, the start continues right after the first server address answers. The step from the server list to the end of the server check takes less than 1 second on the Philips TV, not about 6 seconds.

## TL;DR
- The TV app checks all server addresses at the same time, but it waited for a dead address to give up before it went on.
- After the fix, the app goes on as soon as one address answers. A normal start gets about 6 seconds shorter.
- A direct address still wins over the Plex relay, and each check keeps its time limit.
- The Windows and LG TV apps already go on at the first answer, so they do not change.

## Acceptance
- `cd android && gradlew.bat assembleDebug` (with `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` when not set) → `BUILD SUCCESSFUL`.
- `gradle -p android test` (or `android\gradlew.bat -p android test`) → `BUILD SUCCESSFUL`, existing tests pass.
- `adb connect 192.168.50.60:5555` → `connected to 192.168.50.60:5555`.
- `adb -s 192.168.50.60:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk` → `Success`.
- `adb -s 192.168.50.60:5555 shell am force-stop com.dkrb56.bufferzone`, `adb -s 192.168.50.60:5555 logcat -c`, then a cold start with `adb -s 192.168.50.60:5555 shell monkey -p com.dkrb56.bufferzone -c android.intent.category.LAUNCHER 1`.
- `adb -s 192.168.50.60:5555 logcat -d -v time -s BufferZone` → the temporary lines "probe begin" and "probe end" are less than 1.0 s apart, in at least 3 cold starts. Before the fix they are about 6 s apart (brief: 0.17 s win, return after 6 s).
- `git diff --stat` before the commit → only `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`, and no temporary log line in it.

## Checks
- [x] gradle tests — the change touches the network code of the Android app; the existing tests must still pass.

## Files to change
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`, function `probe` (anchor: `val jobs = list.map { c -> launch { if (request("${c.uri}/identity", token = token, timeoutMs = 6000).ok) result.complete(c) } }`). Replace the `request(...)` call in each job with a private cancellable identity check: build the OkHttp `Call` for `${c.uri}/identity` with the same headers (`clientHeaders()` + `X-Plex-Token`) and the same 6000 ms `callTimeout`, run it with `enqueue` inside `suspendCancellableCoroutine`, and call `call.cancel()` in `invokeOnCancellation`. Result `true` only for a 2xx answer; any failure gives `false`. Reason: the blocking `execute()` in `request` ignores coroutine cancellation, so `coroutineScope` waits for the dead address until its 6 s timeout.
- Keep the 7000 ms `withTimeoutOrNull`, the two rounds (direct first, then relay) and `request()` itself unchanged.

## Files read
- `.dispatch/briefs/android-tv-start-waits-6-seconds-for-a.md` — the symptom: winner at 0.17 s, `probe()` returned 5.9 s later, 4 of 4 starts.
- `android/.../Plex.kt:197-214` — `request` runs a blocking `execute()` in `withContext(Dispatchers.IO)`; nothing cancels the OkHttp call.
- `android/.../Plex.kt:239-253` — `probe` completes a `CompletableDeferred` at the first answer, then `cancelChildren()`, but `coroutineScope` still joins all children.
- `android/.../AppState.kt:157-169` — `reconnect()` shows Loading, calls `plex.resources()`, then `connect(saved)` → `plex.connect` → `probe`.
- `android/.../AppState.kt:103` — `App.log` writes `Log.i("BufferZone", …)`.
- `android/.../Onboarding.kt:155` — the server list of onboarding also calls `probe` per server; it gets the same speed-up.
- `renderer/js/plex.js:78-89` — Windows (and webOS, built from `renderer/`) uses `Promise.any`, which resolves at the first success and does not wait for slow ones.
- `webos/src/shim.js:21` — webOS has a `Promise.any` polyfill; `webos/service/index.js:29` keeps the per-request timeout.
- `android/app/src/test/.../SearchCancelTest.kt` — the only unit test; it does not touch `probe`.
- `android/app/build.gradle*` — OkHttp 4.12.0, JUnit 4; no MockWebServer.

## Notes for the work run
- Root cause: a coroutine cancel does not stop a blocking `OkHttp execute()` inside `withContext(Dispatchers.IO)`; `withContext` returns only after the block ends. So `coroutineScope` in `first()` waits for the slowest address (6000 ms `callTimeout`).
- The measured gap (0.17 s win, return at +5.9 s) matches the 6000 ms timeout of the dead address.
- `cancelChildren()` already runs after the win; once each job cancels its `Call`, the scope ends at once. No other change in `first()` is needed.
- `suspendCancellableCoroutine` needs `import kotlinx.coroutines.suspendCancellableCoroutine` and `kotlin.coroutines.resume`; `okhttp3.Call`, `okhttp3.Callback` and `java.io.IOException` for the callback.
- In `onResponse`, close the response (`response.use { … }`) and resume with `response.isSuccessful`. In `onFailure`, resume with `false`. Guard with `if (cont.isActive)` so a late callback after cancel does nothing.
- Build the client with `http.newBuilder().callTimeout(6000, TimeUnit.MILLISECONDS).build()`, as `request` does for a non-default timeout.
- Temporary log lines for the measurement only: `App.log("probe begin")` / `App.log("probe end")` around the `plex.connect(res)` call or inside `probe`. Remove them before the commit.
- `JAVA_HOME` default from `scripts/build-all.js`: `C:\Program Files\Android\Android Studio\jbr`.
- Measure at least 3 cold starts with `am force-stop` before each; the brief's numbers came from 4 starts.
- Project memory asks for a version bump, `npm run build:all`, a push and a GitHub release after each task. Dispatch rules forbid push for a worker; leave this to the user and name it in Follow-ups.

## Steps
1. Add the temporary log lines "probe begin" and "probe end" around the probe. Build, install, and measure 3 cold starts on the TV to confirm the ~6 s gap before the fix. Record the times in the report.
2. In `Plex.kt`, add the private cancellable identity check and use it in `probe`'s jobs.
3. Build, install, and measure 3 cold starts again. Each gap must be under 1.0 s.
4. Check that the app still reaches the home screen and that the server label shows the same kind of connection as before (local or remote, not relay).
5. Remove the temporary log lines. Run `gradle -p android test` and `assembleDebug` again.
6. Commit only `Plex.kt` with the ticket id in the message. Write the report.

## Decisions
1. Should only the server check stop its slow tries, or every network request of the TV app?
   - Default: Only the server check stops its slow tries.
   Recommended: it fixes the wait and changes nothing else in the app.
   - Other: Every request of the TV app stops when the screen no longer needs it.
   Not recommended: it touches all screens and needs more testing than this ticket asks.

## Do not change
- The order of preference: direct addresses first, the Plex relay only when no direct one answers.
- The 6000 ms limit of each address check and the 7000 ms limit of each round.
- `request()` in `Plex.kt` and all other callers.
- `renderer/js/plex.js`, `webos/` and `AppState.kt` (except the temporary log lines, removed before the commit).
- The version in `package.json`; no build:all, push or release.

## Size
1 file (`Plex.kt`), about 25 lines.

## Work run
model: opus
effort: medium
The code change is small and clear; most of the work is the measurement on the TV.
