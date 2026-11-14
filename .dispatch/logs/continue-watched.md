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
| claude-fable-5-1 | 386 | 162078 | 1281689 | 29268 | 1473421 |
