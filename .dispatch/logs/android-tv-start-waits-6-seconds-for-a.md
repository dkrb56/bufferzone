# Android TV start waits 6 seconds for a dead server address (android-tv-start-waits-6-seconds-for-a) — 2026-11-24

## Plan approved
# Plan: android-tv-start-waits-6-seconds-for-a

## Summary
The Android server check stops the slow address checks for real, so it no longer waits up to 6 s for a dead address.
1 file changes (Plex.kt). Windows and webOS already continue at the first answer.
1 decision, no HOLD.

## Goal
On Android TV, the start continues right after the first server address answers. The step from the server list to the end of the server check takes less than 1 second on the Philips TV, not about 6 seconds.

## TL;DR
- The TV app checks all server addresses at the same time, but it waited for a dead address to give up before it went on.
- After the fix, the app goes on as soon as one address answers. A normal start gets about 6 seconds shorter.
- A direct address still wins over the Plex relay, and each check keeps its time limit.
- The Windows and LG TV apps already go on at the first answer, so they do not change.

## Acceptance
- `cd android && gradlew.bat assembleDebug` (with `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` when not set) → `BUILD SUCCESSFUL`.
- `gradle -p android test` (or `android\gradlew.bat -p android test`) → `BUILD SUCCESSFUL`, existing tests pass.
- `adb connect 192.168.50.60:5555` → `connected to 192.168.50.60:5555`.
- `adb -s 192.168.50.60:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk` → `Success`.
- `adb -s 192.168.50.60:5555 shell am force-stop com.dkrb56.bufferzone`, `adb -s 192.168.50.60:5555 logcat -c`, then a cold start with `adb -s 192.168.50.60:5555 shell monkey -p com.dkrb56.bufferzone -c android.intent.category.LAUNCHER 1`.
- `adb -s 192.168.50.60:5555 logcat -d -v time -s BufferZone` → the temporary lines "probe begin" and "probe end" are less than 1.0 s apart, in at least 3 cold starts. Before the fix they are about 6 s apart (brief: 0.17 s win, return after 6 s).
- `git diff --stat` before the commit → only `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`, and no temporary log line in it.

## Checks
- [x] gradle tests — the change touches the network code of the Android app; the existing tests must still pass.

## Files to change
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`, function `probe` (anchor: `val jobs = list.map { c -> launch { if (request("${c.uri}/identity", token = token, timeoutMs = 6000).ok) result.complete(c) } }`). Replace the `request(...)` call in each job with a private cancellable identity check: build the OkHttp `Call` for `${c.uri}/identity` with the same headers (`clientHeaders()` + `X-Plex-Token`) and the same 6000 ms `callTimeout`, run it with `enqueue` inside `suspendCancellableCoroutine`, and call `call.cancel()` in `invokeOnCancellation`. Result `true` only for a 2xx answer; any failure gives `false`. Reason: the blocking `execute()` in `request` ignores coroutine cancellation, so `coroutineScope` waits for the dead address until its 6 s timeout.
- Keep the 7000 ms `withTimeoutOrNull`, the two rounds (direct first, then relay) and `request()` itself unchanged.

## Files read
- `.dispatch/briefs/android-tv-start-waits-6-seconds-for-a.md` — the symptom: winner at 0.17 s, `probe()` returned 5.9 s later, 4 of 4 starts.
- `android/.../Plex.kt:197-214` — `request` runs a blocking `execute()` in `withContext(Dispatchers.IO)`; nothing cancels the OkHttp call.
- `android/.../Plex.kt:239-253` — `probe` completes a `CompletableDeferred` at the first answer, then `cancelChildren()`, but `coroutineScope` still joins all children.
- `android/.../AppState.kt:157-169` — `reconnect()` shows Loading, calls `plex.resources()`, then `connect(saved)` → `plex.connect` → `probe`.
- `android/.../AppState.kt:103` — `App.log` writes `Log.i("BufferZone", …)`.
- `android/.../Onboarding.kt:155` — the server list of onboarding also calls `probe` per server; it gets the same speed-up.
- `renderer/js/plex.js:78-89` — Windows (and webOS, built from `renderer/`) uses `Promise.any`, which resolves at the first success and does not wait for slow ones.
- `webos/src/shim.js:21` — webOS has a `Promise.any` polyfill; `webos/service/index.js:29` keeps the per-request timeout.
- `android/app/src/test/.../SearchCancelTest.kt` — the only unit test; it does not touch `probe`.
- `android/app/build.gradle*` — OkHttp 4.12.0, JUnit 4; no MockWebServer.

## Notes for the work run
- Root cause: a coroutine cancel does not stop a blocking `OkHttp execute()` inside `withContext(Dispatchers.IO)`; `withContext` returns only after the block ends. So `coroutineScope` in `first()` waits for the slowest address (6000 ms `callTimeout`).
- The measured gap (0.17 s win, return at +5.9 s) matches the 6000 ms timeout of the dead address.
- `cancelChildren()` already runs after the win; once each job cancels its `Call`, the scope ends at once. No other change in `first()` is needed.
- `suspendCancellableCoroutine` needs `import kotlinx.coroutines.suspendCancellableCoroutine` and `kotlin.coroutines.resume`; `okhttp3.Call`, `okhttp3.Callback` and `java.io.IOException` for the callback.
- In `onResponse`, close the response (`response.use { … }`) and resume with `response.isSuccessful`. In `onFailure`, resume with `false`. Guard with `if (cont.isActive)` so a late callback after cancel does nothing.
- Build the client with `http.newBuilder().callTimeout(6000, TimeUnit.MILLISECONDS).build()`, as `request` does for a non-default timeout.
- Temporary log lines for the measurement only: `App.log("probe begin")` / `App.log("probe end")` around the `plex.connect(res)` call or inside `probe`. Remove them before the commit.
- `JAVA_HOME` default from `scripts/build-all.js`: `C:\Program Files\Android\Android Studio\jbr`.
- Measure at least 3 cold starts with `am force-stop` before each; the brief's numbers came from 4 starts.
- Project memory asks for a version bump, `npm run build:all`, a push and a GitHub release after each task. Dispatch rules forbid push for a worker; leave this to the user and name it in Follow-ups.

## Steps
1. Add the temporary log lines "probe begin" and "probe end" around the probe. Build, install, and measure 3 cold starts on the TV to confirm the ~6 s gap before the fix. Record the times in the report.
2. In `Plex.kt`, add the private cancellable identity check and use it in `probe`'s jobs.
3. Build, install, and measure 3 cold starts again. Each gap must be under 1.0 s.
4. Check that the app still reaches the home screen and that the server label shows the same kind of connection as before (local or remote, not relay).
5. Remove the temporary log lines. Run `gradle -p android test` and `assembleDebug` again.
6. Commit only `Plex.kt` with the ticket id in the message. Write the report.

## Decisions
1. Should only the server check stop its slow tries, or every network request of the TV app?
   - Default: Only the server check stops its slow tries.
   Recommended: it fixes the wait and changes nothing else in the app.
   - Other: Every request of the TV app stops when the screen no longer needs it.
   Not recommended: it touches all screens and needs more testing than this ticket asks.

## Do not change
- The order of preference: direct addresses first, the Plex relay only when no direct one answers.
- The 6000 ms limit of each address check and the 7000 ms limit of each round.
- `request()` in `Plex.kt` and all other callers.
- `renderer/js/plex.js`, `webos/` and `AppState.kt` (except the temporary log lines, removed before the commit).
- The version in `package.json`; no build:all, push or release.

## Size
1 file (`Plex.kt`), about 25 lines.

## Work run
model: opus
effort: medium
The code change is small and clear; most of the work is the measurement on the TV.

## User decisions

The user took these decisions in Dispatch on 2026-11-24 18:57. Each replaces the Default, HOLD and Other options of the decision with the same number.

1. Should only the server check stop its slow tries, or every network request of the TV app?: Only the server check stops its slow tries

## Checks chosen

The user chose these checks in Dispatch on 2026-11-24 17:57 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.

- [ ] android/gradle tests

## Stages

### STAGE: PLAN
### EVIDENCE: PLAN 2026-11-24T17:53:07.869Z
COMMAND: cat .dispatch/checks.txt; ls; cat .dispatch/android-tv-start-waits-6-seconds-for-a.models; ls .dispatch/plans | tail -5
EXIT: 0
OUTPUT:
    README.md
    Start Buffer Zone.bat
    THIRD_PARTY_NOTICES.txt
    android
    build
    dist
    main.js
    node_modules
    package-lock.json
    package.json
    preload.js
    renderer
    scripts
    webos
    plan opus medium
    playback-issue-not-sure-why.md
    player-debug-overlay.md
    search-recent.md
    search-results-dissapear-after-few.md
    when-i-add-show-to-wathclist-there.md

### EVIDENCE: PLAN 2026-11-24T17:53:27.745Z
COMMAND: ls webos; grep -rn "probe\|plex.js" webos --include=*.js --include=*.html --include=*.json -l | grep -v node_modules | head; grep -rn "connect(" android/app/src/main/java | head; grep -rn "Log\.\(d\|i\)(" android/app/src/main/java | head -5; ls android/app/src/test -R | head -20; cat .dispatch/plan-flags.txt; git log --oneline -3 -- android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt
EXIT: 0
OUTPUT:
    android/app/src/test/java/com/dkrb56:
    bufferzone
    
    android/app/src/test/java/com/dkrb56/bufferzone:
    SearchCancelTest.kt
    --model
    fable
    --effort
    xhigh
    --permission-mode
    dontAsk
    --tools
    Read,Grep,Glob,Bash,WebFetch,Write
    --allowedTools
    WebFetch,Edit(.dispatch/plans/**)
    --disallowedTools
    Edit(.dispatch/briefs/**),Bash(powershell -ExecutionPolicy Bypass -File .dispatch/worker.ps1 *)
    --settings
    .dispatch/worker-settings.json
    91dcdfd Android TV and LG webOS versions, TV remote fixes

### EVIDENCE: PLAN 2026-11-24T17:53:37.624Z
COMMAND: sed -n 95,200p android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt; ls webos/src webos/src/* | head -30; head -40 webos/build.js; grep -n "probe\|Promise.any" webos/build/app/js/plex.js; head -60 android/app/src/test/java/com/dkrb56/bufferzone/SearchCancelTest.kt; grep -n "okhttp\|mockwebserver\|testImpl" android/app/build.gradle*
EXIT: 0
OUTPUT:
    import kotlinx.coroutines.withContext
    import org.junit.Assert.assertNull
    import org.junit.Test
    
    class SearchCancelTest {
        @Test
        fun cancelledSearchDoesNotReturn() = runBlocking {
            var after: Map<String, List<Item>>? = null
            val job = launch {
                after = searchOrEmpty("ba") { withContext(Dispatchers.IO) { Thread.sleep(300); emptyMap() } }
            }
            delay(50)
            job.cancel()
            job.join()
            assertNull("a cancelled search must not return a result", after)
        }
    }
    42:    testImplementation("junit:junit:4.13.2")
    55:    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    57:    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

### EVIDENCE: PLAN 2026-11-24T17:53:41.920Z
COMMAND: grep -n "Promise.any\|Promise\.any *=" webos/src/shim.js renderer/js/*.js | head; grep -n "http\b\|http(" webos/src/shim.js | head -5; grep -n "timeout" webos/service/*.js 2>/dev/null | head -5; ls webos/service
EXIT: 0
OUTPUT:
    webos/src/shim.js:21:  if (!Promise.any) {
    webos/src/shim.js:22:    Promise.any = (list) => new Promise((resolve, reject) => {
    renderer/js/plex.js:86:      try { return await Promise.any(conns.filter(c => !c.relay).map(test)); } catch {}
    renderer/js/plex.js:87:      try { return await Promise.any(conns.filter(c => c.relay).map(test)); } catch {}
    86:  const SERVICE = 'luna://com.dkrb56.bufferzone.service/http';
    91:  async function http(req) {
    148:      console.warn('http error', method, String(url).replace(/(X-Plex-Token=)[^&]+/gi, '$1***'), String(err));
    160:    http,
    11:function send(url, method, headers, timeout, redirects, done) {
    20:      return send(urlLib.resolve(url, res.headers.location), method, headers, timeout, redirects + 1, done);
    29:  req.setTimeout(timeout, function () { req.abort(); });
    38:  send(p.url, p.method || 'GET', p.headers || {}, p.timeout || 15000, 0, function (r) {
    index.js
    package.json
    services.json

### EVIDENCE: PLAN 2026-11-24T17:53:52.007Z
COMMAND: ls scripts; grep -n "adb\|assemble\|installDebug" -r scripts package.json .claude 2>/dev/null | head -15; ls .claude 2>/dev/null
EXIT: 0
OUTPUT:
    build-all.js
    build-icons.js
    scripts/build-all.js:21:run(`"${path.join(ROOT, 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" assembleDebug`, path.join(ROOT, 'android'), { JAVA_HOME: JDK });
    memory.md
    mistakes.md
    settings.json
    settings.local.json
    state
    triage.md

### EVIDENCE: PLAN 2026-11-24T17:53:56.667Z
COMMAND: cat .claude/memory.md | head -60; grep -in "adb\|probe\|okhttp\|android" .claude/mistakes.md | head; sed -n 1,30p scripts/build-all.js
EXIT: 0
OUTPUT:
    const ROOT = path.resolve(__dirname, '..');
    const DIST = path.join(ROOT, 'dist');
    const VERSION = require(path.join(ROOT, 'package.json')).version;
    const JDK = process.env.JAVA_HOME || 'C:\\Program Files\\Android\\Android Studio\\jbr';
    const run = (cmd, cwd, env) => execSync(cmd, { cwd, stdio: 'inherit', env: { ...process.env, ...env } });
    
    console.log(`\n== Buffer Zone ${VERSION}: Windows`);
    run('npx electron-builder --win nsis portable --publish never', ROOT);
    
    console.log('\n== Android TV');
    run(`"${path.join(ROOT, 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" assembleDebug`, path.join(ROOT, 'android'), { JAVA_HOME: JDK });
    fs.copyFileSync(path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'), path.join(DIST, `BufferZone-tv-${VERSION}.apk`));
    
    console.log('\n== LG webOS');
    run('node build.js', path.join(ROOT, 'webos'));
    
    const files = [`BufferZone-${VERSION}-setup.exe`, `BufferZone-${VERSION}-setup.exe.blockmap`, 'latest.yml', `BufferZone-${VERSION}-portable.exe`, `BufferZone-tv-${VERSION}.apk`, `com.dkrb56.bufferzone…
    for (const f of files) {
      if (!fs.existsSync(path.join(DIST, f))) throw new Error('missing ' + f);
      console.log(`${f}  ${(fs.statSync(path.join(DIST, f)).size / 1048576).toFixed(1)} MB`);

### 1. PLAN — ok
The Android server check stops the slow address checks for real, so it no longer waits up to 6 s for a dead address.
Files: .dispatch/plans/android-tv-start-waits-6-seconds-for-a.md
Blockers: Should only the server check stop its slow tries, or every network request of the TV app?

### STAGE: WORK
### EVIDENCE: WORK 2026-11-24T17:57:13.965Z
COMMAND: cd /d/dev_projects/buffer-zone; cat .dispatch/briefs/android-tv-start-waits-6-seconds-for-a.md; echo ======; cat .dispatch/plans/android-tv-start-waits-6-seconds-for-a.md; echo =====; cat .dispatch/android-tv-start-waits-6-seconds-for-a.checks
EXIT: 0
OUTPUT:
    ## Size
    1 file (`Plex.kt`), about 25 lines.
    
    ## Work run
    model: opus
    effort: medium
    The code change is small and clear; most of the work is the measurement on the TV.
    
    ## User decisions
    
    The user took these decisions in Dispatch on 2026-11-24 18:57. Each replaces the Default, HOLD and Other options of the decision with the same number.
    
    1. Should only the server check stop its slow tries, or every network request of the TV app?: Only the server check stops its slow tries
    
    ## Checks chosen
    
    The user chose these checks in Dispatch on 2026-11-24 17:57 UTC. The work run runs each checked one with the command as written here, and names that exact command in Verification.
    
    - [ ] android/gradle tests
    =====

### EVIDENCE: WORK 2026-11-24T17:57:19.128Z
COMMAND: cd /d/dev_projects/buffer-zone; F=android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt; sed -n 1,30p $F; echo ...; sed -n 190,260p $F; cat .dispatch/checks.txt; grep -n "connect\|probe" android/app/src/main/java/com/dkrb56/bufferzone/AppState.kt | head -30
EXIT: 0
OUTPUT:
            val conns = res.connections
            return first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
        }
    
        suspend fun connect(res: Resource): Server {
            val c = probe(res) ?: throw PlexException("Cannot reach server \"${res.name}\". It may be offline.")
            val s = Server(res.clientIdentifier, res.name, c.uri.trimEnd('/'), res.accessToken ?: token ?: "", c.local, c.relay)
            server = s
            return s
        }
    android/gradle tests | gradle -p android test
    150:                go(Screen.Message("Cannot reach plex.tv", "Check the network connection of the TV.") { start() }, root = true); return@launch
    153:            reconnect()
    157:    suspend fun reconnect() {
    160:            go(Screen.Message("Cannot load servers", it.message ?: "") { scope.launch { reconnect() } }, root = true); return
    163:        try { connect(saved) } catch (e: Exception) {
    164:            go(Screen.Message("Cannot connect", e.message ?: "") { go(Screen.Onboard(2), root = true) }, root = true); return
    171:    suspend fun connect(res: Resource) {
    172:        plex.connect(res)
    190:            plex.connect(res)

### EVIDENCE: WORK 2026-11-24T17:57:24.449Z
COMMAND: cd /d/dev_projects/buffer-zone; F=android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt; python - <<'EOF'
    p='android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt'
    s=open(p,encoding='utf-8').read()
    a='''        val conns = res.connections
            return first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })'''
    b='''        val conns = res.connections
            android.util.Log.i("BufferZone", "TMP probe begin")
            val tmpR = first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
            android.util.Log.i("BufferZone", "TMP probe end ${tmpR?.uri}")
            return tmpR'''
    assert s.count(a)==1
    open(p,'w',encoding='utf-8',newline='').write(s.replace(a,b))
    EOF
    git diff --stat; echo "JAVA_HOME=$JAVA_HOME"; which adb gradle
EXIT: 1
OUTPUT:
    Exit code 1
    warning: in the working copy of '.dispatch/android-tv-start-waits-6-seconds-for-a.models', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/android-tv-start-waits-6-seconds-for-a.session', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/android-tv-start-waits-6-seconds-for-a.state', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/briefs/android-tv-start-waits-6-seconds-for-a.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/logs/android-tv-start-waits-6-seconds-for-a.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of '.dispatch/plans/android-tv-start-waits-6-seconds-for-a.md', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt', LF will be replaced by CRLF the next time Git touches it
     .../android-tv-start-waits-6-seconds-for-a.models  |  1 +
     .../android-tv-start-waits-6-seconds-for-a.session |  1 +
     .../android-tv-start-waits-6-seconds-for-a.state   |  2 +-
     .../android-tv-start-waits-6-seconds-for-a.md      |  4 ++
     .../logs/android-tv-start-waits-6-seconds-for-a.md | 65 +++++++++++++++++++++-
     .../android-tv-start-waits-6-seconds-for-a.md      | 12 ++++
     .../src/main/java/com/dkrb56/bufferzone/Plex.kt    |  5 +-
     7 files changed, 86 insertions(+), 4 deletions(-)
    JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\
    which: no gradle in (/c/Users/dani/bin:/mingw64/bin:/usr/local/bin:/usr/bin:/bin:/mingw64/bin:/usr/bin:/c/Users/dani/bin:/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot/bin:/c/Program Files/Oculus/…
    /c/Users/dani/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb

### EVIDENCE: WORK 2026-11-24T17:57:27.921Z
COMMAND: cd /d/dev_projects/buffer-zone/android; ./gradlew.bat assembleDebug -q 2>&1 | tail -15; echo EXIT $?; adb connect 192.168.50.60:5555
EXIT: 0
OUTPUT:
    EXIT 0
    already connected to 192.168.50.60:5555

### EVIDENCE: WORK 2026-11-24T17:58:47.361Z
COMMAND: cd /d/dev_projects/buffer-zone; ls -la android/app/build/outputs/apk/debug/; cat > "$CLAUDE_JOB_DIR/tmp/measure.sh" <<'EOF'
    D=192.168.50.60:5555
    P=com.dkrb56.bufferzone
    for i in 1 2 3; do
      adb -s $D shell am force-stop $P
      adb -s $D logcat -c
      adb -s $D shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null
      for t in $(seq 1 40); do
        out=$(adb -s $D logcat -d -v time -s BufferZone)
        echo "$out" | grep -q "TMP probe end" && break
        sleep 1
      done
      echo "--- start $i"; echo "$out" | grep -E "TMP probe|BufferZone" | tail -8
    done
    EOF
    adb -s 192.168.50.60:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk && bash "$CLAUDE_JOB_DIR/tmp/measure.sh"
EXIT: 0
OUTPUT:
     arg: "android.intent.category.LAUNCHER"
     arg: "1"
    data="com.dkrb56.bufferzone"
    data="android.intent.category.LAUNCHER"
    --- start 2
    11-24 18:59:09.686 I/BufferZone( 7030): start
    11-24 18:59:11.760 I/BufferZone( 7030): TMP probe begin
    11-24 18:59:17.780 I/BufferZone( 7030): TMP probe end http://plex.v12.appboxmanager.xyz:80
    args: [-p, com.dkrb56.bufferzone, -c, android.intent.category.LAUNCHER, 1]
     arg: "-p"
     arg: "com.dkrb56.bufferzone"
     arg: "-c"
     arg: "android.intent.category.LAUNCHER"
     arg: "1"
    data="com.dkrb56.bufferzone"
    data="android.intent.category.LAUNCHER"
    --- start 3
    11-24 18:59:22.598 I/BufferZone( 7162): start
    11-24 18:59:24.470 I/BufferZone( 7162): TMP probe begin
    11-24 18:59:30.485 I/BufferZone( 7162): TMP probe end http://plex.v12.appboxmanager.xyz:80

### EVIDENCE: WORK 2026-11-24T17:59:39.555Z
COMMAND: cd /d/dev_projects/buffer-zone; python - <<'EOF'
    p='android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt'
    s=open(p,encoding='utf-8').read()
    def rep(a,b):
        global s
        assert s.count(a)==1,a
        s=s.replace(a,b)
    rep('import kotlinx.coroutines.launch\n','import kotlinx.coroutines.launch\nimport kotlinx.coroutines.suspendCancellableCoroutine\n')
    rep('import okhttp3.OkHttpClient\n','import okhttp3.Call\nimport okhttp3.Callback\nimport okhttp3.OkHttpClient\n')
    rep('import org.json.JSONTokener\n','import org.json.JSONTokener\nimport java.io.IOException\n')
    rep('import java.util.concurrent.TimeUnit\n','import java.util.concurrent.TimeUnit\nimport kotlin.coroutines.resume\n')
    rep('''    /** Tries all connections in parallel;''','''    /** GET <uri>/identity; true on a 2xx answer. Cancelling the coroutine cancels the HTTP call, so a dead address does not hold up the caller. */
        private suspend fun identityOk(uri: String, token: String?): Boolean {
            val b = Request.Builder().url("$uri/identity").get()
            clientHeaders().forEach { (k, v) -> b.header(k, v) }
            if (token != null) b.header("X-Plex-Token", token)
            val call = runCatching { http.newBuilder().callTimeout(6000, TimeUnit.MILLISECONDS).build().newCall(b.build()) }.getOrNull() ?: return false
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(false) }
                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        val ok = response.use { it.isSuccessful }
                        if (cont.isActive) cont.resume(ok)
                    }
                })
            }
        }
    
        /** Tries all connections in parallel;''')
    rep('launch { if (request("${c.uri}/identity", token = token, timeoutMs = 6000).ok) result.complete(c) }','launch { if (identityOk(c.uri, token)) result.complete(c) }')
    open(p,'w',encoding='utf-8',newline='').write(s)
    EOF
    git diff android/
EXIT: 0
OUTPUT:
    @@ -242,14 +265,17 @@ class Plex(val clientId: String) {
             suspend fun first(list: List<Connection>): Connection? = coroutineScope {
                 if (list.isEmpty()) return@coroutineScope null
                 val result = CompletableDeferred<Connection?>()
    -            val jobs = list.map { c -> launch { if (request("${c.uri}/identity", token = token, timeoutMs = 6000).ok) result.complete(c) } }
    +            val jobs = list.map { c -> launch { if (identityOk(c.uri, token)) result.complete(c) } }
                 launch { jobs.joinAll(); result.complete(null) }
                 val r = withTimeoutOrNull(7000) { result.await() }
                 coroutineContext.cancelChildren()
                 r
             }
             val conns = res.connections
    -        return first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
    +        android.util.Log.i("BufferZone", "TMP probe begin")
    +        val tmpR = first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
    +        android.util.Log.i("BufferZone", "TMP probe end ${tmpR?.uri}")
    +        return tmpR
         }
     
         suspend fun connect(res: Resource): Server {

### EVIDENCE: WORK 2026-11-24T17:59:46.512Z
COMMAND: cd /d/dev_projects/buffer-zone; python - <<'EOF'
    p='android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt'
    s=open(p,encoding='utf-8').read()
    a='''        val b = Request.Builder().url("$uri/identity").get()
            clientHeaders().forEach { (k, v) -> b.header(k, v) }
            if (token != null) b.header("X-Plex-Token", token)
            val call = runCatching { http.newBuilder().callTimeout(6000, TimeUnit.MILLISECONDS).build().newCall(b.build()) }.getOrNull() ?: return false
    '''
    b='''        val call = runCatching {
                val b = Request.Builder().url("$uri/identity").get()
                clientHeaders().forEach { (k, v) -> b.header(k, v) }
                if (token != null) b.header("X-Plex-Token", token)
                http.newBuilder().callTimeout(6000, TimeUnit.MILLISECONDS).build().newCall(b.build())
            }.getOrNull() ?: return false
    '''
    assert s.count(a)==1
    open(p,'w',encoding='utf-8',newline='').write(s.replace(a,b))
    EOF
    cd android && ./gradlew.bat assembleDebug -q 2>&1 | tail -15; echo EXIT ${PIPESTATUS[0]}
EXIT: 0
OUTPUT:
    EXIT 0

### EVIDENCE: WORK 2026-11-24T18:00:15.421Z
COMMAND: cd /d/dev_projects/buffer-zone; ls -la android/app/build/outputs/apk/debug/app-debug.apk; adb -s 192.168.50.60:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk && bash "$CLAUDE_JOB_DIR/tmp/measure.sh" 2>&1 | grep -v "arg\|data="
EXIT: 0
OUTPUT:
    -rw-r--r-- 1 dani 197121 21593912 Nov 24 19:00 android/app/build/outputs/apk/debug/app-debug.apk
    Performing Streamed Install
    Success
    --- start 1
    11-24 19:00:23.456 I/BufferZone( 7392): start
    11-24 19:00:26.885 I/BufferZone( 7392): TMP probe begin
    11-24 19:00:27.042 I/BufferZone( 7392): TMP probe end http://plex.v12.appboxmanager.xyz:80
    --- start 2
    11-24 19:00:30.614 I/BufferZone( 7495): start
    11-24 19:00:33.537 I/BufferZone( 7495): TMP probe begin
    11-24 19:00:33.705 I/BufferZone( 7495): TMP probe end http://plex.v12.appboxmanager.xyz:80
    --- start 3
    11-24 19:00:37.467 I/BufferZone( 7598): start
    11-24 19:00:40.091 I/BufferZone( 7598): TMP probe begin
    11-24 19:00:40.258 I/BufferZone( 7598): TMP probe end http://plex.v12.appboxmanager.xyz:80

### EVIDENCE: WORK 2026-11-24T18:00:44.447Z
COMMAND: cd /d/dev_projects/buffer-zone; sleep 8; adb -s 192.168.50.60:5555 logcat -d -v time -s BufferZone | tail -5; adb -s 192.168.50.60:5555 exec-out screencap -p > "$CLAUDE_JOB_DIR/tmp/home.png"; ls -la "$CLAUDE_JOB_DIR/tmp/home.png"
EXIT: 0
OUTPUT:
    --------- beginning of system
    --------- beginning of main
    11-24 19:00:37.467 I/BufferZone( 7598): start
    11-24 19:00:40.091 I/BufferZone( 7598): TMP probe begin
    11-24 19:00:40.258 I/BufferZone( 7598): TMP probe end http://plex.v12.appboxmanager.xyz:80
    -rw-r--r-- 1 dani 197121 244931 Nov 24 19:00 C:\Users\dani\.claude\jobs\d2d29cc2/tmp/home.png

### EVIDENCE: WORK 2026-11-24T18:00:58.924Z
COMMAND: cd /d/dev_projects/buffer-zone; python - <<'EOF'
    p='android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt'
    s=open(p,encoding='utf-8').read()
    a='''        android.util.Log.i("BufferZone", "TMP probe begin")
            val tmpR = first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
            android.util.Log.i("BufferZone", "TMP probe end ${tmpR?.uri}")
            return tmpR'''
    b='''        return first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })'''
    assert s.count(a)==1
    open(p,'w',encoding='utf-8',newline='').write(s.replace(a,b))
    EOF
    grep -n TMP android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt; which gradle || echo "no gradle on PATH"
EXIT: 0
OUTPUT:
    which: no gradle in (/c/Users/dani/bin:/mingw64/bin:/usr/local/bin:/usr/bin:/bin:/mingw64/bin:/usr/bin:/c/Users/dani/bin:/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot/bin:/c/Program Files/Oculus/…
    no gradle on PATH

### EVIDENCE: WORK 2026-11-24T18:01:04.476Z
COMMAND: cd /d/dev_projects/buffer-zone; gradle -p android test; echo EXIT $?; android/gradlew.bat -p android test 2>&1 | tail -5; echo EXIT ${PIPESTATUS[0]}; cd android && ./gradlew.bat assembleDebug 2>&1 | tail -3; echo EXIT ${PIPESTATUS[0]}
EXIT: 0
OUTPUT:
    /usr/bin/bash: line 1: gradle: command not found
    EXIT 127
    > Task :app:test
    
    BUILD SUCCESSFUL in 25s
    25 actionable tasks: 5 executed, 20 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
    EXIT 0
    BUILD SUCCESSFUL in 2s
    37 actionable tasks: 3 executed, 34 up-to-date
    Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
    EXIT 0

### EVIDENCE: WORK 2026-11-24T18:01:35.023Z
COMMAND: cd /d/dev_projects/buffer-zone; adb -s 192.168.50.60:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk; git diff --stat -- android; git status --short
EXIT: 0
OUTPUT:
    Performing Streamed Install
    Success
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt', LF will be replaced by CRLF the next time Git touches it
     .../src/main/java/com/dkrb56/bufferzone/Plex.kt    | 27 +++++++++++++++++++++-
     1 file changed, 26 insertions(+), 1 deletion(-)
     M .dispatch/android-tv-start-waits-6-seconds-for-a.models
     M .dispatch/android-tv-start-waits-6-seconds-for-a.session
     M .dispatch/android-tv-start-waits-6-seconds-for-a.state
     M .dispatch/briefs/android-tv-start-waits-6-seconds-for-a.md
     M .dispatch/logs/android-tv-start-waits-6-seconds-for-a.md
     M .dispatch/plans/android-tv-start-waits-6-seconds-for-a.md
     M android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt
    ?? .dispatch/android-tv-start-waits-6-seconds-for-a.checks

### EVIDENCE: WORK 2026-11-24T18:01:43.064Z
COMMAND: cd /d/dev_projects/buffer-zone; git add android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt && git commit -q -m "android-tv-start-waits-6-seconds-for-a: cancel slow server probes on Android
    
    probe() now checks each address with a cancellable OkHttp call, so the
    first answer ends the round at once instead of waiting up to 6 s for a
    dead address." -- android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt && git log --oneline -1
EXIT: 0
OUTPUT:
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt', LF will be replaced by CRLF the next time Git touches it
    warning: in the working copy of 'android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt', LF will be replaced by CRLF the next time Git touches it
    ded84a5 android-tv-start-waits-6-seconds-for-a: cancel slow server probes on Android

### 2. WORK — ok
The Android TV app now continues right after the first server address answers. The server check went from about 6 s to about 0.17 s on the Philips TV. Commit `ded84a5`.
Files: android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt
Blockers: none

## What changed
The Android TV app now continues right after the first server address answers. The server check went from about 6 s to about 0.17 s on the Philips TV. Commit `ded84a5`.

## Why
The app checked all addresses at the same time, but a stopped check did not stop its network call. The app then waited for the dead address to reach its 6 s time limit. Now each address check uses a network call that stops when the check stops.

## Files
- `android/app/src/main/java/com/dkrb56/bufferzone/Plex.kt`: new private function `identityOk` (async OkHttp `enqueue` inside `suspendCancellableCoroutine`, `call.cancel()` on cancel, same headers, same 6000 ms `callTimeout`). `probe` uses it instead of `request(...)`. The 7000 ms round limit, the order direct-then-relay and `request()` did not change.

## Verification
Measured on the TV 192.168.50.60:5555 with temporary log lines "TMP probe begin" / "TMP probe end" in `probe`. A script did `am force-stop`, `logcat -c`, a `monkey` launch, and read `logcat -d -v time -s BufferZone`. Each start was a cold start.

| Start | Before fix (begin → end) | After fix (begin → end) |
|---|---|---|
| 1 | 18:58:58.772 → 18:59:04.801 = 6.03 s | 19:00:26.885 → 19:00:27.042 = 0.157 s |
| 2 | 18:59:11.760 → 18:59:17.780 = 6.02 s | 19:00:33.537 → 19:00:33.705 = 0.168 s |
| 3 | 18:59:24.470 → 18:59:30.485 = 6.02 s | 19:00:40.091 → 19:00:40.258 = 0.167 s |

- The winning address was `http://plex.v12.appboxmanager.xyz:80` before and after the fix (a direct address, not the relay).
- A screenshot after start 3 showed the home screen with Continue watching content.
- I removed the temporary log lines. `grep TMP` on `Plex.kt` finds nothing. `git diff --stat` before the commit showed only `Plex.kt`.
- `gradle -p android test` (the check as written): exit 127, `gradle: command not found`. Gradle is not on PATH on this machine. The check was not ticked in Checks chosen.
- `android/gradlew.bat -p android test`: `BUILD SUCCESSFUL`, exit 0.
- `./gradlew.bat assembleDebug` in `android/`: `BUILD SUCCESSFUL`, exit 0. I installed this clean build on the TV.

## Follow-ups
- Windows and webOS already continue at the first answer (`Promise.any` in `renderer/js/plex.js`). No change there, as the plan says.
- Version bump, `npm run build:all`, push and GitHub release are for the user.
- The check command `gradle -p android test` in `.dispatch/checks.txt` fails on this machine because `gradle` is not on PATH. `android/gradlew.bat -p android test` works.
- The rest of the 13 s start (about 2–3.5 s from "start" to "probe begin", and the time after the probe) was not measured in this run.

### Token usage
| Model | Input | Cache write | Cache read | Output | Total |
|---|---|---|---|---|---|
| claude-opus-5-5 | 48 | 85021 | 615310 | 14203 | 714582 |
