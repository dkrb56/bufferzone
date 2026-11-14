# Plan add-loading-indicator-when-search-is: loading indicator in the search dialog while a search runs

## Summary
A small spinning ring takes the place of the search icon in the search box while the app waits for the server.
2 files change: `renderer/js/search.js` and `renderer/styles.css`; a third, `android/.../SearchScreen.kt`, only with Decision 2.
2 decisions, none is HOLD.

## Goal
From the moment the search request leaves until the results stand in the list, the search box shows that a search runs. The ring also goes away when the answer is an error (the dialog then shows "No results." as today). An older answer that arrives after a newer request leaves the ring on; only the answer to the newest request takes it off. The keyboard, the footer hints, the recent searches and the Plex request stay as they are. The webOS build gets the same ring through the shared `renderer/` files; the remote is not affected.

## TL;DR
- While the app asks the server for your search, the magnifier in the search box turns into a small spinning ring.
- The ring stops when the results are in, also when nothing is found or the server does not answer.
- Type fast and change the words: the ring stays until the last search has its answer.
- The TV gets the same ring. The Android app is Decision 2 below.

## Acceptance
- `node --check renderer/js/search.js` exits 0 and prints nothing.
- `node build.js` in `webos/`: ends without an error, prints the `ares-package` output, and `dist/com.dkrb56.bufferzone_0.2.8_all.ipk` exists afterwards.
- `node scripts/build-all.js`: ends with the file list of `dist/` and no error. If the Windows or Android step fails on missing tooling, the report says so, and the webOS step above stands as the build proof (the brief allows this). With Decision 2 the Android step of this build, or `gradlew.bat assembleDebug` in `android/`, must end with `BUILD SUCCESSFUL`.
- `git status --short`: shows only the changed files before the commit; `dist/`, `webos/build/` and `android/app/build/` are ignored by `.gitignore`.
- Manual, for the tester (Windows): press Ctrl+F, type a title. While the app waits, the magnifier at the left of the field is a spinning ring; when the rows appear, the magnifier is back. Type fast and change the words several times: the ring stays on until the rows for the last words stand in the list. Delete the text down to one character: the ring goes off at once and the text "Type at least 2 characters." shows. Switch the network off and search: the ring goes off when "No results." shows. Open a recent search from the empty dialog: the ring shows while that search runs.
- Manual, for the tester (TV): open the search, type with the remote keyboard: the same ring shows and goes away. Down from the field still lands on the tabs, then on the rows; OK opens; Back closes.
- Manual, with Decision 2 (Android TV): open Search, type a title: a small spinning ring shows at the right end of the field until the results are in; it goes off also when nothing is found or the network is off.

## Files to change
- `renderer/js/search.js`, `Palette.open()` (the block that sets `this.tab`, `this.query`, `this.results`, `this.active`): add a request counter that starts at zero, and keep a reference to the input row (the `div.pal-input` built inside the `replaceChildren` call) so the busy state can be set on it. Reason: the busy class lives on the input row; a counter per open is enough because `close()` drops the whole dialog.
- `renderer/js/search.js`, `Palette.search()`: count the request up at the top of the method, before the length check, so every call makes the earlier in-flight request stale. In the `q.length < 2` branch, switch the busy state off before the draw. Before the `this.app.plex.search(q)` call, switch the busy state on. Replace the stale check `q !== this.query || !this.isOpen` with a check of the saved request number against the counter (and `!this.isOpen` as today); a stale answer returns without touching the busy state. After that check, switch the busy state off, then store and draw the results as today. Reason: this is the one place where a request leaves and an answer arrives. The `.catch` that returns empty lists stays, so an error takes the same path and the ring goes off when "No results." is drawn. The counter also covers the case of the same words typed again while an older request for the same words is still open; the query-text check could not tell those apart.
- `renderer/js/search.js`, new small method `busy(on)` on `Palette`: toggles the class `busy` on the input row and sets `aria-busy` on the results box (`this.resEl`) to the same value. Reason: three callers, one place for the state; `aria-busy` tells a screen reader that the list is being refreshed, at no cost to the layout.
- `renderer/styles.css`, the `.pal-input` rule (line 371): add `position: relative`. Reason: the ring is positioned inside the input row.
- `renderer/styles.css`, after the `.pal-input input` rule (line 372): two new rules. First: while the row has the class `busy`, the search icon (`.icon` inside `.pal-input`) gets `visibility: hidden`, so it keeps its box. Second: the busy row draws the ring as its `::before` pseudo-element: absolute, 20 by 20 pixels at the icon's place (18 pixels from the left, 20 pixels from the top of the 60 pixel row), round, a 2 pixel border in the faint white that `.spinner` uses with the accent colour on top, and the existing `spin` animation (`@keyframes spin`, line 158) at the same speed as `.spinner`. Reason: no new DOM node and nothing focusable, so the remote navigation in `tvnav.js` sees the same controls as before; the icon keeps its box, so the flex-gap margin fallback that `webos/build.js` adds for old TVs does not shift the field while the ring shows.
- With Decision 2 only: `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt`, `SearchScreen()`: a `searching` state next to `results`. In the `LaunchedEffect(query)`: the `< 2` branch sets it false; after the 300 ms pause it is set true, and after the `App.plex.search` line (which cannot throw because of `runCatching`) it is set false. The `OutlinedTextField` gets a `trailingIcon` that shows a `CircularProgressIndicator` of about 20 dp with a 2 dp stroke in `C.accent` while `searching` is true, and nothing otherwise. Reason: Compose cancels the previous effect run when `query` changes, so the newest run owns the flag and a stale answer never arrives; `CircularProgressIndicator` with `C.accent` is already the app's loading mark in `MainActivity.kt` and `PlayerScreen.kt`.

## Files read
- `renderer/js/search.js`: `search()` is debounced by 250 ms, awaits `plex.search`, swallows errors into empty lists, and drops a stale answer with `q !== this.query`; between request and answer nothing in the dialog changes.
- `renderer/js/plex.js` `search()` and `get()`: a failed request throws a `PlexError`; the renderer's `.catch` turns it into empty lists, which draw as "No results.".
- `renderer/styles.css` lines 370 to 390: the input row is a 60 pixel flex row with 18 pixel side padding and a 12 pixel gap; the icon is 20 pixels; `.pal-input` has no `position`.
- `renderer/styles.css` lines 155 to 158: `.spinner` is a 54 pixel ring with the `spin` keyframes; it is too big for the input row, so the plan adds a small ring and reuses only the keyframes.
- `renderer/js/icons.js`: `icon()` returns an `svg.icon`; the set has no loader icon, and a new one would need the generator in `scripts/build-icons.js`.
- `renderer/js/ui.js`: `h()` makes an element focusable on the TV only when it has `onclick`; a pure CSS ring adds nothing focusable.
- `webos/src/tvnav.js`: the remote moves between `button`, `input`, `[tabindex]` and similar elements inside `#palette`; a pseudo-element is not a candidate.
- `webos/src/webos.css`: hides `.kbd` and `.pal-foot` on the TV; the input row keeps the icon and the field.
- `webos/build.js`: copies `renderer/` as is, lowers the JS to Chromium 68, skips `@keyframes` in its CSS pass, and adds `.no-flexgap .pal-input > * + *` margins for TVs without flex gap; `visibility: hidden` keeps the icon as a sibling, `display: none` would not.
- `webos/src/shim.js` line 46 and 54: the TV app zooms the layout and sets `no-flexgap` on old TVs; both are compatible with an absolute pseudo-element inside a relative row.
- `renderer/js/player.js` lines 141 to 143: the player already shows the CSS `spin` animation on the TV, so the animation itself is proven there.
- `renderer/js/multiview.js` line 122: Multi-view opens the same palette with `onPick`; the ring shows there too without extra code.
- `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt`: the same gap exists there: `LaunchedEffect(query)` waits 300 ms, calls `App.plex.search`, and the screen shows the old results or the empty text meanwhile, with no indicator. This makes the Android app a decision per the brief.
- `android/app/src/main/java/com/dkrb56/bufferzone/MainActivity.kt` line 75 and `PlayerScreen.kt` line 224: `CircularProgressIndicator(color = C.accent)` is the app's existing loading mark.
- `scripts/build-all.js`: runs electron-builder, the Android gradle build with `JAVA_HOME` from the environment or Android Studio's JDK, then `node build.js` in `webos/`.
- `.gitignore`: `dist/`, `webos/build/` and `android/app/build/` are ignored, so a build leaves the tree clean.
- `.claude/memory.md` (user rule of 2026-10-25): every feature goes to all platforms; the version bump and the release are the user's steps after the task.
- `.dispatch/plans/search-recent.md`: the previous task on this dialog kept Android out because its brief said so; this brief makes it a decision.

## Steps
1. Read `renderer/js/search.js` again and apply the three changes: `open()` (counter and row reference), `busy(on)`, `search()`.
2. Edit the `.pal-input` rule and add the two rules in `renderer/styles.css`.
3. With Decision 2: apply the `SearchScreen.kt` change.
4. Run `node --check renderer/js/search.js`.
5. Run `node build.js` in `webos/`; confirm the `.ipk` in `dist/`.
6. Run `node scripts/build-all.js`; when a non-webOS step fails on tooling, note it in the report and keep step 5 as the build proof. With Decision 2 the Android step must pass, or run `gradlew.bat assembleDebug` in `android/` on its own.
7. Run `git status --short`; confirm only the changed files.
8. Commit the files on `main` with `add-loading-indicator-when-search-is` in the message.
9. Write the report to `.dispatch/add-loading-indicator-when-search-is.md` with the Verification lines and the manual test list for the tester, including what nobody tried (the TV and Android checks need a device).

## Decisions
1. Where and how does the indicator show?
   - Default: the search icon at the left of the field turns into a small spinning ring while the search runs. Recommended: it is where the eye is while typing, moves no other element, adds no DOM node, and the remote is unaffected.
   - Other: a small spinning ring at the right end of the field, next to the Esc hint. Not recommended: on the TV the Esc hint is hidden and the ring sits alone at the edge; it needs a new element and a layout slot.
   - Other: a thin moving line along the bottom edge of the field. Not recommended: harder to see from the couch on a TV, more CSS for the same information.
2. Does the Android TV app get the same indicator in this task?
   - Default: yes, a small ring at the right end of the search field in `SearchScreen.kt`, with the Android build as proof. Recommended: the user's rule says every feature goes to all platforms, and the same gap exists there.
   - Other: no, renderer only; the Android app goes into the Follow-ups of the report as a separate ticket. Not recommended: the three platforms drift apart until the next ticket.

## Do not touch
- `renderer/js/plex.js`: no change to the Plex search request.
- The footer, the tabs, the result rows, the recent searches (`remember`, `recentRow`) and the texts "Search for a title.", "Type at least 2 characters." and "No results." in `renderer/js/search.js`.
- `webos/src/tvnav.js`, `webos/src/webos.css`, `webos/src/shim.js` and `webos/build.js`: the ring needs nothing from them.
- `renderer/js/icons.js` and `scripts/build-icons.js`: no new icon.
- `renderer/js/multiview.js`: it reuses the palette and gets the ring for free.
- `package.json`: no version bump; the user bumps and releases after the task per the rule in `.claude/memory.md`.
- No reduced-motion rule: the codebase has none for `.spinner` either; a note goes into Follow-ups of the report.
- `android/`: untouched unless Decision 2 says yes; then only `SearchScreen.kt`.

## Size
2 files: `renderer/js/search.js` (about 12 changed or added lines), `renderer/styles.css` (3 lines). With Decision 2 a third: `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt` (about 6 lines).

## User decisions

The user took these decisions in Dispatch on 2026-11-14 22:23. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Where and how does the indicator show?: the search icon at the left of the field turns into a small spinning ring while the search runs
2. Does the Android TV app get the same indicator in this task?: yes, a small ring at the right end of the search field in `SearchScreen.kt`, with the Android build as proof
