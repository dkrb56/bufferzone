# search-recent: show recent searches in the search dialog

## User's words
"show recent searches here" — with a screenshot of the search dialog in its empty state:
the search field is empty, the filter chips All / Series / Movies / Episodes stand under it,
and the body shows only the text "Search for a title." The user wants recent searches in that empty body.

## Constraints
- The empty state of the search dialog (renderer/js/search.js, text "Search for a title.") shows the user's recent searches.
- Keyboard use stays as the footer says: arrows move, Enter opens, Ctrl Enter plays, Esc closes. A recent search is reachable with the keys and with the mouse.
- The recent searches survive a restart of the app.
- The webOS build takes its search.js from renderer; the change must not break the TV remote.
- No new dependency. No change to the Plex requests.
- Stay inside the request: no other change to the dialog.

## Proof
- `node --check renderer/js/search.js` exits 0.
- `node scripts/build-all.js` builds without an error (or the webOS step alone, `node build.js` in webos/, if the full build needs tooling that is missing).
- Manual, for the tester: open the app, search for two titles and open one, close the dialog, open it again with an empty field: the two searches stand in the body; a click or Enter on one runs that search again; restart the app: they are still there.
