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
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.helpers.SubtitleExtractor
import top.rootu.lampa.helpers.SubtitleProxy
import top.rootu.lampa.helpers.SubtitleProxyOwner
import top.rootu.lampa.helpers.SubtitleUiMailbox
import okhttp3.HttpUrl
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val proxyOwner = SubtitleProxyOwner({ SubtitleProxy(SubtitleExtractor) { Log.d("LampaRelay",it) } },
        SubtitleExtractor::registerSource, SubtitleExtractor::shutdown)
    private val proxyExecutor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private var subtitleDeliveryId=0L
    private var subtitleAcknowledge:(()->Unit)?=null
    private val subtitleMailbox = SubtitleUiMailbox({ task -> mainActivity.runOnUiThread { task() } }) { batch, ack ->
        val id=++subtitleDeliveryId
        subtitleAcknowledge=ack
        val receipt=JSONObject().put("type","subs-delivered").put("id",id).toString()
        browser?.evaluateJavaScript("""
            (function(){try {
                ${JSONArray(batch)}.forEach(function(message){if(window.__lampaNativeSubs)window.__lampaNativeSubs.onNative(message);});
            } finally {
                window.cefriumQuery({request:${JSONObject.quote(receipt)},onSuccess:function(){},onFailure:function(){}});
            }})();
        """.trimIndent())
    }

    override var isDestroyed: Boolean = false

    override fun initialize() {
        if (browser != null || isDestroyed) return

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

        // Diagnostics: there is no chrome://inspect on this engine and Cefrium does not
        // forward Chromium's console to the app, so the page patches console.* below and
        // ships the lines back through the existing cefriumQuery bridge.
        cef.setOnRequestInterceptedListener { method, url, blocked ->
            Log.d(NET_TAG, "request blocked=$blocked")
        }

        // The native response filter has an unbounded output queue. Relay media in the APK.
        cef.setResponseTapEnabled(false)

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

        subtitleDeliveryId++;subtitleAcknowledge=null;subtitleMailbox.close()
        proxyOwner.close()
        proxyExecutor.shutdownNow()
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
                "proxy-page" -> {
                    mainActivity.runOnUiThread {
                        subtitleDeliveryId++;subtitleAcknowledge=null;subtitleMailbox.reset()
                        proxyOwner.cancel()
                    }
                    callback.success("{}");true
                }
                "proxy-register" -> {
                    val url = payload.getString("url")
                    val parsed = HttpUrl.parse(url) ?: throw IllegalArgumentException("Invalid media source")
                    require(parsed.queryParameterNames().containsAll(listOf("link", "play")) &&
                        !parsed.queryParameterNames().any { it == "preload" || it == "stat" })
                    val ticket = proxyOwner.request()
                    mainActivity.runOnUiThread {
                        val cef = browser
                        val pageUrl = cef?.url.orEmpty()
                        if (cef == null || isDestroyed) callback.failure(409, "Browser closed")
                        else cef.getCookies(url) { cookies ->
                            try { proxyExecutor.execute {
                                try {
                                    val headers = linkedMapOf("User-Agent" to userAgent)
                                    val cookie = cookies.joinToString("; ") { it.name + "=" + it.value }
                                    if (cookie.isNotEmpty()) headers["Cookie"] = cookie
                                    val registration = proxyOwner.register(ticket, url, pageUrl, headers)
                                    Log.d(TAG, "Proxy source ready: " + registration.sourceId)
                                    callback.success(JSONObject().put("requestId", payload.getLong("requestId"))
                                        .put("sourceId", registration.sourceId).put("originalUrl", registration.originalUrl)
                                        .put("playbackUrl", registration.playbackUrl).toString())
                                } catch (_: Exception) { callback.failure(502, "Cannot prepare video relay") }
                            }} catch (_: java.util.concurrent.RejectedExecutionException) { callback.failure(503, "Relay busy") }
                        }
                    }
                    true
                }
                "proxy-stop" -> {
                    proxyOwner.cancel()
                    callback.success("{}"); true
                }
                "subs-delivered" -> {
                    mainActivity.runOnUiThread {
                        if(payload.optLong("id",-1L)==subtitleDeliveryId) {
                            val ack=subtitleAcknowledge;subtitleAcknowledge=null;ack?.invoke()
                        }
                    }
                    callback.success("{}");true
                }
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

                "console" -> {
                    Log.d(
                        CONSOLE_TAG,
                        "[${payload.optString("level")}] ${payload.optString("message").replace(Regex("https?://[^\\s\"<>]+"), "[URL]")}"
                    )
                    callback.success("{}")
                    true
                }

                "subs-open", "subs-select", "subs-play", "subs-pause", "subs-seek", "subs-time", "subs-stop" -> {
                    // Media response callbacks run off the UI thread, but the engine may only
                    // be driven from the UI thread, so every message is handed over first.
                    if (payload.optString("type") in setOf("subs-open", "subs-select", "subs-seek", "subs-stop")) subtitleMailbox.clear()
                    SubtitleExtractor.handle(payload, subtitleMailbox::offer)
                    callback.success("{}")
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
        val version = JSONObject.quote(BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE)
        val subtitleScript = mainActivity.assets.open("embedded-subtitles.js").bufferedReader().use { it.readText() }
        val proxyScript = mainActivity.assets.open("subtitle-proxy.js").bufferedReader().use { it.readText() }
        val script = """
            (function() {
                var __name = $name;
                var __version = $version;
                var __nativeResponses = {};

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

                // Cefrium answers native queries asynchronously, so a JS expression can
                // never receive a return value from the bridge: __nativeCall() returns null.
                // LAMPA's app.js uses two of them synchronously - checkVersion() gates every
                // native feature on AndroidJS.appVersion(), and Android.httpCall() pulls the
                // HTTP body with AndroidJS.getResp(index) right after the native layer told
                // it the request finished. Answering them locally keeps the whole native
                // transport (online sources, parsers, torrents) alive; the body itself is
                // pushed in by AndroidJS.httpReq just before Lampa.Android.httpCall() runs.
                var __local = {
                    appVersion: function() {
                        return __version;
                    },
                    getResp: function(index) {
                        var key = String(index);
                        var value = __nativeResponses[key];
                        delete __nativeResponses[key];
                        return value === undefined ? '' : value;
                    },
                    __storeResp: function(index, body) {
                        __nativeResponses[String(index)] = body;
                        // let the native side drop its own copy of the body
                        __nativeCall('getResp', [index]);
                        return true;
                    }
                };

                // Diagnostics: there is no remote inspector on this engine, so console
                // output is shipped to logcat through the bridge (tag LampaConsole).
                try {
                    var __stringify = function(value) {
                        if (typeof value === 'string') return value;
                        try { return JSON.stringify(value); }
                        catch (e) { return String(value); }
                    };
                    var __send = function(level, text) {
                        try {
                            if (text.length > 1500) text = text.slice(0, 1500) + '...';
                            window.cefriumQuery({
                                request: JSON.stringify({ type: 'console', level: level, message: text }),
                                onSuccess: function() {},
                                onFailure: function() {}
                            });
                        } catch (e) {}
                    };
                    ['log', 'info', 'warn', 'error', 'debug'].forEach(function(level) {
                        var original = console[level];
                        if (typeof original !== 'function') return;
                        console[level] = function() {
                            var parts = [];
                            for (var i = 0; i < arguments.length; i++) parts.push(__stringify(arguments[i]));
                            __send(level, parts.join(' '));
                            return original.apply(console, arguments);
                        };
                    });
                    window.addEventListener('error', function(e) {
                        __send('uncaught', (e.message || '') + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0));
                    });
                } catch (e) {}

                // Lampa draws its own playback controls. Blink's additional Cast overlay
                // can appear as a white square above them; hide only that native overlay.
                // Remote Playback and Lampa's own broadcast menu remain available.
                if (!document.getElementById('lampa-native-overlay-style')) {
                    var nativeOverlayStyle = document.createElement('style');
                    nativeOverlayStyle.id = 'lampa-native-overlay-style';
                    nativeOverlayStyle.textContent = 'video.player-video__video::-webkit-media-controls-overlay-enclosure { display: none !important; }';
                    (document.head || document.documentElement).appendChild(nativeOverlayStyle);
                }

                $proxyScript
                $subtitleScript

                var bridge = new Proxy({}, {
                    get: function(_, property) {
                        if (typeof property === 'symbol') return undefined;
                        if (Object.prototype.hasOwnProperty.call(__local, property)) {
                            return __local[property];
                        }
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
        private const val CONSOLE_TAG = "LampaConsole"
        private const val NET_TAG = "LampaNet"
    }
}
