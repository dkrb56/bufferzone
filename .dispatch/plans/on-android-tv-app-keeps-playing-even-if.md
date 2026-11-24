# Plan on-android-tv-app-keeps-playing-even-if: Android TV pauses the video when you leave the app

## Summary
The Android TV player and the Multi-view tiles pause when the app goes to the background (Home key, another app), so no sound plays behind other apps.
2 files change (`PlayerScreen.kt`, `MultiView.kt`); with Decision 2 Default also `Playback.kt`.
2 decisions, none is HOLD.

## Goal
Today, when the user presses Home or opens another app while a show plays, the sound goes on behind the other app. After this task, the player pauses as soon as Buffer Zone is no longer on the screen. When the user comes back, the player shows the paused video with its controls (Decision 1). Multi-view tiles also pause in the background and play on when the user comes back, because tiles have no Play button. With Decision 2 Default, the main player also pauses, or gets quieter for a short sound, when another app starts to play sound while Buffer Zone is on the screen.

Cause, with evidence:
- `android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt` lines 46–54: the activity overrides only `onCreate`; there is no `onStop` or `onPause`.
- `grep "Lifecycle|onStop|onPause|AudioAttributes"` over `android/app/src` finds nothing: no code reacts to the app going to the background, and ExoPlayer does not handle audio focus.
- `PlayerScreen.kt` line 106: the player is released only when the composable leaves (`DisposableEffect ... onDispose { pb.release() }`). Home only stops the activity; Compose keeps the composition, so the ExoPlayer plays on.
- `MultiView.kt` line 97: tiles pause only in `onDispose` of `MultiViewScreen`, which also does not run on Home.
- `Playback.kt` lines 57–60: the `ExoPlayer.Builder` sets no `setAudioAttributes(..., handleAudioFocus = true)`, so another app that takes the sound does not pause the player.

## TL;DR
- When you press Home or switch to another app, the show now pauses. You no longer hear it behind other apps.
- When you come back, the show waits paused with the controls open. Press Play to go on.
- Multi-view streams also pause in the background, and play on when you come back.
- Optional: the show also pauses when another app starts to play sound (Decision 2).

## Acceptance
- `cd android && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug` ends with `BUILD SUCCESSFUL`.
- `grep -c "ON_STOP" android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt` prints a count of at least 1 for each file.
- `grep -c "handleAudioFocus\|setAudioAttributes" android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt` prints at least `1` with Decision 2 Default, `0` with Decision 2 Other.
- `git diff --stat HEAD -- renderer webos main.js preload.js package.json android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt` prints nothing.
- `git status --short` before the commit lists only the files of this task as changed, plus the `.dispatch/` files that were there before.
- Reproduction and proof with a device (only when `adb devices` lists a TV or an emulator): install with `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, start a title, wait until it plays, run `adb shell input keyevent KEYCODE_HOME`, wait 3 s, then run `adb shell dumpsys audio`. In the section of the playback activity, the player of `com.dkrb56.bufferzone` shows `state:started` before the fix and `state:paused` after the fix. Run the same steps with the old build first (`git stash` is not allowed; build the old APK from `HEAD` before you change the code) to record the reproduction.
- Manual, for the tester (Android TV): play a show, press Home, open YouTube: no sound of the show. Go back to Buffer Zone: the video is paused at the same spot with the controls open (Decision 1 Default); press OK on Play: it plays. Repeat in Multi-view with two streams: in the background there is no sound; when you come back, both tiles play on.
- Manual, for the tester (Decision 2 Default): while a show plays, hold the voice key of the remote and speak: the show pauses or gets quieter, and plays on after the assistant closes.
- Nobody tried yet: a TV where Home only shows an overlay and the app stays visible behind it; a stream paused in the background for more than 4 minutes and then resumed (the 404 restart path in `Playback.kt` should handle it).

## Checks
- [ ] android/gradle tests — `gradle` is not on PATH on this machine and the project has no Android unit tests; the `assembleDebug` command under Acceptance compiles the changed Kotlin instead.

## Files to change
- `android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt`, in `fun PlayerScreen(req: PlayRequest)`, after the line `DisposableEffect(Unit) { onDispose { pb.release() } }`: add a lifecycle observer on `LocalLifecycleOwner.current` (from `androidx.lifecycle.compose`), added in a `DisposableEffect(owner)` and removed in its `onDispose`. On `Lifecycle.Event.ON_STOP`: remember `pb.player.playWhenReady` and call `pb.player.pause()`. On `Lifecycle.Event.ON_START`, only after an `ON_STOP` was seen: with Decision 1 Default set `controls = true` (the existing effect then focuses Play); with Decision 1 Other call `pb.player.play()` when the remembered value was true. Reason: `ON_STOP` comes when the app is no longer visible (Home, another app); the composable stays alive, so this is the place that has `pb` and `controls`.
- `android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt`, in `fun MultiViewScreen()`, next to `DisposableEffect(Unit) { onDispose { MultiView.pauseAll() } }`: the same kind of observer; `ON_STOP` calls `MultiView.pauseAll()`, `ON_START` after a seen `ON_STOP` calls `MultiView.resumeAll()`. Reason: same fault; tiles have no Play button, so they must play on by themselves when the user comes back.
- Decision 2 Default only: `android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt`, in `init`, inside the existing block `if (!tile) { ... }`: call `player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)` (media3 `AudioAttributes`; `C` is imported here as `MC`). Reason: with `handleAudioFocus = true` ExoPlayer pauses when another app takes the sound, ducks for short sounds and plays on after a short interruption. Only for the main player: four tiles that each ask for audio focus would pause each other.

## Files read
- `MainActivity.kt`: only `onCreate`; `FLAG_KEEP_SCREEN_ON`; `Root()` draws `PlayerScreen` over the current screen when `App.playback` is set.
- `AndroidManifest.xml`: one activity, no picture-in-picture, no service; so the app has no reason to play in the background.
- `PlayerScreen.kt` line 167–169: the controls hide after 4 s only while `pb.playing`; paused, they stay open.
- `PlayerScreen.kt` line 163: the next-episode countdown counts only while `pb.playing`, so a pause in the background also stops the countdown.
- `PlayerScreen.kt` line 170–172: `LaunchedEffect(controls)` focuses the Play button when `controls` turns true.
- `Playback.kt` line 303: `togglePlay()` uses `player.isPlaying`; while buffering it would call `play()`, so the fix must call `pause()` directly.
- `Playback.kt` lines 99–102 and 373–387: a pause sends the timeline `paused`; the timer keeps sending it every 10 s and pings the HLS session every 30 s, also in the background.
- `Playback.kt` lines 277–281: after a long pause the server ends the session; a 404 reloads at the same spot and keeps `playWhenReady` (fix of ticket pause-resumes-after-restart).
- `MultiView.kt` lines 84–85: `pauseAll()` and `resumeAll()` exist; line 96 calls `resumeAll()` when the screen opens.
- `android/app/build.gradle.kts`: Compose BOM 2025.05.01, media3 1.6.1, no direct lifecycle dependency.
- `android/app/build/intermediates/.../file-map.txt`: `lifecycle-runtime-compose` is already on the build classpath (it comes with Compose UI).
- `.claude/memory.md`: Plex ends an idle HLS session after about 210 s; the user rule wants each update on all platforms.

## Notes for the work run
1. `LocalLifecycleOwner` lives in `androidx.lifecycle.compose` (no new dependency). If the import does not resolve, use `androidx.compose.ui.platform.LocalLifecycleOwner` (deprecated, but present); do not add a dependency.
2. An observer added to a started lifecycle gets `ON_CREATE`, `ON_START` and `ON_RESUME` at once. Act on `ON_START` only after an `ON_STOP` was seen, or the player opens with the controls forced on.
3. Use `ON_STOP`, not `ON_PAUSE`: on Google TV a system panel can pause the activity while the video is still visible.
4. Call `pb.player.pause()`, not `pb.togglePlay()` (see Files read, `togglePlay` uses `isPlaying`).
5. Keep the flags of the observer in `remember` state or local variables of the effect; `pb` is `remember`ed per `PlayerScreen`, so the effect keys on the lifecycle owner only.
6. `pause()` triggers `onIsPlayingChanged(false)`, which reports `paused` to Plex; nothing else to do for watch state.
7. With Decision 2 Default, `ExoPlayer.play()` asks for audio focus itself; there is no extra code for the request or the release.
8. Do not touch `MainActivity`: the activity has no access to the `Playback` object of the composable.
9. The background player keeps its ping and timeline every 30 s and 10 s, so the server keeps the stream while the app waits in the background. This is not part of the task; write it under Follow-ups.
10. The desktop and LG TV apps are not changed: the ticket is about Android TV, and nobody saw the fault on LG TV. Write under Follow-ups that LG TV was not checked.
11. Do not bump the version, build all platforms, push or publish; CLAUDE.md forbids the worker to push. List the release under Follow-ups.
12. To record the reproduction on a device, build and install the APK from the unchanged code first, run the `dumpsys audio` steps of Acceptance, then change the code.

## Steps
1. Run `adb devices`. When a device is listed, build the unchanged code with the `assembleDebug` command, install it and record the reproduction with the `dumpsys audio` steps.
2. Add the observer to `PlayerScreen.kt` as in Files to change and Decision 1.
3. Add the observer to `MultiView.kt`.
4. Decision 2 Default: add the audio attributes to `Playback.kt`.
5. Run the `assembleDebug` command, the `grep` commands and the `git diff --stat` command from Acceptance.
6. When a device is listed, install the new APK and run the `dumpsys audio` steps again; otherwise report the device tests as not tried.
7. Run `git status --short`; commit the changed files by name on `main` with `on-android-tv-app-keeps-playing-even-if` in the message.
8. Write the report `.dispatch/on-android-tv-app-keeps-playing-even-if.md` with Follow-ups: version bump and release, background ping (Note 9), LG TV not checked (Note 10).

## Decisions
1. When you come back to the app, what does the paused show do?
   - Default: It waits, paused, with the controls open; you press Play. Recommended: you do not miss a scene while the TV switches back.
   - Other: It plays on by itself where it stopped. Not recommended: sound starts at once, maybe before you sit down.
2. Should the show also pause when another app starts to play sound?
   - Default: Yes; a short sound only makes it quieter, then it plays on. Recommended: this is how TV video apps usually behave, for example during voice search.
   - Other: No; only leaving Buffer Zone pauses the show. Not recommended: the voice assistant and the show can talk over each other.

## Do not touch
- `MainActivity.kt`, `AndroidManifest.xml`: no service, no picture-in-picture.
- `Playback.kt` outside the `if (!tile)` block in `init`: `togglePlay`, the timer, the ping, the timeline and the 404 restart stay as they are.
- `renderer/`, `main.js`, `preload.js`, `webos/`: the desktop and LG TV apps.
- `android/app/build.gradle.kts`: no new dependency.
- The version in `package.json`, the release and any push.

## Size
2 files: `PlayerScreen.kt` (about 15 lines), `MultiView.kt` (about 12 lines). With Decision 2 Default also `Playback.kt` (about 3 lines).

## Work run
model: sonnet
effort: medium
The change is small and the cause is clear; the run only needs care with the lifecycle events of Note 2.
