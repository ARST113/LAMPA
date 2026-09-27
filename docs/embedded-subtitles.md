# Embedded subtitle repair

The app uses the existing Cefrium AC3/EAC3 AAR. The Chromium native library is
unchanged. SubRip (`S_TEXT/UTF8`) is decoded in the Android wrapper and rendered
in Lampa's existing subtitle overlay.

## Same data as video

Cefrium's response-body tap is disabled: its native filter can queue video faster
than playback consumes it. A loopback HTTP relay forwards the actual media request
using at most four synchronous workers, with a reusable 64 KiB block per response.
It stops reading upstream while the browser stops consuming. `MkvSubtitleStream`
incrementally reads EBML metadata and Matroska subtitle blocks before each block is
forwarded, skipping video/audio payload without retaining it. It handles
arbitrary packet boundaries, BlockGroup durations, TimestampScale, signed relative
timestamps, lacing, unknown-size clusters, and recovery after HTTP seeks.

All supported subtitle tracks are collected while video buffers. A bounded text-only
cache lets a language change replay the current cue immediately, including while
paused. Seeking retains metadata and text already seen; new responses resume parsing
at a validated cluster. There is no second subtitle HTTP download or Range scanner
in the active playback path. Unbuffered seeking still depends on video buffering;
this change does not promise zero network latency or recover text preceding the first
cluster delivered by the video request.

Each HTTP response owns independent framing state; only immutable track metadata
and bounded text are shared within one film. Redirects preserve the player's logical
source identity. TorrServer's JSON preload/status responses are excluded. Metadata,
text caches and JavaScript cue windows have explicit bounds. UI delivery coalesces
snapshots into one pending runnable, including during pauses and language changes.

## Other corrected defects

The original reader stripped EBML ID marker bits and treated a subtitle block as a
complete .srt file. Its later separate Range reader encountered zero-filled or
inconsistent responses from the affected TorrServer. Continuous streaming succeeded,
but duplicated media traffic. The active path now shares the video response instead.
The old `MkvSubtitleReader` and its regression tests remain for historical diagnostics;
`SubtitleExtractor` no longer instantiates it.

The page bridge also handles Lampa plugin menus with non-configurable mode accessors,
discovers track metadata before selection, supports paused/time-zero changes, and
rejects stale session/source replies. Native WebVTT, HLS and external URL subtitle
handlers retain their behavior. Subtitle text is assigned with `textContent`.

## Scope

Supported embedded format: SubRip (`S_TEXT/UTF8`). ASS/SSA and bitmap PGS still need
additional decoders. Unsupported codecs retain their metadata ordinal so menu mapping
cannot shift to a different track. This is an app-wrapper repair, not a Chromium rebuild.

The narrow style hiding Chromium's duplicate overlay enclosure remains applied only
to Lampa's custom video element. It preserves Lampa's playback and casting controls.
System WebView/Cefrium isolation and its tested process restart remain unchanged;
see `engine-switch-regression.md`.

## Historical subtitle correctness checks, Pixel 6, 2026-09-27

These checks predate the relay. The released response-tap implementation subsequently
showed unbounded native memory growth; it is not a stable fallback. Current relay
acceptance and limits are recorded in `local-subtitle-proxy-verification.md`.

- 11 incremental-parser JVM tests, including every two-part split and one-byte feeds;
  passed under a 32 MiB heap.
- 5 text-cache tests and 5 source-routing tests, including redirects, signature splits
  concurrent with discovery, extensionless URLs, duplicate ranges and bounded storage.
- 18 JavaScript bridge tests; 14 retained legacy Range-reader regressions.
- Gradle unit suite (35 tests) and `assembleLiteDebug` passed with the saved AC3 AAR.
- The actual 15.21 GB TorrServer MKV exposed all 19 subtitle tracks. The same-stream
  parser delivered thousands of cues continuously without the former Range failures.
- On one paused real-movie frame, Russian -> English -> Russian changed the visible
  text immediately. Disabling removed it; re-enabling restored it. A backward seek
  from ~44 minutes to 28:40 resumed real Russian subtitle rendering on new media data.
- The earlier empty screenshots alone were not evidence of an overlay defect. Visible
  text, paused switching, and rendering with controls hidden were subsequently verified.
- A repeated APK install left an old Chromium process and caused
  `ChildProcessMismatchException`. A full force-stop before/after the final install
  resolved this. Final on-device verification used a fresh main process (PID 5649).

The original installed app and all settings were retained. The repaired debug APK uses
`top.rootu.lampa.repair` / Lampa Repair because the original signing key is unavailable.
No temporary JavaScript diagnostic polling hook is present in the delivered build.

Run `node --test --test-isolation=none scripts/tests/subtitles.test.cjs` and
`./gradlew :app:testLiteDebugUnitTest :app:assembleLiteDebug` with the pinned custom AAR
arguments. `-PdiagnosticPackage=true` enables the separate diagnostic package only.
