# Mistakes

- 2026-10-24: A UI test called `app.saveFolders([...])` and later `app.saveFolders([])` on the user's real settings. This replaced and then deleted the user's own watchlist folder. Nothing could restore it: `settings.json` has no backup.
  Rule: tests against the user's real app must never overwrite user data. Add test data next to the existing data, remove only that test data, and read the current value first.
- 2026-10-24: A playback test set `video.volume = 0`; the player saves the volume, and the test then wrote `volume: 1` without reading the old value first. Same rule as above: read `app.settings.volume` before the test and restore that value.
- 2026-10-25: `gh pr merge` made the merge commit on GitHub's side with the account's primary email (krneker@gmail.com), on the now public repo. Rule: merge pull requests locally (`git merge --no-ff`, repo email is the noreply address), then push; never merge through the GitHub API or website unless the account's email privacy setting is on.
