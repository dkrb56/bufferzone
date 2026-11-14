# continue-watched: Continue watching also shows watched titles that are not on the watchlist

## User's words
"show titles which arent on watchlist here but were watched" — with a screenshot of the
"Continue watching" panel (renderer/js/views.js): a list of episodes with the thumbnail,
the title, the episode, the minutes left, the progress bar and "Watched <date>".
The user wants titles in this panel that were watched but are not on the watchlist.

## Constraints
- The "Continue watching" panel shows, in addition to what it shows today, titles the user watched that are not on the watchlist.
- The plan run finds out first what the panel filters today, and states it in the plan with the code evidence.
- A row keeps its look and its actions: thumbnail, title, episode, minutes left, progress bar, "Watched <date>", Space to play.
- The order stays by the last watched time, newest first.
- The webOS and Android builds take their renderer from this folder; the change must not break the TV remote.
- No new dependency.
- Stay inside the request: no other change to the panel.

## Proof
- `node --check renderer/js/views.js` exits 0.
- `node scripts/build-all.js` builds without an error (or the webOS step alone, `node build.js` in webos/, if the full build needs tooling that is missing).
- Manual, for the tester: play a title that is not on the watchlist for a minute, stop it, open the home screen: the title stands in "Continue watching" with its minutes left and its "Watched" time, in order of last watched. The titles that stood there before still stand there.
