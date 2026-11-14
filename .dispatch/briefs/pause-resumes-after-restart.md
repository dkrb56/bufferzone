# pause-resumes-after-restart: a paused video starts to play again after a stream restart

## User's words
"the app keeps loading the video im watching now. it plays for a couple of seconds then start loading again.
it may be that the servers are at fault. another issue is that i paused the video and after few seconds
i still se loading indicator and it unpaused and start playing video again. even though i paused it to wait
until it loads enough video in the buffer."

## Evidence from the app log (%APPDATA%/Buffer Zone/buffer-zone.log, 2026-11-14 22:41 to 22:44 UTC)
- Every 35 to 40 s: six `hls error fragLoadError 404` on the same segment, the ping of the session answers 404, then `fragLoadError fatal`, then `[renderer] hls session gone, restarting at <seconds>`, a new decision and a new session.
- The session restart path in renderer/js/player.js ends with `this.video.play()` (line 310 in the start path, line 595 in a restart path). Neither path looks at whether the user had paused.
- The repeated session loss itself looks like a server fault (the ping gets 404 before the fatal error). That part is not this ticket; the ticket player-debug-overlay gives the user a way to see it.

## Constraints
- A video that the user paused stays paused through a stall, a session restart and a quality or subtitle restart. The spinner may show while the app recovers, but playback starts again only when the user presses play.
- The plan run reproduces the behaviour first (log lines or a code path with a fresh measurement) and states the cause with its evidence before it plans the change.
- The Plex session keep-alive (timeline and ping while paused, see .claude/memory.md) keeps working while paused.
- The webOS build copies renderer/; the TV player must keep its behaviour. The Android app is native Kotlin: the plan run checks whether its player has the same restart path and makes it a decision.
- No new dependency. Stay inside the request: the restart logic itself and the stall cause are not part of this ticket; note them in the report.

## Proof
- `node --check renderer/js/player.js` exits 0.
- `node scripts/build-all.js` builds without an error (or the webOS step alone if the full build needs tooling that is missing).
- A scripted check, when the plan run finds a way without a real stream: a paused player receives the session-gone restart and stays paused.
- Manual, for the tester: play a converted title, pause it, wait for a restart (the log line `hls session gone`): the video stays paused and the spinner goes away; press play: it plays from the paused position.
