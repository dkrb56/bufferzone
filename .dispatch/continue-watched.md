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
BUILD_PENDING

## Follow-ups
- Version bump and release: not done. The plan keeps `package.json` unchanged. Your release routine (bump the patch version, build, push, publish the six files) is still open.
- The list is now long. With "Normalno" selected it has 50 rows, before it had 17. You can ask for a limit in a new ticket.
- Two rows for one series: "Curb Your Enthusiasm" shows twice (S08E10 and S10E04). The duplicate check works per episode, not per series. This was so before the change; you saw it already with "All".
- Five rows show no "min left" (for example The Orville, Fawlty Towers, Derek). The server sends these as next episodes without progress. This was so before the change.
- If the watchlist load fails, the app treats every title as "not on the watchlist". Then all titles in progress show, also with a folder selected.
- Android: until the watchlist arrives, all titles show for a moment. Then the hidden watchlist titles go away.
- The brief says Android takes its renderer from this folder. That is not true: the Android app is native Kotlin with its own copy of this filter.

## Not tried
- Playback: I did not play a title for one minute. A test stream changes real watch state on the friend's server (see `.claude/memory.md`). Your account already had titles in progress that are not on the watchlist, so the check used those.
- The TV remote on a real webOS TV and the Android app on a device. The row markup and the key handling did not change.
- A managed profile with parental rules, a server that answers the Continue watching hub with an error, and a profile switch on home.
