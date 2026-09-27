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
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.helpers.SubtitleExtractor
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

        // Diagnostics: there is no chrome://inspect on this engine and Cefrium does not
        // forward Chromium's console to the app, so the page patches console.* below and
        // ships the lines back through the existing cefriumQuery bridge.
        cef.setOnRequestInterceptedListener { method, url, blocked ->
            Log.d(NET_TAG, "$method $url blocked=$blocked")
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

        SubtitleExtractor.shutdown()
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

                "console" -> {
                    Log.d(
                        CONSOLE_TAG,
                        "[${payload.optString("level")}] ${payload.optString("message")}"
                    )
                    callback.success("{}")
                    true
                }

                "subs-open", "subs-select", "subs-play", "subs-pause", "subs-seek", "subs-stop" -> {
                    SubtitleExtractor.handle(mainActivity, payload) { message ->
                        mainActivity.runVoidJsFunc(
                            "window.__lampaNativeSubs && window.__lampaNativeSubs.onNative",
                            JSONObject.quote(message)
                        )
                    }
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

                // ------------------------------------------------------------------
                // Embedded subtitle bridge.
                //
                // The torrent MKVs carry `subrip` subtitle tracks. Chromium only builds
                // HTMLVideoElement.textTracks for WebVTT (media/filters/ffmpeg_demuxer.cc:
                // "codec_id != AV_CODEC_ID_WEBVTT -> continue"), so `video.textTracks` stays
                // empty and the LAMPA plugins that switch subtitles by index (tracks.js,
                // pidtor.js) silently do nothing. The native side reads the same stream with a
                // headless ExoPlayer and streams parsed cues back here; they are painted into
                // LAMPA's own subtitle overlay, which the browser does render.
                // ------------------------------------------------------------------
                if (!window.__lampaNativeSubsInstalled) {
                    window.__lampaNativeSubsInstalled = true;
                    var __nativeSubs = { item: null, cues: [], seen: {}, url: '', list: [], lastSeek: 0 };

                    function __subsSendC(payload) {
                        try {
                            if (typeof window.cefriumQuery !== 'function') return;
                            window.cefriumQuery({
                                request: JSON.stringify(payload),
                                onSuccess: function() {},
                                onFailure: function() {}
                            });
                        } catch (e) {}
                    }

                    function __subsVideoEl() {
                        try {
                            if (window.Lampa && Lampa.PlayerVideo && Lampa.PlayerVideo.video) return Lampa.PlayerVideo.video();
                        } catch (e) {}
                        return null;
                    }

                    function __subsMediaUrl() {
                        try {
                            if (window.Lampa && Lampa.Player && Lampa.Player.playdata) {
                                var data = Lampa.Player.playdata();
                                if (data && typeof data.url === 'string' && data.url) return data.url;
                            }
                        } catch (e) {}
                        var video = __subsVideoEl();
                        return video && typeof video.src === 'string' ? video.src : '';
                    }

                    function __subsTextBox() {
                        var box = document.querySelector('.player-video__subtitles');
                        if (!box) return null;
                        var inner = box.querySelector('div');
                        if (!inner) return null;
                        return { box: box, inner: inner };
                    }

                    function __subsPaint() {
                        var target = __subsTextBox();
                        if (!target) return;
                        var video = __subsVideoEl();
                        if (!__nativeSubs.item || !video) {
                            target.inner.innerHTML = '&nbsp;';
                            target.inner.style.display = 'none';
                            return;
                        }
                        var time = video.currentTime * 1000;
                        var text = '';
                        var cues = __nativeSubs.cues;
                        for (var i = 0; i < cues.length; i++) {
                            if (cues[i][0] <= time && time < cues[i][1]) { text = cues[i][2]; break; }
                        }
                        target.box.classList.remove('hide');
                        target.inner.innerHTML = text ? text : '&nbsp;';
                        target.inner.style.display = text ? 'inline-block' : 'none';
                    }

                    function __subsBind() {
                        var video = __subsVideoEl();
                        if (!video || video.__lampaNativeSubsBound) return;
                        video.__lampaNativeSubsBound = true;
                        video.addEventListener('timeupdate', __subsPaint);
                        video.addEventListener('seeked', __subsPaint);
                        video.addEventListener('play', function() { __subsSendC({ type: 'subs-play' }); });
                        video.addEventListener('pause', function() { __subsSendC({ type: 'subs-pause' }); });
                        video.addEventListener('seeking', function() {
                            if (__nativeSubs.item) {
                                __subsSendC({ type: 'subs-seek', position: Math.max(0, Math.round(video.currentTime * 1000)) });
                            }
                        });
                    }

                    function __subsStart(item) {
                        var ordinal = parseInt(item.index, 10);
                        if (!(ordinal >= 0)) return;
                        var video = __subsVideoEl();
                        var url = __subsMediaUrl();
                        if (!url || url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) {
                            console.warn('[LAMPA subs] unsupported source: ' + url);
                            return;
                        }
                        if (__nativeSubs.item === item) return;
                        if (url !== __nativeSubs.url) {
                            __nativeSubs.url = url;
                            __nativeSubs.ordinalSent = -1;
                            __subsSendC({
                                type: 'subs-open',
                                url: url,
                                position: video ? Math.max(0, Math.round(video.currentTime * 1000)) : 0
                            });
                            console.log('[LAMPA subs] open ' + url);
                        }
                        __nativeSubs.item = item;
                        __nativeSubs.cues = [];
                        __nativeSubs.seen = {};
                        __nativeSubs.ordinalSent = ordinal;
                        var position = video ? Math.max(0, Math.round(video.currentTime * 1000)) : 0;
                        if (Math.abs(position - (__nativeSubs.lastSeek || 0)) > 1500) {
                            __nativeSubs.lastSeek = position;
                            __subsSendC({
                                type: 'subs-select',
                                ordinal: ordinal,
                                language: item.language || '',
                                label: item.label || item.title || '',
                                position: position
                            });
                        }
                        console.log('[LAMPA subs] select ordinal ' + ordinal + ' at ' + position + ' of ' + __nativeSubs.list.length + ' native tracks');
                        __subsPaint();
                    }

                    function __subsStop() {
                        __nativeSubs.item = null;
                        __nativeSubs.cues = [];
                        __subsSendC({ type: 'subs-stop' });
                        __subsPaint();
                    }

                    window.__lampaNativeSubs = {
                        onNative: function(raw) {
                            var message = null;
                            try { message = JSON.parse(raw); } catch (e) { return; }
                            if (!message) return;
                            if (message.type === 'cues') {
                                if (__nativeSubs.item === null) return;
                                if (message.ordinal !== parseInt(__nativeSubs.item.index, 10)) return;
                                var incoming = message.cues || [];
                                for (var i = 0; i < incoming.length; i++) {
                                    var cue = incoming[i];
                                    var start = cue[0] < 0 ? 0 : cue[0];
                                    var end = (cue[1] < 0 || cue[1] <= start) ? start + 4000 : cue[1];
                                    var key = start + ':' + end;
                                    if (__nativeSubs.seen[key]) continue;
                                    __nativeSubs.seen[key] = true;
                                    __nativeSubs.cues.push([start, end, cue[2]]);
                                }
                                __subsPaint();
                            } else if (message.type === 'tracks') {
                                __nativeSubs.list = message.tracks || [];
                                var names = [];
                                for (var t = 0; t < __nativeSubs.list.length; t++) {
                                    names.push(__nativeSubs.list[t].ordinal + ':' + __nativeSubs.list[t].mime + ':' + __nativeSubs.list[t].label);
                                }
                                console.log('[LAMPA subs] native tracks ' + __nativeSubs.list.length + ' [' + names.join(' | ') + ']');
                            } else if (message.type === 'selected') {
                                console.log('[LAMPA subs] native selected ' + message.ordinal + ' ' + message.mime);
                            } else if (message.type === 'error') {
                                console.warn('[LAMPA subs] ' + message.message);
                            }
                        },
                        stop: __subsStop
                    };

                    function __subsWrapPanel() {
                        try {
                            var panel = window.Lampa && Lampa.PlayerPanel;
                            if (!panel || panel.__lampaNativeSubsWrapped || typeof panel.setSubs !== 'function') return;
                            var original = panel.setSubs;
                            panel.setSubs = function(items) {
                                var wrapped = items;
                                try {
                                    wrapped = (items || []).map(function(source) {
                                        if (!source || typeof source.index === 'undefined') return source;
                                        var clone = {
                                            index: source.index,
                                            language: source.language,
                                            label: source.label,
                                            title: source.title,
                                            ghost: source.ghost,
                                            selected: source.selected === true
                                        };
                                        Object.defineProperty(clone, 'mode', {
                                            configurable: true,
                                            set: function(value) {
                                                if (value === 'showing') {
                                                    if (parseInt(clone.index, 10) >= 0) __subsStart(clone); else __subsStop();
                                                } else if (__nativeSubs.item === clone) {
                                                    __subsStop();
                                                }
                                            },
                                            get: function() { return __nativeSubs.item === clone ? 'showing' : 'disabled'; }
                                        });
                                        return clone;
                                    });
                                } catch (e) {
                                    wrapped = items;
                                }
                                return original.call(panel, wrapped);
                            };
                            panel.__lampaNativeSubsWrapped = true;
                        } catch (e) {}
                    }

                    setInterval(function() {
                        __subsWrapPanel();
                        __subsBind();
                        __subsPaint();
                    }, 1000);
                }

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
