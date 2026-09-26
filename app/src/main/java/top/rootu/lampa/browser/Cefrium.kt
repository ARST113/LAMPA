package top.rootu.lampa.browser

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import com.cefrium.CefriumBrowser
import org.chromium.base.CommandLine
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Adapter between the original LAMPA Browser abstraction and the custom
 * Cefrium / Chromium 152 runtime. The original Android UI and MainActivity
 * stay intact; only the embedded web engine is replaced.
 */
class Cefrium(
    override val mainActivity: MainActivity,
    override val viewResId: Int
) : Browser {

    private var browser: CefriumBrowser? = null
    private var container: FrameLayout? = null
    private var jsObject: Any? = null
    private var jsObjectName: String = "AndroidJS"
    private var userAgent: String = defaultUserAgent()
    private var keepVisible = false

    private val evalId = AtomicLong(1)
    private val evalCallbacks = ConcurrentHashMap<Long, (String) -> Unit>()

    override var isDestroyed: Boolean = false

    override fun initialize() {
        if (browser != null || isDestroyed) return

        if (!CommandLine.isInitialized()) CommandLine.init(null)
        val commandLine = CommandLine.getInstance()

        commandLine.appendSwitchWithValue("javaless-renderers", "disabled")
        commandLine.appendSwitchWithValue("enable-blink-features", "AudioVideoTracks")
        commandLine.appendSwitch("allow-running-insecure-content")

        // Chromium 152 upgrades every http:// navigation to https:// by itself
        // (HttpsUpgrades / HttpsUpgradesInterceptor). Cefrium surfaces that synthetic
        // redirect as "OnLoadEnd: status 307" and the upgraded request then fails
        // (net_error -200 / -113) because LAMPA mirrors are plain-HTTP servers, or
        // have no TLS endpoint for the requested host. Keep the user's scheme.
        val disabledFeatures = listOf(
            "HttpsUpgrades",
            "HttpsFirstMode",
            "HttpsFirstModeV2",
            "HttpsFirstModeIncognito",
            "HttpsFirstBalancedMode",
            "HttpsFirstBalancedModeAutoEnable",
            "HttpsOnlyMode"
        ) + commandLine.getSwitchValue("disable-features")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        commandLine.appendSwitchWithValue(
            "disable-features",
            disabledFeatures.distinct().joinToString(",")
        )

        val host = mainActivity.findViewById<FrameLayout>(viewResId)
        container = host

        val cef = CefriumBrowser.createWithSurface(mainActivity)
        browser = cef

        cef.setJavaScriptEnabled(true)
        cef.setPinchToZoomEnabled(false)
        cef.setPullToRefreshEnabled(false)
        cef.setMediaSessionEnabled(false)

        cef.setQueryHandler { _, request, _, callback ->
            handleQuery(request, callback)
        }

        cef.setOnLoadingStateChangedListener { isLoading, _, _ ->
            mainActivity.runOnUiThread {
                injectJavascriptBridge()

                if (!isLoading) {
                    val url = cef.url ?: ""
                    mainActivity.onBrowserPageFinished(cef.surfaceContainer, url)
                }
            }
        }

        cef.setOnRenderProcessTerminatedListener { status, errorCode ->
            Log.e(TAG, "Chromium renderer terminated: status=$status error=$errorCode")
        }

        val surface = cef.surfaceContainer
        surface.setBackgroundColor(Color.BLACK)
        surface.isFocusable = true
        surface.isFocusableInTouchMode = true
        host.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surface.requestFocus()

        mainActivity.onBrowserInitCompleted()
    }

    override fun setUserAgentString(ua: String?) {
        userAgent = ua?.takeIf { it.isNotBlank() } ?: defaultUserAgent()
        browser?.setUserAgentOverride(userAgent)
    }

    override fun getUserAgentString(): String = userAgent

    override fun addJavascriptInterface(jsObject: Any, name: String) {
        this.jsObject = jsObject
        this.jsObjectName = name
        injectJavascriptBridge()
    }

    override fun loadUrl(url: String) {
        browser?.loadUrl(url)
    }

    override fun pauseTimers() {
        if (!isDestroyed && !keepVisible) browser?.onPause()
    }

    override fun resumeTimers() {
        if (!isDestroyed) browser?.onResume()
    }

    override fun evaluateJavascript(script: String, resultCallback: (String) -> Unit) {
        val cef = browser ?: run {
            resultCallback("null")
            return
        }

        val id = evalId.getAndIncrement()
        evalCallbacks[id] = resultCallback

        val wrapped = """
            (function() {
                var __lampaResult = null;
                try {
                    var __lampaValue = (0, eval)(${JSONObject.quote(script)});
                    try {
                        __lampaResult = JSON.stringify(__lampaValue);
                        if (typeof __lampaResult === 'undefined') __lampaResult = 'null';
                    } catch (__jsonError) {
                        __lampaResult = JSON.stringify(String(__lampaValue));
                    }
                } catch (__lampaError) {
                    __lampaResult = JSON.stringify('FAILED: ' + (__lampaError && __lampaError.message ? __lampaError.message : String(__lampaError)));
                }

                if (typeof window.cefriumQuery === 'function') {
                    window.cefriumQuery({
                        request: JSON.stringify({
                            type: 'eval-result',
                            id: $id,
                            result: __lampaResult
                        }),
                        onSuccess: function() {},
                        onFailure: function() {}
                    });
                }
            })();
        """.trimIndent()

        cef.evaluateJavaScript(wrapped)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        browser?.clearCache()
    }

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        evalCallbacks.clear()
        browser?.let { cef ->
            try {
                (cef.surfaceContainer.parent as? ViewGroup)?.removeView(cef.surfaceContainer)
            } catch (_: Exception) {
            }
            cef.close()
        }
        browser = null
        container = null
    }

    override fun setKeepVisible(keep: Boolean) {
        keepVisible = keep
        if (keep) browser?.setPageVisible(true)
    }

    override fun setBackgroundColor(color: Int) {
        container?.setBackgroundColor(color)
        browser?.surfaceContainer?.setBackgroundColor(color)
    }

    override fun canGoBack(): Boolean = browser?.canGoBack() == true

    override fun goBack() {
        browser?.goBack()
    }

    override fun setFocus() {
        browser?.surfaceContainer?.requestFocus(View.FOCUS_DOWN)
    }

    override fun getView(): View? = browser?.surfaceContainer

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return browser?.onActivityResult(requestCode, resultCode, data) == true
    }

    private fun handleQuery(
        request: String,
        callback: CefriumBrowser.QueryCallback
    ): Boolean {
        return try {
            val payload = JSONObject(request)
            when (payload.optString("type")) {
                "eval-result" -> {
                    val id = payload.optLong("id", -1L)
                    val result = payload.optString("result", "null")
                    evalCallbacks.remove(id)?.invoke(result)
                    callback.success("")
                    true
                }

                "androidjs" -> {
                    val methodName = payload.optString("method")
                    val args = payload.optJSONArray("args") ?: JSONArray()
                    val result = invokeJavascriptInterface(methodName, args)
                    callback.success(
                        JSONObject()
                            .put("ok", true)
                            .put("value", result ?: JSONObject.NULL)
                            .toString()
                    )
                    true
                }

                else -> {
                    callback.failure(404, "Unknown LAMPA native bridge request")
                    true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Native bridge query failed", e)
            callback.failure(500, e.message ?: e.javaClass.simpleName)
            true
        }
    }

    private fun invokeJavascriptInterface(methodName: String, args: JSONArray): Any? {
        val target = jsObject ?: throw IllegalStateException("$jsObjectName is not registered")

        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == methodName &&
                candidate.parameterTypes.size == args.length() &&
                candidate.getAnnotation(JavascriptInterface::class.java) != null
        } ?: throw NoSuchMethodException("$jsObjectName.$methodName/${args.length()}")

        val converted = Array(method.parameterTypes.size) { index ->
            convertArgument(args.opt(index), method.parameterTypes[index])
        }

        return try {
            method.invoke(target, *converted)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun convertArgument(value: Any?, type: Class<*>): Any? {
        if (value == null || value === JSONObject.NULL) {
            return if (type.isPrimitive) primitiveDefault(type) else null
        }

        return when (type) {
            String::class.java -> value.toString()
            Int::class.javaPrimitiveType, Int::class.javaObjectType ->
                (value as? Number)?.toInt() ?: value.toString().toInt()
            Long::class.javaPrimitiveType, Long::class.javaObjectType ->
                (value as? Number)?.toLong() ?: value.toString().toLong()
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType ->
                when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> value.toString().toBoolean()
                }
            Double::class.javaPrimitiveType, Double::class.javaObjectType ->
                (value as? Number)?.toDouble() ?: value.toString().toDouble()
            Float::class.javaPrimitiveType, Float::class.javaObjectType ->
                (value as? Number)?.toFloat() ?: value.toString().toFloat()
            else -> value
        }
    }

    private fun primitiveDefault(type: Class<*>): Any = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Char::class.javaPrimitiveType -> '\u0000'
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> 0
    }

    private fun injectJavascriptBridge() {
        val cef = browser ?: return
        if (jsObject == null) return

        val name = JSONObject.quote(jsObjectName)
        val script = """
            (function() {
                var __name = $name;

                function __nativeCall(method, args) {
                    if (typeof window.cefriumQuery !== 'function') {
                        throw new Error('Cefrium native bridge is unavailable');
                    }

                    var settled = false;
                    var value = null;
                    var failure = null;

                    window.cefriumQuery({
                        request: JSON.stringify({
                            type: 'androidjs',
                            method: String(method),
                            args: Array.prototype.slice.call(args || [])
                        }),
                        onSuccess: function(raw) {
                            settled = true;
                            try {
                                var parsed = JSON.parse(raw || '{}');
                                value = parsed.value;
                            } catch (e) {
                                failure = e && e.message ? e.message : String(e);
                            }
                        },
                        onFailure: function(code, message) {
                            settled = true;
                            failure = String(message || ('Native error ' + code));
                        }
                    });

                    if (failure) throw new Error(failure);
                    if (!settled) {
                        console.error('[LAMPA Cefrium] synchronous native bridge did not settle: ' + method);
                        return null;
                    }
                    return value;
                }

                var bridge = new Proxy({}, {
                    get: function(_, property) {
                        if (typeof property === 'symbol') return undefined;
                        if (property === 'toString') {
                            return function() { return '[object ' + __name + ']'; };
                        }
                        return function() {
                            return __nativeCall(String(property), arguments);
                        };
                    }
                });

                window[__name] = bridge;
                window.__lampaCefriumBridgeInstalled = true;
            })();
        """.trimIndent()

        cef.evaluateJavaScript(script)
    }

    private fun defaultUserAgent(): String {
        val release = Build.VERSION.RELEASE ?: "10"
        val model = Build.MODEL ?: "Android"
        return "Mozilla/5.0 (Linux; Android $release; $model) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/152.0.0.0 Mobile Safari/537.36"
    }

    companion object {
        private const val TAG = "LampaCefrium"
    }
}
