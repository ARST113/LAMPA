package top.rootu.lampa.browser

import android.util.Log
import org.chromium.base.CommandLine

/**
 * Chromium command line switches that have to be installed *before* the Cefrium
 * runtime starts.
 *
 * com.cefrium.CefriumInitProvider is a ContentProvider of the Cefrium SDK, so the
 * framework creates it (and it calls the native CefInitialize, which freezes the
 * Chromium feature list) before Application.onCreate() of the host app runs.
 * Switches appended later - from MainActivity, for example - only mutate a command
 * line that nobody reads any more. Application.attachBaseContext() is the last hook
 * that still runs before the SDK provider, so the switches live here.
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

    /** Idempotent: safe to call from every process and every startup hook. */
    @JvmStatic
    fun apply() {
        try {
            if (!CommandLine.isInitialized()) CommandLine.init(null)
            val commandLine = CommandLine.getInstance()

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
