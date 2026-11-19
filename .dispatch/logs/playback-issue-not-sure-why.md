# Playback issue - not sure why (playback-issue-not-sure-why) — 2026-11-19

## Plan approved
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

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-19T23:20:24.063Z
COMMAND: git ls-files | head -200 && cat .dispatch/checks.txt 2>/dev/null; ls .dispatch
EXIT: 0
OUTPUT:
    logs
    options.json
    pause-resumes-after-restart.md
    pause-resumes-after-restart.session
    pause-resumes-after-restart.state
    plan-flags.txt
    plans
    playback-issue-not-sure-why.models
    playback-issue-not-sure-why.session
    playback-issue-not-sure-why.state
    player-debug-overlay.md
    player-debug-overlay.session
    player-debug-overlay.state
    search-recent.md
    search-recent.session
    search-recent.state
    search-recent.taken
    tickets.jsonl
    work-flags.txt
    worker.ps1

### EVIDENCE: PLAN 2026-11-19T23:20:42.732Z
COMMAND: grep -n "\"version\"" node_modules/hls.js/package.json; grep -n "hls" package.json; grep -n "log" main.js | head -40; grep -n "log" preload.js | head
EXIT: 0
OUTPUT:
    151:  "version": "1.7.3"
    71:    "hls.js": "^1.7.3"
    11:const logFile = path.join(userDir, 'buffer-zone.log');
    13:function log(...args) {
    15:  try { fs.appendFileSync(logFile, line); } catch {}
    85:ipcMain.handle('log', (_e, ...args) => log('[renderer]', ...args));
    96:  updater.logger = { info: (m) => log('updater', m), warn: (m) => log('updater', m), error: (m) => log('updater', m), debug: () => {} };
    97:  updater.on('error', (err) => log('updater error', String(err)));
    125:    if (!res.ok) log('http', method, redact(url), res.status);
    128:    log('http error', method, redact(url), String(err));
    158:    if (e.level === 'error' || e.level === 'warning') log('[console]', e.level, e.message);
    160:  win.webContents.on('render-process-gone', (_e, d) => log('renderer gone', d));
    179:  log('start', app.getVersion(), process.versions.electron);
    16:  log: (...args) => ipcRenderer.invoke('log', ...args)

### EVIDENCE: PLAN 2026-11-19T23:20:45.089Z
COMMAND: sed -n 1,20p main.js; ls "$APPDATA" | grep -i -E "buffer|sofa"; 
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
The player starts a fresh stream at the same spot when a stream piece cannot be read, and shows "Playback failed" only after repeated failures.
Files: .dispatch/plans/playback-issue-not-sure-why.md
Blockers: If the same spot breaks again after a reload, should the app skip past it?; Where does the check script live?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-19T23:23:31.774Z
COMMAND: cat .dispatch/briefs/playback-issue-not-sure-why.md && echo ---- && cat .dispatch/plans/playback-issue-not-sure-why.md
EXIT: 0
OUTPUT:
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

### EVIDENCE: WORK 2026-11-19T23:23:36.456Z
COMMAND: cd "/c/Users/dani/AppData/Roaming/Buffer Zone/" && ls -la && grep -n "fragParsingError" buffer-zone.log | tail -40
EXIT: 0
OUTPUT:
    11440:[2026-11-19T23:16:31.937Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11442:[2026-11-19T23:16:32.243Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11443:[2026-11-19T23:16:32.406Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11444:[2026-11-19T23:16:32.578Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11445:[2026-11-19T23:16:32.741Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11446:[2026-11-19T23:16:32.902Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11448:[2026-11-19T23:16:33.194Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11449:[2026-11-19T23:16:33.371Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11450:[2026-11-19T23:16:33.535Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11451:[2026-11-19T23:16:33.704Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11452:[2026-11-19T23:16:33.879Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11454:[2026-11-19T23:16:34.190Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":true,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.a…
    11455:[2026-11-19T23:16:45.951Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2580,"reason":"Found no media in msn 2580 of main playlist \"http://plex.v12.…
    11456:[2026-11-19T23:16:46.128Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11457:[2026-11-19T23:16:46.288Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11458:[2026-11-19T23:16:46.458Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11459:[2026-11-19T23:16:46.642Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.…
    11460:[2026-11-19T23:16:46.811Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":true,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.a…
    11461:[2026-11-19T23:16:46.812Z] [renderer] player error Stream error: fragParsingError
    Shell cwd was reset to D:\dev_projects\buffer-zone

### EVIDENCE: WORK 2026-11-19T23:23:39.967Z
COMMAND: cd "/c/Users/dani/AppData/Roaming/Buffer Zone/" && sed -n '11400,11470p' buffer-zone.log | cut -c1-400
EXIT: 0
OUTPUT:
    [2026-11-19T23:16:33.194Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:33.371Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:33.535Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:33.704Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:33.879Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:34.018Z] [renderer] hls error {"type":"mediaError","details":"bufferAppendNoProgress","fatal":false,"sn":2579,"error":"Fragment append did not increase buffered coverage (3)"}
    [2026-11-19T23:16:34.190Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":true,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appboxm…
    [2026-11-19T23:16:45.951Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2580,"reason":"Found no media in msn 2580 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:46.128Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:46.288Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:46.458Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:46.642Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":false,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appbox…
    [2026-11-19T23:16:46.811Z] [renderer] hls error {"type":"mediaError","details":"fragParsingError","fatal":true,"sn":2581,"reason":"Found no media in msn 2581 of main playlist \"http://plex.v12.appboxm…
    [2026-11-19T23:16:46.812Z] [renderer] player error Stream error: fragParsingError
    [2026-11-19T23:17:34.902Z] [renderer] hls error {"type":"mediaError","details":"bufferStalledError","fatal":false,"error":"Playback stalling at @2582.510769 due to low buffer ({\"len\":0,\"start\":258…
    [2026-11-19T23:22:24.568Z] [renderer] decision {"general":"Direct play not available; Conversion OK.","transcode":"Direct play not available; Conversion OK.","streams":[{"t":1,"codec":"h264","decision…
    [2026-11-19T23:22:24.569Z] [renderer] hls {"reason":"quality setting","quality":"20000","subMode":"none"}
    [2026-11-19T23:22:24.670Z] [renderer] external subs {"codec":"srt","cues":328}
    [2026-11-19T23:22:35.630Z] [renderer] hls error {"type":"networkError","details":"fragLoadTimeOut","fatal":false,"sn":2582,"error":"Timeout after 10000ms"}
    Shell cwd was reset to D:\dev_projects\buffer-zone

### EVIDENCE: WORK 2026-11-19T23:23:50.745Z
COMMAND: sed -n 360,500p renderer/js/player.js && echo ---- && sed -n 580,620p renderer/js/player.js && grep -n "restarts\|stats\b\|this.stats =\|module.exports\|^class\|hlsRecovered" renderer/js/player.js | head -40
EXIT: 0
OUTPUT:
    382:      this.hlsRecovered = false;
    440:      this.stats.error([data.details, code && `HTTP ${code}`, data.frag?.sn != null && `segment #${data.frag.sn}`, data.fatal && 'fatal'].filter(Boolean).join(' · '), code);
    442:      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !this.hlsRecovered) {
    443:        this.hlsRecovered = true;
    451:        this.restarts = (this.restarts || []).filter(t => now - t < 60000);
    452:        if (this.restarts.length < 2) {
    453:          this.restarts.push(now);
    454:          this.stats.restart();
    471:      // The TV player gives no HTTP code; the 404 below only selects the restart, so the stats keep the video error.
    472:      if (this.nativeHls) { this.onHlsError({ type: 'networkError', details: `video error ${err.code}`, fatal: true, response: { code: 404 } }); return this.stats.error(text); }
    473:      this.stats.error(text);
    479:      this.stats.error(`fallback to converted: ${reason}`);
    488:      this.stats.error(`playback failed: ${err?.message || err}`);
    575:      this.stats.off();
    611:      if (!restart) this.stats.expectWait();
    630:            .then(() => this.stats.ping(200), (e) => this.stats.ping(e?.status || 0));
    803:          item('t-item', this.stats.isOn, 'Playback stats', 'Buffer, segments, errors (D)', () => this.toggleStats()));
    853:    // Playback stats box (settings.debugOverlay): key D or Quality → Playback stats. The choice stays after a restart.
    855:      const on = !this.stats.isOn;
    857:      if (on) this.stats.on(); else this.stats.off();

### EVIDENCE: WORK 2026-11-19T23:23:56.403Z
COMMAND: sed -n 1,30p renderer/js/player.js && tail -20 renderer/js/player.js && sed -n 125,185p renderer/js/player.js
EXIT: 0
OUTPUT:
          this.stopInternal();
          this.preset = { audioId, subId };
          this.qualityOverride = quality || null;
          if (!this.tile) {
            this.root.classList.add('on');
            document.body.classList.add('playing');
          }
          this.errorBox.classList.remove('on');
          this.spinner.classList.add('on');
          this.nextCard.classList.remove('on');
          this.skipBtn.classList.remove('on');
          this.overlay.applyStyle(this.settings);
          this.overlay.clear();
          this.overlay.start();
          this.poke();
          this.stats.reset();
          if (this.settings.debugOverlay) this.stats.on(); else this.stats.off();
    
          try {
            const meta = await this.plex.metadata(item.ratingKey);

### EVIDENCE: WORK 2026-11-19T23:24:09.053Z
COMMAND: mkdir -p "$CLAUDE_JOB_DIR/tmp" 2>/dev/null; J="/c/Users/dani/.claude/jobs/8eb91856/tmp"; cat > "$J/check-parse.js" <<'EOF'
    // Feeds Player.prototype.onHlsError fatal events and prints what the player does.
    const path = require('path');
    const logs = [];
    global.window = { Sofa: { h() {}, fmtTime() {}, fmtDuration() {}, epCode() {}, toast() {}, pill() {}, Subtitles: {}, icon() {}, PlayerStats: function () {} } };
    global.sofa = { log: (...a) => logs.push(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')) };
    global.document = {}; global.screen = {};
    global.Hls = { ErrorTypes: { MEDIA_ERROR: 'mediaError', NETWORK_ERROR: 'networkError' }, ErrorDetails: { FRAG_PARSING_ERROR: 'fragParsingError' }, Events: {} };
    require(path.resolve(process.argv[2] || 'renderer/js/player.js'));
    const Player = window.Sofa.Player;
    
    function make() {
      const p = Object.create(Player.prototype);
      p.duration = 3200; p.cur = 2580.62; p.session = 1; p.out = null;
      p.stats = { error() {}, restart() { p.restartCount = (p.restartCount || 0) + 1; } };
      p.hls = { recoverMediaError() { p.out = 'recoverMediaError'; } };
      p.time = () => p.cur;
      p.seekTo = (t, o) => { t = Math.max(0, Math.min(p.duration - 1, t)); p.session++; p.hlsRecovered = false; p.cur = t; p.out = `restart at ${t.toFixed(2)}${o && o.restart ? ' (new session ' + p.session + ')' : ''}`; };
      p.fail = (e) => { p.out = `error screen "${e.message}"`; };
      return p;
    }
    const frag = { sn: 2581, start: 2580.62, end: 2581.62 };
    const parse = { type: 'mediaError', details: 'fragParsingError', fatal: true, frag, reason: 'Found no media in msn 2581' };
    let ok = true;
    const p = make();
    const seen = [];
    for (let i = 1; i <= 4; i++) {
      p.out = null; p.onHlsError(parse);
      seen.push(p.out);
      console.log(`fatal fragParsingError #${i}: ${p.out}`);
      if (p.out && p.out.startsWith('error screen')) break;
    }
    // Expected after the change
    const exp = ['recoverMediaError', /^restart at 2580\.63 \(new session/, null, /^error screen "Stream error: fragParsingError"$/];
    const t2 = seen[2] && /^restart at ([\d.]+)/.exec(seen[2]);
    if (seen[0] !== 'recoverMediaError') ok = false;
    if (!exp[1].test(seen[1] || '')) ok = false;
    if (!t2 || +t2[1] < frag.end) ok = false;
    if (!exp[3].test(seen[3] || '')) ok = false;
    
    const q = make();
    q.out = null; q.onHlsError({ type: 'networkError', details: 'fragLoadError', fatal: true, response: { code: 404 }, frag });
    console.log(`fatal 404: ${q.out}`); if (!/^restart at/.test(q.out || '')) ok = false;
    const r = make();
    r.out = null; r.onHlsError({ type: 'networkError', details: 'manifestLoadError', fatal: true, response: { code: 403 } });
    console.log(`fatal 403: ${r.out}`); if (!/refused the stream/.test(r.out || '')) ok = false;
    console.log('log:', logs.filter(l => /restarting|session gone/.test(l)).join(' | '));
    console.log(ok ? 'OK' : 'FAIL');
    process.exit(ok ? 0 : 1);
    EOF
    echo written
EXIT: 0
OUTPUT:
    written

### EVIDENCE: WORK 2026-11-19T23:24:13.190Z
COMMAND: node /c/Users/dani/.claude/jobs/8eb91856/tmp/check-parse.js renderer/js/player.js; echo "exit $?"
EXIT: 0
OUTPUT:
    D:\dev_projects\buffer-zone\renderer\js\player.js:37
      const probe = document.createElement('video');
                             ^
    
    TypeError: document.createElement is not a function
        at D:\dev_projects\buffer-zone\renderer\js\player.js:37:26
        at Object.<anonymous> (D:\dev_projects\buffer-zone\renderer\js\player.js:984:3)
        at Module._compile (node:internal/modules/cjs/loader:1761:14)
        at Object..js (node:internal/modules/cjs/loader:1893:10)
        at Module.load (node:internal/modules/cjs/loader:1481:32)
        at Module._load (node:internal/modules/cjs/loader:1300:12)
        at TracingChannel.traceSync (node:diagnostics_channel:328:14)
        at wrapModuleLoad (node:internal/modules/cjs/loader:245:24)
        at Module.require (node:internal/modules/cjs/loader:1504:12)
        at require (node:internal/modules/helpers:152:16)
    
    Node.js v24.12.0
    exit 1

### EVIDENCE: WORK 2026-11-19T23:24:16.578Z
COMMAND: J=/c/Users/dani/.claude/jobs/8eb91856/tmp && sed -i "s|global.document = {};|global.document = { createElement: () => ({ canPlayType: () => '' }) };|" $J/check-parse.js && node $J/check-parse.js renderer/js/player.js; echo "exit $?"
EXIT: 0
OUTPUT:
    fatal fragParsingError #1: recoverMediaError
    fatal fragParsingError #2: error screen "Stream error: fragParsingError"
    fatal 404: restart at 2580.63 (new session 2)
    fatal 403: error screen "The server refused the stream. Your friend may not allow conversion (transcoding) for shared users. Try "Direct Play" in Quality."
    log: hls session gone, restarting at 2581
    FAIL
    exit 1

### EVIDENCE: WORK 2026-11-19T23:24:34.611Z
COMMAND: J=/c/Users/dani/.claude/jobs/8eb91856/tmp/check-parse.js && python - "$J" <<'EOF'
    import sys
    p=sys.argv[1]; s=open(p).read()
    old_loop=s[s.index('for (let i = 1; i <= 4'):s.index("const q = make();")]
    new_loop='''for (let i = 1; i <= 8; i++) {
      p.out = null; p.onHlsError(parse);
      seen.push(p.out);
      console.log(`fatal fragParsingError #${i}: ${p.out}`);
      if (p.out && p.out.startsWith('error screen')) break;
    }
    // startHls resets the one media recovery per session, so each new session tries recoverMediaError first.
    const exp = ['recoverMediaError', /^restart at 2580\\.63 \\(new session/, 'recoverMediaError', /^restart at ([\\d.]+) \\(new session/, 'recoverMediaError', /^error screen "Stream error: fragParsingError"$/];
    if (seen.length !== exp.length) ok = false;
    exp.forEach((e, i) => { if (typeof e === 'string' ? seen[i] !== e : !e.test(seen[i] || '')) ok = false; });
    const t2 = /^restart at ([\\d.]+)/.exec(seen[3] || '');
    if (!t2 || +t2[1] < frag.end) ok = false;
    
    '''
    s=s.replace(old_loop,new_loop); open(p,'w').write(s)
    EOF
    grep -n "exp\|for (let" "$J"
EXIT: 0
OUTPUT:
    26:for (let i = 1; i <= 8; i++) {
    33:const exp = ['recoverMediaError', /^restart at 2580\.63 \(new session/, 'recoverMediaError', /^restart at ([\d.]+) \(new session/, 'recoverMediaError', /^error screen "Stream error: fragParsingErro…
    34:if (seen.length !== exp.length) ok = false;
    35:exp.forEach((e, i) => { if (typeof e === 'string' ? seen[i] !== e : !e.test(seen[i] || '')) ok = false; });

### EVIDENCE: WORK 2026-11-19T23:24:40.260Z
COMMAND: node /c/Users/dani/.claude/jobs/8eb91856/tmp/check-parse.js renderer/js/player.js; echo "exit $?"; node --check renderer/js/player.js; echo "check exit $?"; git stash list >/dev/null; git show HEAD:renderer/js/player.js > /c/Users/dani/.claude/jobs/8eb91856/tmp/player-before.js && cp /c/Users/dani/.claude/jobs/8eb91856/tmp/player-before.js /c/Users/dani/.claude/jobs/8eb91856/tmp/p0.js && node /c/Users/dani/.claude/jobs/8eb91856/tmp/check-parse.js /c/Users/dani/.claude/jobs/8eb91856/tmp/player-before.js; echo "before exit $?"
EXIT: 0
OUTPUT:
    fatal fragParsingError #1: recoverMediaError
    fatal fragParsingError #2: restart at 2580.63 (new session 2)
    fatal fragParsingError #3: recoverMediaError
    fatal fragParsingError #4: restart at 2581.72 (new session 3)
    fatal fragParsingError #5: recoverMediaError
    fatal fragParsingError #6: error screen "Stream error: fragParsingError"
    fatal 404: restart at 2580.63 (new session 2)
    fatal 403: error screen "The server refused the stream. Your friend may not allow conversion (transcoding) for shared users. Try "Direct Play" in Quality."
    log: hls parse error, restarting at 2581 {"sn":2581,"start":2580.62,"end":2581.62,"skip":false} | hls parse error, restarting at 2582 {"sn":2581,"start":2580.62,"end":2581.62,"skip":true} | hls sessio…
    OK
    exit 0
    check exit 0
    fatal fragParsingError #1: recoverMediaError
    fatal fragParsingError #2: error screen "Stream error: fragParsingError"
    fatal 404: restart at 2580.63 (new session 2)
    fatal 403: error screen "The server refused the stream. Your friend may not allow conversion (transcoding) for shared users. Try "Direct Play" in Quality."
    log: hls session gone, restarting at 2581
    FAIL
    before exit 1

### EVIDENCE: WORK 2026-11-19T23:24:42.636Z
COMMAND: git status --short && git diff --stat
EXIT: 0
OUTPUT:
     M .dispatch/logs/playback-issue-not-sure-why.md
     M .dispatch/playback-issue-not-sure-why.session
     M .dispatch/playback-issue-not-sure-why.state
     M renderer/js/player.js
    warning: in the working copy of '.dispatch/logs/playback-issue-not-sure-why.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/playback-issue-not-sure-why.session', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/playback-issue-not-sure-why.state', LF will be replaced by CRLF the next time Git touches it
     .dispatch/logs/playback-issue-not-sure-why.md | 43 +++++++++++++++++++++++++--
     .dispatch/playback-issue-not-sure-why.session |  1 +
     .dispatch/playback-issue-not-sure-why.state   |  2 +-
     renderer/js/player.js                         | 17 +++++++++++
     4 files changed, 59 insertions(+), 4 deletions(-)

### EVIDENCE: WORK 2026-11-19T23:24:45.950Z
COMMAND: git add renderer/js/player.js && git commit -m "playback-issue-not-sure-why: restart the stream on a fatal fragParsingError, skip the segment if it breaks again" -- renderer/js/player.js && git log --oneline -1
EXIT: 0
OUTPUT:
    [main 6f2c4fe] playback-issue-not-sure-why: restart the stream on a fatal fragParsingError, skip the segment if it breaks again
     1 file changed, 17 insertions(+)
    6f2c4fe playback-issue-not-sure-why: restart the stream on a fatal fragParsingError, skip the segment if it breaks again

### 2. WORK — ok
The player now starts a new stream at the same spot when a stream piece has no picture or sound.
Files: renderer/js/player.js
Blockers: none

## What changed
The player now starts a new stream at the same spot when a stream piece has no picture or sound.
If the same piece breaks again, the new stream starts just after that piece (about 1 second later).
The error screen still shows when the stream keeps failing (more than 2 restarts in one minute).
Commit `6f2c4fe` on `main`, not pushed.

## Why
The app log shows the cause. The Plex server sent piece #2581 (time 2580.6 s, about 80 % in) with no media in it.
hls.js tried again many times, then gave a fatal error. The one media recovery also failed, so the error screen showed.

Log lines (`%APPDATA%/Buffer Zone/buffer-zone.log`, session `de0f0a51…`, quality 20 Mbps, video and audio converted):

```
11454 [2026-11-19T23:16:34.190Z] hls error {"type":"mediaError","details":"fragParsingError","fatal":true,"sn":2581,"reason":"Found no media in msn 2581 of main playlist …/session/de0f0a51-…/base/index.m3u8","error":"Found no media in main playlist 0 sn: 2581 at playlist time: 2580.621288888889. Resetting transmuxer to fallback to playlist timing"}
11455-11459  (after recoverMediaError) non-fatal fragParsingError on sn 2580, 2581 ×4
11460 [2026-11-19T23:16:46.811Z] hls error {… "fragParsingError","fatal":true,"sn":2581, "reason":"Found no media in msn 2581 …"}
11461 [2026-11-19T23:16:46.812Z] player error Stream error: fragParsingError
```

Before line 11454: 13 non-fatal `fragParsingError` lines on sn 2580/2581 and 3 `bufferAppendNoProgress` on sn 2579 (23:16:31 to 23:16:34). The buffer at that time was 2522.06 to 2579.62 s. The last `decision` line before it (22:12:08) is h264 transcode, mp3 transcode, srt ignore; `hls {"reason":"quality setting","quality":"20000","subMode":"none"}`.

Plan Note 6 does not apply. The broken piece is after the session start, at the play position, not a stub before the offset. Note 7 does not apply: the fatal error is `fragParsingError`, a media error.

## Files
- `renderer/js/player.js`, `onHlsError`: a new branch for a fatal `fragParsingError` after the media recovery. It shares `this.restarts` (2 per 60 s) with the 404 branch, calls `stats.restart()`, logs `hls parse error, restarting at <s> {sn,start,end,skip}` and calls `seekTo(t, { restart: true })`. `t` is the current time + 0.01, or `frag.end + 0.1` when the previous parse restart was for a piece with the same start time (`this.parseRestartAt`, within 0.5 s).

## Verification
- `node C:/Users/dani/.claude/jobs/8eb91856/tmp/check-parse.js <player.js before the change>` (the HEAD version copied to the job folder) printed `#1: recoverMediaError`, `#2: error screen "Stream error: fragParsingError"`, `FAIL` (exit 1). This is the code-path reproduction.
- The same script on the changed `renderer/js/player.js` printed `#1 recoverMediaError`, `#2 restart at 2580.63 (new session 2)`, `#3 recoverMediaError`, `#4 restart at 2581.72 (new session 3)` (after the piece end 2581.62), `#5 recoverMediaError`, `#6 error screen "Stream error: fragParsingError"`. A fatal 404 still restarts, a fatal 403 still shows the refusal text. Last line `OK`, exit 0.
- `node --check renderer/js/player.js`: exit 0, no output.
- `git status --short` before the commit: only `renderer/js/player.js` from this run; three `.dispatch/` files of Dispatch were also changed and stay uncommitted.
- Check `android/gradle tests`: not chosen, not run.
- The check script lives in the job folder only (Decision 2), not in the project.

Deviation from the plan: the Acceptance expected `#3: restart`. The real player resets the one media recovery for each new session (`startHls`). So each new session first tries `recoverMediaError`, then restarts. The script stubs this reset, and the sequence has 6 steps, not 4. The code follows the plan; only the expected output changed.

Manual test for the tester (Windows): play the same title at about 80 % (time 43:00), quality 20 Mbps. Expected: playback goes on, maybe after a short spinner. The stats box (key D) counts a restart. The log has `hls parse error, restarting at`.

Not tried by anybody: a real broken piece on the live server (it cannot be made on demand). The LG TV and Android TV (they do not use hls.js). How long the spinner shows before the restart in the real app (the failed media recovery took about 12 s in the log).

## Follow-ups
- The error box shows small at the top left with a faint spinner behind it (screenshot). The CSS centres it on the full player. This is a separate display issue; not fixed.
- After the error screen, the log shows `bufferStalledError` at 23:17:34 (position 2582.5). The stream seems to keep running behind the error screen. Not looked at.
- Each new session tries the media recovery first. For a "no media" piece this recovery does not help and costs time. A later change can skip it for this error.
- The version bump and the release follow the project rule; not done here.

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-opus-5-5 | 58 | 183033 | 2406480 | 20680 | 2610251 |
