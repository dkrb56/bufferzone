state: done
needs: nothing
files: renderer/js/stats.js, renderer/js/player.js, renderer/index.html, renderer/styles.css, webos/src/webos.css, README.md
plan: .dispatch/plans/player-debug-overlay.md

# Report player-debug-overlay (2026-11-15 00:04 CEST)

## What changed
The player has a playback stats box now. Press D on a keyboard, or select "Playback stats" at the end of the Quality menu, to show or hide it. The app saves the choice, so the box comes back after a restart of the app. All builds pass. Nobody played a video with the box yet.

- The box shows 8 lines: Delivery, Server, Video, Buffer, Segment, Bitrate, Stalls, Last error. It updates two times each second.
- Delivery shows Direct Play, or Converted with the quality and the subtitle mode.
- Server shows the connection (local network, direct internet or Plex relay), the first 6 characters of the transcode session and the player engine.
- Video shows the picture size and the dropped frames of the total frames.
- Buffer shows the seconds of video ahead of the play position.
- Segment shows the last video segment: number, size in MB, load time in ms, age in seconds, and the last HTTP status of the stream. On direct play and on the TV player, it shows "n/a".
- Bitrate shows the stream bitrate and the network estimate of hls.js, or the file bitrate on direct play.
- Stalls shows the stall count, the stalled seconds, the session restarts, and the last session ping with its HTTP status and clock time.
- Last error shows the clock time and the text of the last error: the hls.js error name, the HTTP code, the segment number and "fatal".
- The box sits top left under the title area. It takes no clicks and no focus, so the mouse and the TV remote work as before.
- When the box is off, no timer, no screen update and no hls.js listener runs. Only the counters change, in code that runs already.
- Multi-view tiles never show the box.
- On the TV the text is bigger (17 px before the 1.5 zoom).
- The README key table has a row for D.
- Commit: `23efce8` on `main`, not pushed.

Small differences from the plan:
- On the TV, a video element error goes to the restart code with a stand-in 404. The stats record the real video error after that call. Thus the box shows no false "HTTP 404" on the TV.
- The Segment line shows "last HTTP n". This is the last HTTP status of any stream request: a loaded segment (200) or an hls.js error with a code (for example 404). The plan named only the segment status.
- A ping without an HTTP answer shows "ping failed".

## Why
The user sees a spinner every 35 to 40 s and cannot tell a slow network from a server fault. The app log shows HTTP 404 for a segment and for the session ping, then a session restart. The box shows these facts while the video plays:
- Many stalls with HTTP 200 segments, a low network estimate and a low buffer point to the network.
- HTTP 404, "ping HTTP 404" and a restart count that goes up point to the server: it ended the transcode session.
- Many dropped frames with a full buffer point to the computer or the TV.

## Files
- `renderer/js/stats.js` (new): the class `PlayerStats` with the counters, the hooks, the box and the update every 500 ms.
- `renderer/js/player.js`: creates the stats object, calls the hooks at the spinner, at a new hls.js session, at a seek, at each error, at the 404 restart, at the ping, at exit. Adds the Quality menu row, `toggleStats()` and the D key.
- `renderer/index.html`: loads `js/stats.js` before `js/player.js`.
- `renderer/styles.css`: the `.p-stats` rules, and the tile rule that hides the box.
- `webos/src/webos.css`: the bigger TV text size.
- `README.md`: the D row in the player key table.

## Verification
- `node --check renderer/js/player.js; echo "exit $?"`: printed `exit 0`.
- `node --check renderer/js/stats.js; echo "exit $?"`: printed `exit 0`.
- `node "C:/Users/dani/.claude/jobs/dcdac26d/tmp/stats-smoke.js" renderer/js/stats.js; echo "exit $?"`: a scratch smoke script outside the repo, with a fake player, a fake DOM and a fake hls.js. It printed all 8 lines in three modes (converted with hls.js, direct play, TV player) and `OK: all stats checks passed`, `exit 0`. It checks: the first load is no stall, one spinner counts once, a user seek is no stall, the box off has no element and no listener, the box on adds both, subtitle segments do not change the Segment line, off removes element, timer and listener, a tile gets no box. The script is not in the repo; the job folder deletes it later.
- `node build.js; echo "exit $?"` (in `webos/`): printed `Create com.dkrb56.bufferzone_0.2.9_all.ipk to D:\dev_projects\buffer-zone\dist`, `Success` and `exit 0`.
- `ls -la ../dist/com.dkrb56.bufferzone_0.2.9_all.ipk && grep -c "stats.js" build/app/index.html && grep -o "PlayerStats" build/app/js/player.js | head -2 && grep -n "p-stats" build/app/webos.css build/app/styles.css | head` (in `webos/`): the `.ipk` exists (271706 bytes); the TV `index.html` has the `stats.js` tag; the lowered TV `player.js` has `PlayerStats`; the TV styles have all `.p-stats` rules.
- `node scripts/build-all.js > "C:/Users/dani/.claude/jobs/dcdac26d/tmp/build-all.log" 2>&1; echo "exit $?" >> "C:/Users/dani/.claude/jobs/dcdac26d/tmp/build-all.log"`: the log ends with the 6 files `BufferZone-0.2.9-setup.exe`, `BufferZone-0.2.9-setup.exe.blockmap`, `latest.yml`, `BufferZone-0.2.9-portable.exe`, `BufferZone-tv-0.2.9.apk`, `com.dkrb56.bufferzone_0.2.9_all.ipk` and `exit 0`. Gradle printed `BUILD SUCCESSFUL`; all Android tasks were up to date, because no Android file changed.
- `git status --short`: before the commit, the six files and only `.dispatch/` files changed. After the commit, only `.dispatch/` files are open.

Nobody tried these things. A tester must do them:
- Nobody played a video with the box on, on Windows or on a TV.
- Nobody saw a real server 404 or a real stall with the box on.
- Nobody checked the box size and place on a real TV screen from the couch.
- Nobody checked that the TV remote never stops on the box.
- Nobody checked the box over the "Playback failed" screen.

Manual test for the tester (Windows):
1. Set Quality to 8 Mbps and play a title.
2. Press D. A dark box appears top left, under the title area.
3. Look for 5 s. The Buffer line and the segment age change at least once each second. The Segment line shows a number, MB, ms and "last HTTP 200".
4. Open the Quality menu. The last row "Playback stats" has a check mark.
5. Disconnect the network for 15 s, then connect it again. The Stalls count goes up. The Last error line shows a clock time and an hls.js error name. When the server ended the session, the line shows HTTP 404 and the restarts go up by one.
6. Press D. The box goes away completely. The menu row has no check mark.
7. Press D again. Close the app, start it again and play a title. The box is there from the first second.
8. Open Multi-view and add a stream. The tiles show no box.

Manual test for the tester (TV):
1. Play a title. Press down to show the controls.
2. Go to the Quality chip, press OK, go down to "Playback stats", press OK. The box appears and the menu closes.
3. Push the arrows in all directions. The focus never goes to the box.
4. The Segment line shows "n/a (TV player)".
5. Back closes the menu and the player as before.

## Follow-ups
- Android TV: no stats box in the Android app (Decision 3). This is a separate ticket.
- A Settings row for the same switch (not in this task).
- A key that writes the current stats into the app log (not in this task).
- A quality, audio or subtitle change during playback reloads the stream outside `seekTo`. By the HTML media rules, `play()` on an empty video element sends a `waiting` event. Thus such a change probably counts as one stall. One `expectWait()` call at the start of `load()` in `renderer/js/player.js` prevents it. The plan did not include it, so the code does not have it.
- The full build wrote new `0.2.9` installers and a new `latest.yml` into `dist/`. Release 0.2.9 exists already. Do not upload these files as 0.2.9; set a new version in `package.json` before the next release.
