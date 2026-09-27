# Local subtitle relay verification — 2026-09-28

Status: accepted within the scope below. Automated checks, 30-minute device playback,
five-minute pause/resume and final delivery APK installation/playback passed.

## Implementation

- Existing AC3/EAC3 Cefrium AAR retained; SHA-256
  `47dc05df920b5a582d1f9825de8f53fb12856b17ddb35276caaf3bf65d9be53f`.
  Packaged `libcef.so` matches the previous release byte for byte (SHA-256
  `0407ffe10423bea1c7ac44f2c8787acc7a02567666780b13788caf87683e5fa9`).
- CEF response tap disabled, no native video callback registered.
- Loopback-only token route, four workers, no request queue, 64 KiB relay blocks.
- Range/HEAD/416, conditional headers, manual redirect credential stripping and
  CORS restricted to the registering page. No additional subtitle download.
- Separate response parsers, bounded film text and one renderer batch in flight,
  held until JavaScript acknowledges consumption. Navigation retires the old batch.
- Peer disconnects cancel stalled upstream reads; proxy publication and destruction
  are serialized. Source/response identifiers remain unique across proxy lifetimes.
- Player.play prepares the route before Lampa creates its video; PlayerVideo.url
  synchronously substitutes it. Original playdata remains available for casting.
- Explicit direct-play choice when registration fails; response tap stays off.

## Automated checks so far

- JVM: 55 tests, zero failures/errors, including real HTTP/socket backpressure,
  cancellation, concurrent parser framing, byte-count boundaries and UI coalescing.
- JavaScript: 29 tests, zero failures, including immediate ready listeners,
  original-source identity, bounded replacements and existing subtitle handlers.
- Sampler: four injected boundary checks pass without contacting a device.
- Gradle assembled the diagnostic APK successfully.

## Device checks so far

Pixel 6, diagnostic package `top.rootu.lampa.repair`; original app/data retained.

- Generated H264/AC3 MKV played through the relay, duration 20 seconds; seek to
  eight seconds succeeded, playback advanced normally with no media error.
- HTTP Lampa page integration works with the installed NewTorrents plugin.
- HTTPS gate on the user's configured Lampa host could not load the page due to
  `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`, before media loading. No TLS verification or
  browser protections were disabled. HTTPS playback remains unverified.
- Same 4K TorrServer file as the earlier leak reproduced: proxy active, SubRip
  metadata discovered, full Russian track selected, visible Russian text captured.
  Preliminary relay run lasted 27 minutes with no continuous memory growth; after
  startup, aggregate RSS was roughly 1.04–1.12 GiB. This preceded the final lifecycle
  corrections and is not the final APK acceptance result.
- Final transport implementation passed paused RU -> EN -> RU switching, disabling
  and reenabling subtitles, ten forward/backward seeks, and changing films on the
  phone using a generated H264/AC3 MKV with Russian/English SubRip tracks.
  The same runtime then played the real 4K TorrServer file for 30 minutes, with
  advancing video time, visible Russian text and no media error. After startup,
  aggregate app/renderer RSS remained approximately 1105–1160 MiB. The earlier
  tap-disabled control was 1094–1178 MiB over 197 seconds; the faulty tap-enabled
  release rose from 970 to 2806 MiB in 175 seconds. These are different-duration
  runs of the same file, not a claim of identical CPU/thermal conditions.
- Android thermal status stayed 0; battery readings were 30.3–30.5 °C during the
  playback trial. CPU and Java/native memory snapshots are retained with the private
  measurements. The historical tap-disabled comparison provides RAM data only.
- The temporary ADB-script loader used to drive the acceptance sequence was removed
  from the delivery APK; the playback/subtitle implementation is unchanged.
- Five-minute pause/resume passed: text stayed on the paused frame, playback resumed,
  a fresh upstream Range request was observed, and video time/cues advanced without
  a media error. RSS was 1107–1217 MiB across the pause boundary and 1138–1239 MiB
  during the subsequent minute, including the resume transient. There was no
  continuous growth or memory-guard intervention; minimum available RAM during the
  4K trial was 2104 MiB. The former leak reached 2806 MiB within three minutes.
- Leaving playback closed the loopback media listener on the phone; the app's
  unrelated local bridge listener remained. Socket cancellation/thread retirement
  also passed the automated lifecycle tests. Chromium's page caches are not expected
  to disappear merely by leaving the player.
- Final delivery APK installed successfully, followed by a cold launch. The installed
  APK SHA-256 matched the saved release file. NewTorrents v2.1.0 initialized normally;
  opening the same 3840x1716 torrent through the ordinary Lampa menus played video
  through the relay. Selecting the full Russian track showed visible Russian text.
  This check used the delivery APK without the temporary ADB-script loader.

## Delivery identity

- File: `Lampa-Repair-Subtitles-Proxy-arm64.apk`, 333651862 bytes.
- SHA-256: `95cdfeebd2faa116b5421b352a0a83e7a947152595d336301fa074ba56fee414`.
- Playback implementation: commit `3b8fd6d6b50195b8082148dcd8026352b7e11694`;
  subsequent documentation changes do not change the APK.
- Package `top.rootu.lampa.repair`, version name `1.13.1`, version code `568`,
  Android 10+ ARM64. This is the diagnostic `liteDebug` distribution, as in the
  previous Repair release, not the original application's release-signing key.
- Signing certificate SHA-256
  `f17bda429d4c6380624127dd8b1ef3b33c8686534c582df374670e48e20ff34b`, identical
  to the previous Repair APK. Installation preserved the existing profile/plugins.

## Repeat-seek regression

The first extended device trial failed on the sixth seek: the upstream server had
closed its idle keep-alive connection and the relay returned 502 instead of opening
a fresh connection. A raw-socket JVM test reproduced the same 502 before the fix.
Enabling OkHttp's connection-failure recovery for the bodyless GET/HEAD upstream
requests made that test pass; all ten device seeks subsequently passed. Recovery
occurs before a response is forwarded. A truncated response body is still terminated,
never padded or joined to a second response.

Raw logs and private media addresses are retained outside the repository.
The memory sampler writes only to an explicitly supplied diagnostic directory and
stops the diagnostic app below 1 GiB available RAM or above 3 GiB app RSS.

## Scope

Embedded SubRip only. ASS/SSA and bitmap subtitles are not decoded. Subtitle packets
must arrive in the video's stream; an unbuffered seek can require normal network
buffering. No promise of zero network latency is made. The original player's
external subtitle handlers and audio codec engine remain in place.

The private HTTP endpoint is for Chromium's bodyless GET/HEAD requests; pipelining
and request-side TCP half-close are treated as cancellation. Browser HTTP Basic/Digest
authentication beyond transferred cookies has not been verified. The existing
privileged bridge trusts the configured page and its plugins; this change does not
establish a general untrusted-page sandbox.
