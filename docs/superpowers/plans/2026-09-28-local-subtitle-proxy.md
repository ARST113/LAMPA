# Local Subtitle Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve Lampa's Chromium player and embedded SubRip subtitles while removing the unbounded CEF response-filter video queue.

**Architecture:** A loopback HTTP relay forwards the player's actual TorrServer requests, parsing subtitle packets before writing each block downstream. Independent response parsers share bounded metadata/text state. A coalesced UI mailbox supplies the existing subtitle overlay; CEF response tap stays off.

**Tech Stack:** Kotlin/JVM, existing OkHttp 3.12.13 and Okio, Android 10+ arm64, existing Cefrium AC3/EAC3 AAR, JavaScript, JUnit 4, Node tests, ADB.

**Spec:** `docs/superpowers/specs/2026-09-28-local-subtitle-proxy-design.md`, approved 2026-09-28.

## Global Constraints

- Keep Chromium, current player, NewTorrents, settings and AC3/EAC3. No native rebuild, GStreamer, Media3 migration or codec expansion.
- Bind only `127.0.0.1`; random session tokens, one current film, no arbitrary upstream URL endpoint. Never log upstream URLs, credentials or tokens normally.
- Four simultaneously serviced HTTP requests maximum, no unbounded executor/request queue. One reusable 64 KiB relay block per response; no queued video chunks.
- Parser bounds: about 1 MiB input, 64 KiB text block, 128 tracks. Film cache: 12000 cues / 2000000 text characters. Maximum one scheduled UI update.
- Preserve GET/HEAD, Range/conditional requests, 200/206/416 semantics, redirects and cancellation. No whole-body buffering or video disk cache.
- Route only verified TorrServer/NewTorrents playback. Preserve preload/stat, HLS/DASH, native/external subtitles, external player and casting behavior.
- Never restore the problematic CEF tap as fallback. Direct playback without embedded subtitles requires a visible explanation.
- Acceptance: at least 30 minutes of the same 4K film, separate five-minute pause, ten seeks, language/film changes. Investigate growth over 256 MiB versus a comparable tap-off baseline; continuous growth fails.
- Stop the experiment below 1 GiB MemAvailable or above 3 GiB total app RSS. These are experimental safeguards, not the product fix.
- Preserve the saved published APK and original `top.rootu.lampa` data. Keep raw diagnostics outside the repository; release only a verified APK and sanitized notes.

## Review Focus

- Late async registration after a film change must not restart the old film (Task 3).
- Concurrent Range responses must not interleave parser state or borrow another film's metadata (Task 4).
- A paused/sleeping consumer must stop upstream reads; cancellation must release blocked writers (Task 2).
- Redirects and malformed HTTP must not leak credentials, permit ambiguous framing or allow arbitrary forwarding (Task 1).
- Queued JS work and background/foreground changes must not accumulate text or repaint an expired session (Task 5).

## File structure

Under `app/src/main/java/top/rootu/lampa/helpers/` create `SubtitleProxy.kt` (sessions/workers/lifetime), `SubtitleProxyHttp.kt` (HTTP/upstream transport), `SubtitleResponseSession.kt` (one response parser), and `SubtitleUiMailbox.kt` (coalesced delivery). Modify existing `SubtitleExtractor.kt`, `MkvSubtitleStream.kt`, and `SubtitleCueCache.kt` only for this transport and its bounds.

Modify `browser/Cefrium.kt` for ownership, bridge and tap-off. Create `app/src/main/assets/subtitle-proxy.js` for source routing; modify `embedded-subtitles.js` for logical URL resolution and bounded snapshots. JVM tests sit beside existing helper tests. JS tests are `scripts/tests/subtitle-proxy.test.cjs` and the existing `scripts/tests/subtitles.test.cjs`. Diagnostics/documentation go in `scripts/diagnostics/` and `docs/`.

## Verification commands

From `lampa-cefrium-repair`, PowerShell:

```powershell
. '..\.android-subtitles-20260927\toolchain\build-env.ps1'
$aar = (Resolve-Path '..\.android-subtitles-20260927\toolchain\cefrium-sdk-arm64-ac3.aar').Path
$gradleArgs = @('--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process', '-Pandroid.overridePathCheck=true', '-PdiagnosticPackage=true', "-PcefriumAar=$aar", '-PcefriumSha256=47dc05df920b5a582d1f9825de8f53fb12856b17ddb35276caaf3bf65d9be53f')
gradle.bat @gradleArgs :app:testLiteDebugUnitTest
node --test --test-isolation=none scripts/tests/subtitles.test.cjs scripts/tests/subtitle-proxy.test.cjs
```

Before the new JS file exists, run the existing file only. Target JVM suites with `--tests 'top.rootu.lampa.helpers.ClassName'`. Require exit code zero and passing XML test reports. Keep generated files and the existing untracked `socket_700456693` out of commits.

### Task 1: HTTP contract and streaming response

**Files:** Create `SubtitleProxyHttp.kt` and `app/src/test/java/top/rootu/lampa/helpers/SubtitleProxyHttpTest.kt`.

**Interfaces:** Define `ProxySource(id: Long, originalUrl: String, pageOrigin: String, headers: Map<String,String>)` and `ProxyRequest(method: String, path: String, headers: Map<String,String>)`. Define `RelayObserver.begin(responseId: Long, sourceId: Long, finalUrl: String, status: Int, startOffset: Long): Unit`, `data(responseId: Long, bytes: ByteArray, count: Int): Unit`, `end(responseId: Long): Unit`. `SubtitleProxyHttp(client: OkHttpClient)` exposes `readRequest(socket: Socket): ProxyRequest` and `serve(socket: Socket, request: ProxyRequest, source: ProxySource, responseId: Long, observer: RelayObserver): Unit`. Read headers exactly once, resolve the session token from the parsed path before upstream I/O, then serve/close that socket and upstream call.

- [ ] Write failing `preservesRangeAndHead`, `preserves416AndZeroLength`, `streamsUnknownLength`, `rejectsAmbiguousRequest`, `redirectDoesNotForwardSecrets`, `corsMatchesRegisteredOrigin`. For `Range: bytes=9-15`, assert `status == 206`, correct Content-Range and seven exact fixture bytes; HEAD body length is zero; 416 retains `bytes */length`. Reject conflicting length/transfer headers, headers over 16 KiB, GET/HEAD request bodies and unsupported methods before contacting upstream. CORS allows only the registered origin; bounded OPTIONS is answered locally. Use a synthetic local upstream, not a private movie URL.
- [ ] Run `SubtitleProxyHttpTest`; confirm failures concern the missing behavior, not the environment.
- [ ] Implement synchronous OkHttp calls with 15-second connect / 30-second read limits, identity encoding, no video cache/body materialization. Use downstream `Connection: close`, preserve known length or valid close-delimited bodies, strip hop-by-hop headers, and treat truncation as an error. Follow at most five redirects; remove Cookie/Authorization on origin change. Use original request context instead of forwarding the loopback Host/Origin. Retain normal TLS verification.
- [ ] Rerun tests: exact bytes, no manufactured zeros, no credentials crossing origins. Commit only these files as `feat: add streaming subtitle proxy HTTP transport`.

### Task 2: Bounded workers, backpressure and cancellation

**Files:** Create `SubtitleProxy.kt`, `app/src/test/java/top/rootu/lampa/helpers/SubtitleProxyTest.kt`; extend HTTP tests.

**Interfaces:** Define `ProxyRegistration(sourceId: Long, originalUrl: String, playbackUrl: String)` and `ProxyStats(activeRequests: Int, queuedRequests: Int, bytesRead: Long, bytesWritten: Long)`. `SubtitleProxy(observer: RelayObserver)` exposes `register(url: String, pageOrigin: String, headers: Map<String,String>): ProxyRegistration`, `stop(sourceId: Long): Unit`, `close(): Unit`, `stats(): ProxyStats`. A film change advances the source generation; retries/ranges retain it. Use URL-safe tokens from 24 random bytes and bound source records to the active generation.

- [ ] Write failing `slowConsumerStopsUpstream`, `fourWorkersNoQueue`, `cancelBlockedWriteClosesUpstream`, `oldTokenExpires`, `closeReleasesEverything`. With a controlled blocking sink assert `bytesRead - bytesWritten <= 65536`; with real sockets assert read counts plateau after bounded OS buffers fill. A tenfold longer stream must not increase the retained high-water mark. Four held requests cap `activeRequests == 4`; a fifth receives 503 and `queuedRequests == 0`. Cancellation completes within two seconds. Invalid tokens, CR/LF headers and a source pointing back to this proxy trigger no upstream calls.
- [ ] Run the new test class and confirm failures.
- [ ] Implement loopback-only acceptance with four workers, a zero-queue executor (`SynchronousQueue`), small bounded socket backlog and immediate rejection. Use the 64-KiB read/parse/write loop; keep locks away from network I/O. Implement a closeable socket sink with a 30-second write timeout: `Socket.soTimeout` alone does not time out writes. Track both sockets and OkHttp calls for cancellation. Changing film cancels the old source; closing browser closes server/workers/calls.
- [ ] Run both suites, including a 60-second slow-client soak and repeated register/close cycles. Assert no active calls/threads remain. Commit `feat: bound subtitle relay memory and lifetime`.

### Task 3: Route the actual video; device compatibility gate

**Files:** Create `assets/subtitle-proxy.js`, `scripts/tests/subtitle-proxy.test.cjs`, `docs/local-subtitle-proxy-verification.md`; modify `browser/Cefrium.kt`.

**Interfaces:** Native request `{type:'proxy-register',url,requestId}` returns `{sourceId,originalUrl,playbackUrl,requestId}`; `{type:'proxy-stop',sourceId}` stops that source. Native code obtains the page origin from its own browser state. JS exposes `window.__lampaSubtitleProxy.originalUrl(url): string` and `stop(): void`. The browser instance owns the proxy; the UI thread performs no network I/O.

- [ ] Write failing JS tests `defersOriginalVideoLoadUntilRegistration`, `lateRegistrationCannotStartOldFilm`, `preservesPlaydataAndExternalUrls`, `preloadAndHlsBypass`, `newTorrentsBeforeOrAfterHook`, `bridgeFailureAllowsExplicitDirectFallback`. Assert exactly one original loader call using the registered URL and zero earlier media loads; retain `this`/remaining arguments, original playdata URL and ignore stale callbacks. The saved Lampa bundle exports `PlayerVideo.url(src, change_quality)`; verify this contract on the live page during the phone gate.
- [ ] Run JS tests to see failures, then wrap only internal `Lampa.PlayerVideo.url` for verified TorrServer playback. Exclude preload/stat and HLS/DASH; preserve original addresses for casting/external players and metadata. Stop the proxy source on `PlayerVideo.destroy` or replacement of the film; merely disabling subtitles must not close the media stream. On registration failure show “Встроенные субтитры недоступны” with an explicit direct-play choice. Do not route unrelated pages/resources or globally change browser proxy settings.
- [ ] Disable CEF response tap, remove its media-body handler, wire async registration/lifetime, inject the proxy asset before the subtitle asset, and sanitize existing media/proxy request logging. This intermediate APK verifies relay video only, not finished subtitle support.
- [ ] Run JS/JVM suites and `gradle.bat @gradleArgs :app:assembleLiteDebug`. Check packaged assets/native AAR hash; save this diagnostic APK separately from the published APK.
- [ ] Inspect current ADB device/app state. Preserve settings; force-stop the diagnostic package before and after `adb install -r` to avoid the previous ChildProcessMismatchException. Verify the live loader hook and NewTorrents, then loopback playback/duration/Range seek from HTTP and HTTPS Lampa pages using a generated short MKV. Confirm no duplicate media download. Record CORS/local-network/mixed-content errors explicitly; do not disable browser protection globally. If incompatible, stop and report the observed blocker before implementing the remaining tasks.
- [ ] Record actual compatibility results and commit `feat: route Lampa torrent playback through bounded loopback proxy`.

### Task 4: Isolate response parsers and share bounded text

**Files:** Create `SubtitleResponseSession.kt` and `app/src/test/java/top/rootu/lampa/helpers/SubtitleResponseSessionTest.kt`; modify `MkvSubtitleStream.kt`, `SubtitleExtractor.kt`, and their tests.

**Interfaces:** Add immutable `MkvSubtitleStream.Metadata`, `metadata(): Metadata?`, `seedMetadata(metadata: Metadata): Unit`, `feed(bytes: ByteArray, offset: Int, count: Int): Unit`; retain the existing feed overload. `SubtitleResponseSession(sourceId: Long, responseId: Long, seed: Metadata?, emit: (String)->Unit)` provides `feed(bytes: ByteArray, count: Int): Unit`, `metadata(): Metadata?`, `close(): Unit`. `SubtitleExtractor` implements Task 1's `RelayObserver` while retaining `handle(payload: JSONObject, callback: (String)->Unit)` page semantics.

- [ ] Write failing `interleavedRangesKeepSeparateFraming`, `metadataNeverCrossesFilms`, `seekSeedsTrackNumbersAndTimestampScale`, `duplicateResponsesDoNotDuplicateText`, `parserFailureStillForwardsVideo`, `countExcludesUnusedBufferTail`. Alternate chunks from two ranges and assert the exact cue text/times/ordinals. Expired-source packets emit nothing. Late validated metadata is shared before the next parser feed only within the same source; already-skipped packets do not trigger another download.
- [ ] Run targeted tests and confirm new failures. Refactor to one parser per response, immutable shared track/scale metadata and one bounded film text cache. Migrate relevant redirect/discovery regression coverage before removing CEF ByteBuffer callbacks. Never seed partial framing state. Preserve SubRip scope, resynchronization and selected-track mapping; skip HEAD/error/non-MKV response parsing. Catch parser errors at observer boundaries without interrupting media transfer.
- [ ] Run all parser/cache/extractor/session tests and check the 12000-cue/2000000-character cap across responses/film changes. Commit `feat: extract subtitles from isolated proxy response streams`.

### Task 5: Bounded UI delivery and existing overlay

**Files:** Create `SubtitleUiMailbox.kt` and `app/src/test/java/top/rootu/lampa/helpers/SubtitleUiMailboxTest.kt`; modify `SubtitleExtractor.kt`, `Cefrium.kt`, `embedded-subtitles.js` and `scripts/tests/subtitles.test.cjs`.

**Interfaces:** `SubtitleUiMailbox(schedule: (()->Unit)->Unit, deliver: (String)->Unit)` exposes `offer(message: String): Unit` and `clear(): Unit`. Source/selection generations invalidate older pending messages. Cue snapshots use `{type:'cues',replace:true,session,url,ordinal,cues}`; metadata/selection messages retain their existing meaning. One pending runnable drains only the latest bounded state.

- [ ] Write failing `oneRunnableForBurst`, `oldGenerationNeverDelivered`, `replaceSnapshotBoundsJsState`, `samePausedFrameSwitchesLanguage`, `logicalUrlMatchesProxyPlayback`. After 10000 offers into a held scheduler assert one pending runnable and bounded retained payload; clear invalidates stale delivery. Repeated overlapping JS snapshots stay within 12000 cues / 2000000 characters including deduplication state. RU→EN→RU at a paused frame paints the cached cue immediately.
- [ ] Run targeted tests and confirm failures. Coalesce latest track/selection metadata and cue-window replacement, not per-block UI tasks. Enforce JS limits and prune stale text. Resolve local playback URLs to the original source when sending subtitle requests. Include overlapping cues and at most the existing 120-second lookahead; select/seek/source changes invalidate pending delivery before replacing it.
- [ ] Run complete JVM/JS suites, including native WebVTT/HLS/external-menu regressions. Commit `fix: bound subtitle UI delivery and preserve source identity`.

### Task 6: Device acceptance and verified delivery

**Files:** Create `scripts/diagnostics/sample-subtitle-proxy.ps1`; update `docs/local-subtitle-proxy-verification.md`, `docs/embedded-subtitles.md`, and the obsolete reader comment in `app/build.gradle`.

- [ ] Derive a sampler from private `crash-2230/sample-memory.ps1`, parameterized by device serial, package and output directory. Every five seconds record RSS/MemAvailable; every 30 seconds record native/Java PSS, CPU and thermal/battery readings. Thresholds: 1048576 KiB available and 3145728 KiB app RSS. On crossing a threshold, take a final sample then stop only the diagnostic app and record this intervention. Use injected samples to test threshold decisions without stopping a real app. Keep full logcat, settings and movie URLs out of commits.
- [ ] After final edits run all JVM/JS tests, assemble APK and verify assets/AAR/hash/signature. Install only `top.rootu.lampa.repair` with force-stop before/after and no data clearing. Confirm visible RU/EN SubRip, paused switching, disable/re-enable, ten forward/backward seeks, film changes, AC3/EAC3, external subtitles and correct casting/external URL routing. Record measured subtitle delay and unsupported formats honestly.
- [ ] Capture a comparable direct-play tap-off baseline, then at least 30 minutes of the same 4K with proxy plus a separate five-minute pause/resume. Compare stable intervals, total/native memory trends, CPU/thermal state and playback stalls. More than 256 MiB above comparable baseline requires investigation; sustained growth fails even without a crash. Stop at either guard threshold. Verify zero relay connections and released resources after leaving playback.
- [ ] Save private raw captures separately from sanitized results. If device/playback is unavailable, mark acceptance incomplete and do not claim a fix or publish. Do not automatically reinstall the known leaking tap-enabled APK as rollback; preserve it and recover through explicit direct playback in the diagnostic build.
- [ ] Only after acceptance, commit final docs and supply the tested APK with SHA-256. Any release in the previously authorized GitHub repository must identify the tested source commit and diagnostic package/signing/SubRip scope. Do not overwrite the old asset or treat a green build as playback acceptance. Remove temporary test hooks, fixture servers and task-created ADB reverse mappings.

## Self-review and execution recommendation

Every spec section maps to Tasks 1–6: HTTP/security (1), memory/lifetime (2), compatibility/routing (3), subtitle correctness (4), UI bounds (5), device acceptance/delivery (6). Each Review Focus has named tests. Relay byte counts exclude unused buffer tails; source IDs, response IDs and UI selection generations are distinct. No task introduces a second subtitle download or changes the native decoder.

Recommended execution: sequential implementation in this chat, then an independent whole-change review. Transport, source identity and parser interfaces depend on each other; this keeps the phone compatibility gate explicit and avoids competing device control. Plan review and execution-method selection are pending. None of these implementation tasks has run.
