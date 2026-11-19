# Plan playback-issue-not-sure-why: "Stream error: fragParsingError" stops a converted stream

## Summary
The player starts a fresh stream at the same spot when a stream piece cannot be read, and shows "Playback failed" only after repeated failures.
1 file changes (`renderer/js/player.js`); the work run first confirms the cause in the app log.
2 decisions, none is HOLD.

## Goal
The screenshot shows the desktop player with "Playback failed / Stream error: fragParsingError" at about 80 % of the seek bar, quality 20 Mbps (a converted stream, hls.js). Today one broken stream piece ends playback: the player tries one media recovery, and the next fatal error shows the error screen. After the fix, a fatal `fragParsingError` starts a new transcode session at the current position, under the same limit as the session-drop restart (2 per minute). With Decision 1 Default, the second restart for the same spot jumps just past the broken piece. The error screen stays for a third failure within a minute, so a real fault still shows. The work run first reads the app log and states the cause with its log lines before it writes code.

## TL;DR
- A video stopped with "Playback failed" because one small piece of the stream could not be read.
- Now the app reloads the stream at the same spot and goes on, instead of stopping.
- If the same spot breaks again, the app skips a few seconds past it (Decision 1).
- The error screen still shows when the stream keeps failing, so a real problem stays visible.

## Acceptance
- Reproduction from the log: the work run prints the `[renderer] hls error` lines with `fragParsingError` from `%APPDATA%/Buffer Zone/buffer-zone.log` (newest first), with 15 lines of context before the first fatal one, and quotes them in the report with `sn`, `reason`, `error` and the `decision` and `hls` lines of that session.
- `node --check renderer/js/player.js` exits 0 and prints nothing.
- The check script (Decision 2) run before the change prints: `fatal fragParsingError #1: recoverMediaError`, `#2: error screen` (the reproduction of the code path).
- The same script after the change prints: `#1: recoverMediaError`, `#2: restart at <t>` with a new transcode session, `#3: restart at <t2>` (with Decision 1 Default `<t2>` is at or after the broken piece's end), `#4: error screen "Stream error: fragParsingError"`. A fatal 404 still restarts, and 400 or 403 still shows the refusal text. Last line: `OK`.
- `git status --short` shows only `renderer/js/player.js` before the commit.
- Manual, for the tester (Windows): play the same title at the same spot (about 80 % in, quality 20 Mbps). Expected: playback goes on, or the spinner shows briefly and playback goes on; the stats box (key D) counts a restart. The log has `hls parse error, restarting at <seconds>`.
- Nobody tried: a real broken piece on the live server (it cannot be made on demand); the LG TV and Android TV (they do not use hls.js, so this error does not occur there).

## Checks
- [ ] android/gradle tests — no Android code changes; the Android app plays with its own player and has no fragParsingError.

## Files to change
- `renderer/js/player.js`, `onHlsError(data)` (line 437 to 464, anchor `if (data.response?.code === 404 && data.type === Hls.ErrorTypes.NETWORK_ERROR)`): add a branch for a fatal `data.details === Hls.ErrorDetails.FRAG_PARSING_ERROR` that reaches this point (the one `recoverMediaError` is already used). It shares the `this.restarts` limit (2 per 60 s) with the 404 branch, calls `this.stats.restart()`, logs `hls parse error, restarting at <seconds>` with `sn` and the fragment's start and end, and calls `this.seekTo(t, { restart: true })`. `t` is `this.time() + 0.01` on the first restart. With Decision 1 Default, when the previous restart was for a parse error at the same `sn` start time, `t` is `data.frag.end + 0.1` (capped by `seekTo` to `duration - 1`). Over the limit, it falls through to the existing `fail(...)`. Reason: hls.js 1.7.3 makes this error fatal only after its own retries on the one level have run out; a new Plex session gives new segments for the same spot.
- Only when the log shows a cause the branch above does not cover (see Notes 6 to 8): no code; the report says what the log shows and asks the user with `state: needs-decision`.

## Files read
- `renderer/js/player.js` line 379: hls.js runs with `startPosition: this.offset`, one quality level per session (`maxVideoBitrate` fixes the level).
- `renderer/js/player.js` line 437 to 464 (`onHlsError`): a fatal media error calls `recoverMediaError()` once per session (`hlsRecovered`, reset in `startHls` line 382); the next fatal error ends in `fail(new Error('Stream error: ' + details))`, the text in the screenshot.
- `renderer/js/player.js` line 449 to 457: a fatal 404 restarts the session with `seekTo(this.time() + 0.01, { restart: true })`, at most 2 times per 60 s (`this.restarts`).
- `renderer/js/player.js` line 600 to 614 (`seekTo`): with `restart` it calls `destroyStream()` and `startHls(t, this.subMode)`; it keeps a paused video paused.
- `renderer/js/player.js` line 588 (`time()`): `video.currentTime`, or the session offset when that is 0.
- `renderer/js/player.js` line 486 to 496 (`fail`): fills the error box with title, message and Back.
- `node_modules/hls.js/dist/hls.js` (1.7.3) line 6649 to 6662: `FRAG_PARSING_ERROR` on a fragment marked as gap does nothing; otherwise retry or switch level, then fatal when retries run out.
- `node_modules/hls.js/dist/hls.js` line 12803 to 12829: an empty segment ("Found no media in msn N") is non-fatal and gapped; a transmuxer error with only one level is not gapped and escalates to fatal.
- `.claude/memory.md`: with `copyts=1` the segments before the offset are 188-byte stubs; starting hls.js before the offset gives `fragParsingError` "Found no media in msn 0".
- `renderer/styles.css` line 489 to 492: `.player-error` covers the player (`inset: 0`) and centres its content.
- `main.js` line 11 to 16: `sofa.log` lines go to `buffer-zone.log` in the app's user folder (`%APPDATA%/Buffer Zone/`).
- `.dispatch/checks.txt`: one check, `android/gradle tests`.
- The app log was not readable in the plan run (outside its folders); earlier work runs read it with Bash.

## Notes for the work run
1. Log path: `/c/Users/dani/AppData/Roaming/Buffer Zone/buffer-zone.log`; earlier work runs read it with `grep -n` and `sed -n` in Bash.
2. The ticket was written 2026-11-19 23:20 UTC; look for `fragParsingError` near that time first, then any other day.
3. The `hls error` log line (player.js line 438) has `details`, `fatal`, `code`, `sn`, `reason` and `error`; the stats line adds `segment #<sn>`.
4. Expected evidence for the planned fix: non-fatal `fragParsingError` lines on one `sn`, then a fatal one, then (after `recoverMediaError`) a second fatal one, then `player error Stream error: fragParsingError`.
5. Also note the `decision` line of that session (direct stream or transcode, codecs); it tells whether Plex copies or converts the video at that spot.
6. If `reason` says "Found no media" and `sn` is before the session offset, the cause is hls.js loading the stubs before the offset. The planned fix does not cover it: report and ask.
7. If the fatal error is not `fragParsingError` or is a network error, report and ask.
8. If the log has no matching lines (log cleared or other machine), write the code-path reproduction only, say so in the report, and go on with the fix.
9. `Hls.ErrorDetails.FRAG_PARSING_ERROR` is the string `fragParsingError`; `data.frag` has `sn`, `start` and `end` in absolute media time (copyts=1).
10. Keep the restart limit shared with the 404 branch so two kinds of failure cannot loop for more than 2 restarts per minute.
11. `seekTo` caps `t` at `duration - 1`; a broken piece in the last second ends at the error screen, which is fine.
12. Check script: the stubs of the earlier task (`pause-resumes-after-restart`) worked: stub `window`, `document`, `sofa`, `MediaSource`, `Hls` (with `ErrorTypes`, `ErrorDetails`, `Events`) and load `renderer/js/player.js` with `require`.
13. The screenshot shows the error box small at the top left, with a faint spinner behind the text. The CSS centres it on the full player; this is a separate display issue. Do not fix it; name it in Follow-ups.
14. The LG TV build uses the TV's own HLS player (`nativeHls`), which never reports `fragParsingError`; Android uses ExoPlayer. So only the desktop path changes, and the shared file goes to the TV unchanged in behaviour.

## Steps
1. Read the app log (Notes 1 to 5). Quote the lines and state the cause with them. Stop with a question when Note 6 or 7 applies.
2. Write the check script into `$CLAUDE_JOB_DIR/tmp` (Decision 2). It feeds `onHlsError` fatal `fragParsingError` events for one `sn` four times, plus one fatal 404 and one 403, and prints the results from Acceptance.
3. Run it against the unchanged file; keep the output as the code-path reproduction.
4. Add the branch to `onHlsError` as in Files to change.
5. Run the check script again and `node --check renderer/js/player.js`.
6. Run `git status --short`; confirm only `renderer/js/player.js` changed.
7. Commit `renderer/js/player.js` by name on `main` with `playback-issue-not-sure-why` in the message.
8. Write the report `.dispatch/playback-issue-not-sure-why.md` with the log evidence, both script runs, the manual test and Follow-ups (Note 13; the version bump and release per the project rule).

## Decisions
1. If the same spot breaks again after a reload, should the app skip past it?
   - Default: yes, on the second try it jumps a few seconds past the broken piece. Recommended: a damaged spot in the file then costs a few seconds, not the whole film.
   - Other: no, it reloads at the same spot again and then shows the error. Not recommended: a damaged spot in the file stops the film every time.
2. Where does the check script live?
   - Default: in the job's temporary folder; the report lists its runs; nothing new in the project. Recommended: the project rules allow no extra test files.
   - Other: save it in the project so a tester can run it again. Not recommended: a new file the ticket did not ask for.

## Do not touch
- The 404 restart branch and the 400 or 403 refusal text in `onHlsError`, apart from sharing `this.restarts`.
- The hls.js configuration (line 379), `startHls`, `seekTo`, `fail`.
- `renderer/styles.css` and the error box layout (Note 13): a follow-up.
- `android/`, `webos/`, `main.js`, `node_modules/`.
- The version in `package.json`: the release commit bumps it.
- No new dependency; no new test file in the repo (unless Decision 2 Other).

## Size
1 file: `renderer/js/player.js` (about 15 changed lines in `onHlsError`).

## Work run
model: opus
effort: medium
The code change is small, but the work run must read the log and judge whether it matches the planned cause before it writes code.

## User decisions

The user took these decisions in Dispatch on 2026-11-20 00:23. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. If the same spot breaks again after a reload, should the app skip past it?: yes, on the second try it jumps a few seconds past the broken piece
2. Where does the check script live?: in the job's temporary folder; the report lists its runs; nothing new in the project

## Checks chosen

The user chose these checks in Dispatch on 2026-11-19 23:23 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.

- [ ] android/gradle tests
