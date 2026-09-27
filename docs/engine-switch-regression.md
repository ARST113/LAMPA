# Browser engine startup regression

The bundled Cefrium 152 runtime and Android System WebView must not initialize
their native Chromium libraries in the same application process. Destroying a
browser view or recreating the Activity does not unload JNI registrations.

## Reproduction and diagnosis

On the installed 1.13.1 debug APK, selecting **Android System WebView 153.0.8010.36**
from the native browser menu reproduced a crash in the main application process:

```text
WebViewFactory: Loading com.google.android.webview version 153.0.8010.36
java.lang.AssertionError: JNI multiplexing hash lookup failed with J.N
    at org.jni_zero.JniZero.crashIfMultiplexingMisaligned(JniZero.java:65)
    at java.lang.Runtime.nativeLoad(Native Method)
...
jni_zero crashing due to uncaught Java exception
Fatal signal 5 (SIGTRAP)
```

The prebuilt AAR's `CefriumInitProvider.onCreate()` unconditionally calls
`com.cefrium.Cefrium.initialize(context)`, before the application knows which
engine is selected. The SDK exposes `Cefrium.initialize(context)` for explicit
startup after selecting the engine.

As a controlled test on the original installed APK, temporarily disabling only
that provider and cold-starting System WebView allowed the same Lampa URL to
finish loading. The new process loaded `libwebviewchromium.so`, with no Cefrium
native initialization and no JNI crash. The provider's default state, original
native preferences and Cefrium selection were restored afterwards. No app data
was cleared and the Chromium library was not modified.

The first repaired APK also caught a lifecycle constraint: deferring Cefrium's
initialization until `MainActivity.onCreate()` causes `Found untracked Activity`
in `ApplicationStatus.registerStateListenerForActivity`. The SDK registers
Activity lifecycle callbacks during initialization, which must happen before
Activity creation begins. Explicit startup therefore belongs in
`Application.onCreate()`, conditional on the selected engine.

## Implementation

- Remove `CefriumInitProvider` during manifest merging.
- In `Application.onCreate()`, set Cefrium switches and initialize its runtime
  only when Cefrium is selected (including fallback when WebView is unavailable).
  This registers lifecycle callbacks before the first Activity. System WebView
  never executes this setup.
- Commit the engine preference before switching.
- Launch a non-exported Activity in `:engine_restart`, stop the old main process,
  wait until ActivityManager no longer reports it, and launch a new main Activity.
- Skip application jobs and preference initialization in the restart process.

Each engine retains its existing separate browser storage. First use of System
WebView can therefore show Lampa's first-run page without affecting Cefrium data.

## Device verification after building

Use the native Options Menu > Change Browser. Check both the visible page and
the main-process PID; a new Activity with the same PID is insufficient.

| Transition | Required result |
| --- | --- |
| Cold Cefrium launch | Lampa loads; Cefrium native library loads |
| Cefrium to System WebView | Main PID changes; Lampa loads; no Cefrium native startup in the new PID |
| Cold System WebView launch | Selection persists; Lampa loads without a JNI crash |
| System WebView to Cefrium | Main PID changes; Cefrium and the original Cefrium Lampa data load |
| Repeated round trip | No restart helper remains running; no new crash-buffer entry |

For each main PID, inspect its own logcat lines rather than old crash-buffer
entries from other processes. Verify the merged manifest contains no
`com.cefrium.CefriumInitProvider` declaration. Confirm that ordinary Activity
recreation, such as a language change, still reloads the selected engine.

## Verified repaired APK on Pixel 6 (2026-09-27)

The native menu switched Cefrium PID 11559 to System WebView PID 12533, then
back to Cefrium PID 13073. Both new processes finished loading the test page;
WebView loaded its own native library, and neither transition produced the JNI
or untracked-Activity crashes. No restart-helper process remained afterwards.
The subtitle playback, paused language switch, seek and re-enable checks passed
again after returning to Cefrium. The original app was retained alongside the
diagnostic package; its preferences and web settings were copied for testing.
