# Plan: i-keep-seeing-loading-spinner-on-android

## Summary
Fix the endless start-up spinner on Android TV when the app comes back after Android closed it in the background.
2 files change (MainActivity.kt, AppState.kt).
1 decision, no HOLD.

## Goal
When the user opens Buffer Zone on Android TV, the app always goes past the loading spinner: to Home, profiles, sign-in or an error message with "Try again".

## TL;DR
- The TV closes apps in the background to free memory; when you open Buffer Zone again, it can stay on the spinner for ever.
- The fix makes the app start its normal sign-in and server check in that case too, so you land on Home again.
- A slow network at start still shows the spinner until the connection answers or fails; that part stays as it is unless you choose otherwise.

## Acceptance
Cause from code reading (the plan run could not run commands): `MainActivity.onCreate` calls `App.start()` only when `savedInstanceState == null`. When Android kills the background process and later restores the activity, `savedInstanceState` is not null, but `App` is a new object with `nav = [Screen.Loading]`. Nothing calls `start()`, so the spinner never ends.

Reproduction and proof, on an Android TV emulator or a test device (see Notes about the user's TV):
1. `gradle -p android assembleDebug` — expected: `BUILD SUCCESSFUL`.
2. `adb install -r <debug apk>`, open the app, sign in until Home shows.
3. `adb shell input keyevent KEYCODE_HOME`, then `adb shell am kill com.dkrb56.bufferzone`, then `adb shell pidof com.dkrb56.bufferzone` — expected: no output (process gone).
4. `adb logcat -c`, then `adb shell am start -n com.dkrb56.bufferzone/.MainActivity`.
5. After 20 s: `adb logcat -d -s BufferZone` and `adb exec-out screencap -p > <job tmp>/after.png`.
   - Before the fix: no `start` log line, screenshot shows only the spinner.
   - After the fix: the log has the start line, the screenshot shows Home (or the profile picker).
6. `gradle -p android test` — expected: `BUILD SUCCESSFUL` (compiles the changed code).

## Checks
- [x] android/gradle tests — compiles the changed Kotlin code and runs the existing unit tests.

## Files to change
- `android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt`, anchor line `if (savedInstanceState == null) App.start()` — call `App.start()` also when the app object has not started in this process yet. Reason: this is the line that skips start-up after a process restore.
- `android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt`, `object App`, `fun start()` — add a flag that `start()` sets (readable from MainActivity, written only inside App), and one `log("start")` line at the top of `start()` so the proof can see that start-up ran. Reason: the flag lives as long as the process, like `store`; the log line is the scripted proof.
- Only if decision 1 is "Other": `AppState.kt`, `fun start()` / `suspend fun reconnect()` — after 20 s on `Screen.Loading`, show a line "Still connecting…" with a "Try again" button. Reason: covers a slow network at TV wake.

## Files read
- `MainActivity.kt`: `onCreate` runs `App.init(this)` then `App.start()` only for `savedInstanceState == null`.
- `AppState.kt`: `App` is a Kotlin `object`; `nav` starts as `[Screen.Loading]`; only `start()` / `reconnect()` move it on.
- `AppState.kt`: `App.init` returns early when `store` is set, so App state lives as long as the process.
- `AndroidManifest.xml`: `configChanges` covers orientation, screen size, uiMode, keyboard; most config changes do not recreate the activity.
- `Plex.kt`: `request()` catches all errors and returns status 0; `connectTimeout` 10 s, `readTimeout` 25 s; `probe` waits at most 7 s per group.
- `HomeScreen.kt`: the row spinners end in all cases, because each load uses `runCatching` with an empty default.
- `.claude/memory.md`: release rule (bump version, build all, push, publish) — it is the user's step, not a worker's.
- `.claude/mistakes.md`: tests must never overwrite or delete user data.

## Notes for the work run
- Keep the `savedInstanceState == null` start: after Back on Home the activity finishes but the process stays; a new launch must still refresh, as today.
- A config change that recreates the activity keeps the process, so the flag stays set and no second start happens; that keeps today's behaviour.
- `start()` is also the "Try again" action of the plex.tv error message; the flag must not stop that call.
- `start()` also launches the update check (5 s delay); with the flag it still runs once per process.
- In a restored process, `nav` is not saved by Compose; the user lands on Home or Profiles after start-up, not on the old screen. This is expected.
- `adb shell am kill` only kills a process in the background; press Home first.
- Bash was denied to the plan run, so nothing was reproduced yet; the work run must reproduce before the fix (Acceptance steps 2–5).
- Do not install a debug build over the user's own TV app and never `adb uninstall` it: that deletes the sign-in, folders and settings (see `.claude/mistakes.md`). Use an emulator; if only the user's TV is connected, ask under `## Questions`.
- Use `adb devices` first; if no emulator or device exists, report the reproduction as not tried and keep the code change and the gradle check.
- Other uncommitted changes in the tree belong to other tickets (`build.gradle.kts`, `Playback.kt`, `PlayerScreen.kt`, `MultiView.kt`, `SearchScreen.kt`, `android/app/src/test/`); do not commit them.
- Screenshots and logs go to `$CLAUDE_JOB_DIR/tmp`, not into the repo.

## Steps
1. Run `adb devices`; pick an emulator or test device (see Notes).
2. Build and install the current code; run Acceptance steps 2–5; save the log and screenshot as the "before" evidence.
3. Add the flag and the `log("start")` line in `App.start()`.
4. Change the anchor line in `MainActivity.onCreate` to start also when the flag is not set.
5. If decision 1 is "Other", add the "Still connecting…" message with "Try again".
6. Rebuild, reinstall, repeat Acceptance steps 2–5; save the "after" evidence.
7. Run the chosen checks.
8. Commit `MainActivity.kt` and `AppState.kt` by name with the ticket id in the message; write the report.

## Decisions
1. Should the app also show a message when start-up is slow, for example right after the TV wakes?
   - Default: No, only fix the spinner that never ends.
     Recommended: the stuck spinner has a clear cause; a slow network already ends in an error message.
   - Other: Yes, after 20 seconds show "Still connecting…" with a Try again button.
     Not recommended: it adds a new screen state for a case nobody has seen yet.

## Do not touch
- The root `package.json` version, `npm run build:all`, GitHub releases and pushes: the user decides these.
- `.dispatch/tickets.jsonl` and the files of other tickets.
- The user's installed app and its data on the TV.

## Size
2 files.

## Work run
model: sonnet
effort: medium
The code change is small; the care is in the device test and in not touching the user's own TV app.

## User decisions

The user took these decisions in Dispatch on 2026-11-24 17:46. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Should the app also show a message when start-up is slow, for example right after the TV wakes?: No, only fix the spinner that never ends

## Checks chosen

The user chose these checks in Dispatch on 2026-11-24 16:46 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.

- [x] android/gradle tests — `gradle -p android test`
