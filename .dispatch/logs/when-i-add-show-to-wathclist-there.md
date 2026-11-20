# When i add show to wathclist there should also be an option to add it… (when-i-add-show-to-wathclist-there) — 2026-11-20

## Plan approved
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

## User decisions

The user took these decisions in Dispatch on 2026-11-20 21:28. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. How should the folder choice show up when you add a title to the watchlist?: The folder list opens by itself, and a folder button stays next to the bookmark
2. Should the folder list also open by itself when you have no folders yet?: No, only when you have at least one folder

## Checks chosen

The user chose these checks in Dispatch on 2026-11-20 20:28 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.

- [ ] android/gradle tests

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-20T20:23:02.363Z
COMMAND: ls -a && cat .dispatch/checks.txt; ls .dispatch; ls .claude; cat .dispatch/when-i-add-show-to-wathclist-there.models 2>/dev/null
EXIT: 0
OUTPUT:
    player-debug-overlay.md
    player-debug-overlay.session
    player-debug-overlay.state
    search-recent.md
    search-recent.session
    search-recent.state
    search-recent.taken
    tickets.jsonl
    when-i-add-show-to-wathclist-there.models
    when-i-add-show-to-wathclist-there.session
    when-i-add-show-to-wathclist-there.state
    work-flags.txt
    worker.ps1
    memory.md
    mistakes.md
    settings.json
    settings.local.json
    state
    triage.md
    plan opus high

### EVIDENCE: PLAN 2026-11-20T20:23:10.088Z
COMMAND: cat .dispatch/checks.txt; echo ---; cat .claude/memory.md; echo ---; cat .claude/mistakes.md; echo ---; ls .claude/state 2>/dev/null; ls renderer; git ls-files | grep -v node_modules | head -100
EXIT: 0
OUTPUT:
    android/app/src/main/res/mipmap-xhdpi/ic_launcher.png
    android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png
    android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
    android/app/src/main/res/values/themes.xml
    android/build.gradle.kts
    android/gradle.properties
    android/gradle/wrapper/gradle-wrapper.jar
    android/gradle/wrapper/gradle-wrapper.properties
    android/gradlew
    android/gradlew.bat
    android/settings.gradle.kts
    build/icon-512.png
    build/icon.png
    main.js
    package-lock.json
    package.json
    preload.js
    renderer/assets/logo-mark.svg
    renderer/index.html
    renderer/js/app.js

### EVIDENCE: PLAN 2026-11-20T20:23:13.283Z
COMMAND: git ls-files | grep -v node_modules | tail -n +101; wc -l renderer/js/*.js main.js preload.js; cat .claude/triage.md | head -50; cat .claude/project-rules.md .claude/agents-override.md 2>/dev/null | head
EXIT: 0
OUTPUT:
       201 renderer/js/search.js
       213 renderer/js/settings.js
       160 renderer/js/stats.js
       122 renderer/js/subtitles.js
       166 renderer/js/ui.js
       658 renderer/js/views.js
       183 main.js
        17 preload.js
      3798 total
    # Triage session
    
    You are the triage session of this project. The user types tasks here.
    
    - Follow the section "Background workers" in CLAUDE.md. You are "triage" in that section.
    - You do no implementation. For each task, you classify it, write a brief, start the plan run with `powershell -ExecutionPolicy Bypass -File .dispatch/worker.ps1 plan <ticket-id>`, tell the user when …
    - Tickets start their plan is on in this project: Dispatch writes the brief of a task ticket and starts its plan run itself. A line `Ticket <id>: <title> — Dispatch wrote the brief from the ticket and…
    - When you need a decision from the user, ask with the tool `AskUserQuestion`: one question, 2 to 4 options, the option that you recommend first. The chat window shows the options as buttons. Use a te…
    - A worker has no question tool. When a report says `needs: decision` or `needs: permission`, tell the user that the task card of Dispatch shows the question with its options, or the Continue button; …
    - Keep each message to the user short: 6 lines maximum. Give the result first, then the decisions that the user must make. Do not repeat the brief, the list of files or the list of checks. Give detail…
    - This limit has priority over other rules for the form of a reply.

### EVIDENCE: PLAN 2026-11-20T20:23:18.371Z
COMMAND: cat .dispatch/plans/playback-issue-not-sure-why.md | sed -n '1,200p' | grep -n -i -E "android|version|release|push|platform|build:all|Work run|model|effort|Checks" ; echo; cat package.json
EXIT: 0
OUTPUT:
        },
        "publish": [
          {
            "provider": "github",
            "owner": "dkrb56",
            "repo": "bufferzone"
          }
        ]
      },
      "devDependencies": {
        "electron": "^45.0.0",
        "electron-builder": "^26.18.0",
        "lucide-static": "^1.61.0"
      },
      "dependencies": {
        "electron-updater": "^6.8.11",
        "hls.js": "^1.7.3"
      },
      "license": "MIT"
    }

### EVIDENCE: PLAN 2026-11-20T20:23:34.912Z
COMMAND: grep -n -i "folder" android/app/src/main/java/com/dkrb56/bufferzone/*.kt | head -60; echo ---; grep -n -i "menu-pop\|menuPop\|folder\|t-item" webos/src/*.js webos/src/webos.css | head -30; echo ---; grep -n "toast" renderer/js/ui.js | head
EXIT: 0
OUTPUT:
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:112:            if (name.isNotEmpty()) { val f = App.newFolder(name); App.store.wlSelected = listOf(f.id) }; dialog = null
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:115:            val f = App.store.folders.firstOrNull { it.id == App.selection().firstOrNull() }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:116:            if (f != null) TextDialog("Rename folder", f.name, onDismiss = { dialog = null }) { name ->
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:117:                if (name.isNotEmpty()) App.store.folders = App.store.folders.map { if (it.id == f.id) Folder(it.id, name, it.guid…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:121:            val f = App.store.folders.firstOrNull { it.id == App.selection().firstOrNull() }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:122:            if (f != null) ConfirmDialog("Delete \"${f.name}\"?", "The titles stay on your watchlist. Only the folder goes away."…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:123:                App.store.folders = App.store.folders.filter { it.id != f.id }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:132:private fun WatchRow(r: WlRow, onFolders: () -> Unit) {
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:134:    val folderNames = App.store.folders.filter { w.guid in it.guids }.joinToString(", ") { it.name }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:145:                    if (folderNames.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:146:                        Icon(Icons.Outlined.Folder, null, tint = C.accent, modifier = Modifier.size(12.dp)); Spacer(Modifier.widt…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:156:            IconBtn(Icons.Outlined.Folder, onFolders, size = 40.dp)
    ---
    ---
    50:  let toastTimer;
    51:  function toast(msg, ms = 3500) {
    52:    const el = document.getElementById('toast');
    55:    clearTimeout(toastTimer);
    56:    toastTimer = setTimeout(() => el.classList.remove('show'), ms);
    165:  Object.assign(window.Sofa, { h, append, fmtTime, fmtDuration, epCode, toast, debounce, LANGUAGES, btn, iconBtn, pill, switchEl, selectEl, checkEl, highlight, askPin, askText, askConfirm });

### EVIDENCE: PLAN 2026-11-20T20:23:39.968Z
COMMAND: grep -n -i "watchlist\|Bookmark\|FolderMenu\|IconBtn(" android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt | head -30; echo ---; sed -n 1,70p android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt | grep -n "import\|fun " | head -50; echo ---; grep -n "fun FolderMenu" -A 40 android/app/src/main/java/com/dkrb56/bufferzone/*.kt
EXIT: 0
OUTPUT:
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-182-            if (folders.isEmpty()) T("No folders yet.", 14.sp, color = C.muted)
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-183-            folders.forEach { f ->
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-184-                val on = guid in f.guids
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-185-                Focusable({ App.setInFolder(f.id, guid, !on) }, Modifier.fillMaxWidth()) { foc ->
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-186-                    Row(Modifier.fillMaxWidth().background(if (foc) C.bg3 else if (on) C.accentSoft else Color.Transparent).paddi…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-187-                        Box(Modifier.width(26.dp)) { if (on) Icon(Icons.Rounded.Check, null, tint = C.accent, modifier = Modifier…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-188-                        T(f.name, 15.sp, FontWeight.Medium)
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-189-                    }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-190-                }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-191-            }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-192-            Focusable({ naming = true }, Modifier.fillMaxWidth()) { foc ->
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-193-                Row(Modifier.fillMaxWidth().background(if (foc) C.bg3 else Color.Transparent).padding(12.dp), verticalAlignment =…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-194-                    Box(Modifier.width(26.dp)) { Icon(Icons.Rounded.Add, null, tint = C.accent, modifier = Modifier.size(18.dp)) …
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-195-                    T("New folder…", 15.sp, FontWeight.Medium)
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-196-                }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-197-            }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-198-            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) { TvButton("Done", onDismi…
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-199-        }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-200-    }
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt-201-}

### EVIDENCE: PLAN 2026-11-20T20:23:45.743Z
COMMAND: sed -n 255,325p android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt; echo ----; sed -n 55,95p android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt; echo ----; sed -n 140,185p android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt; echo; grep -n "fun show\b\|fun show(" android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt
EXIT: 0
OUTPUT:
                        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            T(sh.title, 40.sp, FontWeight.SemiBold, maxLines = 2)
                            T(listOfNotNull(sh.year?.toString(), if (sh.childCount > 0) "${sh.childCount} season${if (sh.childCount == 1) "" else "s"}" else null, sh.contentRating,
                                sh.audienceRating?.let { "★ %.1f".format(it) }, sh.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · "), 14.sp, color = C.soft)
                            sh.summary?.let { T(it, 14.sp, color = C.body, maxLines = 3, modifier = Modifier.widthIn(max = 620.dp)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                if (upNext != null) TvButton("${if (upNext.viewOffset > 0 && upNext.viewCount == 0) "Resume" else "Play"} ${upNext.code}", { App.play(upNext) }, icon = Icons.Rounded.PlayAr…
                                TvButton(if (allWatched) "Mark series unwatched" else "Mark series watched", { App.scope.launch { App.toggleWatched(sh, allWatched) } }, icon = Icons.Rounded.Check, large =…
                                val inWl = App.inWatchlist(sh)
                                IconBtn(if (inWl) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, { App.scope.launch { App.toggleWatchlist(sh) } }, on = inWl)
                                IconBtn(Icons.Outlined.PushPin, { App.togglePin(sh) }, on = App.isPinned(sh.ratingKey))
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    
    104:    fun show(msg: String) { toast = msg; scope.launch { delay(3500); if (toast == msg) toast = null } }

### EVIDENCE: PLAN 2026-11-20T20:23:51.138Z
COMMAND: grep -n "watchGuids\|var watchlist" android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt | head -5; ls android/app/src/test 2>/dev/null || echo "no android unit tests dir"; echo ---; wc -l webos/src/tvnav.js; grep -n "querySelector\|scope\|modal\|dialog\|pop" webos/src/tvnav.js | head -40
EXIT: 0
OUTPUT:
    no android unit tests dir
    ---
    205 webos/src/tvnav.js
    12:  // The layer that takes the remote: the newest dialog, then the search, the player, Multi-view, the page.
    14:    const modals = document.querySelectorAll('.modal-bg');
    15:    if (modals.length) return modals[modals.length - 1];
    20:      const pop = player.querySelector('.p-pop');
    21:      return on(pop) ? pop : player;
    37:    return Array.from(root.querySelectorAll(FOCUSABLE)).filter(el => {
    38:      if (el.closest('.p-controls') && !document.getElementById('player').classList.contains('show-ui') && !el.closest('.p-pop')) return false;
    60:    const head = sc.querySelector('.page-head');
    90:      // Nothing focused yet (new page or closed dialog): start near the last place, else top left of the page.
    112:    const modals = document.querySelectorAll('.modal-bg');
    113:    if (modals.length) {
    114:      const m = modals[modals.length - 1];
    115:      const cancel = Array.from(m.querySelectorAll('button')).find(b => /^(cancel|close|not now)$/i.test(b.textContent.trim()));
    122:      if (on(a.player.pop)) return a.player.closePop();
    152:  // The arrows and OK come last: the search box, the player and the dialogs handle their own keys first.
    188:  // A closed dialog or a new page removes the focused control; the next arrow press finds a new one.
    200:        const primary = document.querySelector('#main .btn.primary');

### EVIDENCE: PLAN 2026-11-20T20:24:02.473Z
COMMAND: grep -n "menu-pop" -r renderer webos/src | head; grep -n "^\.menu-pop\|\.t-item" renderer/styles.css | head -5; grep -n "render(\|  go(\|  back(" renderer/js/app.js | head -10; git log --oneline -8 -- renderer/js/views.js
EXIT: 0
OUTPUT:
    renderer/js/views.js:551:    const pop = h('div.menu-pop', h('div.eyebrow', { style: { padding: '6px 10px' } }, 'Folders'),
    renderer/styles.css:289:.menu-pop { position: fixed; z-index: 60; min-width: 220px; max-height: 60vh; overflow-y: auto; background: var(--bg2); border: 1px solid var(--line-hover); border-radius: 12px…
    289:.menu-pop { position: fixed; z-index: 60; min-width: 220px; max-height: 60vh; overflow-y: auto; background: var(--bg2); border: 1px solid var(--line-hover); border-radius: 12px; box-shadow: var(--…
    457:.t-item { display: flex; gap: 8px; width: 100%; text-align: left; background: none; border: 0; padding: 7px 10px; border-radius: 6px; color: var(--text); }
    458:.t-item:hover { background: rgba(255,255,255,.06); }
    459:.t-item.active { background: var(--accent-soft); }
    461:.t-item b { display: block; font-size: 13px; font-weight: 600; }
    420:    go(name, args = {}, { replace = false, root = false } = {}) {
    426:      this.render();
    429:    back() {
    435:      this.render(prev.scroll);
    440:    async render(scroll = 0) {
    453:        if (token === this.renderToken) view.replaceChildren(h('div.empty', h('h2', 'Something went wrong'), h('p.muted', e.message), btn('Try again', { onclick: () => this.render() })));
    473:      if (this.current && !document.body.classList.contains('bare')) this.render(this.main.scrollTop);
    4a9e1af continue-watched: show titles not on the watchlist in Continue watching
    dac5b5e Fix TV start-up lag and Back from subtitle style
    50c8035 Home: link for titles outside folders, fix "nullnull"
    5eea14b Watchlist folders: multi-select, shared selection, filter home
    335c67f Watchlist: folders, larger posters, show on home
    7a17614 Redesign: OLED black + violet, icon rail, new features

### EVIDENCE: PLAN 2026-11-20T20:24:20.036Z
COMMAND: which gradle node; grep -n "gradle\|exec\|spawn" scripts/build-all.js | head; grep -n "android/gradle" .dispatch/logs/*.md | head -5; grep -n "^private fun\|^fun \|^internal fun" android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt | grep -i "IconBtn\|BzDialog\|FolderMenu"
EXIT: 0
OUTPUT:
    which: no gradle in (/c/Users/dani/bin:/mingw64/bin:/usr/local/bin:/usr/bin:/bin:/mingw64/bin:/usr/bin:/c/Users/dani/bin:/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot/bin:/c/Program Files/Oculus/…
    /c/Program Files/nodejs/node
    9:const { execSync } = require('child_process');
    15:const run = (cmd, cwd, env) => execSync(cmd, { cwd, stdio: 'inherit', env: { ...process.env, ...env } });
    21:run(`"${path.join(ROOT, 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" assembleDebug`, path.join(ROOT, 'android'), { JAVA_HOME: JDK });
    .dispatch/logs/pause-resumes-after-restart.md:188:COMMAND: echo "== node"; node --version; echo "== ticket"; grep -o '{[^}]*pause-resumes-after-restart[^}]*}' .dispatch/tickets.jsonl | cut -c1-400; ec…
    .dispatch/logs/playback-issue-not-sure-why.md:30:- [ ] android/gradle tests — no Android code changes; the Android app plays with its own player and has no fragParsingError.
    .dispatch/logs/playback-issue-not-sure-why.md:48:- `.dispatch/checks.txt`: one check, `android/gradle tests`.
    .dispatch/logs/playback-issue-not-sure-why.md:112:- [ ] android/gradle tests
    .dispatch/logs/playback-issue-not-sure-why.md:196:    - [ ] android/gradle tests
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt:168:private fun FolderMenu(guid: String, onDismiss: () -> Unit) {
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt:147:fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, size: Dp = 44.dp, label: String? = nul…
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt:230:fun BzDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) = Dialog(onDismissRequest = onDismiss) {
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt:236:fun <V> OptionDialog(title: String, options: List<Pair<V, String>>, current: V?, onDismiss: () -> Unit, onPick: (V) -> Unit) = BzDialog(onDism…
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt:257:fun TextDialog(title: String, initial: String = "", hint: String? = null, okLabel: String = "Save", password: Boolean = false, numeric: Boolea…
    android/app/src/main/java/com/dkrb56/bufferzone/Ui.kt:281:fun ConfirmDialog(title: String, text: String, okLabel: String, onDismiss: () -> Unit, onOk: () -> Unit) = BzDialog(onDismiss) {

### EVIDENCE: PLAN 2026-11-20T20:24:26.776Z
COMMAND: sed -n 1,40p scripts/build-all.js; ls dist 2>/dev/null | tail -8
EXIT: 0
OUTPUT:
    console.log('\n== Android TV');
    run(`"${path.join(ROOT, 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" assembleDebug`, path.join(ROOT, 'android'), { JAVA_HOME: JDK });
    fs.copyFileSync(path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'), path.join(DIST, `BufferZone-tv-${VERSION}.apk`));
    
    console.log('\n== LG webOS');
    run('node build.js', path.join(ROOT, 'webos'));
    
    const files = [`BufferZone-${VERSION}-setup.exe`, `BufferZone-${VERSION}-setup.exe.blockmap`, 'latest.yml', `BufferZone-${VERSION}-portable.exe`, `BufferZone-tv-${VERSION}.apk`, `com.dkrb56.bufferzone…
    for (const f of files) {
      if (!fs.existsSync(path.join(DIST, f))) throw new Error('missing ' + f);
      console.log(`${f}  ${(fs.statSync(path.join(DIST, f)).size / 1048576).toFixed(1)} MB`);
    }
    com.dkrb56.bufferzone_0.2.4_all.ipk
    com.dkrb56.bufferzone_0.2.5_all.ipk
    com.dkrb56.bufferzone_0.2.6_all.ipk
    com.dkrb56.bufferzone_0.2.7_all.ipk
    com.dkrb56.bufferzone_0.2.8_all.ipk
    com.dkrb56.bufferzone_0.2.9_all.ipk
    latest.yml
    win-unpacked

### 1. PLAN — ok
On the series and movie pages, a folder button sits next to the bookmark while the title is on the watchlist, and the folder list opens right after you add it.
Files: .dispatch/plans/when-i-add-show-to-wathclist-there.md
Blockers: How should the folder choice show up when you add a title to the watchlist?; Should the folder list also open by itself when you have no folders yet?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-20T20:28:55.096Z
COMMAND: sed -n 535,575p renderer/js/views.js; sed -n 320,330p renderer/js/views.js; sed -n 368,378p renderer/js/views.js; sed -n 105,125p webos/src/tvnav.js; grep -n "iconBtn *=\|function iconBtn" renderer/js/*.js | head
EXIT: 0
OUTPUT:
        return true;
      }
    
      function back() {
        const a = app();
        const modals = document.querySelectorAll('.modal-bg');
        if (modals.length) {
          const m = modals[modals.length - 1];
          const cancel = Array.from(m.querySelectorAll('button')).find(b => /^(cancel|close|not now)$/i.test(b.textContent.trim()));
          if (cancel) cancel.click(); else m.remove();
          return;
        }
        if (!a) return;
        if (a.palette && a.palette.isOpen) return a.palette.close();
        if (on(document.getElementById('player'))) {
          if (on(a.player.pop)) return a.player.closePop();
          return a.player.exit();
        }
        if (a.multiview && a.multiview.isOpen) return a.multiview.close();
    renderer/js/ui.js:71:  function iconBtn(name, { title, onclick, cls = '', size = 18, stroke } = {}) {

### EVIDENCE: WORK 2026-11-20T20:28:56.104Z
COMMAND: cd /d/dev_projects/buffer-zone/android/app/src/main/java/com/dkrb56/bufferzone && sed -n 75,95p DetailScreens.kt; sed -n 170,190p DetailScreens.kt; sed -n 1,30p DetailScreens.kt | grep -n import | head -30; sed -n 160,200p WatchlistScreen.kt; grep -n "fun inWatchlist\|folders" AppState.kt | head -20
EXIT: 0
OUTPUT:
                        }
                    }
                }
                Focusable({ naming = true }, Modifier.fillMaxWidth()) { foc ->
                    Row(Modifier.fillMaxWidth().background(if (foc) C.bg3 else Color.Transparent).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp)) { Icon(Icons.Rounded.Add, null, tint = C.accent, modifier = Modifier.size(18.dp)) }
                        T("New folder…", 15.sp, FontWeight.Medium)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) { TvButton("Done", onDismiss, kind = Kind.Primary) }
            }
        }
    278:    fun inWatchlist(i: Item) = i.guid != null && i.guid in watchGuids
    293:        store.folders = store.folders.map { Folder(it.id, it.name, it.guids - g) }
    296:    // ---------- folders ----------
    300:        if (key == "none") return store.folders.none { guid in it.guids }
    301:        return store.folders.firstOrNull { it.id == key }?.guids?.contains(guid) == true
    304:        val ids = store.folders.map { it.id }.toSet()
    314:        store.folders = store.folders + f
    318:        store.folders = store.folders.map { if (it.id != id) it else Folder(it.id, it.name, if (on) (it.guids + guid).distinct() else it.guids - guid) }

### EVIDENCE: WORK 2026-11-20T20:29:06.143Z
COMMAND: cd /d/dev_projects/buffer-zone && python - <<'EOF'
    p='renderer/js/views.js'
    s=open(p,encoding='utf-8').read()
    helper='''  // Folder button for a detail page; shown while the title is on the watchlist.
      P.wlFolderBtn = function (item) {
        const inAny = () => this.wlFolders().some(f => f.guids.includes(item.guid));
        const b = iconBtn('folder', { cls: 'boxed wl-folder' + (inAny() ? ' on' : ''), title: 'Folders', onclick: () => this.folderMenu(b, item.guid, () => b.classList.toggle('on', inAny())) });
        return b;
      };
    
      // Bookmark click on a detail page: toggle, redraw, then offer the folder list when the title was added and folders exist.
      P.wlToggleAndOffer = async function (item) {
        const was = this.inWatchlist(item);
        await this.toggleWatchlist(item);
        await this.render();
        if (was || !this.inWatchlist(item) || !this.wlFolders().length) return;
        const b = this.main && this.main.querySelector('.wl-folder');
        if (b) b.click();
      };
    
    '''
    a='  // Folder menu for one title: a check per folder, plus "New folder".\n'
    assert a in s
    s=s.replace(a,helper+a,1)
    o="onclick: async () => { await this.toggleWatchlist(m); this.render(); } }),"
    assert s.count(o)==1
    s=s.replace(o,"onclick: () => this.wlToggleAndOffer(m) }),\n        this.inWatchlist(m) ? this.wlFolderBtn(m) : null,")
    o="onclick: async () => { await this.toggleWatchlist(show); this.render(); } }),"
    assert s.count(o)==1
    s=s.replace(o,"onclick: () => this.wlToggleAndOffer(show) }),\n            inWl ? this.wlFolderBtn(show) : null,")
    open(p,'w',encoding='utf-8',newline='').write(s)
    EOF
    git diff --stat; grep -n "this.main\b" renderer/js/app.js | head -3; grep -n "wlFolders = " renderer/js/views.js
EXIT: 0
OUTPUT:
    warning: in the working copy of '.dispatch/briefs/when-i-add-show-to-wathclist-there.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/logs/when-i-add-show-to-wathclist-there.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/plans/when-i-add-show-to-wathclist-there.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/when-i-add-show-to-wathclist-there.models', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/when-i-add-show-to-wathclist-there.session', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/when-i-add-show-to-wathclist-there.state', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'renderer/js/views.js', LF will be replaced by CRLF the next time Git touches it
     .../briefs/when-i-add-show-to-wathclist-there.md   |  4 ++++
     .../logs/when-i-add-show-to-wathclist-there.md     | 15 +++++++++++++-
     .../plans/when-i-add-show-to-wathclist-there.md    | 13 ++++++++++++
     .../when-i-add-show-to-wathclist-there.models      |  1 +
     .../when-i-add-show-to-wathclist-there.session     |  1 +
     .dispatch/when-i-add-show-to-wathclist-there.state |  2 +-
     renderer/js/views.js                               | 23 ++++++++++++++++++++--
     7 files changed, 55 insertions(+), 4 deletions(-)
    33:      this.main = document.getElementById('main');
    104:      this.main.replaceChildren(...children);
    424:      else if (this.current && !replace) this.history.push({ ...this.current, scroll: this.main.scrollTop });
    458:  P.wlFolders = function () { return this.settings.watchlistFolders || []; };

### EVIDENCE: WORK 2026-11-20T20:29:14.510Z
COMMAND: cd /d/dev_projects/buffer-zone/android/app/src/main/java/com/dkrb56/bufferzone && grep -n "fun MovieScreen\|fun ShowScreen\|fun SeriesScreen\|^fun \|remember\b" DetailScreens.kt | head -30; grep -n "^import.*Outlined\|import.*runtime" DetailScreens.kt
EXIT: 0
OUTPUT:
    41:import androidx.compose.runtime.remember
    56:fun MovieScreen(key: String) {
    57:    var m by remember(key) { mutableStateOf<Item?>(null) }
    65:    var audio by remember(key) { mutableStateOf<Long?>(null) }        // null = automatic
    66:    var sub by remember(key) { mutableStateOf<Long?>(-1) }            // -1 = automatic, 0 = off
    67:    var quality by remember(key) { mutableStateOf(App.store.quality) }
    68:    var dialog by remember { mutableStateOf<String?>(null) }
    70:    val first = remember { FocusRequester() }
    138:fun ShowScreen(s: Screen.Show) {
    139:    var show by remember(s.key) { mutableStateOf<Item?>(null) }
    140:    var seasons by remember(s.key) { mutableStateOf<List<Item>>(emptyList()) }
    141:    var leaves by remember(s.key) { mutableStateOf<List<Item>>(emptyList()) }
    142:    var season by remember(s.key) { mutableStateOf(s.season) }
    157:    val first = remember { FocusRequester() }
    37:import androidx.compose.runtime.Composable
    38:import androidx.compose.runtime.LaunchedEffect
    39:import androidx.compose.runtime.getValue
    40:import androidx.compose.runtime.mutableStateOf
    41:import androidx.compose.runtime.remember
    42:import androidx.compose.runtime.setValue

### EVIDENCE: WORK 2026-11-20T20:29:19.784Z
COMMAND: python - <<'EOF'
    p='DetailScreens.kt'
    s=open(p,encoding='utf-8',newline='').read()
    nl='\r\n' if '\r\n' in s else '\n'
    def rep(o,n):
        global s
        assert s.count(o)==1,o
        s=s.replace(o,n)
    rep("import androidx.compose.material.icons.outlined.BookmarkBorder"+nl,"import androidx.compose.material.icons.outlined.BookmarkBorder"+nl+"import androidx.compose.material.icons.outlined.Folder"+nl)
    rep("    var dialog by remember { mutableStateOf<String?>(null) }","    var dialog by remember { mutableStateOf<String?>(null) }"+nl+"    var folderDlg by remember { mutableStateOf(false) }")
    rep("{ App.scope.launch { App.toggleWatchlist(item) } }, on = inWl)",
    "{ App.scope.launch { val was = App.inWatchlist(item); App.toggleWatchlist(item); if (!was && App.inWatchlist(item) && App.store.folders.isNotEmpty()) folderDlg = true } }, on = inWl)"+nl+
    "                if (inWl && item.guid != null) IconBtn(Icons.Outlined.Folder, { folderDlg = true }, on = App.store.folders.any { item.guid in it.guids })")
    rep("{ App.scope.launch { App.toggleWatchlist(sh) } }, on = inWl)",
    "{ App.scope.launch { val was = App.inWatchlist(sh); App.toggleWatchlist(sh); if (!was && App.inWatchlist(sh) && App.store.folders.isNotEmpty()) folderDlg = true } }, on = inWl)"+nl+
    "                            if (inWl && sh.guid != null) IconBtn(Icons.Outlined.Folder, { folderDlg = true }, on = App.store.folders.any { sh.guid in it.guids })")
    open(p,'w',encoding='utf-8',newline='').write(s)
    EOF
    grep -n "dialog" DetailScreens.kt | head; sed -n 138,165p DetailScreens.kt
EXIT: 0
OUTPUT:
        Reload(s.key) {
            show = runCatching { App.plex.metadata(s.key) }.getOrNull()
            seasons = runCatching { App.plex.children(s.key) }.getOrDefault(emptyList())
            leaves = runCatching { App.plex.allLeaves(s.key) }.getOrDefault(emptyList())
        }
        App.store.version
        val sh = show ?: return Loading()
        if (!App.itemAllowed(sh)) { T("This series is not available for this profile.", modifier = Modifier.padding(40.dp)); return }
        val upNext = leaves.firstOrNull { it.viewOffset > 0 && it.viewCount == 0 } ?: leaves.firstOrNull { it.viewCount == 0 } ?: leaves.firstOrNull()
        val allWatched = leaves.isNotEmpty() && leaves.all { it.viewCount > 0 }
        val cur = season ?: upNext?.parentRatingKey ?: seasons.firstOrNull()?.ratingKey
        val eps = leaves.filter { it.parentRatingKey == cur }
        val seasonItem = seasons.firstOrNull { it.ratingKey == cur }
        val watchedN = eps.count { it.viewCount > 0 }
        val first = remember { FocusRequester() }
        val listState = rememberLazyListState()
    
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(380.dp)) {

### EVIDENCE: WORK 2026-11-20T20:29:22.648Z
COMMAND: sed -n 112,137p DetailScreens.kt; awk 'NR>=165' DetailScreens.kt | grep -n "^}" | head -3; grep -n "^private fun\|^fun \|^@Composable" DetailScreens.kt
EXIT: 0
OUTPUT:
                Box(Modifier.fillMaxWidth().background(if (f) C.bgHover else C.bg3).padding(horizontal = 12.dp, vertical = 10.dp)) { T(value, 14.sp, maxLines = 1) }
            }
        }
    }
    
    @Composable
    private fun TechRow(k: String, v: String) = Row {
        T(k, 13.sp, color = C.muted, modifier = Modifier.width(90.dp)); T(v, 13.sp)
    37:}
    68:}
    56:@Composable
    57:fun MovieScreen(key: String) {
    125:@Composable
    126:private fun PresetButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    135:@Composable
    136:private fun TechRow(k: String, v: String) = Row {
    140:@Composable
    141:fun ShowScreen(s: Screen.Show) {
    203:@Composable
    204:private fun EpisodeRow(e: Item, focus: Boolean) {

### EVIDENCE: WORK 2026-11-20T20:29:24.428Z
COMMAND: sed -n 190,202p DetailScreens.kt; grep -n "^import.*Unit\|^import.*runtime" DetailScreens.kt | head -2
EXIT: 0
OUTPUT:
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(seasons, key = { it.ratingKey }) { se -> Chip(se.title, se.ratingKey == cur, { season = se.ratingKey }, check = false) }
                    }
                    T("${eps.size} episodes · $watchedN watched", 12.sp, color = C.muted, modifier = Modifier.padding(horizontal = 12.dp))
                    if (seasonItem != null) TvButton(if (eps.isNotEmpty() && watchedN == eps.size) "Mark season unwatched" else "Mark season watched",
                        { App.scope.launch { App.toggleWatched(seasonItem, eps.isNotEmpty() && watchedN == eps.size) } }, kind = Kind.Ghost)
                }
            }
            items(eps, key = { it.ratingKey }) { e -> EpisodeRow(e, e.ratingKey == s.focus) }
        }
        LaunchedEffect(s.key, show != null, App.playback == null) { if (App.playback == null) runCatching { first.requestFocus() } }
    }
    
    38:import androidx.compose.runtime.Composable
    39:import androidx.compose.runtime.LaunchedEffect

### EVIDENCE: WORK 2026-11-20T20:29:30.144Z
COMMAND: python - <<'EOF'
    p='DetailScreens.kt'
    s=open(p,encoding='utf-8',newline='').read()
    nl='\r\n' if '\r\n' in s else '\n'
    def rep(o,n):
        global s
        assert s.count(o)==1,o
        s=s.replace(o,n)
    rep("    var season by remember(s.key) { mutableStateOf(s.season) }"+nl,"    var season by remember(s.key) { mutableStateOf(s.season) }"+nl+"    var folderDlg by remember { mutableStateOf(false) }"+nl)
    rep("""        "quality" -> OptionDialog("Quality", QUALITY.map { it.id to it.label }, quality, { dialog = null }) { quality = it }
        }
    """.replace("\n",nl),"""        "quality" -> OptionDialog("Quality", QUALITY.map { it.id to it.label }, quality, { dialog = null }) { quality = it }
        }
        if (folderDlg) item.guid?.let { g -> FolderMenu(g) { folderDlg = false } }
    """.replace("\n",nl))
    rep("""if (App.playback == null) runCatching { first.requestFocus() } }
    }
    """.replace("\n",nl).replace("}\n","}\n") if False else "    LaunchedEffect(s.key, show != null, App.playback == null) { if (App.playback == null) runCatching { first.requestFocus() } }"+nl,
    "    LaunchedEffect(s.key, show != null, App.playback == null) { if (App.playback == null) runCatching { first.requestFocus() } }"+nl+"    if (folderDlg) sh.guid?.let { g -> FolderMenu(g) { folderDlg = false } }"+nl)
    open(p,'w',encoding='utf-8',newline='').write(s)
    EOF
    cd /d/dev_projects/buffer-zone && node --check renderer/js/views.js && node --check webos/src/tvnav.js && echo OK; grep -n "val item\|item =" android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt | head -4
EXIT: 0
OUTPUT:
    OK
    61:    val item = m ?: return Loading()

### EVIDENCE: WORK 2026-11-20T20:29:32.761Z
COMMAND: node scripts/build-all.js > $CLAUDE_JOB_DIR/tmp/build.log 2>&1; echo exit $?; tail -25 $CLAUDE_JOB_DIR/tmp/build.log
EXIT: 0
OUTPUT:
    > Task :app:packageDebug
    > Task :app:createDebugApkListingFileRedirect UP-TO-DATE
    > Task :app:assembleDebug
    
    BUILD SUCCESSFUL in 1m 28s
    37 actionable tasks: 5 executed, 32 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
    
    == LG webOS
    
    Create com.dkrb56.bufferzone_0.2.10_all.ipk to D:\dev_projects\buffer-zone\dist
    [Warning] The Node.js version available on the target device may be lower than your development environment. Check the supported version before using recent Node.js features:
    https://webostv.developer.lge.com/develop/guides/js-service-basics#supported-nodejs-version
    Success
    BufferZone-0.2.10-setup.exe  122.0 MB
    BufferZone-0.2.10-setup.exe.blockmap  0.1 MB
    latest.yml  0.0 MB
    BufferZone-0.2.10-portable.exe  108.0 MB
    BufferZone-tv-0.2.10.apk  20.6 MB
    com.dkrb56.bufferzone_0.2.10_all.ipk  0.3 MB

### EVIDENCE: WORK 2026-11-20T20:33:14.989Z
COMMAND: git status --short | grep -v "^.. .dispatch" ; F="renderer/js/views.js webos/src/tvnav.js android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt"; git add $F && git commit -q -m "when-i-add-show-to-wathclist-there: folder choice when adding a title to the watchlist" $F && git log --oneline -1
EXIT: 0
OUTPUT:
     M android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt
     M android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt
     M renderer/js/views.js
     M webos/src/tvnav.js
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'renderer/js/views.js', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'webos/src/tvnav.js', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'renderer/js/views.js', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'webos/src/tvnav.js', LF will be replaced by CRLF the next time Git touches it
    afc276c when-i-add-show-to-wathclist-there: folder choice when adding a title to the watchlist

### 2. WORK — ok
Series and movie pages show a folder button next to the bookmark while the title is on the watchlist.
Files: renderer/js/views.js, webos/src/tvnav.js, android/app/src/main/java/com/dkrb56/bufferzone/DetailScreens.kt, android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt
Blockers: none

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

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-opus-5-5 | 36 | 154858 | 1937120 | 14297 | 2106311 |
| claude-sonnet-5-5 | 24 | 44317 | 790494 | 6204 | 841039 |
