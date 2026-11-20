# Plan when-i-add-show-to-wathclist-there: put a title into a folder right when you add it to the watchlist

## Summary
On the series and movie pages, a folder button sits next to the bookmark while the title is on the watchlist, and the folder list opens right after you add it.
4 files change: `renderer/js/views.js`, `webos/src/tvnav.js`, `android/.../DetailScreens.kt`, `android/.../WatchlistScreen.kt`.
2 decisions, none is HOLD.

## Goal
The screenshot shows the series page of "FROM" with Play, Mark series watched, the bookmark (watchlist) button and the pin button. Today a title goes into a folder only from the Watchlist page (folder button per row, or drag onto a folder chip). After this task, the series page and the movie page offer the folder choice at the moment of the add: with Decision 1 Default, the folder list ("Folders", a check per folder, "New folder…") opens under the new folder button right after the bookmark click adds the title, and the folder button stays next to the bookmark while the title is on the watchlist. The same holds on Windows, the LG TV (same renderer code) and Android TV (its own Kotlin screens), per the project rule that every feature goes to all three platforms.

## TL;DR
- When you add a series or movie to your watchlist, the folder list opens so you can put it in a folder at once.
- A folder button stays next to the bookmark, so you can change the folders later from the same page.
- It works the same on Windows, the LG TV and Android TV.

## Acceptance
- `node --check renderer/js/views.js` exits 0 and prints nothing.
- `node --check webos/src/tvnav.js` exits 0 and prints nothing.
- `node scripts/build-all.js` ends with the list of the six files of version 0.2.10 and no error. Its Android step (`gradlew.bat assembleDebug`) is the compile proof for the two Kotlin files; its webOS step proves the TV build.
- `git status --short` before the commit lists only the four changed files as modified (plus the untracked `.dispatch/` files that were there before); `dist/` and the Android build output are ignored.
- Manual, for the tester (Windows): create a test folder named "zz test" on the Watchlist page first (do not touch existing folders). Open a series that is not on the watchlist. Click the bookmark: the toast says "Added to watchlist", the bookmark turns on, a folder button appears next to it, and the folder list opens under that button. Click "zz test": the list closes, and the folder button shows as on. Open the Watchlist page: the title shows "zz test" under its name. Back on the series page, click the bookmark again: the title leaves the watchlist and the folder button goes away. Repeat once on a movie page. Then delete only the "zz test" folder.
- Manual, for the tester (LG TV): same flow with the remote. After OK on the bookmark, the arrows reach the folder list; OK on a folder puts the title in it; Back with the list open closes only the list and stays on the page.
- Manual, for the tester (Android TV): same flow; the folder list is a dialog with "Done"; Back closes it.

## Checks
- [ ] android/gradle tests — `gradle` is not on PATH on this machine (`which gradle` finds nothing) and the project has no Android unit tests; the Android step of `node scripts/build-all.js` compiles the changed Kotlin instead.

## Files to change
- `renderer/js/views.js`, near `P.folderMenu` (line 541): a new helper method that builds the folder button for a detail page: `iconBtn('folder', …)` with class `boxed`, plus ` on` when any folder holds the title's guid, title "Folders", and an onclick that calls `this.folderMenu(button, guid, onChange)`; `onChange` only updates the `on` class of that button. It also carries a marker class (for example `wl-folder`) so the page can find it after a render. Reason: one helper for both pages; no full page render after a folder pick, because the series page render reloads three server requests.
- `renderer/js/views.js`, a second new helper for the bookmark click: note whether the title was on the watchlist, `await this.toggleWatchlist(item)`, `await this.render()`, then, when the title is now on the watchlist and the rule of Decision 2 allows it, find the marker button in `this.main` and open `this.folderMenu` on it. Reason: `render()` replaces the whole view, so the menu must anchor on the new button after the render, not on the clicked one.
- `renderer/js/views.js`, `S.views.movie`, the line `iconBtn(this.inWatchlist(m) ? 'bookmark-check' …` (line 326): onclick uses the bookmark helper; right after it, the folder button helper when `this.inWatchlist(m)`. Reason: the movie page has the same bookmark.
- `renderer/js/views.js`, `S.views.show`, the line `iconBtn(inWl ? 'bookmark-check' …` (line 373): same two changes for `show`. Reason: this is the page of the screenshot.
- `webos/src/tvnav.js`, `function back()` (line 110), after the `.modal-bg` block: when a `.menu-pop` element exists, remove it and return. Reason: the folder list is appended to `document.body` and closes only on a mouse click outside; on the TV, Back would otherwise leave the page and leave the list floating on the next page.
- `android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt`, `private fun FolderMenu(guid: String, onDismiss: () -> Unit)` (line 168): drop `private`. Reason: the detail screens must call the same folder dialog.
- `android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt`, `MovieScreen`, the row with `IconBtn(if (inWl) Icons.Outlined.Bookmark …` (line 83), and the series screen, the same line (line 177): a `remember` flag for the folder dialog; the bookmark click launches `App.toggleWatchlist(x)` and afterwards sets the flag when the title is now on the watchlist and Decision 2 allows it; a folder `IconBtn(Icons.Outlined.Folder, …, on = <title in any folder>)` after the bookmark while `inWl`; `if (flag) FolderMenu(guid) { flag = false }` in the screen. Add the import `androidx.compose.material.icons.outlined.Folder`. Reason: Android TV has its own screens and the same folder store.

## Files read
- `renderer/js/views.js`: folders live in settings as `watchlistFolders: [{ id, name, guids }]`; `setInFolder`, `newFolder` and `folderMenu` already exist; only the Watchlist page row calls `folderMenu`.
- `renderer/js/views.js` lines 326 and 373: both bookmark buttons call `await this.toggleWatchlist(x); this.render();`.
- `renderer/js/app.js`: `toggleWatchlist` updates `watchGuids` and shows the toast; `render()` is async, replaces `this.main`'s view and resolves after the view function has drawn.
- `renderer/js/app.js`: `inWatchlist(item)` checks `item.guid` against `watchGuids`; server items and watchlist items share the `plex://` guid, which is the key that folders store.
- `renderer/styles.css` line 289: `.menu-pop` is `position: fixed` with z-index 60; `.t-item` styles the rows.
- `webos/src/tvnav.js`: the layer function returns `document.body` for normal pages, so a `.menu-pop` on the body is reachable with the arrows; `back()` knows no `.menu-pop`.
- `android/.../AppState.kt`: `toggleWatchlist` is a suspend function; `watchGuids` is Compose state; `newFolder` and `setInFolder` write `store.folders`.
- `android/.../WatchlistScreen.kt`: `FolderMenu` is a `BzDialog` with a check per folder, "New folder…" and "Done"; it is private.
- `android/.../DetailScreens.kt`: both screens read `App.store.version`, so a folder change recomposes them.
- `scripts/build-all.js`: builds Windows, then `gradlew.bat assembleDebug` with `JAVA_HOME` set to Android Studio's JBR, then webOS, and checks six files in `dist/`.
- `.claude/memory.md`: every feature goes to all three platforms; the version bump and release follow after a task.
- `.claude/mistakes.md`: tests must never overwrite the user's folders; add test data and remove only that.

## Notes for the work run
1. The guid to store is `item.guid` of the server item on the page (`m.guid`, `show.guid`); it equals the watchlist guid. A title with a non-`plex://` guid never reaches the watchlist, so the folder button never shows for it.
2. `toggleWatchlist` on remove does not take the title out of its folders (the Watchlist page row does). Leave it; re-adding then shows the old folder checks. Note it under Follow-ups.
3. `folderMenu` removes any open `.menu-pop` first and closes on a mousedown outside; its onChange runs after a pick or after "New folder…".
4. Do not open the list when the bookmark click removed the title, or when `toggleWatchlist` failed (its toast says "Watchlist failed").
5. Check `alive`-style safety: if the user leaves the page before `render()` resolves, the marker button is not found; then open nothing.
6. On the TV, `render()` removes the focused bookmark; `tvnav.js` then starts near the last place, so the arrows reach the list. Do not add focus code unless the manual test shows a problem.
7. Android: set the flag from the coroutine after `App.toggleWatchlist(x)` returns, by checking `App.inWatchlist(x)`; Compose state may be set from `App.scope`.
8. The user's real settings hold real folders (`.claude/mistakes.md`). Manual tests use a new test folder and remove only that folder.
9. `node scripts/build-all.js` runs electron-builder, which needs a few minutes; a missing tool is reported, not worked around.
10. Do not bump the version in `package.json` and do not push or publish; CLAUDE.md forbids the worker to push. List the release under Follow-ups.

## Steps
1. Add the two helpers in `renderer/js/views.js` and use them in `S.views.movie` and `S.views.show`.
2. Add the `.menu-pop` close to `back()` in `webos/src/tvnav.js`.
3. Drop `private` from `FolderMenu` in `WatchlistScreen.kt`.
4. Add the folder button, the flag and the dialog to both screens in `DetailScreens.kt`, with the import.
5. Run `node --check renderer/js/views.js` and `node --check webos/src/tvnav.js`.
6. Run `node scripts/build-all.js`.
7. Run the Windows manual test from Acceptance with a test folder; remove only that folder after.
8. Run `git status --short`, commit the four files by name on `main` with `when-i-add-show-to-wathclist-there` in the message.
9. Write the report `.dispatch/when-i-add-show-to-wathclist-there.md`, with the TV and Android manual tests listed as not tried when no device was used, and the Follow-ups (Note 2, version bump and release).

## Decisions
1. How should the folder choice show up when you add a title to the watchlist?
   - Default: The folder list opens by itself, and a folder button stays next to the bookmark. Recommended: you pick a folder in the same click flow, and can change it later on the same page.
   - Other: Only a folder button next to the bookmark; nothing opens by itself. Not recommended: one more click every time, and easy to forget.
   - Other: The folder list opens by itself; no extra button. Not recommended: to change a folder later you must go to the Watchlist page.
2. Should the folder list also open by itself when you have no folders yet?
   - Default: No, only when you have at least one folder. Recommended: people who do not use folders are not interrupted; the folder button still offers "New folder".
   - Other: Yes, always; it then offers "New folder". Not recommended: an extra window after every add for people without folders.

## Do not touch
- The Watchlist page, the home page and the folder chips.
- `renderer/js/app.js` (`toggleWatchlist`), `renderer/js/plex.js`, `main.js`, `webos/src/shim.js`.
- `renderer/styles.css`: the existing `.menu-pop`, `.t-item` and `boxed` button styles are enough.
- `android/.../AppState.kt` and `Store.kt`.
- The version in `package.json`, the release and any push.

## Size
4 files: `renderer/js/views.js` (about 25 lines), `webos/src/tvnav.js` (about 3 lines), `DetailScreens.kt` (about 15 lines), `WatchlistScreen.kt` (1 word).

## Work run
model: sonnet
effort: medium
The change is small and reuses the existing folder list, but it touches two languages and must pass the full build of all three apps.
