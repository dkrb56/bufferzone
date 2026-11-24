# Search results dissapear after few seconds (search-results-dissapear-after-few) — 2026-11-24

## Plan approved
# Plan search-results-dissapear-after-few: Search results dissapear after few seconds

## Summary
Android TV search: a search that was replaced by newer typing no longer wipes the newer results when its late answer arrives.
1 code file changes (`SearchScreen.kt`); with Decision 1 at its default, a new test file and one test line in `android/app/build.gradle.kts`.
2 decisions, none is HOLD.

## Goal
On Android TV, the results of the newest search stay on screen until the user changes the words. An older search that the user typed past must not replace the list with "No results." when its answer comes in late. The first proof is a failing automatic test that shows the late answer still writes into the list. The same test must pass after the fix. The Windows and LG TV search already ignores late answers and does not change.

## TL;DR
- While you type, the app starts a new search for each short pause and drops the older ones.
- On Android TV a dropped search still put its empty answer on screen when the server replied late, so your results vanished.
- After the fix, only the newest search can change the list. Your results stay until you change the words.
- Windows and the LG TV already work this way and stay as they are.

## Acceptance
- Before the fix (red): `gradle -p android test` ends with `BUILD FAILED`. The output names the new test as failed. The failure shows that the cancelled search still wrote its empty answer.
- After the fix (green): `gradle -p android test` ends with `BUILD SUCCESSFUL`. With Decision 1 at its default, `android/app/build/test-results/testDebugUnitTest/` holds a result file for the new test with `failures="0"`.
- Android build: run `gradlew.bat assembleDebug` in `android/` with `JAVA_HOME` set as in `scripts/build-all.js` (the Android Studio `jbr` folder when `JAVA_HOME` is empty). It must end with `BUILD SUCCESSFUL`.
- `git status --short` before the commit shows only the files of this plan outside `.dispatch/`.
- Manual, for the tester (Android TV, slow server or relay connection): open Search and type a title letter by letter with pauses. When the results show, wait 20 seconds without input. The results must stay and must not change to "No results.".

## Checks
- [x] android/gradle tests — the change is in the Android app; this run compiles it and runs the new test (red before the fix, green after).

## Files to change
- `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt`, the `LaunchedEffect(query)` block in `SearchScreen()` (anchor: `results = runCatching { App.plex.search(q) }.getOrDefault(emptyMap()).mapValues { App.allow(it.value) }`). Move the search call into a small top-level `internal suspend` helper in the same file. The helper takes the query and the search function, and returns an empty map on an error. It must throw a cancel again and must not turn it into an empty map. The effect then writes `results` only when the helper returns normally, and sets `searching` to false after that. The `if (isActive)` guard and the comment above it become unnecessary; remove them and the `isActive` import if it becomes unused. Reason: this is the one place where a cancelled search writes its empty answer into the list.
- With Decision 1 at its default: new file `android/app/src/test/java/com/dkrb56/bufferzone/SearchCancelTest.kt`. One JUnit 4 test with `runBlocking`. It starts the helper in a child coroutine with a fake search that blocks on `Dispatchers.IO` (for example `Thread.sleep(300)`) and then returns a map. The test cancels the child after about 50 ms and joins it. It then asserts that the code after the helper call did not run (a variable set after the call stays null). Reason: this shows the real code path of the bug and keeps it from coming back.
- With Decision 1 at its default: `android/app/build.gradle.kts`, the `dependencies` block: add `testImplementation("junit:junit:4.13.2")`. Reason: the project has no test framework yet; unit tests already see the `implementation` libraries, so coroutines need no new line.

## Files read
- `android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt` lines 61-70: the effect waits 300 ms, then writes `results` from `runCatching { App.plex.search(q) }.getOrDefault(emptyMap())` with no check for cancellation.
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt` lines 197-214: `request()` runs a blocking OkHttp `execute()` inside `withContext(Dispatchers.IO)`; a cancel does not stop the call, so the cancelled search ends only when the server answers.
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt` lines 315-320: `search()` is one `/hubs/search` request with `limit=30`.
- `.dispatch/add-loading-indicator-when-search-is.md`, Follow-ups: the earlier worker saw this cause ("the cancelled search still writes its result as an empty list") and left it unfixed.
- `renderer/js/search.js` `search()`: Windows and the LG TV drop any answer whose request number is not the newest (`if (n !== this.req || !this.isOpen) return;`), so they do not have this bug.
- `renderer/js/app.js`, `webos/src/tvnav.js`, `main.js`: no timer or settings write closes or redraws the search dialog.
- `android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt` and `MainActivity.kt`: no timer changes the screen stack while Search is open; the 5-second update note only sets the toast.
- `android/app/src/main/AndroidManifest.xml`: `configChanges` covers keyboard, navigation and screen size, so a keyboard change does not restart the activity and clear the field.
- `android/app/build.gradle.kts`: no `testImplementation` line and no `src/test` folder exist yet.
- `.dispatch/checks.txt`: one check, `android/gradle tests | gradle -p android test`.
- `scripts/build-all.js`: the Android build uses `gradlew.bat assembleDebug` with `JAVA_HOME` or `C:\Program Files\Android\Android Studio\jbr`.

## Notes for the work run
- Root cause: `runCatching` catches the `CancellationException` that `withContext(Dispatchers.IO)` throws when its blocking call returns after a cancel. `getOrDefault(emptyMap())` then gives an empty map, and the cancelled effect writes it into `results`.
- The symptom needs an older search that answers after the newer one. A short query ("ba") gives more hits and is often slower than "batman", so the empty answer arrives last and "No results." replaces the results.
- The plan run could not measure this: its shell was refused after a few commands. The red test run is the first fresh measurement; record its output in the report.
- Red order: first move the current code into the helper unchanged (still `runCatching`), write the test, and run the check. It must fail. Only then make the helper throw the cancel again and run the check again.
- `kotlinx.coroutines.CancellationException` is a subclass of `IllegalStateException`. A `catch (e: Exception)` catches it, so catch the cancel first and throw it again, or check with `coroutineContext.ensureActive()` after the call.
- `App.plex.search` throws `PlexException` on a server error and when not connected; the helper must still turn those into an empty map, so "No results." shows as today.
- The test must not create an `Item`: `Item` reads `org.json`, which throws "Method ... not mocked" in plain JVM unit tests. Let the fake search return an empty map or a map of empty lists.
- Keep the helper free of Compose and Android calls, so the JVM test can load `SearchScreenKt`.
- `gradle -p android test` needs `gradle` on the PATH and a JDK. If `gradle` is missing, the check fails as written; report it and run `gradlew.bat test` in `android/` with the `JAVA_HOME` of `scripts/build-all.js` as extra proof.
- `core.autocrlf` is `true` and the Kotlin files have CRLF line ends; the earlier worker checked that edits keep CRLF.
- With Decision 1 at its other option: delete the test file and the `testImplementation` line before the commit, and run the check once more after the deletion.
- With Decision 2 at its other option: after the Android fix, read `renderer/js/search.js`, `renderer/js/app.js` and `webos/src/tvnav.js` again for another cause. Write any finding into Follow-ups and change no code there.

## Steps
1. Run `git status --short` and confirm no uncommitted change in the files of this plan.
2. In `SearchScreen.kt`, move the search call into the helper with today's behaviour (no fix yet). Adjust the effect to call it.
3. Add `testImplementation("junit:junit:4.13.2")` to `android/app/build.gradle.kts` and write `SearchCancelTest.kt`.
4. Run `gradle -p android test`. It must fail on the new test. Keep the output for the report.
5. Change the helper so a cancel is thrown again. Make the effect write `results` and `searching` only after a normal return. Remove the now unused `isActive` guard and import.
6. Run `gradle -p android test`. It must end with `BUILD SUCCESSFUL`. Show the result file of the test.
7. Run `gradlew.bat assembleDebug` in `android/` with `JAVA_HOME` as in `scripts/build-all.js`. It must end with `BUILD SUCCESSFUL`.
8. With Decision 1 at its other option: remove the test file and the test line, then run step 6 once more.
9. With Decision 2 at its other option: do the read-only check from the Notes and note the result.
10. Run `git status --short`. Commit only the changed files by name, with `search-results-dissapear-after-few` in the message.
11. Write the report `.dispatch/search-results-dissapear-after-few.md`, with the red and green runs, the manual test for the tester, and what nobody tried.

## Decisions
1. Do we keep the small automatic test that catches this bug?
   - Default: yes, keep it in the project. Recommended: it shows the bug before the fix and stops it from coming back; it adds one test file and one build line.
   - Other: no, use it only to prove the fix, then delete it. Not recommended: the proof stays only in the report, and the bug can come back unseen.
2. Do we also look for a cause on Windows and the LG TV?
   - Default: no, fix Android TV only. Recommended: Windows and the LG TV already ignore late answers; the code shows no other cause there.
   - Other: yes, also check the Windows and LG TV search again, without code changes. Not recommended: more work for a problem the code does not show; any finding becomes a new ticket.

## Do not touch
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`: no change to the request or to the search call.
- The 300 ms pause, the ring, the tabs, the result rows and the texts "Search for a title.", "Type at least 2 characters." and "No results." in `SearchScreen.kt`.
- `renderer/`, `webos/` and `main.js`: no change.
- `package.json`: no version bump; the user bumps and releases after the task, as the rule in `.claude/memory.md` says.
- `.dispatch/tickets.jsonl`.

## Size
3 files with Decision 1 at its default: `SearchScreen.kt` (about 10 changed lines), `android/app/build.gradle.kts` (1 line), new `SearchCancelTest.kt` (about 30 lines). With the other option of Decision 1: 1 file.

## Work run
model: sonnet
effort: medium
The fix is a few lines with a clear cause; the most work is the Gradle test setup and the red and green runs.

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-24T16:37:18.799Z
COMMAND: ls -la && ls .dispatch && cat .dispatch/checks.txt 2>/dev/null; ls .dispatch/plans 2>/dev/null; cat .dispatch/search-results-dissapear-after-few.models 2>/dev/null; git ls-files | head -100
EXIT: 0
OUTPUT:
    Start Buffer Zone.bat
    THIRD_PARTY_NOTICES.txt
    android/app/build.gradle.kts
    android/app/src/main/AndroidManifest.xml
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
    android/app/src/main/java/com/dkrb56/bufferzone/Updater.kt
    android/app/src/main/java/com/dkrb56/bufferzone/WatchlistScreen.kt

### EVIDENCE: PLAN 2026-11-24T16:37:24.329Z
COMMAND: git ls-files | sed -n '100,400p'; cat .claude/memory.md; echo ----; cat .claude/mistakes.md; echo ----; grep -i "search" .dispatch/tickets.jsonl | head -20; ls .claude
EXIT: 0
OUTPUT:
    # Mistakes
    
    - 2026-10-24: A UI test called `app.saveFolders([...])` and later `app.saveFolders([])` on the user's real settings. This replaced and then deleted the user's own watchlist folder. Nothing could resto…
      Rule: tests against the user's real app must never overwrite user data. Add test data next to the existing data, remove only that test data, and read the current value first.
    - 2026-10-24: A playback test set `video.volume = 0`; the player saves the volume, and the test then wrote `volume: 1` without reading the old value first. Same rule as above: read `app.settings.volum…
    - 2026-10-25: `gh pr merge` made the merge commit on GitHub's side with the account's primary email (krneker@gmail.com), on the now public repo. Rule: merge pull requests locally (`git merge --no-ff`,…
    ----
    {"op":"open","id":"add-loading-indicator-when-search-is","ts":"2026-11-14T20:45:55.389Z","title":"Add loading indicator when search is executing in the search box -…","type":"feature","summary":"add l…
    {"op":"status","ref":"add-loading-indicator-when-search-is","ts":"2026-11-14T21:08:48.540Z","status":"todo","order":1}
    {"op":"update","ref":"add-loading-indicator-when-search-is","ts":"2026-11-14T21:09:05.025Z","title":"loading indicator in the search dialog while a search runs"}
    {"op":"status","ref":"add-loading-indicator-when-search-is","ts":"2026-11-14T21:09:05.025Z","status":"in_progress","order":null}
    {"op":"close","ref":"add-loading-indicator-when-search-is","ts":"2026-11-14T21:38:34.771Z","resolution":"done","note":"done","order":null}
    {"op":"open","id":"search-results-dissapear-after-few","ts":"2026-11-24T16:37:13.021Z","title":"Search results dissapear after few seconds","type":"feature","summary":"search results dissapear after f…
    {"op":"status","ref":"search-results-dissapear-after-few","ts":"2026-11-24T16:37:18.201Z","status":"in_progress","order":null}
    memory.md
    mistakes.md
    settings.json
    settings.local.json
    state
    triage.md

### EVIDENCE: PLAN 2026-11-24T16:37:27.469Z
COMMAND: cat renderer/js/search.js; echo ======; cat android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt
EXIT: 0
OUTPUT:
        Focusable({ if (pick != null) { searchPick = null; App.back(); pick(i) } else App.open(i) }, Modifier.fillMaxWidth()) { f ->
            Row(Modifier.fillMaxWidth().background(if (f) C.accentSoft else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArran…
                if (i.type == "episode") Art(i.thumb, 144, 81, Modifier.width(80.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)))
                else Art(i.thumb, 80, 120, Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f)) {
                    if (i.type == "episode") {
                        androidx.compose.material3.Text(hl("${i.grandparentTitle} · ${i.code}"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.text, maxLines = 1)
                        androidx.compose.material3.Text(hl(i.title), fontSize = 12.sp, color = C.muted, maxLines = 1)
                    } else {
                        androidx.compose.material3.Text(hl(i.title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.text, maxLines = 1)
                        T(listOfNotNull(if (i.type == "show") "Series" else "Movie", i.year?.toString(),
                            if (i.type == "show" && i.childCount > 0) "${i.childCount} season${if (i.childCount == 1) "" else "s"}" else null,
                            if (i.type == "movie" && i.duration > 0) fmtDuration(i.duration) else null, i.librarySectionTitle).joinToString(" · "), 12.sp, color = C.muted, maxLines = 1)
                    }
                }
                if (f && pick == null) Icon(Icons.Rounded.PlayArrow, null, tint = C.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
        }
    }

### EVIDENCE: PLAN 2026-11-24T16:37:33.607Z
COMMAND: grep -n "palette\|setInterval\|saveSettings(\|render()\|isOpen" renderer/js/app.js | head -80; echo ====; grep -rn "palette" renderer/js/*.js webos/src/*.js | grep -v "^renderer/js/search.js" | head -40
EXIT: 0
OUTPUT:
    45:      this.palette = new S.Palette(this);
    81:    saveSettings(patch) {
    88:      if (this.palette.isOpen || this.multiview.isOpen || document.querySelector('.modal-bg')) return;
    92:        this.palette.open();
    247:        this.saveSettings({ hiddenLibraries: [...hidden] });
    277:      this.saveSettings({ serverId: resource.clientIdentifier });
    366:      this.saveSettings({ pinned: list });
    385:        item('search', 'Search (Ctrl+F)', 'search', () => this.palette.open()),
    426:      this.render();
    453:        if (token === this.renderToken) view.replaceChildren(h('div.empty', h('h2', 'Something went wrong'), h('p.muted', e.message), btn('Try again', { onclick: () => this.render() })));
    ====
    renderer/js/app.js:45:      this.palette = new S.Palette(this);
    renderer/js/app.js:88:      if (this.palette.isOpen || this.multiview.isOpen || document.querySelector('.modal-bg')) return;
    renderer/js/app.js:92:        this.palette.open();
    renderer/js/app.js:385:        item('search', 'Search (Ctrl+F)', 'search', () => this.palette.open()),
    renderer/js/multiview.js:17:        if (this.isOpen && e.key === 'Escape' && !this.app.palette.isOpen) { e.preventDefault(); this.close(); }
    renderer/js/multiview.js:122:      this.app.palette.open({ title: 'Add a stream to Multi-view', onPick: (item) => this.add(item) });
    renderer/js/views.js:33:    return h('div.search-field', { onclick: () => this.palette.open() }, icon('search', { size: 15 }), 'Search  ·  Ctrl F');
    webos/src/tvnav.js:16:    const pal = document.getElementById('palette');
    webos/src/tvnav.js:122:    if (a.palette && a.palette.isOpen) return a.palette.close();

### EVIDENCE: PLAN 2026-11-24T16:37:35.707Z
COMMAND: sed -n 1,130p renderer/js/app.js; echo ====; cat webos/src/tvnav.js
EXIT: 0
OUTPUT:
      });
    
      // A closed dialog or a new page removes the focused control; the next arrow press finds a new one.
      document.addEventListener('focusin', (e) => { if (e.target && e.target.getBoundingClientRect) lastRect = e.target.getBoundingClientRect(); });
      document.addEventListener('focusout', (e) => { if (e.target && e.target.classList) e.target.classList.remove('adjusting'); });
    
      // A new page with a main button (Play, Resume, Sign in…) puts the focus on it.
      let pending = null;
      document.addEventListener('DOMContentLoaded', () => {
        new MutationObserver(() => {
          clearTimeout(pending);
          pending = setTimeout(() => {
            const cur = document.activeElement;
            if (layer() !== document.body || (cur && cur !== document.body && cur.isConnected)) return;
            const primary = document.querySelector('#main .btn.primary');
            if (primary && visible(primary)) focusEl(primary);
          }, 250);
        }).observe(document.getElementById('main'), { childList: true, subtree: true });
      });
    })();

### EVIDENCE: PLAN 2026-11-24T16:37:40.363Z
COMMAND: cd /d/dev_projects/buffer-zone/android/app/src/main/java/com/dkrb56/bufferzone && grep -n "SearchScreen\|Screen\.\|screen\b\|key(\|saveableStateHolder\|SaveableStateProvider\|delay(\|while (\|LaunchedEffect\|onResume\|onStop\|onPause\|refresh\|epoch\|version" MainActivity.kt AppState.kt | head -80
EXIT: unknown
OUTPUT:
    Permission to use Bash has been denied because Claude Code is running in don't ask mode. IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomp…

### 1. PLAN — ok
Android TV search: a search that was replaced by newer typing no longer wipes the newer results when its late answer arrives.
Files: .dispatch/plans/search-results-dissapear-after-few.md
Blockers: Do we keep the small automatic test that catches this bug?; Do we also look for a cause on Windows and the LG TV?

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
| claude-opus-5-5 | 38 | 81938 | 891494 | 17648 | 991118 |
