# add-loading-indicator-when-search-is: loading indicator in the search dialog while a search runs

## User's words
"add loading indicator when search is executing in the search box - until data is retrieved"

## Context
The search dialog (renderer/js/search.js) asks the Plex server after a pause in typing and
draws the results when they arrive. Between the request and the answer the user sees no sign
that a search runs. The ticket asks for a loading indicator in the search box during that time.

## Constraints
- The indicator shows from the moment the search request goes out until the results are drawn, also when the answer is an error.
- The indicator stands in the search box area of the dialog; the plan run decides the exact place and form.
- An older answer that arrives after a newer request does not hide the indicator of the newer request.
- Keyboard use stays as the footer says: arrows move, Enter opens, Ctrl Enter plays, Esc closes.
- The recent searches of the empty state (commit 936bdb4) stay as they are.
- The webOS build copies renderer/; the indicator must work on the TV and must not break the remote. The Android app is native Kotlin and is out of scope unless the plan run says the same text or behaviour exists there; then it is a decision.
- No new dependency. No change to the Plex requests.
- Stay inside the request: no other change to the dialog.

## Proof
- `node --check renderer/js/search.js` exits 0.
- `node scripts/build-all.js` builds without an error (or the webOS step alone, `node build.js` in webos/, if the full build needs tooling that is missing).
- Manual, for the tester: open the search, type a title: the indicator shows while the app waits for the server and goes away when the results stand in the list. Type fast and change the words: the indicator stays until the last answer is in. Switch off the network and search: the indicator goes away when the error shows.
