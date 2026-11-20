state: done
needs: nothing
files: android/app/src/main/AndroidManifest.xml, android/app/src/main/java/com/dkrb56/bufferzone/SettingsScreen.kt, android/app/src/main/java/com/dkrb56/bufferzone/Updater.kt
plan: .dispatch/plans/android-tv-install-updates-from-inside.md

## What changed
- On Android TV, the row "Version X is available" in Settings → Account now shows "Update". The browser link is gone.
- One press downloads `BufferZone-tv-<X>.apk` of release `v<X>` from GitHub. The row shows "Downloading N%".
- Then the Android install screen asks to confirm.
- When "Install unknown apps" is off, the app opens that TV setting and shows a message. The user then presses Update again.
- A failed download shows "Download failed. Try again later." and the row goes back to "Update". A failed install shows "Update failed: <reason>".
- Commit: 7ec95bd on `main`.

## Why
Many TVs have no browser, so the old Open button could not update the app.

## Files
- `AndroidManifest.xml`: permission `REQUEST_INSTALL_PACKAGES` and the receiver `UpdateReceiver`.
- `Updater.kt` (new): `Updater` (download into a `PackageInstaller` session, progress state) and `UpdateReceiver` (starts the confirm screen, reports failures).
- `SettingsScreen.kt`: the update row.

## Verification
- `cd android && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug` ended with `BUILD SUCCESSFUL` (also for the 0.2.0 test build).
- `grep -rl "android.permission.REQUEST_INSTALL_PACKAGES" android/app/build/intermediates/merged_manifests/debug` printed one path.
- `grep -c "ACTION_VIEW" .../SettingsScreen.kt` printed `0`.
- `git diff --stat HEAD -- AppState.kt renderer webos main.js preload.js package.json` printed nothing.
- `git status --short` before the commit showed only the three task files plus `.dispatch/` files. The commit holds only the three files.
- Check "android/gradle tests" was not chosen. The project has no Android unit tests.
- Manual test: not finished. I built a 0.2.0 build (`package.json` changed for that build only, then restored; `git diff --stat package.json` is empty) and installed it on the emulator `emulator-5554`. The app stopped at "Sign in with Plex". I have no Plex account, so I could not reach Settings. I removed the test install from the emulator.

## Deviations
- I did not install on the real TV `192.168.50.60` (Philips Android TV, API 30, with Buffer Zone 0.1.0). That would replace the app of the user.
- The progress shows "Downloading…" without a number when the server sends no size (Note 10 of the plan).

## Follow-ups
- Nobody tried the update flow itself. A tester must run the two manual tests of the plan on a TV or on a signed-in device: the install, and the failed download with the cable pulled. The "Install unknown apps" screen, the confirm screen and the signing match are untested.
- The feature works only after a release: bump the version in `package.json`, build and publish a release with `BufferZone-tv-<version>.apk`. The version that brings this button must still be installed the old way once.
- The release APK is a debug build signed with the debug key of the build machine. An APK built on another machine has another key, and the install then fails with a conflict. Keep one build machine for releases.
- The app cannot update itself on a TV that has Buffer Zone from a different key (for example 0.1.0 on the Philips TV, if it came from another machine).
