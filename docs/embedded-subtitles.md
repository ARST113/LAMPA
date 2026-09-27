# Embedded MKV subtitles

This repair uses the existing Cefrium Chromium 152 AC3/EAC3 AAR. No Chromium
rebuild or native library change is required. The Android application reads
Matroska SubRip tracks and displays their text in Lampa's subtitle overlay.

## What was broken

- EBML element IDs were read like sizes, stripping their identifying marker bit.
- MKV SubRip blocks were treated as complete SRT files with timestamp lines.
  Their payload is plain UTF-8 text; timestamps and durations belong to the
  container. BlockGroup duration, negative relative time and TimestampScale
  were consequently lost.
- Lampa's tracks plugin creates non-configurable mode accessors. Replacing the
  item within the retained subtitle array is necessary to attach a working
  selector, including menus created internally by Lampa.
- Track changes at time zero or while paused were suppressed by a clock gate.

The reader now validates framing, reads container timing, selects the requested
language/label and skips media payload with HTTP Range. A subtitle-track Cues
index allows direct seeking. If the index contains only video keyframes, earlier
subtitle headers must still be scanned so a long active cue is not discarded.
Initial latency therefore depends on the file's index and TorrServer response;
instant selection cannot be guaranteed for every torrent.

The page bridge cancels old readers and rejects stale session replies on seek,
track change and stop. Native WebVTT and external URL subtitle loaders retain
their original behavior. Text is rendered with textContent rather than HTML.

## Scope

Supported embedded format: `S_TEXT/UTF8` (SubRip). ASS/SSA and bitmap PGS require
additional decoders and are reported as unsupported. Servers ignoring Range
use a sequential fallback, which can be slower. No server transcoding is needed.

A narrow style hides Chromium's duplicate overlay enclosure on Lampa's custom
video element; it does not disable Remote Playback or Lampa's broadcast menu.
The reported white Cast square matched that native control's geometry, but did
not reproduce with the local test video, so this visual repair still needs a
check on the affected remote stream.

## Verification

- Eight JVM regression tests: EBML/timing, unknown-size clusters, track matching,
  active long cues and indexed Range seeking, including video-only Cues.
- Nine JavaScript tests: real plugin descriptor shape, internal menus, native
  and external subtitles, paused/zero-time changes and stale replies.
- Pixel 6, repaired APK, real H.264 + AC3 MKV with two SubRip tracks: Russian
  text rendered, paused switch to English, seek to the second cue, disable and
  re-enable all passed, including after a WebView/Cefrium round trip. Native
  `textTracks.length` remained zero, confirming the new reader supplied the text.
- App assembled and JVM tests passed using the saved AC3 AAR. Its libcef.so was
  unchanged. The original installed app and its data were retained.

Run `node --test scripts/tests/subtitles.test.cjs` and
`./gradlew :app:testLiteDebugUnitTest :app:assembleLiteDebug` with the pinned
custom AAR arguments from the CI workflow.

`-PdiagnosticPackage=true` gives debug builds the package `top.rootu.lampa.repair`
and label Lampa Repair, allowing safe device testing when the installed APK's
ephemeral CI signing key is unavailable. It does not alter release builds.
