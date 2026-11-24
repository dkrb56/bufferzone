# android-tv-start-waits-6-seconds-for-a: Android TV start waits 6 seconds for a dead server address

Dispatch wrote this brief from ticket android-tv-start-waits-6-seconds-for-a of the tickets board on 2026-11-24 17:53 UTC (No triage).

## The user's words
The Android TV app opens the home screen as soon as one server address answers, without the 6-second wait for an address that never answers.

## Why
The user sees the loading spinner for about 20 seconds at each start of 0.2.12 on the Philips TV. About 6 seconds of it is this wait.

## Context
Project rule: every fix goes to all platforms. Check whether the Windows and webOS start code has the same wait.

## What I see
Measured on 2026-11-24 with temporary log lines (since removed): the fast address won after 0.17 s (18:47:20.459), but probe() returned at 18:47:26.314. The same happened in all 4 measured starts.

## What I expect
The start continues right after the first address answers. A normal start is then about 6 seconds shorter (from about 13 s to about 7 s).

## Constraints
Do not change: The order of preference stays the same: a direct address wins over the Plex relay. Keep the time limits of each address check.
CLAUDE.md and the project rules hold.

## The command that proves the work
Done when: On the TV (adb 192.168.50.60:5555), the step from the server list to the end of the server check takes less than 1 second, not 6. Measure it on a cold start with log lines or logcat timestamps.
The plan run names the commands that show it under Acceptance.

## Step lines for Dispatch

Dispatch shows the progress of this run to the user from these lines. Before you start each numbered item of the section Steps of the plan, write one line of text in your message, on its own line: `Step <number>: <what the step does, in at most 8 plain words>`. Write it once per step, as normal text and not through a tool. Use this form for nothing else.
