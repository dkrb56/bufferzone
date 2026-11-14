# Continue watching also shows watched titles that are not on the watchlist (continue-watched) — 2026-11-14

## Plan approved
# Plan continue-watched: Continue watching also shows watched titles that are not on the watchlist

## Summary
- Continue watching keeps in-progress titles that are not on the watchlist visible when folders are selected; Next up follows the same rule.
- 3 files: renderer/js/views.js, android/.../HomeScreen.kt, README.md.
- 3 decisions, none is HOLD.

## Goal
A title that the user watched, and that is not on the watchlist, stands in "Continue watching" also when the user has watchlist folders selected. Rows, order and actions do not change.

What the panel filters today (code evidence):
- Source: `renderer/js/plex.js:165-173`, `continueWatching()` asks the server for `/hubs/home/continueWatching` (30 items) and falls back to `/library/onDeck`. The server puts only titles in progress and next episodes in this hub. A title watched to the end is not in it. The plan run did not query the server; this is Plex behaviour, not a check of this run.
- Filter 1, profile rules: `renderer/js/views.js:115`, `this.allow(cw)` drops titles that the parental rules of a managed profile hide (`renderer/js/app.js:303-313`). Not active for the admin profile.
- Filter 2, library tab: `renderer/js/views.js:89` and `:115`, `inTab` keeps titles of the libraries of the active home tab.
- Filter 3, duplicates: `renderer/js/views.js:110` and `:116`, `once` keeps one row per guid (the same title in two libraries).
- Filter 4, folder selection: `renderer/js/views.js:112-120`. `inSel` calls `inSelection(titleGuid(item), sel)` (`views.js:493-499`). With no folder selected `sel` is empty and every title passes. With one or more folders selected a title passes only when its guid is in a selected folder (`inFolder`, `views.js:466-470`). Folders hold watchlist guids only (`views.js:452`, the folder menu and drag live on the watchlist page). A title that is not on the watchlist is in no folder, so it never passes. It goes into the `outside` count (`views.js:118`) and shows only after a click on "N more in progress outside these folders · Show" (`views.js:138-140`), until the app restarts.
- The user's screenshot shows the folder note "Normalno" above the rows, so filter 4 is active. This filter is the cause.
- Next up (`views.js:122`) uses the same `inSel`, so a next episode of a series that is not on the watchlist is hidden the same way.
- The Android TV app has its own copy of this logic in Kotlin: `android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt:125-135`. The brief says Android takes the renderer from this folder; that is not so (`README.md:55`). The webOS build does copy `renderer/` (`webos/build.js:112`).

## TL;DR
- Today, when you select a folder, Continue watching hides every title that is not in that folder.
- Titles that are not on your watchlist can never be in a folder, so they vanish behind the "more" link.
- After the change they stay in the list, in order of last watched. Watchlist titles still follow your folders.

## Acceptance
- `node --check renderer/js/views.js` prints nothing and exits with code 0.
- `node scripts/build-all.js` prints the six output files with their sizes and no "missing" error. If the Windows or Android step cannot run on the worker's machine, `node build.js` in `webos/` ends with the ipk in `dist/` and no error.
- `git status --short` after the commit prints nothing.
- Manual, for the tester, desktop: select one folder chip on home (the folder note shows above the rows). Play a title that is not on the watchlist for one minute, stop it, open home. The title stands in Continue watching with its minutes left and its "Watched" time, at the place of its last watched time. The titles that stood there before still stand there. The "more" link, when it shows, counts only watchlist titles in other folders. Click "All": the list is the same as today.
- Manual, for the tester, Next up (with Decision 2 default): finish an episode of a series that is not on the watchlist; the next episode stands under Next up with the folder selected.
- Manual, for the tester, TV: on the webOS build the arrows move over the rows and OK plays, as before.
- Nobody tried: a managed profile with parental rules, a server that answers the Continue watching hub with an error (fallback to On Deck), a watchlist with more than 100 titles.

## Files to change
- `renderer/js/views.js`, `S.views.home`, the `Promise.all` at line 102: also wait for the watchlist guids when the app has not loaded them yet (the Discover list only, not the match against the server). The right column starts that request in line 101 and the app shares one request (`app.js:331-345`), so no second request goes out. Reason: the new rule must know which titles are on the watchlist.
- `renderer/js/views.js`, `S.views.home`, lines 111-122: a title passes the folder filter when it is in a selected folder, or when its title guid (`titleGuid`, series for an episode) is not in the watchlist guids. `outside` counts only the hidden watchlist titles. With Decision 2 default, the Next up filter at line 122 uses the same rule. Update the two comments at lines 111 and 117. Reason: this is the change the user asked for.
- `android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt`, lines 131-135 (with Decision 3 default): the same rule with `App.watchGuids` and `titleGuid` (`Plex.kt:132`). `watchGuids` is Compose state (`AppState.kt:75`), so the column redraws when the watchlist arrives; until then every title passes for a moment. Reason: the Android app has its own copy of the filter.
- `README.md`, line 26: one clause: titles that are not on the watchlist stay visible in Continue watching. Reason: the sentence "the selection applies to both columns of home" would be wrong after the change.

## Files read
- `renderer/js/views.js`: the home filters stand at lines 108-122; `wlSelection`, `inSelection` and `titleGuid` at lines 483-499; `watchedText` and `leftText` at lines 60-76 do not change.
- `renderer/js/app.js`: `loadWatchlist()` (331-345) sets `watchGuids` and shares one in-flight request; `toggleWatchlist` keeps `watchGuids` current; `onKey` (86-98) skips the home keys on TV.
- `renderer/js/plex.js`: `continueWatching()` and `onDeck()` (165-173, 214-217); no new request is needed.
- `renderer/styles.css` lines 188-199: the row and link styles; no change.
- `webos/src/tvnav.js`: the remote focuses buttons and elements with tabindex; the row markup stays, so the remote is not affected.
- `webos/src/webos.css` line 10 and 15: focus styles of `.cw-row`; no change.
- `webos/build.js`: copies `renderer/` and lowers the JS to Chrome 68; no new syntax is needed.
- `scripts/build-all.js`: builds Windows, Android and webOS and checks the six output files.
- `android/.../HomeScreen.kt` 95-160: the same filter chain as the desktop, including the "more" link.
- `android/.../AppState.kt` 174-177, 242-253, 297-307: `loadWatchlist()` runs at `enterApp()`; `inFolder`, `selection`, `inSelection` mirror the desktop.
- `README.md` line 26 describes the folder selection on home; line 55 says the Android app is native Kotlin.
- `.claude/memory.md`: the user's rule of 2026-10-25, every feature goes to all three platforms; the release routine bumps the version and publishes.
- `.claude/mistakes.md`: a test must never overwrite the user's settings (folders, selection).
- Screenshot `paste-1794688495949.png` in the Dispatch pasted folder: Continue watching with the folder note "Normalno" and seven in-progress episodes.

## Steps
1. In `views.js`, wait for the watchlist guids next to the two Plex requests, only when they are not loaded yet.
2. In `views.js`, add the "on the watchlist" test from the title guid and apply it to the Continue watching rows, the `outside` count and, with Decision 2 default, Next up.
3. With Decision 3 default, make the same change in `HomeScreen.kt`.
4. Update the clause in `README.md` line 26.
5. Run `node --check renderer/js/views.js`, then `node scripts/build-all.js` (or the webOS step alone).
6. Do the manual test on the desktop with a folder selected. Do not change the user's folders or selection in `settings.json`; read the current value first and put it back.
7. Commit once on the current branch with the ticket id in the message. Do not push.

## Decisions
1. Which titles pass the folder filter in Continue watching?
   - Default: A title that is not on the watchlist always passes; a watchlist title passes only through a selected folder. Recommended: this is the user's request word for word, and the "No folder" chip keeps its meaning.
   - Other: Any title that is in no folder passes, watchlist or not. Not recommended: watchlist titles that you left out of all folders then also appear, and the "No folder" chip loses its meaning.
2. Does Next up follow the same rule?
   - Default: Yes, Next up also shows the next episode of a series that is not on the watchlist. Recommended: both lists share one filter line; without it a series leaves the column as soon as an episode ends.
   - Other: No, only the Continue watching rows change. Not recommended: a series that is not on the watchlist disappears from home when its episode ends.
3. Does the Android TV app change in this task?
   - Default: Yes, HomeScreen.kt gets the same rule. Recommended: the Android app has its own copy of this filter, your rule says every feature goes to all platforms, and the build command already builds the APK.
   - Other: No, Android gets a follow-up ticket. Not recommended: the three platforms then behave differently until that ticket is done.

## Do not touch
- `renderer/js/plex.js`: no new or changed Plex request.
- The row markup in `row()` (`views.js:125-137`), `renderer/styles.css`, `webos/src/tvnav.js`, `webos/src/webos.css`: the TV remote depends on the row structure.
- The folder chips, the watchlist column and the watchlist page.
- The text of the "more" link and the folder note.
- `package.json` version: the user's release routine bumps it after the task; the report says so.
- The user's `settings.json` (folders and selection) during the manual test.

## Size
- 3 files with all defaults: `renderer/js/views.js`, `android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt`, `README.md`. 2 files when Decision 3 is "Other".

## User decisions

The user took these decisions in Dispatch on 2026-11-14 21:43. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Which titles pass the folder filter in Continue watching?: A title that is not on the watchlist always passes; a watchlist title passes only through a selected folder
2. Does Next up follow the same rule?: Yes, Next up also shows the next episode of a series that is not on the watchlist
3. Does the Android TV app change in this task?: Yes, HomeScreen.kt gets the same rule

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-14T20:36:11.314Z
COMMAND: git log --oneline -15 -- renderer/js/views.js && git status --short && ls .dispatch .dispatch/plans .dispatch/briefs 2>/dev/null
EXIT: 0
OUTPUT:
    50c8035 Home: link for titles outside folders, fix "nullnull"
    5eea14b Watchlist folders: multi-select, shared selection, filter home
    335c67f Watchlist: folders, larger posters, show on home
    7a17614 Redesign: OLED black + violet, icon rail, new features
    .dispatch:
    briefs
    continue-watched.session
    continue-watched.state
    plan-flags.txt
    plans
    search-recent.session
    search-recent.state
    work-flags.txt
    worker.ps1
    
    .dispatch/briefs:
    continue-watched.md
    search-recent.md
    
    .dispatch/plans:

### EVIDENCE: PLAN 2026-11-14T20:36:30.382Z
COMMAND: git show 335c67f --stat | head -20 && git show 5eea14b --stat | head -20 && git show 50c8035 --stat | head -20
EXIT: 0
OUTPUT:
        - Record a testing mistake in .claude/mistakes.md.
    
     README.md            |   2 +-
     renderer/js/views.js | 102 ++++++++++++++++++++++++++++++++++++++-------------
     renderer/styles.css  |   4 ++
    commit 50c8035e07a9a3d7ec506f7edf2379e92455780c
    Author: dani <10598301+dkrb56@users.noreply.github.com>
    Date:   Sat Oct 24 00:20:19 2026 +0200
    
        Home: link for titles outside folders, fix "nullnull"
        
        - Continue watching keeps the folder filter, but a line under the list
          says how many titles in progress are outside the selected folders and
          can show or hide them (until the app restarts).
        - Fix "null" text on home and the series page: replaceChildren() writes
          null arguments as text, so empty parts are now left out.
    
     renderer/js/views.js | 19 ++++++++++++++-----
     renderer/styles.css  |  2 ++
     2 files changed, 16 insertions(+), 5 deletions(-)

### EVIDENCE: PLAN 2026-11-14T20:36:31.147Z
COMMAND: git show 5eea14b -- renderer/js/views.js | head -200
EXIT: 0
OUTPUT:
         const draw = () => {
    +      const sel = this.wlSelection();
           const items = all.filter(r => this.watchGuids.has(r.w.guid));
    -      let rows = items.filter(r => this.inFolder(f.folder, r.w.guid));
    +      let rows = items.filter(r => this.inSelection(r.w.guid, sel));
           if (f.type !== 'all') rows = rows.filter(r => r.w.type === f.type);
           if (f.sort === 'added') rows.sort((a, b) => (b.w.watchlistedAt || 0) - (a.w.watchlistedAt || 0));
           if (f.sort === 'title') rows.sort((a, b) => a.w.title.localeCompare(b.w.title));
           if (f.sort === 'length') rows.sort((a, b) => ((b.s?.duration || b.w.duration || 0) - (a.s?.duration || a.w.duration || 0)));
           count.textContent = `${items.length} titles`;
           const added = (w) => w.watchlistedAt ? new Date(w.watchlistedAt * 1000).toISOString().slice(0, 10) : '';
    -      const folder = this.wlFolders().find(x => x.id === f.folder);
    +      // Rename and delete act on one folder, so they show when exactly one folder is selected.
    +      const folder = sel.length === 1 ? this.wlFolders().find(x => x.id === sel[0]) : null;
           const folderNames = (guid) => this.wlFolders().filter(x => x.guids.includes(guid)).map(x => x.name).join(', ');
           const row = ({ w, s }) => {
             const el = h('div.t-row', { draggable: true, title: 'Drag onto a folder to add it' },
    @@ -561,8 +612,8 @@
           };
           body.replaceChildren(

### EVIDENCE: PLAN 2026-11-14T20:38:11.575Z
COMMAND: ls "C:/Users/dani/AppData/Roaming/" | grep -i -E "buffer|bufferzone|dkrb" ; ls "C:/Users/dani/AppData/Roaming/buffer-zone" "C:/Users/dani/AppData/Roaming/Buffer Zone" 2>/dev/null
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
Continue watching keeps in-progress titles that are not on the watchlist visible when folders are selected; Next up follows the same rule.
Files: .dispatch/plans/continue-watched.md
Blockers: Which titles pass the folder filter in Continue watching?; Does Next up follow the same rule?; Does the Android TV app change in this task?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-14T20:47:39.957Z
COMMAND: git status --short && git log --oneline -5 && date
EXIT: 0
OUTPUT:
     M .dispatch/continue-watched.session
     M .dispatch/continue-watched.state
    f11ac41 Dispatch: 8 task files
    936bdb4 search-recent: show recent searches in the empty search dialog
    d843bf7 Dispatch: 1 task file
    6671b31 Dispatch: 2 task files
    b144a7e Dispatch: 1 task file
    Sat Nov 14 21:47:41 CEST 2026

### EVIDENCE: WORK 2026-11-14T20:47:45.660Z
COMMAND: git show --stat 936bdb4 | head -20 && git diff 50725f4 HEAD --stat -- renderer android README.md
EXIT: 0
OUTPUT:
    commit 936bdb44798787c15681a8164e986c514cf80342
    Author: dani <10598301+dkrb56@users.noreply.github.com>
    Date:   Sat Nov 14 21:46:34 2026 +0100
    
        search-recent: show recent searches in the empty search dialog
        
        The empty search dialog lists up to 8 recent searches as rows. Arrows,
        Enter, click and the TV OK key run one again. A search is kept when it
        finds at least one title; typing steps fold into the longer query.
        The list is stored in settings (recentSearches), so it survives a restart.
    
     renderer/js/search.js | 41 +++++++++++++++++++++++++++++++++++++++--
     renderer/styles.css   |  1 +
     2 files changed, 40 insertions(+), 2 deletions(-)
     renderer/js/search.js | 41 +++++++++++++++++++++++++++++++++++++++--
     renderer/styles.css   |  1 +
     2 files changed, 40 insertions(+), 2 deletions(-)

### EVIDENCE: WORK 2026-11-14T20:48:40.186Z
COMMAND: node --check renderer/js/views.js; echo "exit=$?"
EXIT: 0
OUTPUT:
    exit=0

### EVIDENCE: WORK 2026-11-14T20:48:40.791Z
COMMAND: git diff
EXIT: 0
OUTPUT:
         ]);
         if (!alive()) return;
     
         // Left: continue watching + next up. The same title can be in two libraries (e.g. Movies and Movies - 4K).
         const seenGuid = new Set();
         const once = (i) => { const k = i.guid || i.ratingKey; return seenGuid.has(k) ? false : seenGuid.add(k); };
    -    // The selected watchlist folders filter the left column too.
    +    // The selected watchlist folders filter the left column too. Titles that are not on the watchlist are in no folder, so they always show.
         const sel = this.wlSelection();
    -    const inSel = (i) => this.inSelection(this.titleGuid(i), sel);
    +    const wlGuids = this.watchGuids || new Set();
    +    const inSel = (i) => !wlGuids.has(this.titleGuid(i)) || this.inSelection(this.titleGuid(i), sel);
         // Newest first by last watched time; the app sorts itself and does not rely on the server's order.
         const contAll = this.allow(cw).filter(inTab)
           .sort((a, b) => (b.lastViewedAt || 0) - (a.lastViewedAt || 0)).filter(once);
    -    // Titles in progress outside the selected folders are hidden, but a link can show them (until the app restarts).
    +    // Watchlist titles in progress outside the selected folders are hidden, but a link can show them (until the app restarts).
         const outside = sel.length ? contAll.filter(i => !inSel(i)).length : 0;
         const showAll = !!this.cwShowAll && outside > 0;
         const cont = showAll ? contAll : contAll.filter(inSel);

### EVIDENCE: WORK 2026-11-14T20:48:47.417Z
COMMAND: node scripts/build-all.js
EXIT: 0
OUTPUT:
    Command running in background with ID: b9xl3a6ss. Output is being written to: C:\Users\dani\AppData\Local\Temp\claude\24e98aa09f2c58d6\cb833239-5ec7-4b8a-b7b3-5cae8ca1387e\tasks\b9xl3a6ss.output. You …

### EVIDENCE: WORK 2026-11-14T20:49:14.820Z
COMMAND: ls -la "$APPDATA/Buffer Zone/" 2>&1 | head -20; node --version; node -e "const s=require(process.env.APPDATA+'/Buffer Zone/settings.json'); console.log(JSON.stringify({wlSelected:s.wlSelected, folders:(s.watchlistFolders||[]).map(f=>({id:f.id,name:f.name,n:f.guids.length})), homeTab:s.homeTab, profileId:s.profileId}))"
EXIT: 0
OUTPUT:
    drwxr-xr-x 1 dani 197121       0 Nov 14 20:25 ..
    drwxr-xr-x 1 dani 197121       0 Oct 24 01:34 Cache
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 Code Cache
    -rw-r--r-- 1 dani 197121   36864 Nov 13 13:39 DIPS
    -rw-r--r-- 1 dani 197121  214272 Nov 14 21:26 DIPS-wal
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 DawnGraphiteCache
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 DawnWebGPUCache
    -rw-r--r-- 1 dani 197121      59 Oct 24 12:54 DevToolsActivePort
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 GPUCache
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 GPUPersistentCache
    drwxr-xr-x 1 dani 197121       0 Nov 13 20:27 GrShaderCache
    -rw-r--r-- 1 dani 197121     490 Oct 23 16:50 Local State
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 Local Storage
    drwxr-xr-x 1 dani 197121       0 Nov  8 20:15 Network
    -rw-r--r-- 1 dani 197121      57 Nov 14 21:13 Preferences
    drwxr-xr-x 1 dani 197121       0 Nov 13 13:39 Session Storage
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 ShaderCache
    drwxr-xr-x 1 dani 197121       0 Oct 23 18:48 Shared Dictionary
    v24.12.0
    {"wlSelected":["fmvliok90"],"folders":[{"id":"fmvlin4vy","name":"Boris","n":38},{"id":"fmvliok90","name":"Normalno","n":49}],"homeTab":"all","profileId":null}

### EVIDENCE: WORK 2026-11-14T20:49:19.797Z
COMMAND: cp "$APPDATA/Buffer Zone/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json" && sha256sum "$APPDATA/Buffer Zone/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json"; tasklist //FI "IMAGENAME eq Buffer Zone.exe" //FO CSV //NH; tasklist //FI "IMAGENAME eq electron.exe" //FO CSV //NH
EXIT: 0
OUTPUT:
    \d2445ef3ecdda2367560001ed842c4d17fcfb3e75c51f7b2366c18d2680fc31b *C:\\Users\\dani\\AppData\\Roaming/Buffer Zone/settings.json
    \d2445ef3ecdda2367560001ed842c4d17fcfb3e75c51f7b2366c18d2680fc31b *C:\\Users\\dani\\.claude\\jobs\\cb833239/tmp/settings.before.json
    "Buffer Zone.exe","23104","Console","1","130,240 K"
    "Buffer Zone.exe","39412","Console","1","255,972 K"
    "Buffer Zone.exe","17944","Console","1","50,096 K"
    "Buffer Zone.exe","3456","Console","1","306,368 K"
    "Buffer Zone.exe","44660","Console","1","93,076 K"
    INFO: No tasks are running which match the specified criteria.

### EVIDENCE: WORK 2026-11-14T20:49:39.235Z
COMMAND: ls -la "$APPDATA/Buffer Zone/" | tail -n +21
EXIT: 0
OUTPUT:
    drwxr-xr-x 1 dani 197121       0 Nov 14 21:44 VideoDecodeStats
    drwxr-xr-x 1 dani 197121       0 Nov 14 21:13 blob_storage
    -rw-r--r-- 1 dani 197121 1543836 Nov 14 21:48 buffer-zone.log
    -rw-r--r-- 1 dani 197121   32768 Oct 23 16:50 declarative_performance_observer.db
    -rw-r--r-- 1 dani 197121       0 Oct 23 16:50 declarative_performance_observer.db-journal
    -rw-r--r-- 1 dani 197121    5890 Nov 14 21:44 settings.json
    drwxr-xr-x 1 dani 197121       0 Nov 14 21:44 shared_proto_db
    -rw-r--r-- 1 dani 197121      51 Oct 23 16:56 token.bin

### EVIDENCE: WORK 2026-11-14T20:49:44.749Z
COMMAND: P="$CLAUDE_JOB_DIR/tmp/bz-profile" && mkdir -p "$P" && cp "$APPDATA/Buffer Zone/settings.json" "$APPDATA/Buffer Zone/token.bin" "$APPDATA/Buffer Zone/Local State" "$P/" && ls -la "$P"
EXIT: 0
OUTPUT:
    total 10
    drwxr-xr-x 1 dani 197121    0 Nov 14 21:49 .
    drwxr-xr-x 1 dani 197121    0 Nov 14 21:49 ..
    -rw-r--r-- 1 dani 197121  490 Nov 14 21:49 Local State
    -rw-r--r-- 1 dani 197121 5890 Nov 14 21:49 settings.json
    -rw-r--r-- 1 dani 197121   51 Nov 14 21:49 token.bin

### EVIDENCE: WORK 2026-11-14T20:50:24.449Z
COMMAND: npx electron . --user-data-dir="$CLAUDE_JOB_DIR/tmp/bz-profile" --remote-debugging-port=9333
EXIT: 0
OUTPUT:
    Command running in background with ID: bfbw1sk59. Output is being written to: C:\Users\dani\AppData\Local\Temp\claude\24e98aa09f2c58d6\cb833239-5ec7-4b8a-b7b3-5cae8ca1387e\tasks\bfbw1sk59.output. You …

### EVIDENCE: WORK 2026-11-14T20:50:28.146Z
COMMAND: node "$CLAUDE_JOB_DIR/tmp/cw-check.js" 9333
EXIT: 0
OUTPUT:
          "left": "5 min left",
          "when": "Watched 2026-08-19"
        },
        {
          "title": "Curb Your Enthusiasm",
          "sub": "S08E10 · Larry vs. Michael J. Fox",
          "left": "18 min left",
          "when": "Watched 2026-08-14"
        },
        {
          "title": "Curb Your Enthusiasm",
          "sub": "S10E04 · You're Not Going to Get Me to Say Anything Bad About Mickey",
          "left": "",
          "when": "Watched 2026-08-13"
        }
      ],
      "screenMatchesNewRule": true,
      "screenSortedNewestFirst": true,
      "nextUp": []
    }

### EVIDENCE: WORK 2026-11-14T20:50:45.469Z
COMMAND: ls "$CLAUDE_JOB_DIR/tmp/bz-profile" | head -30; echo ---; grep -c "" "$CLAUDE_JOB_DIR/tmp/bz-profile/buffer-zone.log" 2>&1; echo ---; sha256sum "$APPDATA/Buffer Zone/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json"; cmp "$CLAUDE_JOB_DIR/tmp/bz-profile/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json" && echo "test profile settings unchanged"
EXIT: 0
OUTPUT:
    GrShaderCache
    Local State
    Local Storage
    Network
    Preferences
    Session Storage
    ShaderCache
    Shared Dictionary
    blob_storage
    buffer-zone.log
    declarative_performance_observer.db
    declarative_performance_observer.db-journal
    settings.json
    token.bin
    ---
    2
    ---
    \d2445ef3ecdda2367560001ed842c4d17fcfb3e75c51f7b2366c18d2680fc31b *C:\\Users\\dani\\AppData\\Roaming/Buffer Zone/settings.json
    \d2445ef3ecdda2367560001ed842c4d17fcfb3e75c51f7b2366c18d2680fc31b *C:\\Users\\dani\\.claude\\jobs\\cb833239/tmp/settings.before.json
    test profile settings unchanged

### EVIDENCE: WORK 2026-11-14T20:50:49.513Z
COMMAND: rm -rf "$CLAUDE_JOB_DIR/tmp/bz-profile" && ls "$CLAUDE_JOB_DIR/tmp"
EXIT: 0
OUTPUT:
    cw-check.js
    settings.before.json

### EVIDENCE: WORK 2026-11-14T20:51:31.623Z
COMMAND: F="C:/Users/dani/AppData/Local/Temp/claude/24e98aa09f2c58d6/cb833239-5ec7-4b8a-b7b3-5cae8ca1387e/tasks/b9xl3a6ss.output"; tail -n 12 "$F"; echo ---; grep -nE "^w: .*HomeScreen|^e: |BUILD SUCCESSFUL|BUILD FAILED|compileDebugKotlin" "$F" | head -20
EXIT: 0
OUTPUT:
    Create com.dkrb56.bufferzone_0.2.8_all.ipk to D:\dev_projects\buffer-zone\dist
    [Warning] The Node.js version available on the target device may be lower than your development environment. Check the supported version before using recent Node.js features:
    https://webostv.developer.lge.com/develop/guides/js-service-basics#supported-nodejs-version
    Success
    BufferZone-0.2.8-setup.exe  122.0 MB
    BufferZone-0.2.8-setup.exe.blockmap  0.1 MB
    latest.yml  0.0 MB
    BufferZone-0.2.8-portable.exe  108.0 MB
    BufferZone-tv-0.2.8.apk  20.6 MB
    com.dkrb56.bufferzone_0.2.8_all.ipk  0.3 MB
    
    [exited with code 0]
    ---
    57:> Task :app:compileDebugKotlin
    68:BUILD SUCCESSFUL in 32s

### EVIDENCE: WORK 2026-11-14T20:51:36.077Z
COMMAND: grep -c "wlGuids" webos/build/app/js/views.js; git status --short
EXIT: 0
OUTPUT:
    2
     M README.md
     M android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt
     M renderer/js/views.js

### EVIDENCE: WORK 2026-11-14T20:51:40.085Z
COMMAND: git log --oneline -3; git check-ignore -v .dispatch/continue-watched.md; git ls-files .dispatch | head -20
EXIT: 0
OUTPUT:
    5e4e5d7 Dispatch: 4 task files
    f11ac41 Dispatch: 8 task files
    936bdb4 search-recent: show recent searches in the empty search dialog
    .dispatch/briefs/continue-watched.md
    .dispatch/briefs/search-recent.md
    .dispatch/continue-watched.md
    .dispatch/continue-watched.session
    .dispatch/continue-watched.state
    .dispatch/logs/continue-watched.md
    .dispatch/logs/search-recent.md
    .dispatch/plan-flags.txt
    .dispatch/plans/continue-watched.md
    .dispatch/plans/search-recent.md
    .dispatch/search-recent.md
    .dispatch/search-recent.session
    .dispatch/search-recent.state
    .dispatch/tickets.jsonl
    .dispatch/work-flags.txt
    .dispatch/worker.ps1

### EVIDENCE: WORK 2026-11-14T20:51:45.425Z
COMMAND: git add README.md android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt renderer/js/views.js && git commit -q -F - <<'EOF'
    continue-watched: show titles not on the watchlist in Continue watching
    
    With a folder selected, home kept only titles in a selected folder.
    Folders hold watchlist titles only, so in-progress titles that are not
    on the watchlist were always hidden behind the "more" link.
    
    Now a title passes the folder filter when it is not on the watchlist or
    when it is in a selected folder. Next up uses the same rule, and the
    "more" link counts only hidden watchlist titles. Home waits for the
    watchlist load that the right column already starts. Same change in the
    Android TV home screen; webOS takes the desktop renderer.
    EOF
    git log --oneline -1; git status --short
EXIT: 0
OUTPUT:
    warning: in the working copy of 'README.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt', LF will be replaced by CRLF the next time Git touches it
    4a9e1af continue-watched: show titles not on the watchlist in Continue watching

### EVIDENCE: WORK 2026-11-14T20:51:53.385Z
COMMAND: git status --short
EXIT: 0
OUTPUT:
    (Bash completed with no output)

### 2. WORK — ok
When you select a folder on home, "Continue watching" now also shows the titles that are not on your watchlist. Before, these titles were always hidden behind the "more" link.
Files: renderer/js/views.js, android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt, README.md
Blockers: none

## What changed
- When you select a folder on home, "Continue watching" now also shows the titles that are not on your watchlist. Before, these titles were always hidden behind the "more" link.
- Watchlist titles still follow your folder selection. The "more" link now counts only the hidden watchlist titles.
- "Next up" uses the same rule (Decision 2).
- The Android TV app has the same change (Decision 3). The webOS build takes the desktop code, so it has the change too.
- The rows, their order (newest first) and their actions do not change.
- Home now waits for the watchlist before it draws the left column, when the watchlist is not loaded yet. It uses the request that the right column starts, so no second request goes out.

## Why
- The folder filter kept a title only when its guid was in a selected folder. Folders hold only watchlist titles. So a title that is not on the watchlist never passed when a folder was selected.
- Evidence before the change: `renderer/js/views.js` line 113 (`inSel` = `inSelection(titleGuid(item), sel)`), the same in `HomeScreen.kt` lines 131-135.
- The new rule: a title passes when it is not on the watchlist, or when it is in a selected folder.
- The wait condition uses `this.watchlist`, not `this.watchGuids`. After a profile switch, the app clears the watchlist but keeps the guids of the old profile until the new list arrives.

## Files
- `renderer/js/views.js`: `S.views.home` waits for the watchlist load; `inSel` lets titles that are not on the watchlist pass; two comments updated.
- `android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt`: a local `inSel()` with the same rule, used for the rows, the "more" count and Next up.
- `README.md`: one clause in the watchlist folders line.

## Verification
- `node --check renderer/js/views.js; echo "exit=$?"` printed `exit=0`.
evidence: yes
- `node scripts/build-all.js` ended with exit code 0. It printed the six files: `BufferZone-0.2.8-setup.exe` 122.0 MB, its `.blockmap` 0.1 MB, `latest.yml`, `BufferZone-0.2.8-portable.exe` 108.0 MB, `BufferZone-tv-0.2.8.apk` 20.6 MB, `com.dkrb56.bufferzone_0.2.8_all.ipk` 0.3 MB. Gradle printed `BUILD SUCCESSFUL in 32s` after `:app:compileDebugKotlin`, with no warning for `HomeScreen.kt`.
evidence: yes
- `grep -c "wlGuids" webos/build/app/js/views.js; git status --short` printed `2`: the webOS package has the new rule.
evidence: yes
- `npx electron . --user-data-dir="$CLAUDE_JOB_DIR/tmp/bz-profile" --remote-debugging-port=9333` started the dev app on a separate test profile (copies of your settings, token and key file). Your own app was open, so the test did not use your settings folder.
evidence: yes
- `node "$CLAUDE_JOB_DIR/tmp/cw-check.js" 9333` read the home view and compared the old and the new rule on the same server data. It does not play or change anything. Result, with the folder "Normalno" selected:
evidence: yes
  - The old rule showed 17 rows. The new rule shows 50 rows. The 33 new rows are all titles that are not on the watchlist (for example Murderbot "Watched today 21:50", Man of War, Warfare, Doctor Who (2005)).
  - The 17 rows from before still show.
  - The rows on screen are the same as the new rule (`screenMatchesNewRule: true`) and sort newest first (`screenSortedNewestFirst: true`).
  - Each row has its thumbnail, title, episode, minutes left and "Watched" text, as before.
  - The "more" link says "12 more in progress outside these folders · Show". These 12 are watchlist titles in your other folder.
  - Next up was empty: all its titles are already in Continue watching.
- `ls "$CLAUDE_JOB_DIR/tmp/bz-profile" | head -30; echo ---; grep -c "" "$CLAUDE_JOB_DIR/tmp/bz-profile/buffer-zone.log" 2>&1; echo ---; sha256sum "$APPDATA/Buffer Zone/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json"; cmp "$CLAUDE_JOB_DIR/tmp/bz-profile/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json" && echo "test profile settings unchanged"` showed that the dev app wrote its log into the test profile. Your `settings.json` has the same hash as before the test. I then deleted the test profile with the token copy.
evidence: yes
- `git status --short` after the commit printed nothing. The commit is `4a9e1af` on `main`. Nothing is pushed.
evidence: yes

## Follow-ups
- Version bump and release: not done. The plan keeps `package.json` unchanged. Your release routine (bump the patch version, build, push, publish the six files) is still open.
- The list is now long. With "Normalno" selected it has 50 rows, before it had 17. You can ask for a limit in a new ticket.
- Two rows for one series: "Curb Your Enthusiasm" shows twice (S08E10 and S10E04). The duplicate check works per episode, not per series. This was so before the change; you saw it already with "All".
- Five rows show no "min left" (for example The Orville, Fawlty Towers, Derek). The server sends these as next episodes without progress. This was so before the change.
- If the watchlist load fails, the app treats every title as "not on the watchlist". Then all titles in progress show, also with a folder selected.
- Android: until the watchlist arrives, all titles show for a moment. Then the hidden watchlist titles go away.
- The brief says Android takes its renderer from this folder. That is not true: the Android app is native Kotlin with its own copy of this filter.

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-fable-5-1 | 386 | 162078 | 1281689 | 29268 | 1473421 |
| claude-opus-5-5 | 51 | 455120 | 6870215 | 25091 | 7350477 |
