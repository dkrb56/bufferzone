# a paused video starts to play again after a stream restart (pause-resumes-after-restart) — 2026-11-14

## Plan approved
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

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-14T22:44:59.820Z
COMMAND: wc -l renderer/js/player.js && ls && ls renderer renderer/js scripts .claude 2>/dev/null && git log --oneline -5 -- renderer/js/player.js
EXIT: 0
OUTPUT:
    app.js
    icons.js
    multiview.js
    player.js
    plex.js
    profiles.js
    search.js
    settings.js
    subtitles.js
    ui.js
    views.js
    
    scripts:
    build-all.js
    build-icons.js
    abbe015 webOS: the TV draws embedded text subtitles, so those files play directly (0.2.6)
    dac5b5e Fix TV start-up lag and Back from subtitle style
    91dcdfd Android TV and LG webOS versions, TV remote fixes
    df609a0 Player: fast subtitles, visible cursor, volume boost, keep-alive
    7a17614 Redesign: OLED black + violet, icon rail, new features

### EVIDENCE: PLAN 2026-11-14T22:48:45.234Z
COMMAND: cat .dispatch/plan-flags.txt; echo ---; cat .dispatch/work-flags.txt; echo ---; ls .dispatch .dispatch/plans .dispatch/briefs; echo ---; cat package.json; echo ---; cat .claude/memory.md; echo --- mistakes; cat .claude/mistakes.md
EXIT: 0
OUTPUT:
    # Project memory
    
    - Plex HLS with `copyts=1` and `offset>0`: the playlist covers the whole file from 0, and segments before the offset are 188-byte PAT-only stubs. hls.js must start at `startPosition = offset`, and med…
    - Testing via CDP: do not mute the test video. Chromium pauses muted media when the window is not visible, which looks like a playback stall.
    - Plex Discover watchlist: `X-Plex-Container-Size` above 100 gives status 400, so page by 100. The list has no `watchlistedAt`; get it from `/library/metadata/{ratingKey}/userState`. Match items to th…
    - Moving a `<video>` element to a new parent pauses it. Multi-view redraws must call `play()` again on tiles that were playing.
    - Multi-view tiles use volume 0, not `muted`, for the same Chromium reason as above.
    - Plex ends an idle HLS transcode session after about 3 minutes (measured: 404 after 210 s idle, 200 with a ping every 30 s). A paused player must keep sending the timeline and `/video/:/transcode/uni…
    - Embedded text subtitles on this server: with `protocol=hls&subtitles=auto` the decision says `location=segments-subs`, the HLS master has no subtitle playlist, and `/video/:/transcode/universal/subt…
    - Web Audio boost needs `video.crossOrigin = 'anonymous'`, or direct-play media is tainted and the graph outputs silence. Tested: HLS and direct play both give sound through the gain node.
    - Test streams change real watch state: the "stopped" timeline from `exit()` can arrive after a `scrobble`, and every scrobble adds to `viewCount`. Reset with a `stopped` timeline at time 0, then scro…
    - The friend's Plex server answers a browser GET with `Access-Control-Allow-Origin: https://app.plex.tv` for any other origin (also `null`), so a packaged TV web app cannot call it with fetch. plex.tv…
    - User rule (2026-10-25): every update and feature goes to all supported platforms (Windows desktop, Android TV, LG webOS). After each task: bump the patch version in the root `package.json` (Android …
    --- mistakes
    # Mistakes
    
    - 2026-10-24: A UI test called `app.saveFolders([...])` and later `app.saveFolders([])` on the user's real settings. This replaced and then deleted the user's own watchlist folder. Nothing could resto…
      Rule: tests against the user's real app must never overwrite user data. Add test data next to the existing data, remove only that test data, and read the current value first.
    - 2026-10-24: A playback test set `video.volume = 0`; the player saves the volume, and the test then wrote `volume: 1` without reading the old value first. Same rule as above: read `app.settings.volum…
    - 2026-10-25: `gh pr merge` made the merge commit on GitHub's side with the account's primary email (krneker@gmail.com), on the now public repo. Rule: merge pull requests locally (`git merge --no-ff`,…

### EVIDENCE: PLAN 2026-11-14T22:49:03.315Z
COMMAND: echo "== android files"; find android -name "*.kt" | head -40; echo "== android restart/404/play"; grep -rn -i "404\|restart\|retry\|playWhenReady\|\.play()\|pause\|onPlayerError\|ERROR_CODE" android --include=*.kt | head -60; echo "== webos"; ls webos webos/src 2>/dev/null; echo "== build-all"; cat scripts/build-all.js; echo "== webos build.js head"; sed -n 1,80p webos/build.js; echo "== git status"; git status --short
EXIT: 0
OUTPUT:
      // inset (Chromium 87) -> longhands
      css = css.replace(/(^|[;{\s])inset:\s*0\s*;/g, '$1top: 0; right: 0; bottom: 0; left: 0;');
    
      const gapRules = [], minMax = [];
      for (const { sel, body } of rules(css)) {
        const d = decls(body);
        const sels = splitTop(sel);
        // flex gap (Chromium 84) -> margins, only when the shim finds no flex gap support
        if (/(^|\s)(inline-)?flex/.test(d.display || '') && d.gap) {
          const [rowGap, colGap = rowGap] = d.gap.split(/\s+/);
          const column = /column/.test(d['flex-direction'] || '');
          const wrap = /wrap/.test(d['flex-wrap'] || '') && !/nowrap/.test(d['flex-wrap']);
          const pre = sels.map(s => '.no-flexgap ' + s);
          if (wrap) gapRules.push(`${pre.map(s => s + ' > *').join(', ')} { margin-right: ${colGap}; margin-bottom: ${rowGap}; }`);
          else if (column) gapRules.push(`${pre.map(s => s + ' > * + *').join(', ')} { margin-top: ${rowGap}; }`);
          else gapRules.push(`${pre.map(s => s + ' > * + *').join(', ')} { margin-left: ${colGap}; }`);
        }
        // min()/max() (Chromium 79): min -> first value, max -> last value
        for (const [prop, val] of Object.entries(d)) {
    == git status

### EVIDENCE: PLAN 2026-11-14T22:51:05.694Z
COMMAND: LOG="/c/Users/dani/AppData/Roaming/Buffer Zone/buffer-zone.log"; ls -la "$LOG" && echo "== restarts" && grep -n "hls session gone" "$LOG" | tail -12 && echo "== context of the last restart" && n=$(grep -n "hls session gone" "$LOG" | tail -1 | cut -d: -f1) && sed -n "$((n-14)),$((n+4))p" "$LOG" | cut -c1-260
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### EVIDENCE: PLAN 2026-11-14T22:51:18.052Z
COMMAND: echo "== node"; node --version; echo "== ticket"; grep -o '{[^}]*pause-resumes-after-restart[^}]*}' .dispatch/tickets.jsonl | cut -c1-400; echo "== webos src refs to video/play"; grep -n "video\|\.play()\|paused" webos/src/shim.js webos/src/tvnav.js | head -20; echo "== webos build.js: how JS is handled"; grep -n "player\|esbuild\.\|transform\|TARGET\|copyDir(" webos/build.js | head -20; echo "== index.html hls"; grep -n "hls" renderer/index.html; echo "== hls.js version"; node -e "console.log(require('hls.js/package.json').version)"; echo "== hls.js calls play()?"; grep -c "\.play()" node_modules/hls.js/dist/hls.js; grep -n "\.play()" node_modules/hls.js/dist/hls.js | head -5; echo "== hls.js seekToStartPos"; grep -n "_seekToStartPos\|seekToStartPos() {" node_modules/hls.js/dist/hls.js | head -6; echo "== android tooling"; ls "C:/Program Files/Android/Android Studio/jbr/bin/java.exe" 2>&1 | head -1; ls android/gradlew.bat; ls webos/node_modules/.bin 2>/dev/null | grep -i "ares\|esbuild" | head -5; where ares-package 2>&1 | head -2
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### EVIDENCE: PLAN 2026-11-14T22:52:18.399Z
COMMAND: node --version && mkdir -p /c/Users/dani/.claude/jobs/f8c8c5fb/tmp && cat > /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/repro-pause.js <<'EOF'
    // Reproduction harness: loads renderer/js/player.js on stubs and measures whether a paused
    // player calls video.play() after the "hls session gone" restart and after a quality restart.
    'use strict';
    const path = require('path');
    function el(tag) {
      const set = new Set();
      const e = {
        tag, children: [], style: {}, dataset: {}, listeners: {}, textContent: '', value: '', disabled: false,
        classList: { add: (c) => set.add(c), remove: (c) => set.delete(c), contains: (c) => set.has(c), toggle: (c, f) => f ? set.add(c) : set.delete(c) },
        addEventListener(n, f) { (this.listeners[n] = this.listeners[n] || []).push(f); },
        removeEventListener() {},
        dispatch(n) { (this.listeners[n] || []).forEach(f => f({})); },
        append(...c) { this.children.push(...c); }, appendChild(c) { this.children.push(c); },
        replaceChildren(...c) { this.children = c; },
        removeAttribute(a) { delete this[a]; }, setAttribute(a, v) { this[a] = v; },
        getBoundingClientRect() { return { left: 0, width: 100 }; },
        closest() { return null; }, contains() { return false; }, focus() {},
        canPlayType() { return ''; }
      };
      return e;
    }
    function videoEl() {
      const v = el('video');
      Object.assign(v, {
        paused: true, currentTime: 0, readyState: 0, volume: 1, muted: false, buffered: { length: 0 }, playCalls: 0, loads: 0,
        play() { this.playCalls++; this.paused = false; this.dispatch('play'); return Promise.resolve(); },
        pause() { this.paused = true; this.dispatch('pause'); },
        // HTML media element load algorithm, step "if paused is false, set it to true".
        load() { this.loads++; this.paused = true; }
      });
      return v;
    }
    const h = (sel, attrs, ...kids) => {
      const tag = sel.split('.')[0];
      const e = tag === 'video' ? videoEl() : el(tag);
      Object.assign(e, attrs || {});
      e.children.push(...kids.flat().filter(Boolean));
      return e;
    };
    globalThis.window = globalThis;
    globalThis.addEventListener = () => {};
    globalThis.screen = { width: 1920, height: 1080 };
    globalThis.MediaSource = { isTypeSupported: () => false };
    globalThis.document = { createElement: (t) => t === 'video' ? videoEl() : el(t), getElementById: () => el('div'), addEventListener() {}, body: el('body'), pictureInPictureElement: null };
    if (!globalThis.crypto || !globalThis.crypto.randomUUID) globalThis.crypto = require('crypto').webcrypto;
    const logs = [];
    globalThis.sofa = { tv: false, luna: null, nativeHls: false, log: (...a) => logs.push(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')), http: () => {}, fullscreen: () => {} };
    class Hls {
      static isSupported() { return true; }
      constructor(cfg) { this.cfg = cfg; this.handlers = {}; Hls.instances.push(this); }
      on(ev, f) { this.handlers[ev] = f; }
      loadSource(u) { this.url = u; }
      attachMedia(v) { this.media = v; }
      destroy() { this.destroyed = true; }
      recoverMediaError() {}
    }
    Hls.instances = [];
    Hls.Events = { ERROR: 'hlsError', SUBTITLE_TRACKS_UPDATED: 'hlsSubtitleTracksUpdated', CUES_PARSED: 'hlsCuesParsed' };
    Hls.ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    globalThis.Hls = Hls;
    globalThis.Sofa = {
      h, fmtTime: () => '0:00', fmtDuration: () => '', epCode: () => '', toast: () => {}, pill: () => el('span'), icon: () => el('i'),
      Subtitles: { SubtitleOverlay: class { constructor() { this.cues = []; } applyStyle() {} clear() {} start() {} stop() {} setCues(c) { this.cues = c; } }, isTextSub: () => false, parse: () => [] }
    };
    require(path.resolve(process.argv[2] || 'D:/dev_projects/buffer-zone/renderer/js/player.js'));
    const Player = globalThis.Sofa.Player;
    
    const meta = { ratingKey: '1', type: 'movie', title: 'T', duration: 600000, Media: [{ container: 'mkv', videoCodec: 'hevc', Part: [{ id: 'p1', key: '/k', Stream: [{ streamType: 1, codec: 'hevc' }, { streamType: 2, id: 'a1', codec: 'eac3', languageCode: 'en' }] }] }] };
    const decisions = [];
    const app = {
      settings: { quality: 'original', audioLang: 'en', subtitleMode: 'off', seriesPrefs: {}, volume: 1, audioBoost: 100 },
      plex: {
        server: { token: 't', local: true }, url: (p) => 'http://plex' + p,
        get: async (p, q) => { if (p.includes('decision')) decisions.push(q.session); return {}; },
        getText: async () => '', selectStreams: async () => {}, timeline: () => {}, scrobble: async () => {},
        clientParams: () => ({}), clientHeaders: () => ({}), metadata: async () => meta, allLeaves: async () => []
      },
      saveSettings() {}, onPlayerStart() {}, onPlayerExit() {}, go() {}
    };
    const tick = (ms = 30) => new Promise(r => setTimeout(r, ms));
    const gone = { type: 'networkError', details: 'fragLoadError', fatal: true, response: { code: 404 }, frag: { sn: 5 } };
    const rows = [];
    async function scenario(name, userPauses, action) {
      const p = new Player(app);
      await p.play(meta);
      await tick();
      const before = p.video.playCalls; // 1: the start of playback
      if (userPauses) p.togglePlay();
      p.video.currentTime = 123;
      const session = p.transcodeSession;
      action(p);
      await tick();
      rows.push({ scenario: name, 'user paused': userPauses, 'new session': p.transcodeSession !== session, 'play() calls after the action': p.video.playCalls - before, 'video.paused at the end': p.video.paused });
      p.stopInternal();
    }
    (async () => {
      await scenario('session gone (404) restart', true, p => p.onHlsError(gone));
      await scenario('session gone (404) restart', false, p => p.onHlsError(gone));
      await scenario('quality change restart', true, p => p.changeQuality('4000'));
      await scenario('seek outside the buffer', true, p => p.seekTo(400));
      console.table(rows);
      console.log('restart log lines:', logs.filter(l => l.startsWith('hls session gone')).length, '| decisions requested:', decisions.length);
      const bug = rows.filter(r => r['user paused'] && r['play() calls after the action'] > 0).map(r => r.scenario);
      console.log(bug.length ? 'RESUMED WHILE PAUSED: ' + bug.join('; ') : 'OK: every paused scenario stays paused');
      process.exit(0);
    })().catch(e => { console.error(e); process.exit(1); });
    EOF
    node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/repro-pause.js D:/dev_projects/buffer-zone/renderer/js/player.js
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
A paused video stays paused through a session restart, a seek and a quality or subtitle change; it plays again only on Play.
Files: .dispatch/plans/pause-resumes-after-restart.md
Blockers: Does a seek while paused keep the pause, like the automatic restart?; Does the Android TV app get the same fix in this task?; Where does the check script live?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-14T23:04:59.718Z
COMMAND: git status --short && git log --oneline -3 && node --version && date -u +"%Y-%m-%d %H:%M UTC"
EXIT: 0
OUTPUT:
     M .dispatch/pause-resumes-after-restart.session
     M .dispatch/pause-resumes-after-restart.state
    f7c0275 Dispatch: 7 task files
    23efce8 player-debug-overlay: playback stats box in the player (key D, Quality menu)
    3c102b7 Dispatch: 2 task files
    v24.12.0
    2026-11-14 23:05 UTC

### EVIDENCE: WORK 2026-11-14T23:05:01.564Z
COMMAND: LOG="/c/Users/dani/AppData/Roaming/Buffer Zone/buffer-zone.log"; ls -la "$LOG"; echo "== restarts (last 12)"; grep -n "hls session gone" "$LOG" | tail -12 | cut -c1-200; echo "== context of the last restart"; n=$(grep -n "hls session gone" "$LOG" | tail -1 | cut -d: -f1); [ -n "$n" ] && sed -n "$((n-12)),$((n+4))p" "$LOG" | cut -c1-240
EXIT: 0
OUTPUT:
    9328:[2026-11-14T22:46:13.884Z] [renderer] hls session gone, restarting at 1052
    9339:[2026-11-14T22:46:50.831Z] [renderer] hls session gone, restarting at 1054
    == context of the last restart
    [2026-11-14T22:46:13.884Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":true,"code":404,"sn":219,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:13.884Z] [renderer] hls session gone, restarting at 1052
    [2026-11-14T22:46:13.970Z] http GET http://plex.v12.appboxmanager.xyz:80/video/:/transcode/universal/stop?session=d0361323-7c8c-4962-9c0f-27df8ff8d85e&X-Plex-Token=*** 404
    [2026-11-14T22:46:14.168Z] [renderer] decision {"general":"Direct play not available; Conversion OK.","transcode":"Direct play not available; Conversion OK.","streams":[{"t":1,"codec":"h264","decision…
    [2026-11-14T22:46:19.167Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:20.348Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:21.725Z] http GET http://plex.v12.appboxmanager.xyz:80/video/:/transcode/universal/ping?session=61b8ee9f-a4d8-49d7-ad7f-8a1976fd6d0c 404
    [2026-11-14T22:46:22.456Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:26.553Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:34.633Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:42.741Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:50.830Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":true,"code":404,"sn":216,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:50.831Z] [renderer] hls session gone, restarting at 1054
    [2026-11-14T22:46:51.116Z] http GET http://plex.v12.appboxmanager.xyz:80/video/:/transcode/universal/stop?session=61b8ee9f-a4d8-49d7-ad7f-8a1976fd6d0c&X-Plex-Token=*** 404
    [2026-11-14T22:46:51.127Z] [renderer] decision {"general":"Direct play not available; Conversion OK.","transcode":"Direct play not available; Conversion OK.","streams":[{"t":1,"codec":"h264","decision…
    [2026-11-14T22:46:52.476Z] [renderer] hls error {"type":"networkError","details":"fragLoadError","fatal":false,"code":404,"sn":211,"error":"HTTP Error 404 Not Found"}
    [2026-11-14T22:46:53.534Z] [renderer] hls error {"type":"mediaError","details":"bufferStalledError","fatal":false,"error":"Playback stalling at @1055.002744 due to low buffer ({\"len\":0.0132560000001…

### EVIDENCE: WORK 2026-11-14T23:05:07.864Z
COMMAND: git show --stat 23efce8 | head -20 && git diff b4c6e34 HEAD -- renderer/js/player.js android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt | head -200
EXIT: 0
OUTPUT:
     
    +    // Playback stats box (settings.debugOverlay): key D or Quality → Playback stats. The choice stays after a restart.
    +    toggleStats() {
    +      const on = !this.stats.isOn;
    +      this.app.saveSettings({ debugOverlay: on });
    +      if (on) this.stats.on(); else this.stats.off();
    +    }
    +
         togglePip() {
           if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
           else this.video.requestPictureInPicture().catch(() => toast('Picture in picture is not available.'));
    @@ -935,7 +958,7 @@
             arrowup: () => { this.video.volume = Math.min(1, this.video.volume + 0.05); },
             arrowdown: () => { this.video.volume = Math.max(0, this.video.volume - 0.05); },
             f: () => sofa.fullscreen(), m: () => { this.video.muted = !this.video.muted; },
    -        n: () => this.playNext(), s: () => this.doSkip(),
    +        n: () => this.playNext(), s: () => this.doSkip(), d: () => this.toggleStats(),
             escape: () => {
               if (this.pop.classList.contains('on')) return this.closePop();
               if (!sofa.tv && window.innerHeight === screen.height && window.innerWidth === screen.width) sofa.fullscreen(false);

### EVIDENCE: WORK 2026-11-14T23:05:12.821Z
COMMAND: grep -n "await this.load(startAt)\|async load(startAt)\|this.destroyStream();\|this.video.play().catch\|seekTo(t, { restart\|this.load(" renderer/js/player.js && sed -n 160,170p renderer/js/player.js && sed -n 276,282p renderer/js/player.js && sed -n 310,320p renderer/js/player.js && sed -n 596,612p renderer/js/player.js
EXIT: 0
OUTPUT:
        hlsParams(offset, subMode) {
          const q = QUALITY.find(x => x.id === this.quality) || QUALITY[1];
          const boost = sofa.tv && !this.tile ? (this.settings.audioBoost || 100) : 100;
    
        // `restart` starts a new transcode session even when `t` is already buffered.
        seekTo(t, { restart = false } = {}) {
          t = Math.max(0, Math.min(this.duration - 1, t));
          if (this.mode === 'direct') { this.video.currentTime = t; return; }
          const b = this.video.buffered;
          for (let i = 0; !restart && i < b.length; i++) {
            if (t >= b.start(i) && t <= b.end(i)) { this.video.currentTime = t; return; }
          }
          // Outside the buffer: restart the transcode at the new position.
          this.spinner.classList.add('on');
          if (!restart) this.stats.expectWait();
          this.destroyStream();
          this.startHls(t, this.subMode).then(() => this.video.play().catch(() => {})).catch(e => this.fail(e));
        }
    
        togglePlay() {

### EVIDENCE: WORK 2026-11-14T23:06:30.841Z
COMMAND: node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"
EXIT: 0
OUTPUT:
    player: D:\dev_projects\buffer-zone\renderer\js\player.js
    ┌─────────┬──────────────────────────────┬─────────────┬─────────────┬────────────────────┬───────────────┬────────────────┬─────────────────────┐
    │ (index) │ scenario                     │ user paused │ new session │ play() calls after │ paused at end │ timeline state │ ping to new session │
    ├─────────┼──────────────────────────────┼─────────────┼─────────────┼────────────────────┼───────────────┼────────────────┼─────────────────────┤
    │ 0       │ 'session gone (404) restart' │ true        │ true        │ 1                  │ false         │ 'playing'      │ true                │
    │ 1       │ 'session gone (404) restart' │ false       │ true        │ 1                  │ false         │ 'playing'      │ true                │
    │ 2       │ 'quality change'             │ true        │ true        │ 1                  │ false         │ 'playing'      │ true                │
    │ 3       │ 'seek outside the buffer'    │ true        │ true        │ 1                  │ false         │ 'playing'      │ true                │
    └─────────┴──────────────────────────────┴─────────────┴─────────────┴────────────────────┴───────────────┴────────────────┴─────────────────────┘
    log lines "hls session gone": 2
    KEEP-ALIVE BROKEN: no ping to the new session or wrong timeline state
    RESUMED WHILE PAUSED: session gone (404) restart; quality change; seek outside the buffer
    exit=1

### EVIDENCE: WORK 2026-11-14T23:06:47.235Z
COMMAND: node --check renderer/js/player.js; echo "check exit=$?"; node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"; git diff --stat
EXIT: 0
OUTPUT:
    check exit=0
    player: D:\dev_projects\buffer-zone\renderer\js\player.js
    ┌─────────┬──────────────────────────────┬─────────────┬─────────────┬────────────────────┬───────────────┬────────────────┬─────────────────────┐
    │ (index) │ scenario                     │ user paused │ new session │ play() calls after │ paused at end │ timeline state │ ping to new session │
    ├─────────┼──────────────────────────────┼─────────────┼─────────────┼────────────────────┼───────────────┼────────────────┼─────────────────────┤
    │ 0       │ 'session gone (404) restart' │ true        │ true        │ 0                  │ true          │ 'paused'       │ true                │
    │ 1       │ 'session gone (404) restart' │ false       │ true        │ 1                  │ false         │ 'playing'      │ true                │
    │ 2       │ 'quality change'             │ true        │ true        │ 0                  │ true          │ 'paused'       │ true                │
    │ 3       │ 'seek outside the buffer'    │ true        │ true        │ 0                  │ true          │ 'paused'       │ true                │
    └─────────┴──────────────────────────────┴─────────────┴─────────────┴────────────────────┴───────────────┴────────────────┴─────────────────────┘
    log lines "hls session gone": 2
    OK: every paused scenario stays paused
    exit=0
    warning: in the working copy of '.dispatch/pause-resumes-after-restart.session', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/pause-resumes-after-restart.state', LF will be replaced by CRLF the next time Git touches it
     .dispatch/pause-resumes-after-restart.session |  1 +
     .dispatch/pause-resumes-after-restart.state   |  2 +-
     renderer/js/player.js                         | 12 ++++++++----
     3 files changed, 10 insertions(+), 5 deletions(-)

### EVIDENCE: WORK 2026-11-14T23:06:55.204Z
COMMAND: grep -n "load(\|playWhenReady" android/app/src/main/java/com/dkrb56/bufferzone/*.kt
EXIT: 0
OUTPUT:
    android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt:143:    Reload(s.key) {
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:110:    fun reload(from: Int = 0) { gen++; items.clear(); total = -1; startAt = from; loading = false; scope.launch { more() } }
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:115:        reload()
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:129:                Focusable({ unwatched = !unwatched; save(); reload() }) { f ->
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:182:                            reload(entry.second)
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:193:        "sort" -> OptionDialog("Sort", SORTS, sort, { dialog = null }) { sort = it; save(); reload() }
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:194:        "genre" -> OptionDialog("Genre", listOf("" to "All genres") + genres, genre, { dialog = null }) { genre = it; save(); reloa…
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt:219:    Reload(key) {
    android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt:175:fun Reload(vararg keys: Any?, block: suspend () -> Unit) {
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:136:                load(req.startAt)
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:176:    private suspend fun load(startAt: Double) {
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:214:        player.playWhenReady = true
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:280:        if (is404 && restarts.size < 2) { restarts += now; scope.launch { reload(position) }; return }
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:289:        scope.launch { reload(t) }
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:292:    private suspend fun reload(t: Double) {
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:294:        load(t)
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:309:        scope.launch { reload(t) }
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:322:        } else { forceHls = false; scope.launch { reload(position) } }
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:334:        } else scope.launch { reload(position) }
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt:341:        scope.launch { reload(position) }

### EVIDENCE: WORK 2026-11-14T23:07:06.796Z
COMMAND: cd /d/dev_projects/buffer-zone/webos && node build.js 2>&1 | tail -15; echo "exit=${PIPESTATUS[0]}"; ls -la ../dist/com.dkrb56.bufferzone_0.2.9_all.ipk
EXIT: 0
OUTPUT:
    Create com.dkrb56.bufferzone_0.2.9_all.ipk to D:\dev_projects\buffer-zone\dist
    [Warning] The Node.js version available on the target device may be lower than your development environment. Check the supported version before using recent Node.js features:
    https://webostv.developer.lge.com/develop/guides/js-service-basics#supported-nodejs-version
    Success
    exit=0
    -rw-r--r-- 1 dani 197121 271746 Nov 15 00:07 ../dist/com.dkrb56.bufferzone_0.2.9_all.ipk

### EVIDENCE: WORK 2026-11-14T23:07:13.291Z
COMMAND: cd /d/dev_projects/buffer-zone && grep -c "if (!paused)" webos/build/app/js/player.js && grep -n "paused: false\|load(startAt, {" webos/build/app/js/player.js | head -5
EXIT: 0
OUTPUT:
    2
    229:        await this.load(startAt, { paused: false });
    281:    async load(startAt, { paused = this.video.paused } = {}) {

### EVIDENCE: WORK 2026-11-14T23:07:15.245Z
COMMAND: cd /d/dev_projects/buffer-zone && node scripts/build-all.js > /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log 2>&1; echo "exit=$?"; grep -n "^== \|BUILD\|error\|Error\|FAILURE\|MB$\|missing" /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | grep -v "^.*warning" | tail -30
EXIT: 0
OUTPUT:
    exit=0
    2:== Buffer Zone 0.2.9: Windows
    23:== Android TV
    68:BUILD SUCCESSFUL in 26s
    72:== LG webOS
    78:BufferZone-0.2.9-setup.exe  122.0 MB
    79:BufferZone-0.2.9-setup.exe.blockmap  0.1 MB
    80:latest.yml  0.0 MB
    81:BufferZone-0.2.9-portable.exe  108.0 MB
    82:BufferZone-tv-0.2.9.apk  20.6 MB
    83:com.dkrb56.bufferzone_0.2.9_all.ipk  0.3 MB

### EVIDENCE: WORK 2026-11-14T23:09:48.828Z
COMMAND: grep -n "compileDebugKotlin\|w: \|e: " /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | head -10; sed -n 60,70p /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log
EXIT: 0
OUTPUT:
    57:> Task :app:compileDebugKotlin
    > Task :app:mergeDebugGlobalSynthetics UP-TO-DATE
    > Task :app:processDebugJavaRes UP-TO-DATE
    > Task :app:mergeDebugJavaResource UP-TO-DATE
    > Task :app:mergeProjectDexDebug
    > Task :app:packageDebug
    > Task :app:createDebugApkListingFileRedirect UP-TO-DATE
    > Task :app:assembleDebug
    
    BUILD SUCCESSFUL in 26s
    37 actionable tasks: 4 executed, 33 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html

### EVIDENCE: WORK 2026-11-14T23:09:52.325Z
COMMAND: git status --short && git diff -- renderer/js/player.js android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
EXIT: 0
OUTPUT:
           this.applyBoost();
    -      this.video.play().catch(() => {});
    +      if (!paused) this.video.play().catch(() => {});
           this.startTimers();
           this.updateUi();
         }
    @@ -603,10 +605,12 @@
             if (t >= b.start(i) && t <= b.end(i)) { this.video.currentTime = t; return; }
           }
           // Outside the buffer: restart the transcode at the new position.
    +      // A paused video stays paused; read it first, because destroyStream() resets `paused` to true.
    +      const paused = this.video.paused;
           this.spinner.classList.add('on');
           if (!restart) this.stats.expectWait();
           this.destroyStream();
    -      this.startHls(t, this.subMode).then(() => this.video.play().catch(() => {})).catch(e => this.fail(e));
    +      this.startHls(t, this.subMode).then(() => { if (!paused) this.video.play().catch(() => {}); }).catch(e => this.fail(e));
         }
     
         togglePlay() {

### EVIDENCE: WORK 2026-11-14T23:10:15.744Z
COMMAND: git add renderer/js/player.js android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt && git commit -q -F - <<'EOF'
    pause-resumes-after-restart: a paused video stays paused through a stream restart
    
    After the server drops the converted session (log line "hls session
    gone"), the player rebuilt the stream and always called video.play(),
    so a paused video started again. The same happened after a quality,
    audio or subtitle change and a seek outside the buffer.
    
    seekTo() and load() now read the paused state before destroyStream()
    (the media load algorithm resets it) and call play() only when the
    video was playing. play() of a new title still starts on its own.
    
    Android TV: load() takes playWhenReady; reload() passes the current
    value, so the 404 restart, seeks and track changes keep a pause.
    EOF
    git log --oneline -1 && git status --short
EXIT: 0
OUTPUT:
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt', LF will be replaced by CRLF the next time Git touches it
    b7bef44 pause-resumes-after-restart: a paused video stays paused through a stream restart
     M .dispatch/pause-resumes-after-restart.session
     M .dispatch/pause-resumes-after-restart.state

### 2. WORK — ok
A paused video now stays paused when the app rebuilds the stream. Playback starts again only when the user presses Play.
Files: renderer/js/player.js, android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
Blockers: none

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
evidence: yes
- `node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"` on the unchanged file (reproduction): every paused row showed 1 `play()` call, `paused at end` false and timeline state "playing". The script printed `RESUMED WHILE PAUSED: session gone (404) restart; quality change; seek outside the buffer` and `exit=1`. The ping went to the new session in every row.
evidence: yes
- `node --check renderer/js/player.js; echo "check exit=$?"; node /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/check-player-pause.js renderer/js/player.js; echo "exit=$?"; git diff --stat` after the change: `check exit=0`. Session-gone restart while paused: 0 `play()` calls, paused, new session, timeline "paused", ping to the new session. Session-gone restart while playing: 1 call, playing, new session. Quality change while paused: 0 calls. Seek outside the buffer while paused: 0 calls. Last line `OK: every paused scenario stays paused`, `exit=0`.
evidence: yes
- `node build.js 2>&1 | tail -15; echo "exit=${PIPESTATUS[0]}"; ls -la ../dist/com.dkrb56.bufferzone_0.2.9_all.ipk` in `webos/`: `Success`, `exit=0`, the ipk exists (271746 bytes).
evidence: yes
- `grep -c "if (!paused)" webos/build/app/js/player.js && grep -n "paused: false\|load(startAt, {" webos/build/app/js/player.js | head -5` showed 2 checks and both changed lines in the TV build.
evidence: yes
- `node scripts/build-all.js > /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log 2>&1; echo "exit=$?"; grep -n "^== \|BUILD\|error\|Error\|FAILURE\|MB$\|missing" /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | grep -v "^.*warning" | tail -30` gave `exit=0`, `BUILD SUCCESSFUL` for Android, and all six files in `dist/` (setup exe, blockmap, latest.yml, portable exe, apk, ipk).
evidence: yes
- `grep -n "compileDebugKotlin\|w: \|e: " /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log | head -10; sed -n 60,70p /c/Users/dani/.claude/jobs/f8c8c5fb/tmp/build-all.log` showed that the task `:app:compileDebugKotlin` ran (not up to date) with no warning and no error.
evidence: yes
- `git status --short && git diff -- renderer/js/player.js android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt` showed only the two code files changed, plus the two Dispatch files of this task.
evidence: yes

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

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-fable-5-1 | 450 | 175291 | 1747848 | 47664 | 1971253 |
| claude-opus-5-5 | 30 | 432397 | 3735569 | 17331 | 4185327 |
