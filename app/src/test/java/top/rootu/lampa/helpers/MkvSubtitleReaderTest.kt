package top.rootu.lampa.helpers

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

/** Real Matroska bytes over HTTP. Expected cue times come from the fixture, not the parser. */
class MkvSubtitleReaderTest {
    private fun join(vararg bytes: ByteArray) = bytes.fold(byteArrayOf()) { a, b -> a + b }
    private fun uint(value: Long): ByteArray {
        var n = value
        val bytes = ArrayList<Byte>()
        do { bytes.add(0, n.toByte()); n = n ushr 8 } while (n > 0)
        return bytes.toByteArray()
    }
    private fun size(value: Int): ByteArray {
        for (width in 1..4) if (value.toLong() < (1L shl (7 * width)) - 1) {
            val encoded = value.toLong() or (1L shl (7 * width))
            return ByteArray(width) { (encoded ushr (8 * (width - it - 1))).toByte() }
        }
        error("fixture too large")
    }
    private fun el(id: Long, data: ByteArray) = uint(id) + size(data.size) + data
    private fun number(id: Long, n: Long) = el(id, uint(n))
    private fun string(id: Long, text: String) = el(id, text.toByteArray(Charsets.UTF_8))
    private fun track(n: Long, language: String, label: String, codec: String = "S_TEXT/UTF8") = el(0xAE,
        join(number(0xD7, n), number(0x83, 17), string(0x86, codec), string(0x22B59C, language), string(0x536E, label)))
    private fun block(track: Int, relative: Int, text: String) =
        byteArrayOf((track or 0x80).toByte(), (relative shr 8).toByte(), relative.toByte(), 0) + text.toByteArray(Charsets.UTF_8)
    private fun group(track: Int, relative: Int, duration: Long, text: String, durationFirst: Boolean = false): ByteArray {
        val data = el(0xA1, block(track, relative, text))
        val time = number(0x9B, duration)
        return el(0xA0, if (durationFirst) time + data else data + time)
    }
    private fun fixture(scale: Long = 1_000_000, unknownSegment: Boolean = false): ByteArray {
        val info = el(0x1549A966, number(0x2AD7B1, scale))
        val tracks = el(0x1654AE6B, track(2, "rus", "Full") + track(3, "eng", "English"))
        val cluster = el(0x1F43B675, join(number(0xE7, 5000),
            group(2, -100, 1250, "Привет\nмир"),
            group(3, 0, 1000, "Hello"),
            group(2, 2000, 3000, "Второй титр", true)))
        val body = info + tracks + cluster
        val segment = if (unknownSegment) uint(0x18538067) + byteArrayOf(0xFF.toByte()) + body else el(0x18538067, body)
        return el(0x1A45DFA3, string(0x4282, "matroska")) + segment
    }
    private fun read(bytes: ByteArray, ordinal: Int = 0, language: String = "", label: String = "", at: Long = 0,
                     delivered: (Int) -> Unit = {}, responseBody: (Int, ByteArray) -> ByteArray = { _, body -> body },
                     ready: (MkvSubtitleReader) -> Unit = {}, log: (String) -> Unit = {}): List<JSONObject> {
        val output = ArrayList<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fixture.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            delivered(end - start + 1)
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(responseBody(start, bytes.copyOfRange(start, end + 1))) }
        }
        server.start()
        try {
            MkvSubtitleReader({ output.add(JSONObject(it)) }, language, label, log).also { it.playerPositionMs = at; ready(it) }
                .run("http://127.0.0.1:${server.address.port}/fixture.mkv", ordinal)
        } finally { server.stop(0) }
        return output
    }
    private fun cues(events: List<JSONObject>): List<List<Any>> = events.filter { it.optString("type") == "cues" }.flatMap { event ->
        val items = event.getJSONArray("cues")
        (0 until items.length()).map { val a = items.getJSONArray(it); listOf(a.getLong(0), a.getLong(1), a.getString(2)) }
    }
    @Test fun readsUtf8BlockTextWithContainerTimingAndKeepsFollowingBlocks() {
        val events = read(fixture())
        assertEquals(listOf(listOf(4900L, 6150L, "Привет\nмир"), listOf(7000L, 10000L, "Второй титр")), cues(events))
        assertFalse(events.toString(), events.any { it.optString("type") == "error" })
    }

    private fun retryFixture(): Pair<ByteArray, Int> {
        val tracks = el(0x1654AE6B, track(2, "rus", "Full"))
        val video = el(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + ByteArray(128 * 1024))
        val last = group(2, 3000, 2000, "Following subtitle")
        val cluster = el(0x1F43B675, number(0xE7, 1000) + group(2, 0, 1000, "First subtitle") + video + last)
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, tracks + cluster)
        return bytes to bytes.size - last.size
    }

    @Test fun refetchesTransientZeroFilledRangeAtTheSameHeaderAndKeepsFollowingCue() {
        val (bytes, headerAt) = retryFixture()
        val requests = ArrayList<Int>()
        val events = read(bytes, responseBody = { start, body ->
            requests.add(start)
            if (start == headerAt && requests.count { it == headerAt } == 1) ByteArray(body.size) else body
        })
        assertEquals(listOf(listOf(1000L, 2000L, "First subtitle"), listOf(4000L, 6000L, "Following subtitle")), cues(events))
        assertEquals("The missing header must be retried without skipping it", 2, requests.count { it == headerAt })
        assertFalse(events.toString(), events.any { it.optString("type") == "error" })
    }

    @Test fun reportsPermanentZeroFilledRangeAfterBoundedRetries() {
        val (bytes, headerAt) = retryFixture()
        var attempts = 0
        val events = read(bytes, responseBody = { start, body ->
            if (start == headerAt) { attempts++; ByteArray(body.size) } else body
        })
        assertEquals(4, attempts)
        assertEquals(listOf(listOf(1000L, 2000L, "First subtitle")), cues(events))
        val error = events.single { it.optString("type") == "error" }.getString("message")
        assertTrue(error, error.contains("Zero-filled HTTP range at byte $headerAt"))
    }

    @Test fun doesNotRetryGenuinelyInvalidHeaderWithNonzeroData() {
        val (bytes, headerAt) = retryFixture()
        var attempts = 0
        val events = read(bytes, responseBody = { start, body ->
            if (start == headerAt) { attempts++; body[0] = 0 }
            body
        })
        assertEquals(1, attempts)
        assertEquals("Invalid EBML integer", events.single { it.optString("type") == "error" }.getString("message"))
    }

    @Test fun stoppingDuringZeroRangeRetryCancelsFurtherReadsAndErrors() {
        val (bytes, headerAt) = retryFixture()
        lateinit var reader: MkvSubtitleReader
        var attempts = 0
        val events = read(bytes, responseBody = { start, body ->
            if (start == headerAt) { attempts++; ByteArray(body.size) } else body
        }, ready = { reader = it }, log = { if (it.startsWith("zero-filled subtitle range")) reader.stop() })
        assertEquals(1, attempts)
        assertEquals(listOf(listOf(1000L, 2000L, "First subtitle")), cues(events))
        assertFalse(events.toString(), events.any { it.optString("type") == "error" })
    }

    @Test fun truncatedHttpRangeStillFailsWithoutZeroRangeRetries() {
        val (bytes, headerAt) = retryFixture()
        val messages = ArrayList<String>()
        val events = read(bytes, responseBody = { start, body ->
            if (start == headerAt) body.copyOf(body.size - 1) else body
        }, log = { messages.add(it) })
        // OkHttp may retry a broken transport itself; the parser must never classify a
        // truncated response as a complete zero-filled range or enter its recovery loop.
        assertFalse(messages.toString(), messages.any { it.startsWith("zero-filled subtitle range") })
        assertEquals(listOf(listOf(1000L, 2000L, "First subtitle")), cues(events))
        val error = events.single { it.optString("type") == "error" }.getString("message")
        assertTrue(error.isNotBlank())
        assertFalse(error, error.startsWith("Zero-filled HTTP range"))
    }

    @Test fun metadataProbeListsTracksWithoutSelectingOrStreamingCues() {
        val events = read(fixture(), ordinal = -1)
        assertEquals(listOf("tracks"), events.map { it.optString("type") })
        val tracks = events.single().getJSONArray("tracks")
        assertEquals(2, tracks.length())
        assertEquals("S_TEXT/UTF8", tracks.getJSONObject(0).getString("mime"))
        assertEquals("Full", tracks.getJSONObject(0).getString("label"))
        assertEquals(1, tracks.getJSONObject(1).getInt("ordinal"))
    }
    @Test fun appliesTimestampScaleAndAcceptsOneByteUnknownSegmentSize() {
        assertEquals(listOf(listOf(9800L, 12300L, "Привет\nмир"), listOf(14000L, 20000L, "Второй титр")), cues(read(fixture(2_000_000, true))))
    }
    @Test fun selectsTheRequestedLanguageAndPreservesTheMenuOrdinalInReplies() {
        val events = read(fixture(), 7, "eng", "English")
        assertEquals(listOf(listOf(5000L, 6000L, "Hello")), cues(events))
        assertEquals(7, events.first { it.optString("type") == "cues" }.getInt("ordinal"))
    }

    @Test fun prefersAnExactLabelToAnEarlierPrefixAndBreaksDuplicateTiesByOrdinal() {
        val tracks = el(0x1654AE6B, join(
            track(2, "rus", "Full commentary"), track(3, "eng", "Full"),
            track(4, "rus", "Full"), track(5, "rus", "Full")))
        val cluster = el(0x1F43B675, join(number(0xE7, 0),
            group(2, 0, 1000, "Commentary"), group(3, 0, 1000, "English"),
            group(4, 0, 1000, "First exact Russian"), group(5, 0, 1000, "Second exact Russian")))
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, tracks + cluster)
        assertEquals(listOf(listOf(0L, 1000L, "First exact Russian")), cues(read(bytes, 2, "rus", "Full")))
        assertEquals(listOf(listOf(0L, 1000L, "Second exact Russian")), cues(read(bytes, 3, "rus", "Full")))
    }

    @Test fun keepsReadingAfterAnUnknownLengthCluster() {
        val tracks = el(0x1654AE6B, track(2, "rus", "Full"))
        val first = uint(0x1F43B675) + byteArrayOf(0xFF.toByte()) + number(0xE7, 0) + group(2, 0, 2000, "First")
        val second = el(0x1F43B675, number(0xE7, 10000) + group(2, 0, 3000, "Second"))
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, tracks + first + second)
        assertEquals(listOf(listOf(0L, 2000L, "First"), listOf(10000L, 13000L, "Second")), cues(read(bytes)))
    }

    @Test fun preservesCueAlreadyActiveAtResumedPosition() {
        val tracks = el(0x1654AE6B, track(2, "rus", "Full"))
        val cluster = el(0x1F43B675, number(0xE7, 0) + group(2, 0, 30000, "Still visible"))
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, tracks + cluster)
        assertEquals(listOf(listOf(0L, 30000L, "Still visible")), cues(read(bytes, at = 20000)))
    }

    @Test fun seeksUsingCuesWithoutDownloadingEarlierVideo() {
        // Info after Tracks is valid and must be read before interpreting the seek index.
        val info = el(0x1549A966, number(0x2AD7B1, 2_000_000))
        val tracks = el(0x1654AE6B, track(2, "rus", "Full"))
        val earlier = el(0x1F43B675, number(0xE7, 0) + el(0xEC, ByteArray(2 * 1024 * 1024)))
        val later = el(0x1F43B675, number(0xE7, 50000) + group(2, 0, 2500, "At the new position"))
        // Fixed-width offsets make the layout deterministic, independent of encoded values.
        fun offset(id: Long, n: Int) = el(id, byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        fun seek(cuesAt: Int) = el(0x114D9B74, el(0x4DBB, el(0x53AB, uint(0x1C53BB6B)) + offset(0x53AC, cuesAt)))
        fun cue(time: Long, at: Int) = el(0xBB, number(0xB3, time) + el(0xB7, number(0xF7, 2) + offset(0xF1, at)))
        val headSize = seek(0).size + info.size + tracks.size
        val cues = el(0x1C53BB6B, cue(0, headSize) + cue(50000, headSize + earlier.size))
        val body = seek(headSize + earlier.size + later.size) + tracks + info + earlier + later + cues
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, body)
        var fetched = 0
        val events = read(bytes, at = 100000, delivered = { fetched += it })
        assertEquals(listOf(listOf(100000L, 105000L, "At the new position")), cues(events))
        assertTrue("Downloaded $fetched bytes instead of using the cue index", fetched < 256 * 1024)
    }

    @Test fun videoOnlyCuesDoNotSkipAnEarlierSubtitleThatIsStillActive() {
        val videoTrack = el(0xAE, join(number(0xD7, 1), number(0x83, 1), string(0x86, "V_MPEG4/ISO/AVC")))
        val tracks = el(0x1654AE6B, videoTrack + track(2, "rus", "Full"))
        val videoBlock = el(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + ByteArray(2 * 1024 * 1024))
        val first = el(0x1F43B675, number(0xE7, 0) + group(2, 0, 30000, "Still active at 20 seconds") + videoBlock)
        val second = el(0x1F43B675, number(0xE7, 20000) + group(2, 15000, 1000, "Following subtitle"))
        fun offset(id: Long, n: Int) = el(id, byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        fun seek(cuesAt: Int) = el(0x114D9B74, el(0x4DBB, el(0x53AB, uint(0x1C53BB6B)) + offset(0x53AC, cuesAt)))
        fun videoCue(time: Long, at: Int) = el(0xBB, number(0xB3, time) + el(0xB7, number(0xF7, 1) + offset(0xF1, at)))
        val firstAt = seek(0).size + tracks.size
        val index = el(0x1C53BB6B, videoCue(0, firstAt) + videoCue(20000, firstAt + first.size))
        val body = seek(firstAt + first.size + second.size) + tracks + first + second + index
        val bytes = el(0x1A45DFA3, string(0x4282, "matroska")) + el(0x18538067, body)
        var fetched = 0
        val events = read(bytes, at = 20000, delivered = { fetched += it })
        assertEquals(listOf(listOf(0L, 30000L, "Still active at 20 seconds"), listOf(35000L, 36000L, "Following subtitle")), cues(events))
        assertTrue("Downloaded $fetched bytes instead of skipping video payload", fetched < 256 * 1024)
    }
}
