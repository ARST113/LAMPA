package top.rootu.lampa.helpers

import okhttp3.*
import okio.Okio
import java.io.IOException
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ProxyRequest(val method: String, val path: String, val headers: Map<String,String>)

internal class SubtitleProxyHttp(private val client: OkHttpClient,
    private val readCount: (Int)->Unit, private val writeCount: (Int)->Unit) {
    fun readRequest(socket: Socket): ProxyRequest {
        val input=socket.getInputStream()
        var total=0
        fun line(): String {
            val out=StringBuilder()
            while(true) {
                val b=input.read()
                require(b>=0 && ++total<=16384) {"Invalid HTTP headers"}
                if(b==10) { require(out.isNotEmpty() && out.last()=='\r');return out.dropLast(1).toString() }
                require(b in 32..126 || b==13 || b==9)
                out.append(b.toChar())
            }
        }
        val first=line().split(' ')
        require(first.size==3 && first[0] in setOf("GET","HEAD","OPTIONS") &&
            first[1].startsWith("/") && !first[1].startsWith("//") && first[2] in setOf("HTTP/1.0","HTTP/1.1"))
        val headers=linkedMapOf<String,String>()
        while(true) {
            val row=line();if(row.isEmpty())break
            val colon=row.indexOf(':');require(colon>0)
            val name=row.substring(0,colon).lowercase(Locale.ROOT)
            require(name.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(name))
            headers[name]=row.substring(colon+1).trim()
        }
        require(!headers.containsKey("transfer-encoding") && (headers["content-length"]?: "0")=="0")
        require(headers["host"] in setOf("127.0.0.1:"+socket.localPort,"localhost:"+socket.localPort))
        return ProxyRequest(first[0],first[1],headers)
    }
    fun serve(connection: RelayConnection, request: ProxyRequest, source: ProxySource, id: Long, observer: RelayObserver) {
        val socket=connection.socket
        val origin=request.headers["origin"]
        if(origin!=null && origin!=source.pageOrigin) {error(socket,403);return}
        val sink=Okio.buffer(Okio.sink(socket))
        sink.timeout().timeout(30,TimeUnit.SECONDS)
        val cors="Access-Control-Allow-Origin: "+source.pageOrigin+"\r\nVary: Origin\r\n"+
            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n"
        if(request.method=="OPTIONS") {
            connection.responseStarted=true
            sink.writeUtf8("HTTP/1.1 204 No Content\r\n"+cors+
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"+
                "Access-Control-Allow-Headers: Range, If-Range, If-None-Match, If-Modified-Since\r\n"+
                "Access-Control-Allow-Private-Network: true\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").flush()
            return
        }
        var url=HttpUrl.parse(source.originalUrl) ?: throw IllegalArgumentException("Invalid source")
        var credentials=true
        var redirects=0
        while(true) {
            val builder=Request.Builder().url(url).method(request.method,null).header("Accept-Encoding","identity")
            for((key,value) in source.headers) {
                if(key.lowercase(Locale.ROOT) in setOf("user-agent","referer","accept","authorization","cookie") &&
                    (credentials || key.lowercase(Locale.ROOT) !in setOf("authorization","cookie"))) builder.header(key,value)
            }
            for(key in listOf("range","if-range","if-none-match","if-modified-since","if-unmodified-since","if-match"))
                request.headers[key]?.let {builder.header(key,it)}
            val call=client.newCall(builder.build())
            connection.attach(call)
            val response=call.execute()
            if(response.code() in setOf(301,302,303,307,308)) {
                val next=response.header("Location")?.let {url.resolve(it)}
                response.close()
                require(next!=null && ++redirects<=5)
                require(!(next.host() in setOf("127.0.0.1","localhost","::1") && next.port()==socket.localPort))
                if(next.scheme()!=url.scheme() || next.host()!=url.host() || next.port()!=url.port()) credentials=false
                url=next
                continue
            }
            response.use { upstream ->
                var parsing=true
                val offset=Regex("bytes (\\d+)-").find(upstream.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                try {observer.begin(id,source.id,url.toString(),upstream.code(),offset)}catch(_:Exception){parsing=false}
                connection.responseStarted=true
                sink.writeUtf8("HTTP/1.1 "+upstream.code()+" Response\r\n"+cors+"Connection: close\r\n")
                for(name in listOf("Content-Type","Content-Length","Content-Range","Accept-Ranges","ETag","Last-Modified","Content-Encoding","Cache-Control")) {
                    upstream.header(name)?.let {value -> if(!value.contains('\r')&&!value.contains('\n')) sink.writeUtf8("$name: $value\r\n")}
                }
                sink.writeUtf8("\r\n").flush()
                if(request.method=="HEAD")return
                val input=upstream.body()?.byteStream() ?: return
                val block=ByteArray(65536)
                while(true) {
                    val count=input.read(block);if(count<0)break
                    if(count==0)continue
                    readCount(count)
                    if(parsing) try{observer.data(id,block,count)}catch(_:Exception){parsing=false}
                    sink.write(block,0,count).flush()
                    writeCount(count)
                }
                return
            }
        }
    }
    companion object {
        fun error(socket: Socket, code: Int) {
            try {
                socket.getOutputStream().write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }catch(_:IOException){} finally {runCatching{socket.close()}}
        }
    }
}
