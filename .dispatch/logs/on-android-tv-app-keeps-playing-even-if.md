# On android tv app keeps playing even if its minimized (on-android-tv-app-keeps-playing-even-if) — 2026-11-24

## Plan approved
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

## User decisions

The user took these decisions in Dispatch on 2026-11-24 17:42. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. When you come back to the app, what does the paused show do?: It waits, paused, with the controls open; you press Play
2. Should the show also pause when another app starts to play sound?: Yes; a short sound only makes it quieter, then it plays on

## Checks chosen

The user chose these checks in Dispatch on 2026-11-24 16:42 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.

- [ ] android/gradle tests

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-24T16:36:34.361Z
COMMAND: cat .dispatch/checks.txt; ls; ls .dispatch; cat .dispatch/*.models 2>/dev/null | head; ls .claude 2>/dev/null
EXIT: 0
OUTPUT:
    when-i-add-show-to-wathclist-there.md
    when-i-add-show-to-wathclist-there.models
    when-i-add-show-to-wathclist-there.session
    when-i-add-show-to-wathclist-there.state
    work-flags.txt
    worker-settings.json
    worker.ps1
    plan opus high
    work sonnet high
    plan opus high
    plan opus high
    work opus medium
    plan opus high
    work sonnet medium
    memory.md
    mistakes.md
    settings.json
    settings.local.json
    state
    triage.md

### EVIDENCE: PLAN 2026-11-24T16:36:37.876Z
COMMAND: cat .claude/memory.md .claude/mistakes.md; ls .claude/state; cd android && git ls-files | grep -v -E '\.(png|webp|jar)$' | head -100
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
The Android TV player and the Multi-view tiles pause when the app goes to the background (Home key, another app), so no sound plays behind other apps.
Files: .dispatch/plans/on-android-tv-app-keeps-playing-even-if.md
Blockers: When you come back to the app, what does the paused show do?; Should the show also pause when another app starts to play sound?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-24T16:42:34.263Z
COMMAND: cd /d/dev_projects/buffer-zone; adb devices 2>&1 | head; grep -n "DisposableEffect\|^import" android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt | head -80
EXIT: 0
OUTPUT:
    62:import androidx.compose.ui.graphics.Color
    63:import androidx.compose.ui.graphics.vector.ImageVector
    64:import androidx.compose.ui.input.key.KeyEventType
    65:import androidx.compose.ui.input.key.nativeKeyCode
    66:import androidx.compose.ui.input.key.onKeyEvent
    67:import androidx.compose.ui.input.key.onPreviewKeyEvent
    68:import androidx.compose.ui.input.key.key
    69:import androidx.compose.ui.input.key.type
    70:import androidx.compose.ui.platform.LocalContext
    71:import androidx.compose.ui.text.font.FontWeight
    72:import androidx.compose.ui.unit.dp
    73:import androidx.compose.ui.unit.sp
    74:import androidx.compose.ui.viewinterop.AndroidView
    75:import androidx.media3.common.util.UnstableApi
    76:import androidx.media3.ui.AspectRatioFrameLayout
    77:import androidx.media3.ui.CaptionStyleCompat
    78:import androidx.media3.ui.PlayerView
    79:import androidx.media3.ui.SubtitleView
    80:import kotlinx.coroutines.delay
    106:    DisposableEffect(Unit) { onDispose { pb.release() } }

### EVIDENCE: WORK 2026-11-24T16:42:34.866Z
COMMAND: cd /d/dev_projects/buffer-zone/android/app/src/main/java/com/dkrb56/bufferzone; grep -n "DisposableEffect\|^import" MultiView.kt; grep -n "if (!tile)\|^import\|AudioAttr" Playback.kt
EXIT: 0
OUTPUT:
    14:import androidx.media3.common.Player
    15:import androidx.media3.common.TrackSelectionOverride
    16:import androidx.media3.common.Tracks
    17:import androidx.media3.common.util.UnstableApi
    18:import androidx.media3.datasource.DefaultHttpDataSource
    19:import androidx.media3.exoplayer.DefaultRenderersFactory
    20:import androidx.media3.exoplayer.ExoPlayer
    21:import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
    22:import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
    23:import kotlinx.coroutines.CoroutineScope
    24:import kotlinx.coroutines.Dispatchers
    25:import kotlinx.coroutines.Job
    26:import kotlinx.coroutines.SupervisorJob
    27:import kotlinx.coroutines.cancel
    28:import kotlinx.coroutines.delay
    29:import kotlinx.coroutines.isActive
    30:import kotlinx.coroutines.launch
    31:import java.util.UUID
    106:        if (!tile) {
    287:        if (!tile) App.show("This file cannot play directly. Switching to a converted stream.")

### EVIDENCE: WORK 2026-11-24T16:42:55.154Z
COMMAND: cd /d/dev_projects/buffer-zone/android && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug 2>&1 | tail -25; cd ..; grep -c "ON_STOP" android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt; grep -c "handleAudioFocus\|setAudioAttributes" android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt; git diff --stat HEAD -- renderer webos main.js preload.js package.json android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt
EXIT: 0
OUTPUT:
    exception: fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) opt(k).let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() } else null
    exception:                                                                                                                ^^
    
    > Task :app:compileDebugJavaWithJavac NO-SOURCE
    > Task :app:dexBuilderDebug
    > Task :app:processDebugJavaRes UP-TO-DATE
    > Task :app:mergeDebugJavaResource UP-TO-DATE
    > Task :app:mergeDebugGlobalSynthetics
    > Task :app:mergeProjectDexDebug
    > Task :app:packageDebug
    > Task :app:createDebugApkListingFileRedirect UP-TO-DATE
    > Task :app:assembleDebug
    w: Detected multiple Kotlin daemon sessions at 
    
    BUILD SUCCESSFUL in 1m 51s
    37 actionable tasks: 5 executed, 32 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
    android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt:1
    android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt:1
    1

### EVIDENCE: WORK 2026-11-24T16:44:50.054Z
COMMAND: cd /d/dev_projects/buffer-zone; P=android/app/src/main/java/com/dkrb56/bufferzone; git add $P/PlayerScreen.kt $P/MultiView.kt $P/Playback.kt && git commit -m "on-android-tv-app-keeps-playing-even-if: pause playback when the app leaves the screen" -- $P/PlayerScreen.kt $P/MultiView.kt $P/Playback.kt | tail -3; git status --short | head -20
EXIT: 0
OUTPUT:
     M .dispatch/briefs/on-android-tv-app-keeps-playing-even-if.md
     M .dispatch/briefs/search-results-dissapear-after-few.md
     M .dispatch/logs/on-android-tv-app-keeps-playing-even-if.md
     M .dispatch/logs/search-results-dissapear-after-few.md
     M .dispatch/on-android-tv-app-keeps-playing-even-if.models
     M .dispatch/on-android-tv-app-keeps-playing-even-if.session
     M .dispatch/on-android-tv-app-keeps-playing-even-if.state
     M .dispatch/plans/on-android-tv-app-keeps-playing-even-if.md
     M .dispatch/plans/search-results-dissapear-after-few.md
     M .dispatch/search-results-dissapear-after-few.models
     M .dispatch/search-results-dissapear-after-few.session
     M .dispatch/search-results-dissapear-after-few.state
     M .dispatch/tickets.jsonl
     M android/app/build.gradle.kts
     M android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt
    ?? .dispatch/briefs/i-keep-seeing-loading-spinner-on-android.md
    ?? .dispatch/i-keep-seeing-loading-spinner-on-android.models
    ?? .dispatch/i-keep-seeing-loading-spinner-on-android.session
    ?? .dispatch/i-keep-seeing-loading-spinner-on-android.state
    ?? .dispatch/logs/i-keep-seeing-loading-spinner-on-android.md

### 2. WORK — ok
`PlayerScreen.kt`: a lifecycle observer pauses the player on `ON_STOP`. On the next `ON_START` it opens the controls (Decision 1 Default).
Files: android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt, android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt, android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
Blockers: none

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

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-opus-5-5 | 34 | 75136 | 830397 | 12884 | 918451 |
| claude-sonnet-5-5 | 16 | 51109 | 179033 | 4925 | 235083 |
