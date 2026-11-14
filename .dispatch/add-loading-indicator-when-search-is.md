state: needs-decision
needs: decision
files: renderer/js/search.js, renderer/styles.css, android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt
plan: .dispatch/plans/add-loading-indicator-when-search-is.md

# add-loading-indicator-when-search-is: loading indicator in the search dialog while a search runs

Finished 2026-11-14 22:29 CET. Commit `5b7c37a` on `main`, not pushed.

The ring works on all three platforms and all builds pass. On Android, a late answer from an older search can switch the ring off too early. The plan design causes this. One decision below.

## What changed
- Windows and TV: while the app waits for the server, the search icon at the left of the field is a small spinning ring. When the results show, the search icon comes back.
- Windows and TV: only the answer to the newest search switches the ring off. An older answer is ignored, also when the same words were typed again.
- Windows and TV: an error switches the ring off too, and the dialog shows "No results." as before.
- Windows and TV: when the text gets shorter than 2 characters, the ring goes off at once.
- Android TV: a small spinning ring shows at the right end of the search field while a search runs.
- The keys, the footer, the tabs, the recent searches and the Plex request did not change.

## Why
- The brief asks for a sign that a search runs, from the request until the results show, also after an error.
- The search icon is where the user looks while typing. The ring is drawn with CSS only, so the TV remote finds no new control.
- A counter of search requests replaces the old check of the query text. The old check let an older answer for the same words through.

## Files
- `renderer/js/search.js`: a request counter in `open()`, a reference to the input row, a new method `busy(on)`, and the busy calls and the counter check in `search()`.
- `renderer/styles.css`: `.pal-input` gets `position: relative`. Two new rules hide the search icon and draw the ring while the row has the class `busy`.
- `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt`: a `searching` state, set in the search effect, and a `trailingIcon` that shows a 20 dp ring while it is true.
- The commit has only these three files. The `.dispatch/` files are for Dispatch to commit.

## Verification
- `git status --short && git log --oneline -1 && git diff --stat HEAD -- renderer android`: before the edits, no changes in `renderer/` or `android/`; only Dispatch's own files were changed.
- `node --check renderer/js/search.js; echo "exit=$?"`: printed `exit=0`. I ran it after the edits and again after a one-space fix in the same file.
- `git diff -- renderer android`: showed only the planned changes in the three files.
- `git config --get core.autocrlf; for f in renderer/js/search.js renderer/styles.css android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt; do printf '%s lines=%s crlf=%s\n' "$f" "$(wc -l < "$f")" "$(grep -c $'\r$' "$f")"; done; git show HEAD:renderer/styles.css | grep -c $'\r$'`: `autocrlf` is `true`, and each edited file has CRLF on every line. The edits did not mix line endings. The git warning about LF and CRLF is the normal autocrlf notice.
- `node build.js` (in `webos/`): printed `Create com.dkrb56.bufferzone_0.2.8_all.ipk to D:\dev_projects\buffer-zone\dist` and `Success`.
- `ls -l dist/com.dkrb56.bufferzone_0.2.8_all.ipk && grep -n "pal-input" webos/build/app/styles.css | head -10 && grep -n "busy" webos/build/app/js/search.js`: the ipk exists. The TV build has both ring rules unchanged and the `.no-flexgap .pal-input > * + *` fallback. The lowered TV script has `busy()` and its three calls.
- `node scripts/build-all.js`: ended with exit code 0. Android printed `BUILD SUCCESSFUL in 25s`, and `compileDebugKotlin` ran again for the changed file. The webOS step printed `Success`. The file list showed all six files in `dist/`.
- `tail -n 40 "C:/Users/dani/AppData/Local/Temp/claude/24e98aa09f2c58d6/7a86a6ff-5c42-4275-a42c-f57ba57bdb2e/tasks/bc2vaym3o.output"; echo ----; grep -n -E "BUILD SUCCESSFUL|BUILD FAILED|^== |error|warning: " "C:/Users/dani/AppData/Local/Temp/claude/24e98aa09f2c58d6/7a86a6ff-5c42-4275-a42c-f57ba57bdb2e/tasks/bc2vaym3o.output" | head -40`: showed the three build steps, `BUILD SUCCESSFUL`, and no `BUILD FAILED` line.
- `grep -n -E "^w: |^e: " "C:/Users/dani/AppData/Local/Temp/claude/24e98aa09f2c58d6/7a86a6ff-5c42-4275-a42c-f57ba57bdb2e/tasks/bc2vaym3o.output" | head; echo "----"; cd /d/dev_projects/buffer-zone && git status --short`: no Kotlin warning and no Kotlin error. Before the commit, only the three code files and Dispatch's own files were changed.

What nobody tried:
- Nobody started the app on Windows, on an LG TV or on Android TV. Nobody saw the ring.
- Nobody tested a slow server, typing fast, or a network that is off.
- Nobody tested an older TV without flex gap, or the search in the Multi-view picker.
- Nobody tested a screen reader with the new `aria-busy` state.

Manual tests for the tester:
- Windows: press Ctrl+F and type a title. While the app waits, the search icon is a spinning ring. When the rows show, the search icon is back.
- Windows: type fast and change the words a few times. The ring stays on until the rows for the last words show.
- Windows: delete the text down to 1 character. The ring goes off at once, and "Type at least 2 characters." shows.
- Windows: switch the network off and search. The ring goes off when "No results." shows.
- Windows: open a recent search from the empty dialog. The ring shows while that search runs.
- TV: open the search and type with the remote keyboard. The same ring shows and goes away. Down from the field goes to the tabs, then to the rows. OK opens a title, and Back closes the dialog.
- Android TV: open Search and type a title. A small ring shows at the right end of the field until the results show. It also goes off when nothing is found or the network is off.

Deviations from the plan:
- The ring is centered on the row height (`top: calc(50% - 10px)`), not at a fixed 20 px. It is 19.5 px from the top, the same place as the icon.
- On Android, the icon slot at the right end of the field is always there and stays empty when no search runs. The text area is about 48 dp narrower, but the field does not jump when the ring shows.

## Follow-ups
- Android, older than this task: when the user types again, the cancelled search still writes its result as an empty list. "No results." can flash while typing fast. The cause is the same as in question 1. This task did not fix it.
- No reduced-motion rule for the ring. The big `.spinner` has none either, as the plan says.
- `dist/` now has builds with this change under the old version 0.2.8. Do not publish them as 0.2.8. Installed Windows apps update only to a higher version. Increase the version in `package.json` and build again before the release, as your rule in `.claude/memory.md` says.

## Questions

Problem 1, Android, from the plan design. When the user types again, Compose cancels the running search. The HTTP call in `Plex.request` uses the blocking OkHttp `execute()` in `withContext(Dispatchers.IO)`, so the cancel does not stop it (`Plex.kt`, lines 197 to 213). When the call ends, `withContext` throws a cancellation, and `runCatching` in `SearchScreen` catches it. The cancelled search then continues and sets `searching = false`. If the old call ends after the new search starts, the ring goes off while the new search still runs. This breaks the brief rule about older answers. I found this in the code. I did not see it on a device. Fix: one check, so a cancelled search does not change `searching`.

Problem 2, Windows and TV, from the plan design. The plan sets the request counter to 0 each time the dialog opens. A search from an earlier opening can still be running. Its answer can get the same number as a search of the new opening. Then it shows old results and switches the ring off too early. This is rare: you close the dialog, open it again, and search while an old search still waits for the server. Fix: set the counter once when the app starts, so it keeps counting across openings.

The project rule says a deviation goes into the report, not into the code, so the code follows the plan. Each fix is one line. With a fix, I change the same commit, so the task keeps one commit.

1. Add the guards against late answers from older searches?
   - Add both guards and amend the commit
   - Add the Android guard only and amend the commit
   - Keep the code as planned and fix it in a new ticket
