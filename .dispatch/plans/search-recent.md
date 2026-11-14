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
