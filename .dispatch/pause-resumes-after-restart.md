state: done
needs: nothing
files: renderer/js/player.js, android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
plan: .dispatch/plans/pause-resumes-after-restart.md

## What changed
A paused video now stays paused when the app rebuilds the stream. Playback starts again only when the user presses Play.

- Desktop and LG TV (shared `renderer/js/player.js`): the automatic restart after a lost session keeps the pause. A seek outside the buffer, a quality, audio or subtitle change and a direct-play fallback also keep it.
- A video that plays before the restart plays again after it, as before.
- A new title, the next episode and the return from the subtitle style page start on their own, as before.
- Android TV (`Playback.kt`): the same rule. The 404 restart, seeks, track changes and quality changes keep a pause.
- The timeline report and the session ping continue while paused. They go to the new session after a restart.
- One local commit on `main`: b7bef44. Nothing was pushed.

## Why
Cause, with evidence:
- The app log shows the loop. From 22:39:42 to 22:46:50 UTC on 2026-11-14 the log has 12 lines `hls session gone, restarting at …`, about every 35 to 40 s. Before each one: six `fragLoadError` 404 on one segment, the ping answers 404, then a fatal 404.
- The restart path `onHlsError` calls `seekTo(…, { restart: true })`. `seekTo` called `video.play()` when the new stream was ready and did not look at the pause.
- `load()` also ended with `video.play()` with no check. All quality, audio, subtitle and fallback reloads go through `load()`.
- `destroyStream()` calls `video.load()`. The media load algorithm sets `paused` to true. So the fix reads the pause before `destroyStream()`.
- Android: `load()` set `playWhenReady = true` on every reload, also after the 404 restart.

The check script reproduced the fault before the change: each paused scenario got one `play()` call and ended in play.

## Files
- `renderer/js/player.js`: `seekTo` reads `video.paused` before `destroyStream()` and calls `play()` only when the video played. `load(startAt, { paused })` takes the state before the reload by default and calls `play()` only when not paused. `play(item)` passes `paused: false`.
- `android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt`: `load(startAt, playWhenReady)`. `play(req)` passes true. `reload(t)` passes the current `player.playWhenReady`, read before the old session stops.

## Verification
- `LOG="/c/Users/dani/AppData/Roaming/Buffer Zone/buffer-zone.log"; ls -la "$LOG"; echo "== restarts (last 12)"; grep -n "hls session gone" "$LOG" | tail -12 | cut -c1-200; echo "== context of the last restart"; n=$(grep -n "hls session gone" "$LOG" | tail -1 | cut -d: -f1); [ -n "$n" ] && sed -n "$((n-12)),$((n+4))p" "$LOG" | cut -c1-240` showed 12 restarts from 22:39:42 to 22:46:50 UTC, each after a fatal `fragLoadError` 404, with a ping 404 before it. The restart position moves only 1 to 20 s between restarts.
- `node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"` on the unchanged file (reproduction): every paused row showed 1 `play()` call, `paused at end` false and timeline state "playing". The script printed `RESUMED WHILE PAUSED: session gone (404) restart; quality change; seek outside the buffer` and `exit=1`. The ping went to the new session in every row.
- `node --check renderer/js/player.js; echo "check exit=$?"; node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"; git diff --stat` after the change: `check exit=0`. Session-gone restart while paused: 0 `play()` calls, paused, new session, timeline "paused", ping to the new session. Session-gone restart while playing: 1 call, playing, new session. Quality change while paused: 0 calls. Seek outside the buffer while paused: 0 calls. Last line `OK: every paused scenario stays paused`, `exit=0`.
- `node build.js 2>&1 | tail -15; echo "exit=${PIPESTATUS[0]}"; ls -la ../dist/com.dkrb56.bufferzone_0.2.9_all.ipk` in `webos/`: `Success`, `exit=0`, the ipk exists (271746 bytes).
- `grep -c "if (!paused)" webos/build/app/js/player.js && grep -n "paused: false\|load(startAt, {" webos/build/app/js/player.js | head -5` showed 2 checks and both changed lines in the TV build.
- `node scripts/build-all.js > /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log 2>&1; echo "exit=$?"; grep -n "^== \|BUILD\|error\|Error\|FAILURE\|MB$\|missing" /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | grep -v "^.*warning" | tail -30` gave `exit=0`, `BUILD SUCCESSFUL` for Android, and all six files in `dist/` (setup exe, blockmap, latest.yml, portable exe, apk, ipk).
- `grep -n "compileDebugKotlin\|w: \|e: " /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | head -10; sed -n 60,70p /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log` showed that the task `:app:compileDebugKotlin` ran (not up to date) with no warning and no error.
- `git status --short && git diff -- renderer/js/player.js android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt` showed only the two code files changed, plus the two Dispatch files of this task.

Deviations from the plan:
- The commit player-debug-overlay (23efce8) came after the plan. It moved the line numbers by 3 to 13 and added a stats object. The three changed spots had the same logic, so the change follows the plan. The check script stubs the stats object.
- The check script also fires the 10 s timer by hand. It checks the timeline state and the ping to the new session. This proves the keep-alive constraint of the brief. The script stays in the job folder; nothing new is in the repo.

Manual tests for the tester:
1. Windows: play a title with the `Converted` pill. Press Space to pause. Wait for `hls session gone` in `%APPDATA%/Buffer Zone/buffer-zone.log`; on this server it comes within a minute. Expected: the video stays paused, the spinner goes away, the time does not move. Press Space: it plays from the paused spot.
2. Windows: pause, then press the right arrow 3 times. Expected: the video stays paused at the new spot and the spinner goes away. Pause, change the quality in the player menu. Expected: the stream reloads and stays paused. Press Space: it plays.
3. Windows: let the video play and wait for a restart. Expected: it plays again after the spinner, as before.
4. LG TV: tests 1 to 3 with the PAUSE and PLAY keys of the remote.
5. Android TV: play a converted title, wait until it plays, pause, change the quality. Expected: it stays paused. Press play: it plays.

What nobody tried:
- Nobody watched a real stream with the changed app. The check ran on stubs, not in a browser and not on a real server.
- On the LG TV nobody checked that a playing video plays again after a restart. The TV's own player could report "paused" after an error; the web standard says it does not. If it does, a playing video stays paused after a restart on the TV. Test 4 covers this.
- Nobody tried Android TV on a device, a restart while Multi-view is open, or a direct-play fallback while paused.
- Nobody tried Play during the short reload gap (see Follow-ups).

## Follow-ups
- The server drops the session every 35 to 40 s. This is the "keeps loading" part of the user's report, and this ticket does not fix it. The new playback stats box (key D) shows it.
- The restart limit is 2 per minute. A third session loss in 60 s shows "Stream error". Today the drops are just outside that limit.
- Android TV: `togglePlay` uses `isPlaying`, so pause does nothing while the player buffers. A user cannot pause during the spinner. Not changed.
- A Play press in the short gap of a reload is lost: the new source resets it. The gap is the decision request, about 0.3 s in the log. The user then presses Play again. Before this change every reload played, so the gap did not matter.
- Release: `package.json` still says 0.2.9. The full build wrote files named 0.2.9 into `dist/` with this change. Bump the version before you publish; do not upload these files as 0.2.9.
