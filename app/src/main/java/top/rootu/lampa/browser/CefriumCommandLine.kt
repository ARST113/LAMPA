package top.rootu.lampa.browser

import android.util.Log
import org.chromium.base.CommandLine

/**
 * Chromium command line switches that have to be installed *before* the Cefrium
 * runtime starts.
 *
 * The SDK's automatic ContentProvider is removed from our manifest. Install these
 * switches from Application.onCreate(), immediately before initializing the
 * selected Cefrium runtime. SDK lifecycle tracking must be registered before
 * MainActivity starts. System WebView processes never execute this setup.
 */
object CefriumCommandLine {

    private const val TAG = "LampaCefrium"

    /**
     * Chromium 152 turns every http:// navigation into https:// by itself
     * (HttpsUpgrades / HttpsUpgradesInterceptor). Cefrium reports that synthetic
     * redirect as "OnLoadEnd: status 307" and the upgraded request then fails:
     * net_error -200 when the server certificate does not cover the requested host
     * (plain LAMPA mirrors reached by IP) and -113 when the TLS endpoint is filtered.
     * A reachable plain-HTTP LAMPA server therefore renders as a blank page.
     *
     * LAMPA servers are plain HTTP (and the user explicitly asked for http://),
     * so the requested scheme must survive.
     */
    private val DISABLED_FEATURES = listOf(
        "HttpsUpgrades",
        "HttpsFirstMode",
        "HttpsFirstModeV2",
        "HttpsFirstModeIncognito",
        "HttpsFirstBalancedMode",
        "HttpsFirstBalancedModeAutoEnable",
        "HttpsOnlyMode"
    )

    /** Idempotent; call only in the main process when Cefrium is selected. */
    @JvmStatic
    fun apply() {
        try {
            if (!CommandLine.isInitialized()) CommandLine.init(null)
            val commandLine = CommandLine.getInstance()

            commandLine.appendSwitchWithValue("javaless-renderers", "disabled")
            commandLine.appendSwitch("allow-running-insecure-content")
            val enabledBlinkFeatures = commandLine.getSwitchValue("enable-blink-features")
                .orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            commandLine.appendSwitchWithValue(
                "enable-blink-features",
                (enabledBlinkFeatures + "AudioVideoTracks").distinct().joinToString(",")
            )

            // Never drop switches that are already there (Cefrium appends its own).
            val alreadyDisabled = commandLine.getSwitchValue("disable-features")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val merged = (DISABLED_FEATURES + alreadyDisabled).distinct().joinToString(",")
            if (commandLine.getSwitchValue("disable-features") == merged) return

            commandLine.appendSwitchWithValue("disable-features", merged)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot extend the Cefrium command line", t)
        }
    }
}
