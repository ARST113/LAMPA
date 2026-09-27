package top.rootu.lampa.helpers

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class SubtitleProxyTest {
    private val noObserver = object : RelayObserver {}
    private fun upstream(handler: (com.sun.net.httpserver.HttpExchange)->Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
            executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            start()
        }
    private fun address(s: HttpServer) = "http://127.0.0.1:"+s.address.port+"/stream/test.mkv?link=fixture&index=1&play"
    private fun get(url: String, method: String = "GET", range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout=2000; readTimeout=3000
            range?.let { setRequestProperty("Range", it) }
        }
    @Test fun preservesRangeAndHeadAnd416() {
        val server = upstream {
            val range = it.requestHeaders.getFirst("Range")
            assertEquals("identity", it.requestHeaders.getFirst("Accept-Encoding"))
            it.responseHeaders.add("Accept-Ranges", "bytes")
            when {
                range == "bytes=99-" -> { it.responseHeaders.add("Content-Range","bytes */20"); it.sendResponseHeaders(416,-1) }
                it.requestMethod == "HEAD" -> { it.responseHeaders.add("Content-Length","20"); it.sendResponseHeaders(200,-1) }
                else -> { assertEquals("bytes=9-15",range); it.responseHeaders.add("Content-Range","bytes 9-15/20"); it.sendResponseHeaders(206,7); it.responseBody.write("abcdefg".toByteArray()) }
            }
        }
        val proxy=SubtitleProxy(noObserver)
        try {
            val url=proxy.register(address(server),"https://lampa.test",emptyMap()).playbackUrl
            val r=get(url,range="bytes=9-15")
            assertEquals(206,r.responseCode); assertEquals("bytes 9-15/20",r.getHeaderField("Content-Range"))
            assertEquals("abcdefg",r.inputStream.bufferedReader().readText())
            val h=get(url,"HEAD"); assertEquals(200,h.responseCode); assertEquals(20,h.contentLength); assertEquals(-1,h.inputStream.read())
            val invalid=get(url,range="bytes=99-"); assertEquals(416,invalid.responseCode); assertEquals("bytes */20",invalid.getHeaderField("Content-Range"))
        } finally {proxy.close();server.stop(0)}
    }
    @Test fun corsTokensAndMalformedRequestsCannotOpenArbitraryUpstream() {
        val calls=AtomicLong()
        val server=upstream {calls.incrementAndGet();it.sendResponseHeaders(200,1);it.responseBody.write(65)}
        val proxy=SubtitleProxy(noObserver)
        try {
            val registration=proxy.register(address(server),"https://lampa.test",emptyMap())
            fun raw(path: String, headers: String): String {
                val u=URL(registration.playbackUrl)
                return Socket(u.host,u.port).use { sock ->
                    sock.soTimeout=3000
                    sock.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: "+u.host+":"+u.port+"\r\n"+headers+"\r\n").toByteArray())
                    sock.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(raw(URL(registration.playbackUrl).file,"Origin: https://lampa.test\r\n").contains("Access-Control-Allow-Origin: https://lampa.test"))
            val before=calls.get()
            assertTrue(raw("/unknown","").startsWith("HTTP/1.1 404"))
            assertTrue(raw(URL(registration.playbackUrl).file,"Origin: https://evil.test\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(raw(URL(registration.playbackUrl).file,"Content-Length: 1\r\nContent-Length: 2\r\n").startsWith("HTTP/1.1 400"))
            assertEquals(before,calls.get())
            proxy.stop(registration.sourceId)
            assertEquals(404,get(registration.playbackUrl).responseCode)
        } finally {proxy.close();server.stop(0)}
    }
    @Test fun redirectDropsSecretsAndStreamsUnknownLength() {
        var auth: String?="unobserved"
        val target=upstream { auth=it.requestHeaders.getFirst("Authorization"); assertNull(it.requestHeaders.getFirst("Cookie"));it.sendResponseHeaders(200,0);it.responseBody.write("chunked response".toByteArray()) }
        val redirect=upstream {it.responseHeaders.add("Location",address(target));it.sendResponseHeaders(302,-1)}
        val proxy=SubtitleProxy(noObserver)
        try {
            val url=proxy.register(address(redirect),"https://lampa.test",mapOf("Authorization" to "Basic secret","Cookie" to "secret=yes")).playbackUrl
            val r=get(url);assertEquals("chunked response",r.inputStream.bufferedReader().readText());assertNull(auth)
        } finally {proxy.close();redirect.stop(0);target.stop(0)}
    }
    @Test fun slowConsumerStopsUpstreamAndCloseCancelsBlockedRequests() {
        val server=upstream { e ->
            e.sendResponseHeaders(200,1024L*1024*1024)
            val chunk=ByteArray(65536)
            try { repeat(16384){e.responseBody.write(chunk)} } catch (_:Exception) {}
        }
        val proxy=SubtitleProxy(noObserver)
        try {
            val u=URL(proxy.register(address(server),"http://lampa.test",emptyMap()).playbackUrl)
            val sockets=(0..3).map {
                Socket().apply {receiveBufferSize=1024;connect(InetSocketAddress(u.host,u.port));getOutputStream().write(("GET "+u.file+" HTTP/1.1\r\nHost: "+u.host+":"+u.port+"\r\n\r\n").toByteArray())}
            }
            Thread.sleep(500)
            assertEquals(4,proxy.stats().activeRequests)
            assertEquals(503,get(u.toString()).responseCode)
            Thread.sleep(500)
            val first=proxy.stats().bytesRead
            Thread.sleep(1200)
            assertEquals("must stop reading after socket buffers fill",first,proxy.stats().bytesRead)
            assertEquals(0,proxy.stats().queuedRequests)
            val start=System.nanoTime();proxy.close()
            while(proxy.stats().activeRequests!=0 && System.nanoTime()-start<2_000_000_000) Thread.sleep(10)
            assertEquals(0,proxy.stats().activeRequests)
            sockets.forEach {it.close()}
        } finally {proxy.close();server.stop(0)}
    }
    @Test fun newFilmInvalidatesOldTokenAndObserverFailureDoesNotBreakVideo() {
        val server=upstream {it.sendResponseHeaders(200,4);it.responseBody.write("data".toByteArray())}
        val observer=object:RelayObserver {override fun data(responseId:Long,bytes:ByteArray,count:Int){throw IllegalArgumentException("bad MKV")}}
        val proxy=SubtitleProxy(observer)
        try {
            val first=proxy.register(address(server),"http://lampa.test",emptyMap())
            val second=proxy.register(address(server)+"&new=1","http://lampa.test",emptyMap())
            assertEquals(404,get(first.playbackUrl).responseCode)
            assertEquals("data",get(second.playbackUrl).inputStream.bufferedReader().readText())
        } finally {proxy.close();server.stop(0)}
    }
}
