# Android TV: install updates from inside the app (android-tv-install-updates-from-inside) — 2026-11-20

## Plan approved
# Plan android-tv-install-updates-from-inside: Android TV downloads and installs a new version itself

## Summary
On Android TV, the "Version X is available" row in Settings → Account gets an Update button: the app downloads the new APK and opens the Android install screen.
3 files change: `AndroidManifest.xml`, `SettingsScreen.kt`, and the new `Updater.kt`.
3 decisions, none is HOLD.

## Goal
Today the row "Version X is available" in Settings → Account (`SettingsScreen.kt` line 203) has the button "Open", which opens `github.com/dkrb56/bufferzone/releases/latest` in a browser; many TVs have no browser, so the user cannot update. After this task, the row says "Update". One OK press downloads `BufferZone-tv-<X>.apk` of release `v<X>` from GitHub, while the row shows the progress, and then the Android install screen asks to confirm. When "Install unknown apps" is off for Buffer Zone, the app first sends the user to that setting (Decision 3). The update check itself, the start-up note, the webOS app and the desktop app stay as they are.

## TL;DR
- On Android TV, the "new version" row in Settings gets an Update button instead of a link to a web page.
- One press downloads the new version, and the TV asks you to confirm the install.
- The first time, the TV may ask you to allow "Install unknown apps" for Buffer Zone.
- This works from the next version on; the version that brings this button you still install the old way once.

## Acceptance
- `cd android && JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat assembleDebug` ends with `BUILD SUCCESSFUL`.
- `grep -rl "android.permission.REQUEST_INSTALL_PACKAGES" android/app/build/intermediates/merged_manifests/debug` prints one path (the permission is in the built app).
- `grep -c "ACTION_VIEW" android/app/src/main/java/com/dkrb56/bufferzone/SettingsScreen.kt` prints `0` (the browser link is gone; with Decision 2 Other it prints `1`).
- `git diff --stat HEAD -- android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt renderer webos main.js preload.js package.json` prints nothing (update check, desktop, webOS and version are untouched).
- `git status --short` before the commit lists only the three files of this task as changed or new, plus the `.dispatch/` files that were there before.
- Manual, for the tester (Android TV or Google TV with network debugging on): the newest GitHub release must be newer than the build on the TV. Install a build of this code with a lower version: set `"version"` in `package.json` to `0.2.0`, run the Gradle command above, `adb install -r -d android/app/build/outputs/apk/debug/app-debug.apk`, then put the old `package.json` back at once (`git checkout -- package.json`). Open Buffer Zone → Settings → Account → Version, press OK: the row "Version <latest> is available" appears with "Update". Press OK: when "Install unknown apps" is off, the TV setting for Buffer Zone opens; allow it, press Back, press Update again. The row shows "Downloading …%", then the Android screen asks to update Buffer Zone; confirm. Open Buffer Zone again: Settings → Account → Version shows the latest release version, and pressing OK says "You have the newest version."
- Manual, for the tester: with the network cable pulled after pressing Update, the app shows "Download failed. Try again later." and the row goes back to "Update".

## Checks
- [ ] android/gradle tests — `gradle` is not on PATH on this machine and the project has no Android unit tests; the `assembleDebug` command under Acceptance compiles the changed Kotlin instead.

## Files to change
- `android/app/src/main/AndroidManifest.xml`, after the line `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`: add `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`. Inside `<application>`, after the `</activity>`: a `<receiver android:name=".UpdateReceiver" android:exported="false" />`. Reason: the install needs the permission, and the install result comes back to that receiver.
- `android/app/src/main/java/com/dkrb56/bufferzone/Updater.kt` (new): `object Updater` with Compose state `progress: Int?` (null = idle, 0–100 = downloading) and `fun install(ctx: Context, version: String)`. It (1) on API 26+ checks `packageManager.canRequestPackageInstalls()` and, when false, follows Decision 3 and returns; (2) on `App.scope` + `Dispatchers.IO` opens a `PackageInstaller` session (`MODE_FULL_INSTALL`), GETs `https://github.com/dkrb56/bufferzone/releases/download/v<version>/BufferZone-tv-<version>.apk` with `App.plex.http` and streams the body into `session.openWrite("base.apk", 0, contentLength)`, updating `progress` from the bytes read; (3) `fsync`, closes the stream and commits with a `PendingIntent.getBroadcast` to `UpdateReceiver`; on any error it abandons the session, sets `progress = null` and shows "Download failed. Try again later.". In the same file `class UpdateReceiver : BroadcastReceiver`: on `STATUS_PENDING_USER_ACTION` it starts the `Intent.EXTRA_INTENT` activity with `FLAG_ACTIVITY_NEW_TASK`; on a failure status it sets `Updater.progress = null` and shows "Update failed: <EXTRA_STATUS_MESSAGE>" with `App.show`. Reason: one new file keeps the install code out of the settings screen; the session streams the file straight to the installer, so no FileProvider and no file paths XML are needed.
- `android/app/src/main/java/com/dkrb56/bufferzone/SettingsScreen.kt`, the item `App.updateVersion?.let { v -> item { SettingRow("Version $v is available", "Download: github.com/dkrb56/bufferzone/releases/latest", "Open", {` (lines 203–206): the hint becomes "Downloads and installs it. The TV asks you to confirm."; the value is `Updater.progress?.let { "Downloading $it%" } ?: "Update"`; the click calls `Updater.install(ctx.applicationContext, v)` unless `Updater.progress != null`. With Decision 2 Other, the old row with "Open" stays as a second row under it, with the label "Open the download page". Reason: this is the row that the brief names.

## Files read
- `android/.../AppState.kt` line 117: `checkUpdate()` reads `releases/latest`, compares `tag_name` without `v` to `appVersion`, sets `App.updateVersion`; it keeps no asset URL.
- `android/.../AppState.kt` line 135: the start-up note says "Buffer Zone $it is available. See Settings → Account." 5 s after start.
- `android/.../SettingsScreen.kt` lines 196–206: the Version row runs the check; the update row opens the browser with `ACTION_VIEW` and shows "Open the address on a phone or computer." when that fails.
- `android/.../SettingsScreen.kt` line 82: `ctx = LocalContext.current` is the activity.
- `android/.../Plex.kt` line 165: `http` has connect timeout 10 s, read timeout 25 s per read, no call timeout, and follows redirects (OkHttp default).
- `android/.../Ui.kt` line 296: `SettingRow(label, hint, value, onClick)`; the value is one line of text.
- `android/app/build.gradle.kts`: `minSdk 24`, `targetSdk 36`; `versionCode = major*10000 + minor*100 + patch` from the root `package.json`, now `0.2.10`.
- `scripts/build-all.js` line 21–22: the release APK is `assembleDebug` output copied to `dist/BufferZone-tv-<version>.apk`.
- `.claude/memory.md`: each release is tag `v<version>` with the six files of `dist/`, the APK among them.
- `android/app/src/main/AndroidManifest.xml`: only `INTERNET` and `ACCESS_NETWORK_STATE`; one activity, no receiver.

## Notes for the work run
1. Build the APK URL from the version: `releases/download/v<v>/BufferZone-tv-<v>.apk`. The brief forbids changes to the update check, so do not read the asset list in `checkUpdate()`.
2. GitHub answers that URL with a 302 to `objects.githubusercontent.com`; OkHttp follows it. Check `r.isSuccessful` after the redirect.
3. The release APK is a debug build, signed with the debug key of the build machine (`~/.android/debug.keystore`). An APK from another machine fails with a conflict status; the receiver message then shows it. Note this under Follow-ups; do not change signing.
4. `canRequestPackageInstalls()` exists from API 26. Below 26 skip the check; the installer screen handles the old "Unknown sources" switch.
5. Decision 3 Default: start `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `Uri.parse("package:" + ctx.packageName)` and show "Allow Buffer Zone to install apps, then press Update again."; when that activity is missing (`ActivityNotFoundException`), show "Allow Install unknown apps for Buffer Zone in the TV settings, then press Update again."
6. `PendingIntent` for the session commit: explicit intent to `UpdateReceiver`, flags `FLAG_UPDATE_CURRENT` plus `FLAG_MUTABLE` on API 31+ (the installer adds extras; an immutable intent loses them).
7. The receiver starts the confirm activity while the app is in front, so the Android limit on starting activities from the background does not apply; do not add a foreground service.
8. On success Android stops the app process and replaces it; the TV does not restart the app. Nothing to code for that.
9. Set `progress` on the main thread (`withContext(Dispatchers.Main)`), not more often than once per whole percent, so the settings list does not redraw for every block.
10. When the server sends no `Content-Length`, pass `-1` to `openWrite` and show "Downloading…" without a number.
11. Do not bump the version in `package.json`, and do not push or publish; CLAUDE.md forbids the worker to push. List the release under Follow-ups.
12. The manual test changes `package.json` for one build only; put it back with `git checkout -- package.json` before any commit and confirm with `git diff --stat package.json`.

## Steps
1. Add the permission and the receiver to `AndroidManifest.xml`.
2. Write `Updater.kt` with `Updater` and `UpdateReceiver`, as in Files to change and Notes 1–10.
3. Change the update row in `SettingsScreen.kt`, as Decision 2 says.
4. Run the `assembleDebug` command from Acceptance, then the three `grep`/`git diff` commands.
5. When `adb devices` lists a TV, run the manual tests from Acceptance; otherwise report them as not tried.
6. Run `git status --short`; commit the three files by name on `main` with `android-tv-install-updates-from-inside` in the message.
7. Write the report `.dispatch/android-tv-install-updates-from-inside.md` with Follow-ups: version bump and release, Note 3 (one build machine for the signing key).

## Decisions
1. Should the note at app start change?
   - Default: No, keep the short note that points to Settings. Recommended: start-up stays calm; the Update button is one menu away.
   - Other: Show a window at start with "Update now" and "Later". Not recommended: it interrupts every start until you update.
2. Should the button that opens the download page in a browser stay?
   - Default: No, Update replaces it. Recommended: on most TVs the browser button does nothing.
   - Other: Keep it as a second row under Update. Not recommended: one more row that rarely works on a TV.
3. What happens when the TV does not yet allow Buffer Zone to install apps?
   - Default: The app opens that TV setting for you; then you press Update again. Recommended: you allow it once and never see it again.
   - Other: The app only shows a message that says where the setting is. Not recommended: on a TV remote, finding the setting yourself is slow.

## Do not touch
- `android/.../AppState.kt`: `checkUpdate()`, `updateVersion` and the start-up note in `start()` (unless Decision 1 Other).
- `renderer/`, `main.js`, `preload.js`, `webos/`: the desktop and webOS apps.
- `android/app/build.gradle.kts`: signing, versions and dependencies.
- The version in `package.json`, the release and any push.

## Size
3 files: `Updater.kt` (new, about 90 lines), `SettingsScreen.kt` (about 5 lines), `AndroidManifest.xml` (2 lines).

## Work run
model: sonnet
effort: high
The code is small, but the Android install steps have several traps, and the run cannot test them without a TV.

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-20T20:57:05.827Z
COMMAND: ls -a && cat .dispatch/checks.txt 2>/dev/null; ls .claude 2>/dev/null; git log --oneline | head -20
EXIT: 0
OUTPUT:
    c4a19a3 Dispatch: brief of android-tv-install-updates-from-inside; tickets
    f5c7dc8 Dispatch: tickets
    364c982 Dispatch: brief, plan and report of when-i-add-show-to-wathclist-there; tickets
    afc276c when-i-add-show-to-wathclist-there: folder choice when adding a title to the watchlist
    1ed53e8 Dispatch: plan of when-i-add-show-to-wathclist-there
    032c32e Dispatch: status of when-i-add-show-to-wathclist-there; tickets
    1afcddf Dispatch setup
    fe93653 Dispatch: brief of when-i-add-show-to-wathclist-there; tickets
    39ff9fc Dispatch: report of playback-issue-not-sure-why; tickets
    6f2c4fe playback-issue-not-sure-why: restart the stream on a fatal fragParsingError, skip the segment if it breaks again
    6ac61c7 Dispatch: plan of playback-issue-not-sure-why
    c889613 Dispatch: plan of playback-issue-not-sure-why
    c02b2d7 Dispatch: brief of playback-issue-not-sure-why; tickets
    62d0f7e Dispatch: tickets
    c980922 Dispatch setup
    5f7c471 Dispatch: settings
    2c6586f Dispatch: settings
    fc6ffc2 Dispatch: settings
    d62c780 Dispatch: settings
    149dbc0 Dispatch: 1 task file

### EVIDENCE: PLAN 2026-11-20T20:57:13.198Z
COMMAND: cat .claude/memory.md .claude/mistakes.md; ls .claude; cat .dispatch/android-tv-install-updates-from-inside.models; ls .dispatch/plans | head; find android -name "*.kt" -not -path "*/build/*" | head -50; find android -maxdepth 3 -name "*.gradle*" -not -path "*/build/*"; find android -name AndroidManifest.xml -not -path "*/build/*"
EXIT: 0
OUTPUT:
    android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt
    android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt
    android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt
    android/app/src/main/java/com/dkrb56/bufferzone/LibraryScreen.kt
    android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt
    android/app/src/main/java/com/dkrb56/bufferzone/MultiView.kt
    android/app/src/main/java/com/dkrb56/bufferzone/Onboarding.kt
    android/app/src/main/java/com/dkrb56/bufferzone/Playback.kt
    android/app/src/main/java/com/dkrb56/bufferzone/PlayerScreen.kt
    android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt
    android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt
    android/app/src/main/java/com/dkrb56/bufferzone/SettingsScreen.kt
    android/app/src/main/java/com/dkrb56/bufferzone/Store.kt
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt
    android/.gradle
    android/app/build.gradle.kts
    android/build.gradle.kts
    android/settings.gradle.kts
    android/app/src/main/AndroidManifest.xml

### EVIDENCE: PLAN 2026-11-20T20:57:16.651Z
COMMAND: cat android/app/src/main/AndroidManifest.xml android/app/build.gradle.kts; ls android/app/src/main/res android/app/src/main/res/* ; ls android/app/src/test 2>/dev/null -R | head; cat .dispatch/plans/when-i-add-show-to-wathclist-there.md
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
On Android TV, the "Version X is available" row in Settings → Account gets an Update button: the app downloads the new APK and opens the Android install screen.
Files: .dispatch/plans/android-tv-install-updates-from-inside.md
Blockers: Should the note at app start change?; Should the button that opens the download page in a browser stay?; What happens when the TV does not yet allow Buffer Zone to install apps?

### STAGE: WORK

### 2. WORK — nothing-to-do
No report yet.
Files: none
Blockers: none

## What changed
none

## Why
none

## Files
none

## Verification
none

## Follow-ups
none

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-opus-5-5 | 24 | 126531 | 1017169 | 13454 | 1157178 |
