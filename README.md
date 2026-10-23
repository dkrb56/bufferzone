# Buffer Zone

A clean Windows player for a friend's Plex server.

## Start

Double-click `Start Buffer Zone.bat`, or run `npm start` in this folder.

First start: click **Sign in with Plex**. Your browser opens plex.tv. Sign in there. The app gets a token and never sees your password.

## Features

- Home shows only the server you choose: Continue Watching and Recently Added per library.
- Library pages: sort, genre filter and "unwatched only". The app remembers the filter per library.
- Instant search (Ctrl+F) for movies, series and episodes.
- Quality: Direct Play (original file) or a fixed bitrate. If a file cannot play directly, the app switches to a converted stream.
- Audio and subtitle selection in the player. The app remembers your choice per series.
- Subtitle style: size, color, background and position (Settings).
- Skip intro / skip credits buttons (optional automatic skip).
- Next-episode countdown and auto-play.
- Watch progress and watched status sync to the server.

## Player keys

| Key | Action |
| --- | --- |
| Space / K | Play / pause |
| ← / → | Back 10 s / forward 30 s |
| ↑ / ↓ | Volume |
| F | Fullscreen |
| M | Mute |
| S | Skip intro / credits |
| N | Next episode |
| Esc | Leave fullscreen, then leave the player |

## Files

- Settings and token: `%APPDATA%\Buffer Zone\` (token is encrypted with Windows DPAPI).
- Log for troubleshooting: `%APPDATA%\Buffer Zone\buffer-zone.log`. Tokens are masked.
