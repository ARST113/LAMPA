package top.rootu.lampa.helpers

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class SubtitleProxyTest {
    private val noObserver = object : RelayObserver {}
    @Test fun seekRecoversWhenServerClosesAnIdleKeepAliveConnection() {
        val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))
        val firstRead=CountDownLatch(1)
        val idleClosed=CountDownLatch(1)
        val worker=Thread {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    val reader=socket.getInputStream().bufferedReader()
                    while(reader.readLine()?.isNotEmpty()==true){}
                    socket.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\nContent-Range: bytes 0-3/4\r\n\r\ndata".toByteArray())
                    if(index==0) {firstRead.await(3,TimeUnit.SECONDS);Thread.sleep(100)}
                }; if(index==0)idleClosed.countDown() }
            } catch(_:java.io.IOException) {} finally {idleClosed.countDown()}
        }.apply {isDaemon=true;start()}
        SubtitleProxy(noObserver).use {proxy ->
            try {
                val url=proxy.register("http://127.0.0.1:${server.localPort}/file.mkv","http://lampa.test",emptyMap()).playbackUrl
                assertEquals("data",get(url,range="bytes=0-3").inputStream.bufferedReader().use{it.readText()})
                firstRead.countDown()
                assertTrue(idleClosed.await(3,TimeUnit.SECONDS))
                val seek=get(url,range="bytes=0-3")
                assertEquals("a closed pooled connection must not fail a fresh Range request",206,seek.responseCode)
                assertEquals("data",seek.inputStream.bufferedReader().use{it.readText()})
            }finally{firstRead.countDown();server.close();worker.join(1000)}
        }
    }
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
    @Test fun zeroLengthConditionalAndPreflightDoNotInventBodyBytes() {
        val calls=AtomicLong()
        val server=upstream {
            calls.incrementAndGet()
            if(it.requestHeaders.getFirst("If-None-Match")=="fixture-v1") it.sendResponseHeaders(304,-1)
            else {it.responseHeaders.add("Content-Length","0");it.sendResponseHeaders(200,-1)}
        }
        SubtitleProxy(noObserver).use { proxy ->
            try {
                val url=proxy.register(address(server),"https://lampa.test",emptyMap()).playbackUrl
                val empty=get(url);assertEquals(200,empty.responseCode);assertEquals(-1,empty.inputStream.read())
                val conditional=get(url).apply {setRequestProperty("If-None-Match","fixture-v1")}
                assertEquals(304,conditional.responseCode);assertEquals(-1,conditional.inputStream.read())
                val before=calls.get()
                val preflight=get(url,"OPTIONS")
                assertEquals(204,preflight.responseCode)
                assertEquals("https://lampa.test",preflight.getHeaderField("Access-Control-Allow-Origin"))
                assertEquals(-1,preflight.inputStream.read());assertEquals(before,calls.get())
            }finally{server.stop(0)}
        }
    }
    @Test fun truncatedUpstreamNeverPadsMissingBytes() {
        val upstream=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val thread=Thread {
            upstream.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader()
                while(input.readLine()?.isNotEmpty()==true){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nabc".toByteArray())
            }
        }.apply {isDaemon=true;start()}
        SubtitleProxy(noObserver).use {proxy ->
            try {
                val u=URL(proxy.register("http://127.0.0.1:${upstream.localPort}/file.mkv","http://lampa.test",emptyMap()).playbackUrl)
                val raw=Socket(u.host,u.port).use {socket ->
                    socket.soTimeout=3000
                    socket.getOutputStream().write("GET ${u.file} HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
                }
                assertEquals("abc",raw.substringAfter("\r\n\r\n"))
                assertTrue(raw.contains("Content-Length: 100"))
            }finally{upstream.close();thread.join(1000)}
        }
    }
    @Test fun repeatedCloseReleasesRelayThreadsAndSockets() {
        val server=upstream {it.sendResponseHeaders(200,1);it.responseBody.write(65)}
        val before=Thread.getAllStackTraces().keys.count {it.isAlive && it.name.startsWith("LampaRelay")}
        try {
            repeat(20){
                val proxy=SubtitleProxy(noObserver)
                val url=proxy.register(address(server),"http://lampa.test",emptyMap()).playbackUrl
                assertEquals(65,get(url).inputStream.use {it.read()})
                proxy.close();proxy.close()
            }
            val deadline=System.nanoTime()+2_000_000_000
            while(Thread.getAllStackTraces().keys.count{it.isAlive&&it.name.startsWith("LampaRelay")}>before && System.nanoTime()<deadline) Thread.sleep(10)
            assertEquals(before,Thread.getAllStackTraces().keys.count{it.isAlive&&it.name.startsWith("LampaRelay")})
        }finally{server.stop(0)}
    }
    @Test fun minuteLongSlowConsumerKeepsBoundedReadAhead() {
        val server=upstream { e ->
            e.sendResponseHeaders(200,1024L*1024*1024)
            try{val block=ByteArray(65536);repeat(16384){e.responseBody.write(block)}}catch(_:Exception){}
        }
        SubtitleProxy(noObserver).use {proxy ->
            try {
                val u=URL(proxy.register(address(server),"http://lampa.test",emptyMap()).playbackUrl)
                Socket().use {socket ->
                    socket.receiveBufferSize=1024;socket.connect(InetSocketAddress(u.host,u.port))
                    socket.getOutputStream().write("GET ${u.file} HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\n\r\n".toByteArray())
                    Thread.sleep(1500)
                    val high=proxy.stats().bytesRead
                    repeat(60){Thread.sleep(1000);assertEquals(high,proxy.stats().bytesRead)}
                    assertEquals("write timeout must retire sleeping consumer",0,proxy.stats().activeRequests)
                }
            }finally{server.stop(0)}
        }
    }
    @Test fun canceledStalledPeersReleaseAllSlotsWithinTwoSeconds() {
        val release=CountDownLatch(1)
        val server=upstream {e -> e.sendResponseHeaders(200,100);e.responseBody.flush();release.await(10,TimeUnit.SECONDS)}
        SubtitleProxy(noObserver).use {proxy ->
            try {
                val u=URL(proxy.register(address(server),"http://lampa.test",emptyMap()).playbackUrl)
                val sockets=(0..3).map {
                    Socket(u.host,u.port).apply {
                        soTimeout=3000
                        getOutputStream().write("GET ${u.file} HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\n\r\n".toByteArray())
                        val r=getInputStream().bufferedReader();while(r.readLine()?.isNotEmpty()==true){}
                    }
                }
                assertEquals(4,proxy.stats().activeRequests)
                sockets.forEach{it.close()}
                val deadline=System.nanoTime()+2_000_000_000
                while(proxy.stats().activeRequests>0&&System.nanoTime()<deadline)Thread.sleep(10)
                assertEquals(0,proxy.stats().activeRequests)
                assertEquals(204,get(u.toString(),"OPTIONS").responseCode)
            }finally{release.countDown();server.stop(0)}
        }
    }
    @Test fun rejectsAmbiguousUpstreamLengthsBeforeWritingResponse() {
        for(headers in listOf("Content-Length: 1\r\nTransfer-Encoding: chunked", "Content-Length: 1\r\nContent-Length: 3")) {
            val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
            val thread=Thread {server.accept().use {s ->
                val r=s.getInputStream().bufferedReader();while(r.readLine()?.isNotEmpty()==true){}
                s.getOutputStream().write("HTTP/1.1 200 OK\r\n$headers\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray())
            }}.apply {isDaemon=true;start()}
            SubtitleProxy(noObserver).use {proxy ->
                try {
                    val url=proxy.register("http://127.0.0.1:${server.localPort}/file.mkv","http://lampa.test",emptyMap()).playbackUrl
                    assertEquals(502,get(url).responseCode)
                }finally{server.close();thread.join(1000)}
            }
        }
    }
    @Test fun identifiersDoNotAliasLateCallbacksAcrossProxyLifetimes() {
        val sources=ArrayList<Long>();val responses=ArrayList<Long>()
        val observer=object:RelayObserver {
            override fun begin(responseId:Long,sourceId:Long,finalUrl:String,status:Int,startOffset:Long) {
                synchronized(responses){responses.add(responseId)}
            }
        }
        val server=upstream {it.sendResponseHeaders(200,1);it.responseBody.write(65)}
        try {
            repeat(2){SubtitleProxy(observer).use{proxy ->
                val registration=proxy.register(address(server),"http://lampa.test",emptyMap())
                sources.add(registration.sourceId)
                get(registration.playbackUrl).inputStream.use{assertEquals(65,it.read())}
            }}
            assertEquals(2,sources.toSet().size)
            assertEquals(2,responses.toSet().size)
        }finally{server.stop(0)}
    }
}
