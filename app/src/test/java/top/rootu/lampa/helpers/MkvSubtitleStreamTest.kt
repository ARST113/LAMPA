package top.rootu.lampa.helpers

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MkvSubtitleStreamTest {
    private fun uint(value: Long): ByteArray {
        var n = value
        val bytes = ArrayList<Byte>()
        do { bytes.add(0, n.toByte()); n = n ushr 8 } while (n > 0)
        return bytes.toByteArray()
    }
    private fun size(value: Int): ByteArray {
        for (width in 1..4) if (value.toLong() < (1L shl (7 * width)) - 1) {
            val n = value.toLong() or (1L shl (7 * width))
            return ByteArray(width) { (n ushr (8 * (width - it - 1))).toByte() }
        }
        error("fixture too large")
    }
    private fun el(id: Long, bytes: ByteArray) = uint(id) + size(bytes.size) + bytes
    private fun num(id: Long, n: Long) = el(id, uint(n))
    private fun str(id: Long, text: String) = el(id, text.toByteArray(Charsets.UTF_8))
    private fun track(n: Long, language: String, name: String, codec: String = "S_TEXT/UTF8", defaultNs: Long = 0) = el(0xAE,
        num(0xD7, n) + num(0x83, 17) + str(0x86, codec) + str(0x22B59C, language) + str(0x536E, name) + num(0x23E383, defaultNs))
    private fun block(track: Int, time: Int, text: ByteArray, flags: Int = 0) =
        byteArrayOf((track or 0x80).toByte(), (time shr 8).toByte(), time.toByte(), flags.toByte()) + text
    private fun group(track: Int, time: Int, duration: Long, text: String, durationFirst: Boolean = false): ByteArray {
        val b = el(0xA1, block(track, time, text.toByteArray(Charsets.UTF_8)))
        val d = num(0x9B, duration)
        return el(0xA0, if (durationFirst) d + b else b + d)
    }
    private fun cluster(time: Long, body: ByteArray) = el(0x1F43B675, num(0xE7, time) + body)
    private fun metadata(scale: Long = 1_000_000, tracks: ByteArray = track(2, "rus", "Russian") + track(3, "", "English SDH")) =
        el(0x1A45DFA3, str(0x4282, "matroska")) + uint(0x18538067) + byteArrayOf(0xFF.toByte()) +
            el(0x1549A966, num(0x2AD7B1, scale)) + el(0x1654AE6B, tracks)
    private fun feed(parser: MkvSubtitleStream, bytes: ByteArray, chunk: Int) {
        var offset = 0
        while (offset < bytes.size) { val end = minOf(bytes.size, offset + chunk); parser.feed(bytes.copyOfRange(offset, end)); offset = end }
    }
    private fun cues(events: List<JSONObject>): List<List<Any>> = events.filter { it.optString("type") == "cues" }.flatMap { event ->
        val rows = event.getJSONArray("cues")
        (0 until rows.length()).map { val row = rows.getJSONArray(it); listOf(event.getInt("ordinal"), row.getLong(0), row.getLong(1), row.getString(2)) }
    }

    @Test fun arbitrarySingleByteChunksRetainTracksSignedTimingScaleAndDurationOrder() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        feed(parser, metadata(2_000_000) + cluster(5000, group(2, -100, 1250, "Привет\nмир") + group(3, 2000, 1500, "English", true)), 1)
        assertEquals(listOf(listOf(0, 9800L, 12300L, "Привет\nмир"), listOf(1, 14000L, 17000L, "English")), cues(events))
        val tracks = events.single { it.optString("type") == "tracks" }.getJSONArray("tracks")
        assertEquals("English SDH", tracks.getJSONObject(1).getString("label"))
        assertEquals("", tracks.getJSONObject(1).getString("language"))
        assertEquals(10000L, events.single { it.optString("type") == "cluster" }.getLong("timeMs"))
    }

    @Test fun everyTwoPartSplitIncludingHeadersProducesTheSameCue() {
        val bytes = metadata() + cluster(5000, group(2, -100, 1250, "Split header"))
        for (split in 0..bytes.size) {
            val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
            parser.feed(bytes.copyOfRange(0, split)); parser.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals("split=$split", listOf(listOf(0, 4900L, 6150L, "Split header")), cues(events))
        }
    }

    @Test fun responseResetDropsPartialFrameButRetainsMetadataForValidatedMidstreamResync() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        parser.feed(metadata(2_000_000))
        val partial = cluster(0, group(2, 0, 500, "Must be discarded"))
        parser.feed(partial.copyOf(partial.size - 3)); parser.beginResponse()
        val invalidCandidate = uint(0x1F43B675) + size(5) + byteArrayOf(0xE7.toByte(), 0x89.toByte(), 1, 2, 3)
        val body = el(0xBF, byteArrayOf(0, 0, 0, 0)) + num(0xA7, 1234) + num(0xE7, 25000) + group(2, 0, 1000, "After seek")
        feed(parser, "{\"preload\":true}".toByteArray() + invalidCandidate + el(0x1F43B675, body), 3)
        assertEquals(listOf(listOf(0, 50000L, 52000L, "After seek")), cues(events))
        assertEquals(1, events.count { it.optString("type") == "tracks" })
        assertEquals(1, events.count { it.optString("type") == "cluster" })
    }

    @Test fun neverScansForFalseClustersInsideSkippedVideoPayload() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        val fake = cluster(1000, group(2, 0, 1000, "Not a real cue"))
        val video = el(0xA3, block(1, 0, ByteArray(100000) + fake + ByteArray(100000)))
        feed(parser, metadata() + cluster(0, video + group(2, 5000, 1000, "Real cue")), 4093)
        assertEquals(listOf(listOf(0, 5000L, 6000L, "Real cue")), cues(events))
    }

    @Test fun includesUnsupportedTrackInOrdinalsAndEmitsOnlyUtf8() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        parser.feed(metadata(tracks = track(2, "rus", "Bitmap", "S_HDMV/PGS") + track(3, "eng", "English")) +
            cluster(0, group(2, 0, 1000, "bitmap bytes") + group(3, 1000, 1500, "Text")))
        assertEquals(2, events.single { it.optString("type") == "tracks" }.getJSONArray("tracks").length())
        assertEquals(listOf(listOf(1, 1000L, 2500L, "Text")), cues(events))
    }

    @Test fun xiphFixedAndEbmlLacingKeepFrameBoundariesAndDurations() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        fun laced(time: Int, lace: Int, payload: ByteArray) = el(0xA0, num(0x9B, 6000) + el(0xA1, block(2, time, payload, lace shl 1)))
        val xiph = laced(0, 1, byteArrayOf(1, 1) + "ABC".toByteArray())
        val fixed = laced(6000, 2, byteArrayOf(1) + "DDEE".toByteArray())
        val ebml = laced(12000, 3, byteArrayOf(2, 0x82.toByte(), 0xBE.toByte()) + "FFGHHH".toByteArray())
        feed(parser, metadata() + cluster(1000, xiph + fixed + ebml), 2)
        assertEquals(listOf(listOf(0, 1000L, 4000L, "A"), listOf(0, 4000L, 7000L, "BC"),
            listOf(0, 7000L, 10000L, "DD"), listOf(0, 10000L, 13000L, "EE"),
            listOf(0, 13000L, 15000L, "FF"), listOf(0, 15000L, 17000L, "G"), listOf(0, 17000L, 19000L, "HHH")), cues(events))
    }

    @Test fun unknownSizeClusterEndsAtFollowingClusterWithoutLosingItsHeader() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        val unknown = uint(0x1F43B675) + byteArrayOf(0xFF.toByte()) + num(0xE7, 0) + group(2, 0, 1000, "First")
        feed(parser, metadata() + unknown + cluster(5000, group(2, 0, 2000, "Second")), 7)
        assertEquals(listOf(listOf(0, 0L, 1000L, "First"), listOf(0, 5000L, 7000L, "Second")), cues(events))
        assertEquals(listOf(0L, 5000L), events.filter { it.optString("type") == "cluster" }.map { it.getLong("timeMs") })
    }

    @Test fun oversizedTextAndMetadataAreSkippedWithinBoundedMemoryAndFollowingCueSurvives() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        val tooLargeText = el(0xA0, el(0xA1, block(2, 0, ByteArray(1024 * 1024 + 1))) + num(0x9B, 1000))
        val tooLargeInfo = el(0x1549A966, ByteArray(2 * 1024 * 1024))
        feed(parser, metadata() + tooLargeInfo + cluster(0, tooLargeText + group(2, 5000, 1000, "After oversize")), 65536)
        assertEquals(listOf(listOf(0, 5000L, 6000L, "After oversize")), cues(events))
    }

    @Test fun malformedLacingRecoversAtNextValidatedCluster() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        val invalidLace = el(0xA3, block(2, 0, byteArrayOf(1, 127, 65), 2))
        feed(parser, metadata() + cluster(0, invalidLace) + cluster(10000, group(2, 0, 2000, "Recovered")), 5)
        assertEquals(listOf(listOf(0, 10000L, 12000L, "Recovered")), cues(events))
    }

    @Test fun simpleBlocksUseDefaultDurationAndTwoByteTrackNumbers() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        val header = byteArrayOf(0x40, 0x82.toByte(), 0xFF.toByte(), 0x9C.toByte(), 0) // track 130, -100 ticks
        val simple = el(0xA3, header + "{\\an8}<i>Hello</i><br>world".toByteArray())
        feed(parser, metadata(tracks = track(130, "eng", "English", defaultNs = 1_250_000_000)) + cluster(5000, simple), 1)
        assertEquals(listOf(listOf(0, 4900L, 6150L, "Hello\nworld")), cues(events))
    }

    @Test fun oversizedMetadataStringsAreCappedBeforePublishing() {
        val events = ArrayList<JSONObject>(); val parser = MkvSubtitleStream({ events.add(JSONObject(it)) })
        feed(parser, metadata(tracks = track(2, "e".repeat(1000), "x".repeat(300000))) + cluster(0, group(2, 0, 1000, "Text")), 8192)
        val track = events.single { it.optString("type") == "tracks" }.getJSONArray("tracks").getJSONObject(0)
        assertEquals(512, track.getString("label").length)
        assertEquals(128, track.getString("language").length)
        assertEquals(listOf(listOf(0, 0L, 1000L, "Text")), cues(events))
    }
}
