state: done
needs: nothing
files: renderer/js/player.js
plan: .dispatch/plans/playback-issue-not-sure-why.md

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
