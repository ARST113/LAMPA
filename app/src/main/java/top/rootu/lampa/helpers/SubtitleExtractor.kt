package top.rootu.lampa.helpers

import android.util.Log
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

/** Collects embedded subtitle text from the browser's own media responses. No second HTTP reader. */
class SubtitleExtractor {
    companion object : RelayObserver {
        private const val TAG = "LampaSubtitles"

        private class Media(var url: String) {
            val cues = SubtitleCueCache()
            var tracks: JSONObject? = null
            var metadata: MkvSubtitleStream.Metadata? = null
            fun accept(raw: String) {
                val message = JSONObject(raw)
                when (message.optString("type")) {
                    "tracks" -> tracks = message
                    "cues" -> cues.accept(message)
                }
                if (canonical(url) == canonical(selectedUrl)) {
                    when (message.optString("type")) {
                        "tracks" -> { resolveSelection(this); if (probing) deliver(message) else announceSelection(this) }
                        "cues" -> if (ordinal >= 0 && message.optInt("ordinal", -1) == nativeOrdinal) {
                            replay()
                        }
                    }
                }
            }
            val parser = MkvSubtitleStream(::accept) { Log.d(TAG, it) }
        }

        // Two entries tolerate a late response from the previous film without losing the
        // current film's tracks. Each owns a bounded text cache and never stores video.
        private val media = LinkedHashMap<String, Media>()
        private val redirects = LinkedHashMap<String, String>()
        private val pendingHeaders = LinkedHashMap<String, ByteArray>()
        private var selectedUrl = ""
        private var session = 0L
        private var ordinal = -1
        private var nativeOrdinal = -1
        private var language = ""
        private var label = ""
        private var positionMs = 0L
        private var probing = false
        private var emit: ((String) -> Unit)? = null
        private var relaySourceId = -1L
        private var relayUrl = ""
        private val responses = LinkedHashMap<Long, Pair<Media, SubtitleResponseSession>>()

        @Synchronized fun registerSource(sourceId: Long, url: String) {
            if (sourceId == relaySourceId && relayUrl == url) return
            responses.values.forEach { it.second.close() }; responses.clear()
            media.clear(); redirects.clear(); pendingHeaders.clear()
            relaySourceId = sourceId; relayUrl = url
            source(url)
        }

        @Synchronized override fun begin(responseId: Long, sourceId: Long, finalUrl: String, status: Int, startOffset: Long) {
            if (sourceId != relaySourceId || status !in setOf(200, 206) || responses.size >= 4) return
            val owner = source(relayUrl)
            responses[responseId] = owner to SubtitleResponseSession(sourceId, responseId, owner.metadata, owner::accept)
        }

        @Synchronized override fun data(responseId: Long, bytes: ByteArray, count: Int) {
            val (owner, reader) = responses[responseId] ?: return
            owner.metadata?.let { reader.seedMetadata(it) }
            reader.feed(bytes, count)
            reader.metadata()?.let { owner.metadata = it }
        }

        @Synchronized override fun end(responseId: Long) { responses.remove(responseId)?.second?.close() }

        private fun canonical(url: String): String {
            var key = url
            repeat(8) { key = redirects[key] ?: return key }
            return key
        }

        private fun source(rawUrl: String): Media {
            val url = canonical(rawUrl)
            media[url]?.let { return it }
            while (media.size >= 2) {
                val stale = media.keys.firstOrNull { it != canonical(selectedUrl) } ?: media.keys.first()
                media.remove(stale)
            }
            return Media(url).also { media[url] = it }
        }

        /** TorrServer's JSON preload/status endpoint shares the .mkv path with the video. */
        private fun allowed(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme in listOf("http", "https") &&
                !Regex("(?:^|&)(?:preload|stat)(?:=|&|$)").containsMatchIn(uri.rawQuery.orEmpty())
        }.getOrDefault(false)

        private fun isMedia(url: String): Boolean = runCatching {
            val path = URI(url).path.orEmpty()
            allowed(url) && (media.containsKey(canonical(url)) || canonical(url) == canonical(selectedUrl) ||
                path.endsWith(".mkv", true) || path == "/stream" || path.contains("/stream/") ||
                redirects.keys.any { canonical(it) == canonical(url) && URI(it).path.orEmpty().endsWith(".mkv", true) })
        }.getOrDefault(false)

        @Synchronized
        fun onRedirect(old: String?, new: String?) {
            if (old == null || new == null || !allowed(old) || !allowed(new)) return
            val previous = canonical(old)
            val target = canonical(new)
            if (previous == target) return
            // A new signed CDN URL is a replacement, not another hop in an ever-growing chain.
            redirects.entries.forEach { if (it.value == previous) it.setValue(target) }
            redirects[old] = target
            redirects[previous] = target
            media.remove(previous)?.let {
                it.url = target
                if (!media.containsKey(target)) media[target] = it
            }
            while (redirects.size > 32) {
                val stale = redirects.keys.first { it != selectedUrl && it != old }
                redirects.remove(stale)
            }
        }

        @Synchronized
        fun onResponse(url: String?, status: Int) {
            if (url == null || status !in 200..299 || !allowed(url)) return
            if (isMedia(url)) {
                pendingHeaders.remove(url)
                source(url).parser.beginResponse()
            }
            else {
                // Remember at most four signature bytes, even before loadedmetadata lets
                // JavaScript tell us the video URL. Extensionless MKVs need their Tracks too.
                pendingHeaders[url] = byteArrayOf()
                while (pendingHeaders.size > 32) pendingHeaders.remove(pendingHeaders.keys.first())
            }
        }

        @Synchronized
        fun onData(url: String?, data: ByteBuffer?) {
            if (url == null || data == null || !allowed(url)) return
            // CEF owns the native buffer only during this callback. Parse synchronously and
            // retain text only; an unbounded async queue would accumulate video chunks.
            val copy = data.duplicate()
            var prefix = pendingHeaders[url] ?: byteArrayOf()
            if (!isMedia(url)) {
                prefix = pendingHeaders[url] ?: return
                val needed = minOf(4 - prefix.size, copy.remaining())
                val signature = prefix + ByteArray(needed).also { copy.duplicate().get(it) }
                if (signature.size < 4) { pendingHeaders[url] = signature; return }
                pendingHeaders.remove(url)
                if (!signature.contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) return
            } else pendingHeaders.remove(url)
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            try {
                source(url).parser.feed(if (prefix.isEmpty()) bytes else prefix + bytes)
            } catch (error: Exception) {
                Log.w(TAG, "Media subtitle framing reset: ${error.javaClass.simpleName}")
                media[canonical(url)]?.parser?.beginResponse()
            }
        }

        private fun deliver(message: JSONObject) {
            // Copy before adding selection state: cached metadata belongs to the source.
            emit?.invoke(JSONObject(message.toString()).put("session", session).put("url", selectedUrl).toString())
        }

        private fun announceSelection(source: Media) {
            val tracks = source.tracks?.optJSONArray("tracks") ?: return
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                if (track.optInt("ordinal", -1) == nativeOrdinal) {
                    deliver(JSONObject().put("type", "selected").put("ordinal", ordinal).put("mime", track.optString("mime")))
                    return
                }
            }
        }

        private fun resolveSelection(source: Media) {
            nativeOrdinal = source.tracks?.optJSONArray("tracks")?.let {
                SubtitleCueCache.resolveTrack(it, ordinal, language, label)
            } ?: ordinal
        }

        private fun replay(announce: Boolean = false) {
            val source = media[canonical(selectedUrl)] ?: return
            if (probing) source.tracks?.let { deliver(it) }
            if (ordinal >= 0) {
                resolveSelection(source)
                if (announce) announceSelection(source)
                deliver(JSONObject().put("type", "cues").put("ordinal", ordinal)
                    .put("replace", true)
                    .put("cues", source.cues.snapshot(nativeOrdinal, positionMs)))
            }
        }

        @Synchronized
        fun handle(payload: JSONObject, callback: (String) -> Unit) {
            emit = callback
            session = payload.optLong("session", 0L)
            when (payload.optString("type")) {
                "subs-open" -> {
                    selectedUrl = payload.optString("url")
                    positionMs = payload.optLong("position", 0L).coerceAtLeast(0)
                    ordinal = -1
                    probing = payload.optBoolean("probe", false)
                    replay()
                }
                "subs-select" -> {
                    selectedUrl = payload.optString("url", selectedUrl)
                    ordinal = payload.optInt("ordinal", -1)
                    language = payload.optString("language")
                    label = payload.optString("label")
                    positionMs = payload.optLong("position", 0L).coerceAtLeast(0)
                    probing = false
                    Log.d(TAG, "shared video stream: selected subtitle $ordinal at $positionMs ms")
                    replay(announce = true)
                }
                "subs-seek", "subs-time" -> {
                    positionMs = payload.optLong("position", 0L).coerceAtLeast(0)
                    replay()
                }
                "subs-stop" -> {
                    ordinal = -1
                    probing = false
                    selectedUrl = ""
                    emit = null
                }
            }
        }

        @Synchronized
        fun shutdown() {
            responses.values.forEach { it.second.close() }; responses.clear()
            relaySourceId = -1L; relayUrl = ""
            media.clear()
            redirects.clear()
            pendingHeaders.clear()
            selectedUrl = ""
            ordinal = -1
            probing = false
            emit = null
        }
    }
}
