# Plan player-debug-overlay: togglable playback stats overlay in the player

## Summary
A stats box in the player shows delivery, buffer, segment timing, HTTP status, stalls, restarts, dropped frames and the last error; key D or a row in the Quality menu toggles it, and the choice is saved.
6 files change: `renderer/js/stats.js` (new), `renderer/js/player.js`, `renderer/index.html`, `renderer/styles.css`, `webos/src/webos.css`, `README.md`.
3 decisions, none is HOLD.

## Goal
The user sees a spinner every 35 to 40 s and cannot tell a slow network from a server that ended the transcode session. The log in the brief shows the server answers 404 for a segment and for the session ping, and the app restarts the session. The player gets a small text box, top left, that the user can switch on and off while a video plays: with the key D on a keyboard, or with a row "Playback stats" in the Quality menu, which the TV remote reaches like every other menu row. The box updates twice a second and shows: delivery mode and quality, the connection to the server, buffer ahead in seconds, the last segment (number, size, load time, age, HTTP status), the stream bitrate and the network estimate, the stall count with the stalled seconds, the restart count, the last ping status, dropped frames, and the last error line with its clock time. The on/off choice is saved in the settings, so a restart of the app brings the box back in the same state. When the box is off, the player runs no timer, no DOM update and no extra hls.js listener; only a few counters tick in code that already runs. The box is not focusable and lets clicks through, so the remote and the mouse behave as before. Multi-view tiles never show it. The Android app has its own player in Kotlin; Decision 3 covers it.

## TL;DR
- Press D in the player, or pick "Playback stats" in the Quality menu, to show a small stats box.
- It shows how much video is buffered, how fast the pieces arrive, the server's last answer, and the last error with its time.
- The stall and restart counters tell you if the server dropped the stream or your network was slow.
- The box stays on or off after a restart of the app and costs nothing while it is off.

## Acceptance
- `node --check renderer/js/player.js` exits 0 and prints nothing.
- `node --check renderer/js/stats.js` exits 0 and prints nothing.
- `node build.js` in `webos/`: ends without an error, prints the `ares-package` output, and `dist/com.dkrb56.bufferzone_0.2.9_all.ipk` exists afterwards.
- `node scripts/build-all.js`: ends with the file list of `dist/` and no error. If the Windows or Android step fails on missing tooling, the report says so, and the webOS step above stands as the build proof (the brief allows this).
- `git status --short` before the commit: shows the six changed files and nothing else outside `.dispatch/`; `dist/` and `webos/build/` are ignored.
- Manual, for the tester (Windows): play a title with Quality set to 8 Mbps (a converted stream). Press D: a dark box appears top left under the title area. Watch it for 5 s: the Buffer line and the Segment age change at least once a second, the Segment line shows a number, a size in MB, a load time in ms and HTTP 200. Open the Quality menu: the last row "Playback stats" has a check mark. Pull the network cable or turn Wi-Fi off for 15 s, then back on: the Stalls count goes up, the Last error line shows a clock time and an hls.js error name; when the server has ended the session, the line shows HTTP 404 and the Restarts count goes up by one. Press D: the box is gone, nothing of it stays on screen, the menu row has no check mark. Press D again, close the app, start it again and play a title: the box is there from the first second. Open Multi-view and add a stream: no box on the tiles.
- Manual, for the tester (TV): play a title, press down to show the controls, move to the Quality chip, press OK, move down to "Playback stats", press OK: the box appears, the menu closes. Press the arrows in every direction: the focus never lands on the box. The Segment line reads "n/a (TV player)" because the TV's own player loads the stream. Back closes menus and the player as before.

## Files to change
- `renderer/js/stats.js`, new file, class `PlayerStats` on `window.Sofa`, built by the player with the player as its only argument. It holds the counters (stalls, stalled milliseconds, restarts, last segment, last HTTP status, last ping, last error line, "played once" flag) and the hooks the player calls: `reset` (new title), `stall` begin and end, `expectWait` (a user seek that restarts the stream must not count as a stall), `restart`, `ping` with the status, `error` with a text, `attach` with the hls.js instance. `on` builds the box (a `div.p-stats` with one label/value pair per line, no click handler, so `h()` gives it no tabindex), appends it to the player root, starts a 500 ms timer and adds one `FRAG_LOADED` listener on the current hls.js instance when one exists; `on` returns at once for a Multi-view tile. `off` removes the listener, the timer and the element, and keeps the counters. `attach` adds the listener only when the box is on. The `FRAG_LOADED` handler stores the segment number (`frag.sn`), bytes (`frag.stats.loaded`), load time (`frag.stats.loading.end` minus `start`), the clock time and the HTTP status from `networkDetails.status` (the XHR). The tick renders these lines: Delivery (Direct Play, or Converted with the quality label and the subtitle mode burn/auto/none); Server (local network, direct internet or Plex relay from `plex.server`, the first 6 characters of the transcode session, "hls.js" with `Hls.version` or "TV player"); Video (`videoWidth`×`videoHeight`, dropped of total frames from `getVideoPlaybackQuality` when present, else the `webkitDroppedFrameCount` and `webkitDecodedFrameCount` counters, else "n/a"); Buffer (seconds from the current time to the end of the buffered range that holds it, and the number of ranges); Segment (the stored values and the age in seconds, or "n/a (direct play)" or "n/a (TV player)"); Bitrate (stream bitrate from `hls.levels[hls.currentLevel].bitrate` and the network estimate from `hls.bandwidthEstimate`, or the file bitrate from `media.bitrate` for direct play); Stalls (count, total stalled seconds including an open stall, restarts, last ping as "HTTP n at hh:mm:ss"); Last error (clock time and text, or "none"). Reason: one module keeps the player readable, mirrors `subtitles.js`, and makes "nothing runs when off" easy to check in one place.
- `renderer/js/player.js`, line 4 (the destructuring of `window.Sofa`): add `PlayerStats`. Reason: the player builds the object.
- `renderer/js/player.js`, `build()`, after `this.overlay = new Subtitles.SubtitleOverlay(...)` (line 135): create `this.stats`. Reason: tiles and the main player share the constructor; the guard sits in `on()`.
- `renderer/js/player.js`, `build()`, the `waiting` listener (line 141) and the `playing` listener (line 142): add the stall begin and end hooks next to the spinner calls; the begin hook counts only when the video is not seeking, the stream has played once since `reset`, and no `expectWait` is pending. Reason: the spinner already marks the stall; the counter must agree with it and must not count the first load or a user seek.
- `renderer/js/player.js`, `play()`, after `this.poke()` (line 179): call `reset`, then `on` when `this.settings.debugOverlay` is true, else `off`. Reason: counters are per title (the next episode starts at zero), and the saved choice restores the box at the first second of playback.
- `renderer/js/player.js`, `startHls()`, after `this.hls = hls;` (line 375): call `attach`. Reason: every new session (start, seek outside the buffer, 404 restart) is a new hls.js instance; `destroy()` drops the old listener.
- `renderer/js/player.js`, `seekTo()`, before `this.destroyStream()` in the out-of-buffer branch (line 594), only when `restart` is false: call `expectWait`. Reason: a user seek that restarts the transcode shows a spinner but is not a stall; the 404 restart goes through the same path with `restart` true and must count.
- `renderer/js/player.js`, `onHlsError()`, after the `sofa.log` line (line 432): record the error line (details, HTTP code when present, segment number when present, "fatal" when fatal) and the HTTP status. In the 404 branch, beside `this.restarts.push(now)` (line 445): call `restart`. Reason: this is the one place that sees every hls.js error, including the non-fatal `bufferStalledError` (a client-side stall) and the `fragLoadError` 404 (the server ended the session); the limiter, the recover call and the restart stay as they are.
- `renderer/js/player.js`, `onVideoError()` (line 457), `fallbackToHls()` (line 465) and `fail()` (line 473): record one error line each (video error code and message; "fallback to converted" with the reason; "playback failed" with the message). Reason: these are the other paths where the user sees a spinner or an error screen.
- `renderer/js/player.js`, `startTimers()`, the ping call (line 611): pass the status to `ping` in a then handler (200) and in the catch (the `PlexError` status, or 0 when the request failed). Reason: in the brief's log the ping answers 404 before the segment does; that line tells a dead server session from a slow network. The interval and the condition do not change.
- `renderer/js/player.js`, `exit()`, after `this.closePop()` (line 560): call `off`. Reason: nothing of the box stays after the player closes; the setting stays as it is.
- `renderer/js/player.js`, `togglePop()`, the quality branch (lines 781 to 783): append one more `t-item` row "Playback stats" with the detail text "Buffer, segments, errors (D)", active when the box is on, that calls the new `toggleStats()`. Reason: the menu already toggles the delivery the stats describe, the row gets the check mark and the TV focus style for free, and no new icon is needed (Decision 2).
- `renderer/js/player.js`, new method `toggleStats()` next to `togglePip()` (line 833): flip the state, save `debugOverlay` with `this.app.saveSettings`, then call `on` or `off`. Reason: one method for the key and the menu row; `saveSettings` writes `settings.json` on Windows and `localStorage` on the TV, so the restart requirement holds without a change to `main.js` or `shim.js`.
- `renderer/js/player.js`, `onKey()`, the `map` (line 932): add `d` (Decision 1). Reason: the brief asks for a key; `d` is free in the map.
- `renderer/index.html`, line 23 and 24: add the script tag for `js/stats.js` between `js/subtitles.js` and `js/player.js`. Reason: the player reads `Sofa.PlayerStats` at load time; `webos/build.js` copies `renderer/` and lowers every file in `js/`, so the TV build needs nothing more.
- `renderer/styles.css`, after `.err-msg` (line 492): the `.p-stats` rules: absolute, left 22 px, top 150 px (under the 140 px title gradient), z-index 7 (above the error screen, so the stats stay readable when playback fails), pointer-events none, dark translucent background, rounded corners, padding, 13 px, tabular numbers, a two-column grid with a muted label column and a value column, max-width about 560 px, values do not wrap. Add `.tile .p-stats` to the hide rule for tiles (line 507). Reason: the box must sit clear of the title, the skip button, the next-episode card and the subtitles, and must take no clicks and no focus; Chromium 68 supports grid and its gap.
- `webos/src/webos.css`, after the `.p-btn[title^="Fullscreen"]` rule (line 16): one rule that raises `.p-stats` to 17 px (the shim zooms by 1.5 at 1920 px, so about 25 px on the screen) and widens the max-width. Reason: readable from the couch; the desktop keeps the small size.
- `README.md`, the "Player keys" table (after line 50): one row for D, "Show or hide the playback stats". Reason: the table lists every player key.

## Files read
- `.dispatch/briefs/player-debug-overlay.md`: the log evidence (404 for a segment and the ping every 35 to 40 s, then "hls session gone, restarting at") and the required value set.
- `%APPDATA%/Buffer Zone/buffer-zone.log`: not readable in the plan run (outside the allowed folders), so the brief's quoted lines stand as the evidence.
- `renderer/js/player.js`: `onHlsError` handles the 404 restart with a 2-per-minute limiter; `startTimers` pings every 30 s with the result dropped; `waiting`/`playing` drive the spinner; `onKey` has no `d` or `i`; the quality popover builds rows with the `item` helper that draws a check mark for the active row; tiles skip keys and controls.
- `renderer/js/ui.js`: `h()` sets `tabIndex` on the TV only for elements with `onclick`, so a plain `div` box is not focusable.
- `renderer/js/app.js`: `saveSettings(patch)` merges into `this.settings` and calls `sofa.setSettings`; new keys need no default (the `pinned` pattern).
- `main.js`: `settings:set` merges the patch into `settings.json` and keeps unknown keys; the renderer's `sofa.log` goes to `buffer-zone.log`.
- `webos/src/shim.js`: `setSettings` writes the merged object to `localStorage`; `sofa.tv` and `sofa.nativeHls` are true, so the TV plays HLS with its own player and hls.js is not used; the layout zoom is `innerWidth / 1280`.
- `webos/src/tvnav.js`: focus candidates are buttons, inputs, selects, links and `[tabindex]`; controls inside `.p-controls` are skipped while the controls are hidden; Back closes the popover first.
- `webos/src/webos.css`: TV-only additions on top of `styles.css`; the right place for a TV font size.
- `webos/build.js`: copies `renderer/` as is, lowers every `js/*.js` to Chromium 68 with esbuild, rewrites `inset` and flex `gap`; a new file in `js/` is included without a change.
- `scripts/build-all.js`: Windows, Android (gradle) and then the webOS build; the webOS step alone is `node build.js` in `webos/`.
- `renderer/styles.css`: player layers are controls 3, skip button and next card 4, popover 5, error screen 6; `.p-top` is 140 px; tiles hide controls with one `!important` rule at line 507.
- `renderer/index.html`: script order puts `ui.js` and `subtitles.js` before `player.js`; the webOS build edits this file by pattern and does not depend on the tag count.
- `renderer/js/multiview.js`: tiles are `Player` instances with `tile: true` and share the constructor.
- `renderer/js/plex.js`: `get` throws a `PlexError` with `status`, so the ping catch can read the HTTP code; `plex.server` has `local` and `relay`.
- `renderer/js/icons.js`: the set has `info` and `gauge` but no activity or chart icon; a new icon needs the generator in `scripts/build-icons.js`.
- `node_modules/hls.js/dist/hls.d.ts` (version 1.7.3): `FRAG_LOADED` gives `frag` and `networkDetails`; `frag.sn`, `frag.duration` and `frag.stats` (`loaded`, `total`, `loading.start`, `loading.end`) exist; `ErrorData` has `details`, `fatal`, `frag`, `response.code`; `Hls.version`, `hls.levels[].bitrate`, `hls.currentLevel` and `hls.bandwidthEstimate` exist.
- `README.md`: a "Player keys" table lists Space/K, arrows, F, M, S, N, Esc.
- `.gitignore`: `dist/`, `webos/build/` and `*.log` are ignored, so the builds leave the tree clean.
- `android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt` and `Playback.kt`: about 850 lines of Kotlin on ExoPlayer with their own controls and settings store; a matching overlay there is separate work (Decision 3).
- `.dispatch/plans/search-recent.md`: the plan format and the build proof that Dispatch accepted last time.

## Steps
1. Read `renderer/js/player.js` again at the anchors above; the line numbers are from the current file.
2. Write `renderer/js/stats.js` with the class, the hooks, `on`, `off`, `attach` and the tick as described.
3. Add the script tag to `renderer/index.html`.
4. Edit `renderer/js/player.js` in this order: the import, the constructor, the `waiting`/`playing` hooks, `play`, `startHls`, `seekTo`, `onHlsError`, `onVideoError`, `fallbackToHls`, `fail`, `startTimers`, `exit`, the quality row, `toggleStats`, the key.
5. Add the `.p-stats` rules and the tile rule to `renderer/styles.css`.
6. Add the TV size rule to `webos/src/webos.css`.
7. Add the README row.
8. Run `node --check renderer/js/player.js` and `node --check renderer/js/stats.js`.
9. Run `node build.js` in `webos/`; confirm the `.ipk` in `dist/`.
10. Run `node scripts/build-all.js`; when a non-webOS step fails on tooling, note it in the report and keep step 9 as the build proof.
11. Run `git status --short`; confirm the six files and nothing else outside `.dispatch/`.
12. Commit the six files on `main` with `player-debug-overlay` in the message.
13. Write the report to `.dispatch/player-debug-overlay.md` with the Verification lines, the manual test list for the tester, and Follow-ups: the Android overlay (Decision 3), a Settings row for the same switch, a key that copies the stats to the log.

## Decisions
1. Which keyboard key toggles the box?
   - Default: D. Recommended: free in the player key map, matches the word "debug" the user used, and one press turns it off again.
   - Other: I, as in mpv. Not recommended: "info" reads like the title info, and the brief calls it debug.
   - Other: Ctrl+Shift+D. Not recommended: hard to press from a couch keyboard and not needed, since a stray press is undone by the same key.
2. Where is the control in the player?
   - Default: a row "Playback stats" at the end of the Quality menu, with a check mark when on. Recommended: no new icon, the control row stays as it is, the stats sit next to the delivery choice they describe, and the remote reaches it like every menu row.
   - Other: a new button with the existing `info` icon in the control row, left of the Quality chip. Not recommended: one more icon in a full row and an icon that already means "details" elsewhere in the app.
3. What about the Android app?
   - Default: not in this task; the report lists it under Follow-ups. Recommended: the Android player is Kotlin on ExoPlayer with its own controls, store and build; a matching overlay is a second implementation with a gradle proof, better as its own ticket.
   - Other: build a matching overlay in `PlayerScreen.kt` and `Playback.kt` in this task. Not recommended: about twice the size, a different code base and a build that needs the Android SDK on this machine.

## Do not touch
- `main.js` and `webos/src/shim.js`: no default for `debugOverlay`; the renderer reads it with a false fallback.
- The 404 restart branch, the 2-per-minute limiter, `recoverMediaError`, the ping interval and the spinner handling in `renderer/js/player.js`: one counter or record line stands beside each; no condition changes.
- `renderer/js/plex.js`: no new or changed request to the server.
- The hls.js configuration in `startHls` (`maxBufferLength`, `backBufferLength`, `startPosition`).
- `webos/src/tvnav.js`: the box has no focusable element, so the remote logic needs nothing.
- `renderer/js/icons.js` and `scripts/build-icons.js`: no new icon.
- `renderer/js/settings.js`: no Settings row; the player holds the control (Follow-ups).
- `renderer/js/multiview.js`: tiles get no box; the guard in `on()` and the CSS tile rule cover it.
- `android/`: per Decision 3.

## Size
6 files: `renderer/js/stats.js` (new, about 120 lines), `renderer/js/player.js` (about 20 added or changed lines), `renderer/styles.css` (about 6 lines), `webos/src/webos.css` (1 line), `renderer/index.html` (1 line), `README.md` (1 line).

## User decisions

The user took these decisions in Dispatch on 2026-11-14 23:56. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Which keyboard key toggles the box?: D
2. Where is the control in the player?: a row "Playback stats" at the end of the Quality menu, with a check mark when on
3. What about the Android app?: not in this task; the report lists it under Follow-ups
