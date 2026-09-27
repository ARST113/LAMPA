package top.rootu.lampa.helpers

import java.io.Closeable

/** Serializes publication with browser destruction; cookies are fetched before entering here. */
class SubtitleProxyOwner(private val create: ()->SubtitleProxy,
                         private val registered: (Long,String)->Unit,
                         private val cleared: ()->Unit) : Closeable {
    private var proxy: SubtitleProxy? = null
    private var version=0L
    private var closed=false
    @Synchronized fun request(): Long {check(!closed);return ++version}
    @Synchronized fun register(ticket:Long,url:String,page:String,headers:Map<String,String>):ProxyRegistration {
        check(!closed && ticket==version)
        val current=proxy ?: create().also{proxy=it}
        return current.register(url,page,headers).also{registered(it.sourceId,url)}
    }
    @Synchronized fun cancel() {
        version++
        proxy?.close();proxy=null;cleared()
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true;cancel()
    }
}
