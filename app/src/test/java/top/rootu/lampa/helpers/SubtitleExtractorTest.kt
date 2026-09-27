package top.rootu.lampa.helpers

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class SubtitleExtractorTest {
    @After fun reset() = SubtitleExtractor.shutdown()
    private fun el(id: String, data: ByteArray): ByteArray {
        require(data.size < 127)
        return id.chunked(2).map { it.toInt(16).toByte() }.toByteArray() + (0x80 or data.size).toByte() + data
    }
    private fun fixture(): ByteArray {
        val track = el("D7", byteArrayOf(1)) + el("83", byteArrayOf(17)) + el("86", "S_TEXT/UTF8".toByteArray())
        return el("1A45DFA3", el("4282", "matroska".toByteArray())) +
            byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0xFF.toByte()) + el("1654AE6B", el("AE", track))
    }
    private fun probe(url: String): List<JSONObject> {
        val messages = ArrayList<JSONObject>()
        SubtitleExtractor.handle(JSONObject().put("type", "subs-open").put("url", url)
            .put("probe", true).put("session", 12)) { messages.add(JSONObject(it)) }
        return messages
    }
    @Test fun redirectedResponseKeepsTheOriginalPlayerUrlInItsReply() {
        val original = "https://movie.test/film.mkv"
        val target = "https://cdn.test/download?token=fixture"
        SubtitleExtractor.onRedirect(original, target)
        SubtitleExtractor.onResponse(target, 206)
        SubtitleExtractor.onData(target, ByteBuffer.wrap(fixture()))
        val messages = probe(original)
        assertEquals(1, messages.size)
        assertEquals(original, messages[0].getString("url"))
        assertEquals(12L, messages[0].getLong("session"))
    }
    @Test fun extensionlessMediaIsRecognizedBeforeJavascriptDiscoveryEvenWithSplitSignature() {
        val url = "http://media.test/download?id=1"
        SubtitleExtractor.onResponse(url, 200)
        fixture().forEach { SubtitleExtractor.onData(url, ByteBuffer.wrap(byteArrayOf(it))) }
        assertEquals("tracks", probe(url).single().getString("type"))
    }
    @Test fun preloadJsonCannotReplacePlayingMediaMetadata() {
        val url = "http://media.test/stream?play"
        SubtitleExtractor.onResponse(url, 206)
        SubtitleExtractor.onData(url, ByteBuffer.wrap(fixture()))
        SubtitleExtractor.onResponse("http://media.test/stream?preload", 200)
        SubtitleExtractor.onData("http://media.test/stream?preload", ByteBuffer.wrap("{\"title\":\"fixture\"}".toByteArray()))
        assertEquals(1, probe(url).single().getJSONArray("tracks").length())
    }
    @Test fun discoveryBetweenSignatureBytesDoesNotLoseTheBeginningOfTheContainer() {
        val url = "http://media.test/download?id=1"
        val bytes = fixture()
        SubtitleExtractor.onResponse(url, 200)
        SubtitleExtractor.onData(url, ByteBuffer.wrap(bytes.copyOfRange(0, 1)))
        val messages = probe(url)
        bytes.drop(1).forEach { SubtitleExtractor.onData(url, ByteBuffer.wrap(byteArrayOf(it))) }
        assertEquals("tracks", messages.single().getString("type"))
    }
    @Test fun refreshedSignedRedirectsDoNotAccumulateAChainOrEvictTheActiveSource() {
        val original = "https://movie.test/film.mkv"
        val messages = probe(original)
        repeat(12) { SubtitleExtractor.onRedirect(original, "https://cdn.test/download?token=$it") }
        repeat(40) { SubtitleExtractor.onRedirect("https://other.test/$it", "https://other.test/cdn/$it") }
        val target = "https://cdn.test/download?token=11"
        SubtitleExtractor.onResponse(target, 206)
        SubtitleExtractor.onData(target, ByteBuffer.wrap(fixture()))
        assertEquals(original, messages.single().getString("url"))
    }
}
