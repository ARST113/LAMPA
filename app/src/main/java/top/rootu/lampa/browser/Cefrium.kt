package top.rootu.lampa.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.MainActivity
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Третий движок клиента: встроенный Chromium-рантайм Cefrium (`com.cefrium.*`).
 *
 * Особенности, определившие реализацию:
 *  - классы SDK собраны JDK 25 (class-файлы major 69), поэтому компилятор LAMPA
 *    (Kotlin 1.8 / AGP 8.10) их читать не умеет — обращаемся только через рефлексию;
 *  - в движке нет `addJavascriptInterface`: JS-мост строится на очереди запросов
 *    (`setQueryHandler` + `window.cefQuery`), поэтому AndroidJS публикуется шимом;
 *  - `evaluateJavascript` у движка без возврата значения — результат приходит через тот же мост;
 *  - события загрузки — интерфейсы (`setOnLoadingStateChangedListener`, `setLoadHandler`),
 *    подписываемся динамическими прокси.
 *
 * Всё, что не поддержано движком, логируется и деградирует, а не роняет клиент.
 */
class Cefrium(
    override val mainActivity: MainActivity,
    override val viewResId: Int
) : Browser {

    private companion object {
        const val TAG = "LampaCefrium"
        const val BROWSER_CLASS = "com.cefrium.CefriumBrowser"
        const val RUNTIME_CLASS = "com.cefrium.Cefrium"

        /** JS-шим моста: публикует объекты, добавленные через addJavascriptInterface. */
        val BRIDGE_JS = """
            (function(){
              if (window.__lampaCefriumBridge) return;
              function call(object, method, args){
                return new Promise(function(resolve, reject){
                  if (typeof window.cefQuery !== 'function') { reject(new Error('cefQuery недоступен')); return; }
                  window.cefQuery({
                    request: JSON.stringify({op:'call', object:object, method:method, args:args||[]}),
                    onSuccess: function(r){ try { resolve(JSON.parse(r)); } catch(e) { resolve(r); } },
                    onFailure: function(code, msg){ reject(new Error(code + ': ' + msg)); }
                  });
                });
              }
              window.__lampaCefriumBridge = {
                publish: function(name){
                  if (window[name]) return;
                  window[name] = new Proxy({}, {
                    get: function(_t, method){
                      return function(){ return call(name, String(method), Array.prototype.slice.call(arguments)); };
                    }
                  });
                },
                evalResult: function(id, value){
                  if (typeof window.cefQuery !== 'function') return;
                  window.cefQuery({ request: JSON.stringify({op:'evalResult', id:id, value:(value===undefined?null:value)}),
                                    onSuccess: function(){}, onFailure: function(){} });
                },
                reportUserAgent: function(ua){
                  if (typeof window.cefQuery !== 'function') return;
                  window.cefQuery({ request: JSON.stringify({op:'userAgent', value:String(ua)}),
                                    onSuccess: function(){}, onFailure: function(){} });
                }
              };
            })();
        """.trimIndent()
    }

    override var isDestroyed = false

    private var browser: Any? = null
    private var container: ViewGroup? = null
    private var userAgentOverride: String? = null
    private var cachedUserAgent: String? = null
    private var timersPaused = false
    private var keepVisible = false
    private var bridgeInjected = false
    private val jsObjects = LinkedHashMap<String, Any>()
    private val pendingEvaluations = LinkedHashMap<Long, (String) -> Unit>()
    private var nextEvaluationId = 1L
    private val ui = Handler(Looper.getMainLooper())

    // --------------------------------------------------------------- Browser API

    override fun initialize() {
        if (browser != null) return
        try {
            val browserClass = Class.forName(BROWSER_CLASS)
            initRuntime()
            browser = createBrowser(browserClass)
            attachContainer()
            subscribeLoadEvents(browserClass)
            installQueryHandler()
            Log.i(TAG, "движок Cefrium поднят: ${browser?.javaClass?.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "не удалось поднять Cefrium", t)
            throw IllegalStateException("Cefrium initialize failed", t)
        }
    }

    override fun loadUrl(url: String) {
        invoke("loadUrl", url)
    }

    override fun evaluateJavascript(script: String, resultCallback: (String) -> Unit) {
        val id = nextEvaluationId++
        pendingEvaluations[id] = resultCallback
        injectBridge()
        val wrapped = "(function(){try{var __r=eval(${JSONObject.quote(script)});" +
                "window.__lampaCefriumBridge&&window.__lampaCefriumBridge.evalResult($id,__r);}" +
                "catch(e){window.__lampaCefriumBridge&&window.__lampaCefriumBridge.evalResult($id,null);}})()"
        invoke("evaluateJavaScript", wrapped)
    }

    override fun addJavascriptInterface(jsObject: Any, name: String) {
        jsObjects[name] = jsObject
        Log.i(TAG, "публикую JS-объект $name (${jsObject.javaClass.name})")
        injectBridge()
    }

    override fun setUserAgentString(ua: String?) {
        userAgentOverride = ua
        invoke("setUserAgentOverride", ua)
    }

    override fun getUserAgentString(): String? {
        if (!userAgentOverride.isNullOrEmpty()) return userAgentOverride
        if (cachedUserAgent == null) {
            // Движок не отдаёт UA синхронно — просим страницу сообщить его через мост.
            invoke("evaluateJavaScript", "window.__lampaCefriumBridge&&window.__lampaCefriumBridge.reportUserAgent(navigator.userAgent)")
        }
        return cachedUserAgent
    }

    override fun pauseTimers() {
        timersPaused = true
        invoke("setPageVisible", keepVisible)
    }

    override fun resumeTimers() {
        timersPaused = false
        invoke("setPageVisible", true)
    }

    override fun setKeepVisible(keep: Boolean) {
        keepVisible = keep
        invoke("setPageVisible", keep || !timersPaused)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        Log.i(TAG, "clearCache(includeDiskFiles=$includeDiskFiles)")
        invoke("clearCache")
    }

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        jsObjects.clear()
        pendingEvaluations.clear()
        invoke("close")
        browser = null
    }

    override fun setBackgroundColor(color: Int) {
        container?.setBackgroundColor(color)
    }

    override fun canGoBack(): Boolean = invoke("canGoBack") as? Boolean ?: false

    override fun goBack() {
        invoke("goBack")
    }

    override fun setFocus() {
        container?.requestFocus()
    }

    override fun getView(): View? = container

    // ------------------------------------------------------------ рефлексия

    private fun initRuntime() {
        runCatching {
            val runtime = Class.forName(RUNTIME_CLASS)
            runtime.getMethod("initialize", android.content.Context::class.java)
                .invoke(null, mainActivity.applicationContext)
            Log.i(TAG, "Cefrium.initialize(): Chromium ${callStatic(runtime, "getChromiumVersion")}")
        }.onFailure { Log.w(TAG, "Cefrium.initialize() не сработал: ${it.message}") }
    }

    private fun callStatic(type: Class<*>, name: String): Any? = runCatching {
        type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(null)
    }.getOrNull()

    private fun createBrowser(browserClass: Class<*>): Any {
        val factory = browserClass.methods.firstOrNull {
            it.name == "createWithSurface" && Modifier.isStatic(it.modifiers) && it.parameterCount == 1
        } ?: throw IllegalStateException("нет статического createWithSurface(Activity)")
        return factory.invoke(null, mainActivity)
            ?: throw IllegalStateException("createWithSurface вернул null")
    }

    private fun attachContainer() {
        val surface = invoke("getSurfaceContainer") as? View
        val host = mainActivity.findViewById<FrameLayout>(viewResId)
            ?: throw IllegalStateException("в разметке нет контейнера id=$viewResId")
        if (surface != null) {
            (surface.parent as? ViewGroup)?.removeView(surface)
            host.addView(surface, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Log.i(TAG, "поверхность движка добавлена в контейнер")
        } else {
            Log.w(TAG, "getSurfaceContainer() вернул null")
        }
        container = host
    }

    /** Подписывается на события загрузки через доступные интерфейсы-листенеры движка. */
    private fun subscribeLoadEvents(browserClass: Class<*>) {
        var subscribed = 0
        browserClass.methods
            .filter { it.name.startsWith("set") && it.parameterCount == 1 && it.parameterTypes[0].isInterface }
            .forEach { setter ->
                val iface = setter.parameterTypes[0]
                val names = iface.methods.map { it.name }
                if (names.none { it.startsWith("on") }) return@forEach
                runCatching {
                    val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                        onEngineEvent(method.name, args)
                    }
                    setter.invoke(browser, proxy)
                    subscribed++
                    Log.d(TAG, "подписан листенер ${iface.simpleName} через ${setter.name}")
                }.onFailure { Log.d(TAG, "листенер ${iface.simpleName} не подписался: ${it.message}") }
            }
        Log.i(TAG, "подписок на события движка: $subscribed")
    }

    private fun onEngineEvent(name: String, args: Array<out Any?>?): Any? {
        when (name) {
            "onLoadStart" -> Log.i(TAG, "старт загрузки: ${args?.firstOrNull()}")
            "onLoadError" -> Log.w(TAG, "ошибка загрузки: ${args?.joinToString()}")
            "onLoadingStateChanged" -> if (args != null && args.isNotEmpty()) {
                val loading = args[0] as? Boolean ?: false
                if (!loading) Log.i(TAG, "страница загружена (loading=false)")
            }
            "onUrlChanged" -> Log.d(TAG, "URL: ${args?.firstOrNull()}")
            "onConsoleMessage" -> Log.d(TAG, "console: ${args?.joinToString()}")
            "onQuery" -> return handleQuery(args)
            "toString" -> return "LampaCefriumListener"
            "hashCode" -> return System.identityHashCode(this)
            "equals" -> return false
        }
        // boolean-методы движка ожидают ответ; всё остальное — null
        return false
    }

    private fun installQueryHandler() {
        val type = browser?.javaClass ?: return
        val setter = type.methods.firstOrNull {
            it.name == "setQueryHandler" && it.parameterCount == 1 && it.parameterTypes[0].isInterface
        }
        if (setter == null) {
            Log.w(TAG, "движок не предоставил setQueryHandler — мост AndroidJS работать не будет")
            return
        }
        val iface = setter.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "onQuery") handleQuery(args) else null
        }
        setter.invoke(browser, proxy)
        Log.i(TAG, "очередь запросов движка подключена")
    }

    /** Обработка запроса из JS: вызов метода Java-объекта или служебные операции моста. */
    private fun handleQuery(args: Array<out Any?>?): Any {
        if (args == null || args.size < 2) return true
        val request = args[1] as? String ?: return true
        val callback = args.getOrNull(2)
        return try {
            val json = JSONObject(request)
            when (json.optString("op", "call")) {
                "evalResult" -> {
                    val id = json.optLong("id", 0L)
                    val value = json.optString("value", "null")
                    ui.post { pendingEvaluations.remove(id)?.invoke(value) }
                    answer(callback, "null")
                }
                "userAgent" -> {
                    cachedUserAgent = json.optString("value", null)
                    answer(callback, "null")
                }
                else -> {
                    val target = jsObjects[json.getString("object")]
                    if (target == null) {
                        fail(callback, 404, "нет JS-объекта с именем ${json.getString("object")}")
                    } else {
                        val result = dispatch(target, json.getString("method"), json.optJSONArray("args"))
                        answer(callback, toJson(result))
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "запрос из JS не обработан: $request", t)
            fail(callback, 500, t.message ?: t.javaClass.simpleName)
            true
        }
    }

    private fun dispatch(target: Any, methodName: String, args: JSONArray?): Any? {
        val argc = args?.length() ?: 0
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == argc && Modifier.isPublic(it.modifiers)
        } ?: throw NoSuchMethodException("нет $methodName/$argc у ${target.javaClass.name}")
        val values = arrayOfNulls<Any?>(argc)
        for (i in 0 until argc) {
            val raw = args?.opt(i)
            values[i] = when (val type = method.parameterTypes[i]) {
                String::class.java -> raw?.toString()
                Int::class.javaPrimitiveType, Integer::class.java -> (raw as? Number)?.toInt() ?: 0
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> (raw as? Number)?.toLong() ?: 0L
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> raw as? Boolean ?: false
                Double::class.javaPrimitiveType, java.lang.Double::class.java -> (raw as? Number)?.toDouble() ?: 0.0
                else -> if (type.isInstance(raw)) raw else null
            }
        }
        return method.invoke(target, *values)
    }

    /** Результат Java-метода → JSON-строка (JSONObject.valueToString в Android не публичный). */
    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun answer(callback: Any?, value: String) {
        callOn(callback, "success", value)
    }

    private fun fail(callback: Any?, code: Int, message: String) {
        callOn(callback, "failure", code, message)
    }

    private fun callOn(target: Any?, name: String, vararg args: Any?) {
        if (target == null) return
        runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?.invoke(target, *args)
        }.onFailure { Log.w(TAG, "callback.$name не вызвался: ${it.message}") }
    }

    private fun injectBridge() {
        if (bridgeInjected) return
        runCatching {
            invoke("evaluateJavaScript", BRIDGE_JS)
            bridgeInjected = true
        }.onFailure { Log.w(TAG, "JS-шим не внедрён: ${it.message}") }
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        val target = browser ?: return null
        return try {
            val method: Method? = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            }
            if (method == null) {
                Log.d(TAG, "движок не поддерживает $name/${args.size}")
                null
            } else {
                method.invoke(target, *args)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$name не выполнился: ${t.cause?.message ?: t.message}")
            null
        }
    }
}
