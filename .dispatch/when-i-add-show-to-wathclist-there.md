state: done
needs: nothing
files: renderer/js/views.js, webos/src/tvnav.js, android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt, android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt
plan: .dispatch/plans/when-i-add-show-to-wathclist-there.md

## What changed
- Series and movie pages show a folder button next to the bookmark while the title is on the watchlist.
- After you add a title, the folder list opens by itself when at least one folder exists (Decisions 1 and 2).
- On the LG TV, Back closes an open folder list and stays on the page.
- On Android TV, the same flow uses the existing folder dialog.

## Why
The user wanted to put a title in a folder when adding it to the watchlist.

## Files
- `renderer/js/views.js`: new `wlFolderBtn` and `wlToggleAndOffer`; used on the movie and show pages.
- `webos/src/tvnav.js`: `back()` removes `.menu-pop`.
- `WatchlistScreen.kt`: `FolderMenu` is no longer private.
- `DetailScreens.kt`: folder button, dialog flag and dialog on both screens; import `Outlined.Folder`.

## Verification
- `node --check renderer/js/views.js`: exit 0, no output.
- `node --check webos/src/tvnav.js`: exit 0, no output.
- `node scripts/build-all.js`: exit 0. Gradle `assembleDebug` reported BUILD SUCCESSFUL. All six files of 0.2.10 were listed.
- Commit afc276c holds only the four files.
- Check "android/gradle tests" was not chosen. It was not run.

## Follow-ups
- Removing a title from the watchlist on a detail page keeps its folder membership. Re-adding shows the old folder checks.
- Version bump and release are not done.

## Not tried
- The Windows manual test (test folder "zz test", series and movie page) was not run. It needs a Plex server and the app UI.
- The LG TV test (remote, arrows reach the list, Back closes only the list) was not tried. No device.
- The Android TV test (dialog, Done, Back) was not tried. No device. The code compiles only.
- The case where the user leaves the page before the render ends was not tested.
