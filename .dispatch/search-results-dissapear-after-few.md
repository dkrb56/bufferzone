state: done
needs: nothing
files: android/app/src/main/java/com/dkrb56/bufferzone/SearchScreen.kt, android/app/build.gradle.kts, android/app/src/test/java/com/dkrb56/bufferzone/SearchCancelTest.kt
plan: .dispatch/plans/search-results-dissapear-after-few.md

## What changed
- `SearchScreen.kt`: new helper `searchOrEmpty`. It returns an empty map on an error and throws a cancel again. The search effect writes `results` and `searching` only after a normal return. The `isActive` guard and import are gone.
- `build.gradle.kts`: added `testImplementation("junit:junit:4.13.2")`.
- New test `SearchCancelTest.kt`: a cancelled search must not return a result.

## Why
`runCatching` caught the cancel that `withContext(Dispatchers.IO)` throws. A dropped search then wrote an empty list over the newest results.

## Files
See the `files` line. Decisions: keep the test (yes); Android only (yes).

## Verification
- `gradle -p android test` ran as written: `gradle: command not found` (not on PATH). Fallback `gradlew.bat test` in `android/`:
  - Red (helper with `runCatching`): `SearchCancelTest > cancelledSearchDoesNotReturn FAILED`, `BUILD FAILED`.
  - Green (after the fix): `BUILD SUCCESSFUL`. Also with `--rerun-tasks`. Result file: `tests="1" failures="0"`.
- `gradlew.bat assembleDebug`: `BUILD SUCCESSFUL`.
- One run right after the fix printed `BUILD FAILED in 23s`. I saw only its tail, and a rerun passed. I did not find the cause.

## Follow-ups
- Install `gradle` on the PATH, or change `.dispatch/checks.txt` to use `gradlew.bat`.

## Not tried
- The manual test on a real Android TV with a slow server: type a title with pauses, wait 20 seconds, and the results must stay.
- Windows and LG TV: not looked at again (Decision 2).
