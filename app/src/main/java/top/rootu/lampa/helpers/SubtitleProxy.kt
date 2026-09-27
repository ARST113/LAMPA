package top.rootu.lampa.helpers

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

interface RelayObserver {
    fun begin(responseId: Long, sourceId: Long, finalUrl: String, status: Int, startOffset: Long) {}
    fun data(responseId: Long, bytes: ByteArray, count: Int) {}
    fun end(responseId: Long) {}
}
data class ProxySource(val id: Long, val originalUrl: String, val pageOrigin: String, val headers: Map<String,String>)
data class ProxyRegistration(val sourceId: Long, val originalUrl: String, val playbackUrl: String)
data class ProxyStats(val activeRequests: Int, val queuedRequests: Int, val bytesRead: Long, val bytesWritten: Long)

internal class RelayConnection(val socket: Socket) : Closeable {
    @Volatile var sourceId = -1L
    @Volatile var responseStarted = false
    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    @Synchronized fun attach(next: Call) {
        if (closed) { next.cancel(); throw IOException("Relay closed") }
        call = next
    }
    @Synchronized override fun close() {
        closed = true
        call?.cancel()
        runCatching { socket.close() }
    }
}

/** Finite number of synchronous streams; neither workers nor video bodies are queued. */
class SubtitleProxy(private val observer: RelayObserver) : Closeable {
    private val server = ServerSocket().apply { reuseAddress=true; bind(InetSocketAddress("127.0.0.1",0),4) }
    private val ids = AtomicLong()
    private val responseIds = AtomicLong()
    private val random = SecureRandom()
    private val connections = ConcurrentHashMap<Long,RelayConnection>()
    private val slots = Semaphore(4)
    private val read = AtomicLong()
    private val written = AtomicLong()
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val http = SubtitleProxyHttp(client, {read.addAndGet(it.toLong())}, {written.addAndGet(it.toLong())})
    private val workers = ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,SynchronousQueue(),
        ThreadFactory { r -> Thread(r,"LampaRelay").apply { isDaemon=true } })
    @Volatile private var closed=false
    private var source: ProxySource?=null
    private var registration: ProxyRegistration?=null
    private var route=""
    private val acceptor=Thread({accept()},"LampaRelayAccept").apply {isDaemon=true;start()}

    @Synchronized fun register(url: String, pageOrigin: String, headers: Map<String,String>): ProxyRegistration {
        check(!closed)
        val parsed=HttpUrl.parse(url) ?: throw IllegalArgumentException("Invalid media URL")
        require(parsed.scheme() in setOf("http","https"))
        require(!(parsed.host() in setOf("127.0.0.1","localhost","::1") && parsed.port()==server.localPort))
        val page=HttpUrl.parse(pageOrigin) ?: throw IllegalArgumentException("Invalid page origin")
        val origin=page.scheme()+"://"+page.host()+(if(page.port()==HttpUrl.defaultPort(page.scheme())) "" else ":"+page.port())
        require(headers.size<=16 && headers.all { (key,value)-> key.matches(Regex("[A-Za-z0-9-]+")) && value.length<=8192 && !value.any {it=='\r'||it=='\n'||it=='\u0000'} })
        if(source?.originalUrl==url && source?.pageOrigin==origin && source?.headers==headers) return registration!!
        connections.values.forEach { it.close() }
        val id=ids.incrementAndGet()
        val token=ByteArray(24).also {random.nextBytes(it)}.joinToString("") { "%02x".format(it.toInt() and 255) }
        route="/media/$token.mkv"
        source=ProxySource(id,url,origin,headers.toMap())
        return ProxyRegistration(id,url,"http://127.0.0.1:"+server.localPort+route).also {registration=it}
    }
    @Synchronized fun stop(sourceId: Long) {
        if(source?.id!=sourceId)return
        source=null;registration=null;route=""
        connections.values.filter {it.sourceId==sourceId || it.sourceId<0}.forEach {it.close()}
    }
    private fun accept() {
        while(!closed) {
            val socket=try { server.accept() }catch(_:IOException){break}
            socket.soTimeout=15000;socket.sendBufferSize=65536;socket.tcpNoDelay=true
            if(!slots.tryAcquire()) { SubtitleProxyHttp.error(socket,503);continue }
            val id=responseIds.incrementAndGet()
            val connection=RelayConnection(socket)
            connections[id]=connection
            try { workers.execute {
                try {
                    val request=http.readRequest(socket)
                    val selected=synchronized(this) {
                        val selected=source
                        if(selected!=null && request.path==route) connection.sourceId=selected.id
                        if(selected!=null && request.path==route) selected else null
                    }
                    if(selected==null) SubtitleProxyHttp.error(socket,404)
                    else http.serve(connection,request,selected,id,observer)
                } catch (_:IllegalArgumentException) {
                    if(!connection.responseStarted) SubtitleProxyHttp.error(socket,400)
                } catch (_:Exception) {
                    if(!connection.responseStarted) SubtitleProxyHttp.error(socket,502)
                } finally {
                    runCatching {observer.end(id)}
                    connection.close();connections.remove(id);slots.release()
                }
            }} catch(_:RejectedExecutionException) {
                connections.remove(id);slots.release();SubtitleProxyHttp.error(socket,503)
            }
        }
    }
    fun stats()=ProxyStats(connections.size,workers.queue.size,read.get(),written.get())
    @Synchronized override fun close() {
        if(closed)return
        closed=true;source=null;registration=null;route=""
        runCatching {server.close()}
        connections.values.forEach {it.close()}
        workers.shutdownNow()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdown()
    }
}
