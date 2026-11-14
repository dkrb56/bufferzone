# player-debug-overlay: togglable debug overlay in the player

## User's words
"is there an option to add some togglable debug overlays to see if the servers are at fault"
Context from the same message: "the app keeps loading the video im watching now. it plays for a couple of
seconds then start loading again. it may be that the servers are at fault."

## Evidence from the app log (%APPDATA%/Buffer Zone/buffer-zone.log, 2026-11-14 22:41 to 22:44 UTC)
- Every 35 to 40 s the server answers 404 for a segment and for the session ping, the app logs
  `hls session gone, restarting at <seconds>` and starts a new transcode session. The user sees only a spinner
  and cannot tell a slow network from a dead server session.

## Constraints
- The user can switch the overlay on and off while a video plays (a key and a control in the player; the plan run picks them) and the choice survives a restart of the app.
- The overlay shows what tells a server fault from a client fault; the plan run decides the exact set, at least: delivery mode (direct or converted) and quality, buffer ahead in seconds, current bitrate or segment size, segment load time and the last HTTP status, the count of stalls and of session restarts since the start, dropped frames, and the last error line with its time.
- The overlay is readable on a TV from the couch and does not take the focus of the TV remote.
- The overlay does not change playback and costs nothing when it is off.
- The webOS build copies renderer/. The Android app is native Kotlin with its own player: the plan run makes Android a decision.
- No new dependency. No change to the Plex requests. Stay inside the request: no change to the restart or stall logic.

## Proof
- `node --check renderer/js/player.js` exits 0 (and every other file the plan changes).
- `node scripts/build-all.js` builds without an error (or the webOS step alone if the full build needs tooling that is missing).
- Manual, for the tester: play a converted title, switch the overlay on: the values update at least once a second; let the stream stall: the stall count and the last error line change; switch it off: nothing of it stays on screen; restart the app: the overlay is in the state you left it.
