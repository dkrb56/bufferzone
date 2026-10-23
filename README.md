# Buffer Zone

A clean Windows player for a friend's Plex server.

## Start

Double-click `Start Buffer Zone.bat`, or run `npm start` in this folder.

First start: click **Sign in with Plex**. Your browser opens plex.tv. Sign in there. The app gets a token and never sees your password.

## Features

- Icon rail navigation: Home, Search, Movies, Series, Kids, Watchlist, Multi-view, pinned items and Settings.
- Home shows only the server you choose: Continue Watching and Next Up on the left, your watchlist on the right. Tabs filter by library, folder chips filter the watchlist. ↑/↓ and Space play from the list.
- Watchlist folders: make your own folders and put titles in them. Drag a title onto a folder, or use the folder button on a title. A title can be in more than one folder. Folders are saved on this computer, because Plex has no watchlist folders.
- Library pages: sort, genre filter, "unwatched only", grid or list view, collections and an A–Z letter rail. The app remembers the filter per library.
- Search palette (Ctrl+F or /) for movies, series and episodes. Enter opens, Ctrl+Enter plays.
- Watchlist from your Plex account, matched to titles on the server. Add or remove titles from detail pages.
- Pin series, movies or collections to the sidebar.
- Multi-view: up to four streams at once. Click a tile to move the audio to it.
- Plex Home profiles ("Who's watching?") with parental controls per managed profile: rating limit, unrated titles, libraries, PIN to leave and Multi-view.
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

## Development

- Icons are Lucide SVGs, built into `renderer/js/icons.js` by `node scripts/build-icons.js`. Add a name to the list in that script to use a new icon.
- Colors and sizes are CSS variables at the top of `renderer/styles.css`.

## Files

- Settings and token: `%APPDATA%\Buffer Zone\` (token is encrypted with Windows DPAPI).
- Log for troubleshooting: `%APPDATA%\Buffer Zone\buffer-zone.log`. Tokens are masked.
