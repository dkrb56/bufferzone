# android-tv-install-updates-from-inside: Android TV: install updates from inside the app

Dispatch wrote this brief from ticket android-tv-install-updates-from-inside of the tickets board on 2026-11-20 20:56 UTC (No triage).

## The user's words
When GitHub has a newer release, the app downloads the APK and starts the Android install itself.

## Why
The Open button in Settings opens the GitHub page in a browser. Many TVs have no browser, so the user cannot update.

## Context
The release check is in AppState.kt (GitHub releases/latest API). The row is in SettingsScreen.kt. The install needs the permission REQUEST_INSTALL_PACKAGES, and the user allows "Install unknown apps" once. The APK must use the same signing key and a higher versionCode.

## Constraints
Do not change: The update check itself and the webOS and desktop apps.
CLAUDE.md and the project rules hold.

## The command that proves the work
Done when: On a TV with an older version, one click on Update and the Android confirm screen install the new version.
The plan run names the commands that show it under Acceptance.

## Step lines for Dispatch

Dispatch shows the progress of this run to the user from these lines. Before you start each numbered item of the section Steps of the plan, write one line of text in your message, on its own line: `Step <number>: <what the step does, in at most 8 plain words>`. Write it once per step, as normal text and not through a tool. Use this form for nothing else.
