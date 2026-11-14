# show recent searches in the search dialog (search-recent) — 2026-11-14

## Plan approved
# Plan search-recent: recent searches in the empty search dialog

## Summary
The empty search dialog lists the last searches as rows; Enter, click or the TV OK key runs one again.
2 files change: `renderer/js/search.js` and `renderer/styles.css`.
3 decisions, none is HOLD.

## Goal
When the search field is empty, the body of the search dialog shows the user's recent searches instead of the text "Search for a title.". A recent search is a row like a result row: the arrows and the mouse reach it, Enter or a click puts the words back into the field and runs the search. The list survives a restart on Windows and on the TV.

## TL;DR
- The search window remembers what you searched and shows it when the field is empty.
- Pick one with the arrows, the mouse or the remote. It searches again for you.
- The list stays after you close and restart the app.
- Partial words you typed on the way are not kept, only the finished search.

## Acceptance
- `node --check renderer/js/search.js` exits 0 and prints nothing.
- `node build.js` in `webos/`: ends without an error, prints the `ares-package` output, and `dist/com.dkrb56.bufferzone_0.2.8_all.ipk` exists afterwards.
- `node scripts/build-all.js`: ends with the file list of `dist/` and no error. If the Windows or Android step fails on missing tooling, the report says so, and the webOS step above stands as the build proof (the brief allows this).
- `git status --short`: shows only the two changed files before the commit; `dist/` and `webos/build/` are ignored by `.gitignore`.
- Manual, for the tester (Windows): open the app, press Ctrl+F, type a title with results, clear the field, type a second title, open one result. Press Ctrl+F again: the body shows "Recent searches" with the two titles, the second one first. Click one or press Enter on the highlighted one: the field shows the words and the results come back. Close the app, start it again, press Ctrl+F: both rows are still there.
- Manual, for the tester (TV): open the search, press down from the field: the focus lands on the tabs, then on a recent row. OK on the row runs the search. Back closes the dialog as before.

## Files to change
- `renderer/js/search.js`, `Palette.search()` (the line `this.results = r;` after the `allow` filter): after the results are in and still current, count the hits in the three lists. When at least one hit exists, call a new method that records the query. Reason: this is the one point where the app knows that a search ran and found something.
- `renderer/js/search.js`, new method `remember(q)` on `Palette`: reads the list from `this.app.settings.recentSearches` (empty list when missing). Folds typing steps: when the first entry and `q` start the same way (one is the start of the other, case-insensitive), the longer of the two replaces the first entry. Otherwise it removes an entry equal to `q` (case-insensitive) and puts `q` first. Cuts the list to the limit from Decision 2. Saves with `this.app.saveSettings`. Reason: the debounced search fires on every pause while typing; without the fold the list fills with "ba", "bat", "batm". Storage through `saveSettings` persists in `settings.json` on Windows and in `localStorage` on the TV, so the restart requirement holds with no new code in `main.js` or `shim.js`.
- `renderer/js/search.js`, `Palette.draw()` (the `if (!this.results)` branch): when the query is empty and the recent list is not empty, render the heading `Recent searches` (class `pal-head eyebrow`, like the result groups) and one row per entry, each pushed into `this.rows` as `{ query, el }`, then call `this.mark()` so the first row is highlighted. When the query has 1 character, the text "Type at least 2 characters." stays. When the list is empty, "Search for a title." stays. Reason: the rows must live in `this.rows` so `move`, `mark` and `choose` work for them unchanged.
- `renderer/js/search.js`, new method `recentRow(query, idx)` on `Palette`: a `div.pal-row` with the same `onmousemove` and `onclick` handlers as `row()`, a 40 px icon box with the existing `search` icon in place of the poster, the query as `.t`, and the `span.kbd` with `Enter`. Reason: the same class gives the hover, active and TV focus styles for free (`.pal-row.active`, `.pal-row:focus` in `webos.css`), and `h()` makes it focusable on the TV because it has `onclick`.
- `renderer/js/search.js`, `Palette.choose(idx, play)`: when the row has a `query`, put it into `this.input.value`, set `this.query`, call `this.search()`, focus the input, and return without closing. Ctrl+Enter does the same as Enter on such a row. Reason: a recent search re-runs the search; it does not open a title.
- `renderer/styles.css`, after the line `.pal-row .art.still { ... }` (line 379): one rule for the icon box of a recent row: fixed 40 px square, centred content, muted colour, `flex: none`. Reason: the poster box `.pal-row .art` has a 2:3 aspect ratio and a grey background; a recent row needs a plain icon slot of the same width so the text columns line up.

## Files read
- `renderer/js/search.js`: the empty state is one `div.loading` in `draw()`; rows are `{ item, el }` in `this.rows`, and `choose` always closes the dialog.
- `renderer/js/app.js`: `saveSettings(patch)` merges into `this.settings` and calls `sofa.setSettings`; `pinned` uses the `|| []` pattern, so a new key needs no default.
- `main.js`: `settings:set` merges the patch into `settings.json` under `userData`; unknown keys are kept.
- `webos/src/shim.js`: `setSettings` writes the merged object to `localStorage` key `bz.settings`; unknown keys are kept.
- `webos/src/tvnav.js`: the arrows move the focus between focusable elements inside `#palette`; OK calls `click()` on the focused element; Back calls `palette.close()`.
- `webos/src/webos.css`: `.pal-row:focus` has a focus style; `.kbd` and `.pal-foot` are hidden on the TV.
- `renderer/js/ui.js`: `h()` sets `tabIndex = 0` on a non-button element with `onclick` when `sofa.tv` is true.
- `renderer/js/icons.js` and `scripts/build-icons.js`: the icon set has `search` and `rotate-ccw` but no clock or history icon; a new icon would mean a change to the generator list and a regenerated file, so the plan reuses `search`.
- `renderer/styles.css` lines 367 to 389: all palette styles; `.pal-head`, `.pal-row`, `.pal-row .t`, `.pal-row .kbd` are reusable as they are.
- `renderer/js/multiview.js` line 122: Multi-view opens the same palette with `onPick`; the recent rows show there too and behave the same.
- `webos/build.js`: copies `renderer/` as is, lowers the JS to Chromium 68 with esbuild, and adds CSS fallbacks; a plain flex rule needs nothing extra.
- `scripts/build-all.js`: runs electron-builder, the Android gradle build and then `node build.js` in `webos/`.
- `.gitignore`: `dist/` and `webos/build/` are ignored, so a build leaves the tree clean.
- `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt`: has the same empty-state text, but the brief limits the task to the renderer.

## Steps
1. Read `renderer/js/search.js` again and apply the five changes listed above in this order: `remember`, `recentRow`, `draw`, `choose`, `search`.
2. Add the one CSS rule to `renderer/styles.css`.
3. Run `node --check renderer/js/search.js`.
4. Run `node build.js` in `webos/`; confirm the `.ipk` in `dist/`.
5. Run `node scripts/build-all.js`; when a non-webOS step fails on tooling, note it in the report and keep step 4 as the build proof.
6. Run `git status --short`; confirm only the two files changed.
7. Commit the two files on `main` with `search-recent` in the message.
8. Write the report to `.dispatch/search-recent.md` with the Verification lines and the manual test list for the tester.

## Decisions
1. When does a search go into the list?
   - Default: when the results arrive and at least one title matches; typing steps fold into the longer query. Recommended: matches the brief's proof (two searches, one opened) and keeps typos with no hits out.
   - Other: only when the user opens or plays a result. Not recommended: the brief's proof expects both searches after one is opened.
   - Other: when the results arrive, also with zero hits. Not recommended: typos with no results fill the list.
2. How many recent searches does the list keep?
   - Default: 8. Recommended: fits the dialog without scrolling at 1080p and on the TV.
   - Other: 5. Not recommended: too short for a shared household.
   - Other: 12. Not recommended: the list scrolls on the TV.
3. Is the list shared by all profiles or kept per profile?
   - Default: shared, one list for the app, like the pinned items. Recommended: the same as every other setting; the results still pass the profile's parental filter.
   - Other: one list per Plex Home profile, keyed by the profile id. Not recommended: more state, and the proof in the brief does not need it.

## Do not touch
- `main.js` and `webos/src/shim.js`: no new default key; the renderer reads the list with an empty fallback.
- `webos/src/tvnav.js` and `webos/src/webos.css`: the remote already reaches `.pal-row` elements.
- `renderer/js/icons.js` and `scripts/build-icons.js`: no new icon.
- The footer, the tabs, the input, the result rows and the Plex search request in `renderer/js/plex.js`.
- No clear button and no per-row remove; that is extra behaviour and goes into Follow-ups of the report.
- `android/`: out of scope per the brief.

## Size
2 files: `renderer/js/search.js` (about 35 added lines), `renderer/styles.css` (1 line).

## User decisions

The user took these decisions in Dispatch on 2026-11-14 21:40. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. When does a search go into the list?: when the results arrive and at least one title matches; typing steps fold into the longer query
2. How many recent searches does the list keep?: 8
3. Is the list shared by all profiles or kept per profile?: shared, one list for the app, like the pinned items

## Stages

### STAGE: PLAN

### 1. PLAN — ok
The empty search dialog lists the last searches as rows; Enter, click or the TV OK key runs one again.
Files: .dispatch/plans/search-recent.md
Blockers: When does a search go into the list?; How many recent searches does the list keep?; Is the list shared by all profiles or kept per profile?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-14T20:40:54.606Z
COMMAND: git status --short && git log --oneline -3 && date '+%Y-%m-%d %H:%M:%S %Z'
EXIT: 0
OUTPUT:
     M .dispatch/logs/search-recent.md
     M .dispatch/plans/search-recent.md
     M .dispatch/search-recent.session
     M .dispatch/search-recent.state
    d843bf7 Dispatch: 1 task file
    6671b31 Dispatch: 2 task files
    b144a7e Dispatch: 1 task file
    2026-11-14 21:40:56 CEST

### EVIDENCE: WORK 2026-11-14T20:41:55.086Z
COMMAND: node --check renderer/js/search.js; echo "exit=$?"
EXIT: 0
OUTPUT:
    exit=0

### EVIDENCE: WORK 2026-11-14T20:42:03.457Z
COMMAND: cd /d/dev_projects/buffer-zone/webos && node build.js
EXIT: 0
OUTPUT:
    Create com.dkrb56.bufferzone_0.2.8_all.ipk to D:\dev_projects\buffer-zone\dist
    [Warning] The Node.js version available on the target device may be lower than your development environment. Check the supported version before using recent Node.js features:
    https://webostv.developer.lge.com/develop/guides/js-service-basics#supported-nodejs-version
    Success

### EVIDENCE: WORK 2026-11-14T20:42:11.014Z
COMMAND: cd /d/dev_projects/buffer-zone && ls -l dist/com.dkrb56.bufferzone_0.2.8_all.ipk && grep -c "recentSearches" webos/build/app/js/search.js && grep -n "pal-icon" webos/build/app/styles.css
EXIT: 0
OUTPUT:
    -rw-r--r-- 1 dani 197121 269466 Nov 14 21:42 dist/com.dkrb56.bufferzone_0.2.8_all.ipk
    3
    380:.pal-row .pal-icon { width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; color: var(--muted); flex: none; }

### EVIDENCE: WORK 2026-11-14T20:42:17.177Z
COMMAND: node scripts/build-all.js
EXIT: 0
OUTPUT:
    > Task :app:packageDebug UP-TO-DATE
    > Task :app:createDebugApkListingFileRedirect UP-TO-DATE
    > Task :app:assembleDebug UP-TO-DATE
    
    BUILD SUCCESSFUL in 1m 12s
    37 actionable tasks: 37 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
    
    == LG webOS
    
    Create com.dkrb56.bufferzone_0.2.8_all.ipk to D:\dev_projects\buffer-zone\dist
    [Warning] The Node.js version available on the target device may be lower than your development environment. Check the supported version before using recent Node.js features:
    https://webostv.developer.lge.com/develop/guides/js-service-basics#supported-nodejs-version
    Success
    BufferZone-0.2.8-setup.exe  122.0 MB
    BufferZone-0.2.8-setup.exe.blockmap  0.1 MB
    latest.yml  0.0 MB
    BufferZone-0.2.8-portable.exe  108.0 MB
    BufferZone-tv-0.2.8.apk  20.0 MB
    com.dkrb56.bufferzone_0.2.8_all.ipk  0.3 MB

### EVIDENCE: WORK 2026-11-14T20:45:28.727Z
COMMAND: ls node_modules | grep -i -E "^(jsdom|happy-dom|linkedom)$"; echo "---"; node --version; echo "$CLAUDE_JOB_DIR"
EXIT: 0
OUTPUT:
    ---
    v24.12.0
    C:\Users\dani\.claude\jobs\dc506c3b

### EVIDENCE: WORK 2026-11-14T20:46:12.537Z
COMMAND: node "$CLAUDE_JOB_DIR/tmp/check-recent.js" D:/dev_projects/buffer-zone
EXIT: 0
OUTPUT:
    PASS empty list shows the old empty text
    PASS typing steps fold into the longer query
    PASS a search without hits is not kept
    PASS a new search goes first
    PASS one character keeps the old hint
    PASS empty field shows the heading and the rows, first row active
    PASS arrow down moves the highlight
    PASS Enter on a recent row runs that search and keeps the dialog open
    PASS clearing the field shows the rows again, first row active
    PASS a click on a recent row runs that search
    PASS Enter on a result row still opens the title and closes the dialog
    PASS same search in other letter case is not kept twice
    PASS the list keeps 8 entries
    PASS Multi-view: a recent row runs the search and does not pick a stream
    PASS every save holds only recentSearches
    15 checks passed

### EVIDENCE: WORK 2026-11-14T20:46:20.228Z
COMMAND: OLD="$CLAUDE_JOB_DIR/tmp/old" && mkdir -p "$OLD/renderer/js" && git show HEAD:renderer/js/search.js > "$OLD/renderer/js/search.js" && cp renderer/js/icons.js renderer/js/ui.js "$OLD/renderer/js/" && node "$CLAUDE_JOB_DIR/tmp/check-recent.js" "$OLD"; echo "exit=$?"
EXIT: 0
OUTPUT:
    PASS empty list shows the old empty text
    FAIL Expected values to be strictly deep-equal:
    + actual - expected
    
    + undefined
    - [
    -   'batman'
    - ]
    
    exit=1

### EVIDENCE: WORK 2026-11-14T20:46:23.772Z
COMMAND: git status --short && git diff --stat
EXIT: 0
OUTPUT:
     M .dispatch/search-recent.state
     M .dispatch/tickets.jsonl
     M renderer/js/search.js
     M renderer/styles.css
    ?? .dispatch/logs/continue-watched.md
    ?? .dispatch/plans/continue-watched.md
    warning: in the working copy of '.dispatch/logs/search-recent.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/plans/search-recent.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/search-recent.session', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/search-recent.state', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/tickets.jsonl', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'renderer/styles.css', LF will be replaced by CRLF the next time Git touches it
     .dispatch/logs/search-recent.md  | 10 +++++++++-
     .dispatch/plans/search-recent.md |  8 ++++++++
     .dispatch/search-recent.session  |  1 +
     .dispatch/search-recent.state    |  2 +-
     .dispatch/tickets.jsonl          |  1 +
     renderer/js/search.js            | 41 ++++++++++++++++++++++++++++++++++++++--
     renderer/styles.css              |  1 +
     7 files changed, 60 insertions(+), 4 deletions(-)

### EVIDENCE: WORK 2026-11-14T20:46:30.060Z
COMMAND: git diff renderer/js/search.js renderer/styles.css
EXIT: 0
OUTPUT:
    +      if (r.query != null) {
    +        this.input.value = r.query;
    +        this.input.focus();
    +        return this.search();
    +      }
           const pick = this.onPick;
           this.close();
           if (pick) return pick(r.item);
    diff --git a/renderer/styles.css b/renderer/styles.css
    index 5c360d8..b1df1ac 100644
    --- a/renderer/styles.css
    +++ b/renderer/styles.css
    @@ -377,6 +377,7 @@ body.bare #rail { display: none; }
     .pal-row.active { background: var(--accent-soft); }
     .pal-row .art { width: 40px; aspect-ratio: 2 / 3; border-radius: 4px; flex: none; }
     .pal-row .art.still { width: 72px; aspect-ratio: 16 / 9; }
    +.pal-row .pal-icon { width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; color: var(--muted); flex: none; }
     .pal-row .t { font-weight: 600; }
     .pal-row .d { font-size: 12px; color: var(--muted); }
     .pal-row mark { background: none; color: var(--accent); }

### EVIDENCE: WORK 2026-11-14T20:46:33.629Z
COMMAND: git add renderer/js/search.js renderer/styles.css && git commit -m "search-recent: show recent searches in the empty search dialog
    
    The empty search dialog lists up to 8 recent searches as rows. Arrows,
    Enter, click and the TV OK key run one again. A search is kept when it
    finds at least one title; typing steps fold into the longer query.
    The list is stored in settings (recentSearches), so it survives a restart." && git log --oneline -1 && git status --short
EXIT: 0
OUTPUT:
    warning: in the working copy of 'renderer/styles.css', LF will be replaced by CRLF the next time Git touches it
    [main 936bdb4] search-recent: show recent searches in the empty search dialog
     2 files changed, 40 insertions(+), 2 deletions(-)
    936bdb4 search-recent: show recent searches in the empty search dialog
     M .dispatch/logs/search-recent.md
     M .dispatch/plans/search-recent.md
     M .dispatch/search-recent.session
     M .dispatch/search-recent.state
     M .dispatch/tickets.jsonl
    ?? .dispatch/logs/continue-watched.md
    ?? .dispatch/plans/continue-watched.md

### EVIDENCE: WORK 2026-11-14T20:46:59.425Z
COMMAND: git show --stat --oneline HEAD && date '+%Y-%m-%d %H:%M %Z'
EXIT: 0
OUTPUT:
    936bdb4 search-recent: show recent searches in the empty search dialog
     renderer/js/search.js | 41 +++++++++++++++++++++++++++++++++++++++--
     renderer/styles.css   |  1 +
     2 files changed, 40 insertions(+), 2 deletions(-)
    2026-11-14 21:46 CEST

### 2. WORK — blocked
The report says done, but 1 Verification claim matches no command of the transcript.
Files: renderer/js/search.js, renderer/styles.css
Blockers: none

## What changed
- The search dialog now shows "Recent searches" when the field is empty. Each recent search is a row like a result row.
- The arrows move between the rows. Enter, a mouse click or the TV OK key puts the words into the field and runs the search again. The dialog stays open.
- A search goes into the list when it finds at least one title. Typing steps such as "ba", "bat", "batman" become one entry, the longer one.
- The list keeps 8 entries, newest first, one list for all profiles. These are the three decisions you took in Dispatch.
- The list is stored with the other settings. On Windows that is the settings file, on the TV the browser storage. So the list survives a restart.
- When the list is empty, the dialog shows "Search for a title." as before. With one letter typed, it shows "Type at least 2 characters." as before.

## Why
You asked for recent searches in the empty body of the search dialog. The rows reuse the result row, so the keys, the mouse and the TV remote work on them with no change to the TV navigation code.

## Files
- `renderer/js/search.js`: records a search with hits, draws the recent rows in the empty state, and runs a recent search on Enter or click.
- `renderer/styles.css`: one rule for the small search icon at the left of a recent row.

## Verification
- `git status --short && git log --oneline -3 && date '+%Y-%m-%d %H:%M:%S %Z'`: the tree had only Dispatch file changes before the work. Clock 21:40:56 CEST.
evidence: yes
- `node --check renderer/js/search.js; echo "exit=$?"`: printed `exit=0`.
evidence: yes
- `node build.js`: run in `webos/`. It printed `Create com.dkrb56.bufferzone_0.2.8_all.ipk` and `Success`.
evidence: yes
- `ls -l dist/com.dkrb56.bufferzone_0.2.8_all.ipk && grep -c "recentSearches" webos/build/app/js/search.js && grep -n "pal-icon" webos/build/app/styles.css`: the TV package exists, dated 21:42. The built TV code has the new list code and the new style rule.
evidence: yes
- `node scripts/build-all.js`: Windows, Android TV and webOS all built. It ended with the list of the six files in `dist/` and no error. The Android step was all up to date, because no Android file changed.
evidence: yes
- `ls node_modules | grep -i -E "^(jsdom|happy-dom|linkedom)$"; echo "---"; node --version; echo "$CLAUDE_JOB_DIR"`: no DOM library is installed. So the check script below brings a small stand-in DOM.
evidence: yes
- `node "$CLAUDE_JOB_DIR/tmp/check-recent.js" D:/dev_projects/buffer-zone`: 15 checks passed. They cover the empty text, the typing fold, no entry for a search without hits, the order, the 8-entry limit, the letter case, the arrows, Enter and click on a recent row, Enter on a result row, and the Multi-view picker. The script is in the job's temp folder, not in the repo.
evidence: yes
- `OLD="$CLAUDE_JOB_DIR/tmp/old" && mkdir -p "$OLD/renderer/js" && git show HEAD:renderer/js/search.js > "$OLD/renderer/js/search.js" && cp renderer/js/icons.js renderer/js/ui.js "$OLD/renderer/js/" && node "$CLAUDE_JOB_DIR/tmp/check-recent.js" "$OLD"; echo "exit=$?"`: the same checks against the old search file fail at the second check and exit 1. So the checks really detect the feature.
evidence: yes
- `git status --short && git diff --stat`: outside `.dispatch/`, only the two planned files changed.
evidence: yes
- `git diff renderer/js/search.js renderer/styles.css`: the diff matches the plan.
evidence: yes
- `git show --stat --oneline HEAD`: commit `936bdb4` holds only the two files, 40 lines added and 2 removed.
evidence: no

What nobody tried, for the tester:
- Nobody opened the real app on Windows. Please do the manual test from the plan: search two titles, open one, open the search again, use a row, restart the app.
- Nobody tried it on an LG TV. Please check that the remote reaches the rows, that OK runs the search, and that Back still closes the dialog.
- Nobody searched against a real Plex server. The checks used a stand-in server that always finds one movie.
- Nobody looked at the rows on a real screen. Please check the icon and the text line up with the result rows, also for a long search.

## Follow-ups
- There is no way to clear the list or to remove one entry. The plan left this out on purpose.
- The typing fold has a side effect. A search for "batman" followed later by "batman begins" leaves only "batman begins" in the list.
- On the TV, a recent row puts the focus into the search field. On webOS this can open the on-screen keyboard. A tab click does the same today.
- Each search with hits saves the settings once, at each pause while typing. That is one small file write each time.
- The Android TV app has the same "Search for a title." text and no recent searches. The brief kept Android out of scope.
- Two untracked files of the ticket continue-watched stand in `.dispatch/`. This task did not touch them.

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-fable-5-1 | 290 | 136278 | 904805 | 17369 | 1058742 |
| claude-opus-5-5 | 33 | 402513 | 3965601 | 17832 | 4385979 |
