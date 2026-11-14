# Plan pause-resumes-after-restart: a paused video stays paused through a stream restart

## Summary
A paused video stays paused through a session restart, a seek and a quality or subtitle change; it plays again only on Play.
1 file changes (`renderer/js/player.js`); 2 files with the Android fix (`android/.../Playback.kt`).
3 decisions, none is HOLD.

## Goal
When the user has paused a video, the player keeps it paused through everything that rebuilds the stream: the automatic restart after the server drops the session (the log line `hls session gone`), the fallback from direct play, a quality, audio or subtitle change, and a seek outside the buffer. The spinner may show while the new stream loads; playback starts again only when the user presses Play. A new title, the next episode and a resume from the subtitle style page start on their own as today. The timeline and the ping keep going while paused. The LG TV runs the same `player.js`, so it gets the fix with the shared code; the Android app has the same fault in its own Kotlin player (Decision 2).

Cause, with evidence:
- Log (brief, 2026-11-14 22:41 to 22:44 UTC): six `fragLoadError 404` on one segment, the ping answers 404, `fragLoadError fatal`, then `[renderer] hls session gone, restarting at <seconds>`, a new decision and a new session, every 35 to 40 s. The plan run may not read the log file itself (outside its folders), so this evidence is the brief's.
- `renderer/js/player.js` line 441 to 448 (`onHlsError`): a fatal 404 calls `seekTo(time + 0.01, { restart: true })`.
- Line 585 to 596 (`seekTo`): outside the buffer, or with `restart`, it calls `destroyStream()` and then `startHls(...)`, and when that resolves it calls `this.video.play()` (line 595) with no look at the paused state.
- Line 273 to 313 (`load`): ends with `this.video.play()` (line 310), also without a check. Every quality, audio, subtitle and boost change and every direct-play fallback ends here (lines 406, 470, 516, 828, 851, 875, 882).
- Line 530 to 531 (`destroyStream`): `removeAttribute('src')` and `video.load()`. The HTML media element load algorithm sets `paused` to true when it was false. So after `destroyStream()` the element always reads as paused, and the user's state must be read before that call. This is why the fix cannot read `video.paused` after the restart.
- The user pauses through `togglePlay` (line 598 to 600) or the TV remote (`webos/src/tvnav.js` line 134 to 136); both call `video.pause()`, so `video.paused` is the user's intent as long as no `load()` ran since.
- `android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt` line 276 to 280 (`onError`): a 404 calls `reload(position)`, which calls `load()`, and line 214 sets `player.playWhenReady = true` on every load. Same fault; ExoPlayer keeps `playWhenReady` false after `pause()` and does not change it on an error.

## TL;DR
- When you pause, the video stays paused. It no longer starts on its own after the stream reconnects.
- The spinner can still show while the app reconnects. Press Play when you want to go on.
- Seeks and quality or subtitle changes while paused also keep the pause (Decision 1).
- The Android TV app can get the same fix now (Decision 2). The LG TV gets it with the shared code.

## Acceptance
- `node --check renderer/js/player.js` exits 0 and prints nothing.
- The check script (Decision 3) run before the change prints a table where every row with "user paused: true" shows 1 `play()` call after the action; this is the reproduction, and the report quotes it.
- The same script after the change prints: session-gone restart while paused: 0 `play()` calls, `video.paused` true, a new transcode session; session-gone restart while playing: 1 `play()` call, `video.paused` false, a new session; quality change while paused: 0 calls; seek outside the buffer while paused: 0 calls with Decision 1 Default (1 with the Other option). Last line: `OK: every paused scenario stays paused`.
- `node build.js` in `webos/`: ends without an error and `dist/com.dkrb56.bufferzone_0.2.9_all.ipk` exists afterwards.
- `node scripts/build-all.js`: ends with the file list of `dist/` and no error. With Decision 2 Default the Android step compiles `Playback.kt`. If the Windows or Android step fails on missing tooling, the report says so, and the webOS step stands as the build proof (the brief allows this).
- `git status --short`: shows only the changed files before the commit.
- Manual, for the tester (Windows): play a title that shows the `Converted` pill, press Space to pause, wait for the log line `hls session gone` in `%APPDATA%/Buffer Zone/buffer-zone.log` (on the current server it comes within a minute). The video stays paused, the spinner goes away, the time does not move. Press Space: it plays from the paused position.
- Manual, deterministic (Windows): pause, then press the right arrow 3 times (outside the buffer). With Decision 1 Default the video stays paused at the new spot and the spinner goes away. Pause again, change the quality in the player menu: the stream rebuilds and stays paused. Press Space: it plays.
- Manual, for the tester (LG TV): same two checks with the remote's PAUSE key and the quality menu; PLAY resumes.
- Manual, for the tester (Android TV, Decision 2 Default): play a converted title, wait until it plays, press pause, change the quality: the player stays paused; press play: it plays. Note: pause on Android does nothing while the player buffers (see Do not touch).
- Nobody tried: a real session drop on the LG TV and on Android TV (needs the server to drop the session while the tester watches); a restart while a Multi-view tile is open; a direct-play fallback while paused.

## Files to change
- `renderer/js/player.js`, `seekTo(t, { restart })` (line 585 to 596): read `this.video.paused` into a local before `this.destroyStream()`, and after `startHls` resolves call `video.play()` only when the video was not paused. With Decision 1 Other the pause is kept only when `restart` is true. Reason: this is the session-gone path (line 447) and the user-seek path; `destroyStream()` resets `paused`, so the read must come first.
- `renderer/js/player.js`, `load(startAt)` (line 273 to 313): add an options argument with a `paused` flag; when the caller gives none, read `this.video.paused` on entry, before `destroyStream()` on line 274. At the end call `video.play()` only when the flag is false. Reason: the quality, audio, subtitle and boost changes and the direct-play fallbacks (`fallbackToHls`, `loadSidecarSub`, `setNativeSub`) all end in `load`, and they must keep the user's state. They pass nothing and keep working.
- `renderer/js/player.js`, `play(item, opts)` (line 215, `await this.load(startAt)`): pass `paused: false`. Reason: `stopInternal()` on line 165 already reset the element, and after `ended` the element reads paused; a new title and the next episode must start on their own.
- Decision 2 Default only: `android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt`, `load(startAt)` (line 176 to 218): add a `playWhenReady` parameter and set `player.playWhenReady` to it on line 214. `play(req)` (line 136) passes true. `reload(t)` (line 292 to 295) passes the current `player.playWhenReady`, read before `load`. Reason: `onError` (line 280), `seekTo` (line 309), `fallbackToHls` (line 289) and the track and quality changes all go through `reload`; `pause()` sets `playWhenReady` false and an error leaves it so.

## Files read
- `renderer/js/player.js`: `startTimers` (line 605 to 614) reports `paused` or `playing` every 10 s and pings the current `transcodeSession` every 30 s; `startHls` renews the session id, so the keep-alive goes on after a restart while paused with no change.
- `renderer/js/player.js` line 141 to 143: `waiting` turns the spinner on, `playing` and `canplay` turn it off. `canplay` fires on a paused element too, so the spinner goes away once the new stream has data at the position.
- `node_modules/hls.js/dist/hls.js` (1.7.3), `seekToStartPos` (line 29805 to 29840): after the first buffered fragment it sets `media.currentTime` to the start position with no check of `paused`; hls.js calls `media.play()` only in the interstitials controller (line 33049, 33054), which this app does not use. So hls.js does not restart playback by itself.
- `renderer/js/multiview.js` line 84 and 117: after a redraw it calls `play()` again only on tiles whose video was not paused; tiles have no pause control, so a tile restart keeps playing as today.
- `webos/src/tvnav.js` line 130 to 143: PLAY calls `v.play()`, PAUSE calls `v.pause()`, PLAYPAUSE calls `togglePlay()`; the TV's pause state lives in `video.paused` like on the desktop.
- `webos/build.js` line 112 and 136: copies `renderer/` and lowers each JS file to Chromium 68 with esbuild; an options argument with a default needs nothing extra.
- `renderer/index.html` line 19: hls.js comes from `node_modules/hls.js/dist/hls.min.js`; no new dependency.
- `android/.../Playback.kt`: `togglePlay` (line 300) uses `player.isPlaying`, so pause does nothing while the player buffers; `stopInternal` (line 404 to 411) calls `player.stop()`, which leaves `playWhenReady` as it is; `onIsPlayingChanged` reports `paused` and the timer pings every 30 s (line 377 to 379), both unchanged by the fix.
- `scripts/build-all.js`: runs electron-builder, then the Android gradle build with the Android Studio JDK, then `node build.js` in `webos/`.
- `.claude/memory.md`: Plex drops an idle HLS session after about 210 s; a ping every 30 s keeps it; a paused player must keep sending the timeline and the ping. The fix keeps both.
- `.dispatch/tickets.jsonl`: has no entry for this ticket; the title comes from the brief.

## Steps
1. Write the check script into the job's tmp folder (Decision 3). It defines `window`, `document`, `sofa`, `MediaSource`, `Hls` and `window.Sofa` as stubs and loads `renderer/js/player.js` with `require`. Its fake `<video>` counts `play()` calls and sets `paused` false on `play()`, true on `pause()` and true on `load()`, like the HTML media element load algorithm. The Plex stub answers the decision request with an empty object and records the session id. Scenarios, each on a fresh player after `play(meta)`: session-gone error while paused, the same while playing, `changeQuality` while paused, `seekTo` outside the buffer while paused. It prints the table and the last line from Acceptance. On a Node without the global `crypto`, it sets it from the `crypto` module.
2. Run the script against the unchanged file and keep the output for the report (reproduction).
3. Apply the three changes to `renderer/js/player.js` in the order `seekTo`, `load`, `play`.
4. Run the script again and `node --check renderer/js/player.js`.
5. Decision 2 Default: apply the change to `Playback.kt`.
6. Run `node build.js` in `webos/`; confirm the `.ipk` in `dist/`.
7. Run `node scripts/build-all.js`; when a non-webOS step fails on tooling, note it in the report and keep step 6 as the build proof.
8. Run `git status --short`; confirm only the changed files.
9. Commit on `main` with `pause-resumes-after-restart` in the message.
10. Write the report to `.dispatch/pause-resumes-after-restart.md` with the Verification lines, the manual tests and the follow-ups: the server drops the session every 35 to 40 s (server fault, ticket player-debug-overlay); the restart limit of 2 per minute (line 443 to 444) shows `Stream error` on a third drop; Android `togglePlay` cannot pause while buffering.

## Decisions
1. Does a seek while paused keep the pause, like the automatic restart?
   - Default: yes, every seek and every stream rebuild keeps the pause. Recommended: a seek inside the buffer already stays paused today, and other players behave this way.
   - Other: a seek by the user starts playback as today; only the automatic restart and the quality or subtitle changes keep the pause. Not recommended: a seek inside the buffer and a seek outside it then behave differently.
2. Does the Android TV app get the same fix in this task?
   - Default: yes, `Playback.kt` changes in the same commit. Recommended: the same fault is at line 214 and 280, and every update goes to all platforms.
   - Other: desktop and LG TV only; Android in a follow-up ticket. Not recommended: Android TV users keep the fault until then.
3. Where does the check script live?
   - Default: in the job's tmp folder; the report lists its runs; nothing new in the repo. Recommended: the working rules allow no extra test files.
   - Other: commit it as `scripts/check-player-pause.js` so the tester can run it again. Not recommended: a new file outside the request.

## Do not touch
- The restart logic in `onHlsError` (the 404 detection, the limit of 2 per minute) and the cause of the session drops: server fault, noted in the report.
- `startTimers`, `report` and the ping: the keep-alive while paused stays as it is.
- `renderer/js/multiview.js`, `webos/src/*`, `main.js`, the hls.js configuration, `renderer/index.html`.
- The version in `package.json`: the release commit bumps it.
- Android `togglePlay` (uses `isPlaying`, so pause does nothing while buffering): a nearby fault, goes into Follow-ups of the report.
- No new dependency, no new test file in the repo (unless Decision 3 Other).

## Size
1 file: `renderer/js/player.js` (about 8 changed lines). With Decision 2 Default also `android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt` (about 4 changed lines).

## User decisions

The user took these decisions in Dispatch on 2026-11-15 00:03. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Does a seek while paused keep the pause, like the automatic restart?: yes, every seek and every stream rebuild keeps the pause
2. Does the Android TV app get the same fix in this task?: yes, `Playback.kt` changes in the same commit
3. Where does the check script live?: in the job's tmp folder; the report lists its runs; nothing new in the repo
