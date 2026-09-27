# Local subtitle relay verification — 2026-09-28

Status: implementation and initial device playback verified; extended acceptance is
still running. This document does not identify a released or fully accepted APK yet.

## Implementation

- Existing AC3/EAC3 Cefrium AAR retained; SHA-256
  `47dc05df920b5a582d1f9825de8f53fb12856b17ddb35276caaf3bf65d9be53f`.
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
  Extended memory and pause measurements of this implementation are running.

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
