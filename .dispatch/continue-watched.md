state: done
needs: nothing
files: renderer/js/views.js, android/app/src/main/java/com/dkrb56/bufferzone/HomeScreen.kt, README.md
plan: .dispatch/plans/continue-watched.md

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
- `node scripts/build-all.js` ended with exit code 0. It printed the six files: `BufferZone-0.2.8-setup.exe` 122.0 MB, its `.blockmap` 0.1 MB, `latest.yml`, `BufferZone-0.2.8-portable.exe` 108.0 MB, `BufferZone-tv-0.2.8.apk` 20.6 MB, `com.dkrb56.bufferzone_0.2.8_all.ipk` 0.3 MB. Gradle printed `BUILD SUCCESSFUL in 32s` after `:app:compileDebugKotlin`, with no warning for `HomeScreen.kt`.
- `grep -c "wlGuids" webos/build/app/js/views.js; git status --short` printed `2`: the webOS package has the new rule.
- `npx electron . --user-data-dir="$CLAUDE_JOB_DIR/tmp/bz-profile" --remote-debugging-port=9333` started the dev app on a separate test profile (copies of your settings, token and key file). Your own app was open, so the test did not use your settings folder.
- `node "$CLAUDE_JOB_DIR/tmp/cw-check.js" 9333` read the home view and compared the old and the new rule on the same server data. It does not play or change anything. Result, with the folder "Normalno" selected:
  - The old rule showed 17 rows. The new rule shows 50 rows. The 33 new rows are all titles that are not on the watchlist (for example Murderbot "Watched today 21:50", Man of War, Warfare, Doctor Who (2005)).
  - The 17 rows from before still show.
  - The rows on screen are the same as the new rule (`screenMatchesNewRule: true`) and sort newest first (`screenSortedNewestFirst: true`).
  - Each row has its thumbnail, title, episode, minutes left and "Watched" text, as before.
  - The "more" link says "12 more in progress outside these folders · Show". These 12 are watchlist titles in your other folder.
  - Next up was empty: all its titles are already in Continue watching.
- `ls "$CLAUDE_JOB_DIR/tmp/bz-profile" | head -30; echo ---; grep -c "" "$CLAUDE_JOB_DIR/tmp/bz-profile/buffer-zone.log" 2>&1; echo ---; sha256sum "$APPDATA/Buffer Zone/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json"; cmp "$CLAUDE_JOB_DIR/tmp/bz-profile/settings.json" "$CLAUDE_JOB_DIR/tmp/settings.before.json" && echo "test profile settings unchanged"` showed that the dev app wrote its log into the test profile. Your `settings.json` has the same hash as before the test. I then deleted the test profile with the token copy.
- `git status --short` after the commit printed nothing. The commit is `4a9e1af` on `main`. Nothing is pushed.

## Follow-ups
- Version bump and release: not done. The plan keeps `package.json` unchanged. Your release routine (bump the patch version, build, push, publish the six files) is still open.
- The list is now long. With "Normalno" selected it has 50 rows, before it had 17. You can ask for a limit in a new ticket.
- Two rows for one series: "Curb Your Enthusiasm" shows twice (S08E10 and S10E04). The duplicate check works per episode, not per series. This was so before the change; you saw it already with "All".
- Five rows show no "min left" (for example The Orville, Fawlty Towers, Derek). The server sends these as next episodes without progress. This was so before the change.
- If the watchlist load fails, the app treats every title as "not on the watchlist". Then all titles in progress show, also with a folder selected.
- Android: until the watchlist arrives, all titles show for a moment. Then the hidden watchlist titles go away.
- The brief says Android takes its renderer from this folder. That is not true: the Android app is native Kotlin with its own copy of this filter.

## Not tried
- Playback (deviation from plan step 6): I did not play a title for one minute. A test stream changes real watch state on the friend's server (see `.claude/memory.md`). Your account already had titles in progress that are not on the watchlist, so the check used those.
- The TV remote on a real webOS TV and the Android app on a device. The row markup and the key handling did not change.
- A managed profile with parental rules, a server that answers the Continue watching hub with an error, and a profile switch on home.
