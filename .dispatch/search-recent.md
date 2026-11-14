state: done
needs: nothing
files: renderer/js/search.js, renderer/styles.css
plan: .dispatch/plans/search-recent.md

# search-recent: recent searches in the empty search dialog

Finished 2026-11-14 21:46 CEST. Commit `936bdb4` on `main`, not pushed.

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
- `node --check renderer/js/search.js; echo "exit=$?"`: printed `exit=0`.
- `node build.js`: run in `webos/`. It printed `Create com.dkrb56.bufferzone_0.2.8_all.ipk` and `Success`.
- `ls -l dist/com.dkrb56.bufferzone_0.2.8_all.ipk && grep -c "recentSearches" webos/build/app/js/search.js && grep -n "pal-icon" webos/build/app/styles.css`: the TV package exists, dated 21:42. The built TV code has the new list code and the new style rule.
- `node scripts/build-all.js`: Windows, Android TV and webOS all built. It ended with the list of the six files in `dist/` and no error. The Android step was all up to date, because no Android file changed.
- `ls node_modules | grep -i -E "^(jsdom|happy-dom|linkedom)$"; echo "---"; node --version; echo "$CLAUDE_JOB_DIR"`: no DOM library is installed. So the check script below brings a small stand-in DOM.
- `node "$CLAUDE_JOB_DIR/tmp/check-recent.js" D:/dev_projects/buffer-zone`: 15 checks passed. They cover the empty text, the typing fold, no entry for a search without hits, the order, the 8-entry limit, the letter case, the arrows, Enter and click on a recent row, Enter on a result row, and the Multi-view picker. The script is in the job's temp folder, not in the repo.
- `OLD="$CLAUDE_JOB_DIR/tmp/old" && mkdir -p "$OLD/renderer/js" && git show HEAD:renderer/js/search.js > "$OLD/renderer/js/search.js" && cp renderer/js/icons.js renderer/js/ui.js "$OLD/renderer/js/" && node "$CLAUDE_JOB_DIR/tmp/check-recent.js" "$OLD"; echo "exit=$?"`: the same checks against the old search file fail at the second check and exit 1. So the checks really detect the feature.
- `git status --short && git diff --stat`: outside `.dispatch/`, only the two planned files changed.
- `git diff renderer/js/search.js renderer/styles.css`: the diff matches the plan.
- `git show --stat --oneline HEAD`: commit `936bdb4` holds only the two files, 40 lines added and 2 removed.

What nobody tried, for the tester:
- Nobody opened the real app on Windows. Please do the manual test from the plan: search two titles, open one, open the search again, use a row, restart the app.
- Nobody tried it on an LG TV. Please check that the remote reaches the rows, that OK runs the search, and that Back still closes the dialog.
- Nobody searched against a real Plex server. The checks used a stand-in server that always finds one movie.
- Nobody looked at the rows on a real screen. Please check the icon and the text line up with the result rows, also for a long search.

## Deviations from the plan
- When the empty dialog shows the rows, the code also sets the highlight back to the first row. Without that, clearing the field after a search could leave no row highlighted.
- When typing steps fold into the first entry, the code also removes a second copy of that entry further down the list. Without that, the list could hold the same search twice.
- On a recent row, the plan said to set the search words and then search. The search step already reads the words from the field, so the code has no separate line for it.
- The plan did not ask for the check script. It lives only in the job's temp folder.

## Follow-ups
- There is no way to clear the list or to remove one entry. The plan left this out on purpose.
- The typing fold has a side effect. A search for "batman" followed later by "batman begins" leaves only "batman begins" in the list.
- On the TV, a recent row puts the focus into the search field. On webOS this can open the on-screen keyboard. A tab click does the same today.
- Each search with hits saves the settings once, at each pause while typing. That is one small file write each time.
- The Android TV app has the same "Search for a title." text and no recent searches. The brief kept Android out of scope.
- Two untracked files of the ticket continue-watched stand in `.dispatch/`. This task did not touch them.
