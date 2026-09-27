package top.rootu.lampa.helpers

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Reads SubRip subtitle tracks straight out of a Matroska container over plain HTTP.
 *
 * Chromium only exposes in-band tracks as `HTMLVideoElement.textTracks` when their codec is
 * WebVTT (`media/filters/ffmpeg_demuxer.cc` skips every other subtitle codec), so the LAMPA
 * plugins that switch subtitles by index find an empty list for these torrent files. The
 * headless ExoPlayer in [SubtitleExtractor] did discover the tracks, but the media3 copy that
 * ships inside the Cefrium runtime never delivered cue callbacks, so this reader parses the
 * container itself: track layout and cluster framing are documented by the Matroska/EBML
 * specification and a SubRip track's payload is just its UTF-8 text.
 *
 * The reader never seeks. It streams from the first byte and reports cues as it meets them,
 * which costs as much bandwidth as watching the file. [SubtitleExtractor] stops it as soon as
 * the player is closed.
 */
class MkvSubtitleReader(
    private val emit: (String) -> Unit,
    private val wantedLanguage: String = "",
    private val wantedLabel: String = "",
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {

    private data class SubTrack(
        val number: Long,
        val language: String,
        val name: String,
        val codecId: String
    )

    private val tracks = ArrayList<SubTrack>()
    private var wantedNumber = -1L
    private var emitOrdinal = 0

    @Volatile
    private var stopped = false

    @Volatile
    private var active: okhttp3.Call? = null

    fun stop() {
        stopped = true
        runCatching { active?.cancel() }
        active = null
    }

    /** Blocking; call from a worker thread. [ordinal] indexes the subtitle track list. */
    fun run(url: String, ordinal: Int) {
        try {
            BufferedInputStream(open(url), BUFFER_SIZE).use { stream ->
                if (!readSegment(stream)) return
                if (stopped) return
                if (tracks.isEmpty()) {
                    emitError("no subtitle tracks found")
                    return
                }
                emitTrackList()
                val chosen = resolveTrack(ordinal)
                emitOrdinal = tracks.indexOf(chosen)
                wantedNumber = chosen.number
                log("streaming track ${chosen.number} '${chosen.name}' lang=${chosen.language} codec=${chosen.codecId}")
                emit(JSONObject().put("type", "selected").put("ordinal", emitOrdinal).toString())
                readClusters(stream, segmentSize)
            }
        } catch (e: Throwable) {
            if (!stopped) {
                log("mkv reader failed: ${e.javaClass.simpleName}: ${e.message}")
                emitError(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            active = null
        }
    }

    // ------------------------------------------------------------------ segment

    private var segmentSize = -1L

    private fun readSegment(stream: InputStream): Boolean {
        val header = readHeader(stream) ?: return false
        if (header.id != ID_EBML) {
            log("not an EBML file (id=${java.lang.Long.toHexString(header.id)})")
            return false
        }
        skip(stream, header.size)

        val segment = readHeader(stream) ?: return false
        if (segment.id != ID_SEGMENT) {
            log("no Segment element (id=${java.lang.Long.toHexString(segment.id)})")
            return false
        }
        segmentSize = segment.size

        // SeekHead is the first element of a well formed segment and points at Tracks, so the
        // usual path jumps straight there. Files without one get a bounded sequential scan,
        // which has to wait for the torrent to deliver however much media sits in between.
        val started = System.currentTimeMillis()
        var seekHeadSeen = false
        while (!stopped) {
            if (System.currentTimeMillis() - started > TRACK_SCAN_TIMEOUT_MS) {
                log("gave up looking for Tracks after ${TRACK_SCAN_TIMEOUT_MS / 1000}s")
                return false
            }
            val element = readHeader(stream) ?: break
            when (element.id) {
                ID_SEEK_HEAD -> {
                    seekHeadSeen = true
                    parseSeekHead(stream, element.size)
                }

                ID_TRACKS -> {
                    if (!seekHeadSeen) log("found Tracks $position bytes in without a SeekHead")
                    parseTracks(stream, element.size)
                    return true
                }

                ID_CLUSTER -> {
                    // Tracks always precede the first cluster; if it is missing there is
                    // nothing to report and no way to map track numbers to languages.
                    log("reached a cluster without finding Tracks")
                    return false
                }

                else -> skip(stream, element.size)
            }
        }
        return false
    }

    /** Collects byte offsets of top level elements, used to jump over the media data. */
    private fun parseSeekHead(stream: InputStream, size: Long) {
        val end = if (size < 0) Long.MAX_VALUE else position + size
        var seekId = -1L
        var seekPosition = -1L
        while (!stopped && position < end) {
            val element = readHeader(stream) ?: return
            if (element.id != ID_SEEK) {
                skip(stream, element.size)
                continue
            }
            val entryEnd = if (element.size < 0) Long.MAX_VALUE else position + element.size
            seekId = -1L
            seekPosition = -1L
            while (position < entryEnd) {
                val child = readHeader(stream) ?: break
                when (child.id) {
                    ID_SEEK_ID -> seekId = readUInt(stream, child.size)
                    ID_SEEK_POSITION -> seekPosition = readUInt(stream, child.size)
                    else -> skip(stream, child.size)
                }
            }
            if (seekId == ID_TRACKS && seekPosition >= 0) {
                val delta = seekPosition - (position - segmentStart)
                log("SeekHead -> Tracks at +$seekPosition (jump ${delta} bytes)")
                if (delta > 0) skip(stream, delta)
                return
            }
        }
    }

    private fun parseTracks(stream: InputStream, size: Long) {
        val end = if (size < 0) Long.MAX_VALUE else position + size
        while (!stopped && position < end) {
            val entry = readHeader(stream) ?: return
            if (entry.id != ID_TRACK_ENTRY) {
                skip(stream, entry.size)
                continue
            }
            var number = -1L
            var type = -1L
            var codec = ""
            var language = ""
            var name = ""
            val entryEnd = if (entry.size < 0) Long.MAX_VALUE else position + entry.size
            while (!stopped && position < entryEnd) {
                val child = readHeader(stream) ?: break
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUInt(stream, child.size)
                    ID_TRACK_TYPE -> type = readUInt(stream, child.size)
                    ID_CODEC_ID -> codec = readString(stream, child.size)
                    ID_LANGUAGE -> language = readString(stream, child.size)
                    ID_NAME -> name = readString(stream, child.size)
                    else -> skip(stream, child.size)
                }
            }
            if (type == TYPE_SUBTITLE && number > 0) {
                tracks.add(SubTrack(number, language, name, codec))
                log("subtitle track #${number} lang=${language.ifEmpty { "?" }} name='${name}' codec=$codec")
            }
        }
    }

    private fun readClusters(stream: InputStream, segmentSize: Long) {
        val started = System.currentTimeMillis()
        var clusterTime = 0L
        while (!stopped) {
            if (System.currentTimeMillis() - started > SCAN_TIMEOUT_MS) {
                log("scan window of ${SCAN_TIMEOUT_MS / 1000}s elapsed, stopping")
                return
            }
            val element = readHeader(stream, segmentSize) ?: return
            when (element.id) {
                ID_CLUSTER -> {
                    clusterTime = 0L
                    val end = if (element.size < 0) Long.MAX_VALUE else position + element.size
                    readCluster(stream, end, clusterTime)
                }

                ID_TIMESTAMP -> clusterTime = readUInt(stream, element.size)
                else -> skip(stream, element.size)
            }
        }
    }

    private fun readCluster(stream: InputStream, end: Long, clusterTime: Long) {
        var base = clusterTime
        while (!stopped && position < end) {
            val element = readHeader(stream) ?: return
            when (element.id) {
                ID_TIMESTAMP -> base = readUInt(stream, element.size)
                ID_SIMPLE_BLOCK -> readSimpleBlock(stream, element.size, base)
                ID_BLOCK_GROUP -> readBlockGroup(stream, element.size, base)
                else -> skip(stream, element.size)
            }
        }
        flushCues()
    }

    private fun readSimpleBlock(stream: InputStream, size: Long, base: Long) {
        val start = position
        val track = readVInt(stream)
        val relative = readInt16(stream)
        val flags = readByte(stream)
        val lacing = (flags shr 1) and 0x03
        val contentSize = size - (position - start)
        if (track != wantedNumber) {
            skip(stream, contentSize)
            return
        }
        readLacedPayload(stream, contentSize, lacing, base + relative)
    }

    /** BlockGroup frames subtitle data too, usually together with a BlockDuration. */
    private fun readBlockGroup(stream: InputStream, size: Long, base: Long) {
        val end = if (size < 0) Long.MAX_VALUE else position + size
        var relative = 0L
        var handled = false
        while (!stopped && position < end) {
            val child = readHeader(stream) ?: return
            when (child.id) {
                ID_BLOCK -> {
                    val start = position
                    val track = readVInt(stream)
                    relative = readInt16(stream)
                    val lacing = (readByte(stream) shr 1) and 0x03
                    val contentSize = child.size - (position - start)
                    if (track == wantedNumber) {
                        readLacedPayload(stream, contentSize, lacing, base + relative)
                        handled = true
                    }
                    skip(stream, contentSize)
                }

                else -> skip(stream, child.size)
            }
        }
        if (handled) flushCues()
    }

    /**
     * Matroska can pack several frames of one track into a single block. Subtitle tracks are
     * usually written one frame per block, but a merged file may lace them, and skipping the
     * lace header instead of decoding it loses every cue but the first.
     */
    private fun readLacedPayload(stream: InputStream, contentSize: Long, lacing: Int, baseMs: Long) {
        if (contentSize <= 0) return
        if (lacing == 0) {
            deliver(baseMs, readBytes(stream, contentSize))
            return
        }
        var remaining = contentSize
        val frameCount = readByte(stream) + 1
        remaining--
        val sizes = LongArray(frameCount)
        when (lacing) {
            1 -> { // Xiph lacing: runs of 255 terminated by a shorter byte
                for (i in 0 until frameCount - 1) {
                    var size = 0L
                    var value: Int
                    do {
                        value = readByte(stream)
                        remaining--
                        size += value
                    } while (value == 255)
                    sizes[i] = size
                }
            }

            2 -> { // fixed size lacing: every frame is the same length
                val each = remaining / frameCount
                for (i in 0 until frameCount - 1) sizes[i] = each
            }

            else -> { // EBML lacing: first size is a VINT, the rest are signed deltas
                var size = readVInt(stream)
                remaining -= vintLength(size)
                sizes[0] = size
                for (i in 1 until frameCount - 1) {
                    val delta = readSignedVInt(stream)
                    remaining -= vintLength(delta)
                    size += delta
                    sizes[i] = size
                }
            }
        }
        var consumed = 0L
        for (i in 0 until frameCount - 1) consumed += sizes[i]
        sizes[frameCount - 1] = (remaining - consumed).coerceAtLeast(0L)

        for (i in 0 until frameCount) {
            val frame = readBytes(stream, sizes[i])
            deliver(baseMs, frame)
        }
    }

    private fun vintLength(value: Long): Long {
        var length = 1L
        var limit = 0x80L
        while (length <= 8 && value >= limit) {
            limit = limit shl 7
            length++
        }
        return length
    }

    private fun readSignedVInt(stream: InputStream): Long {
        val first = stream.read()
        if (first < 0) throw EOFException("eof")
        position++
        var mask = 0x80
        var length = 1
        while (length <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        var value = (first and (mask - 1)).toLong()
        for (i in 1 until length) {
            val next = stream.read()
            if (next < 0) throw EOFException("truncated lacing size")
            position++
            value = (value shl 8) or next.toLong()
        }
        // The bias is 2^(7*length-1) - 1, so the stored value shifts by that much.
        return value - ((1L shl (7 * length - 1)) - 1L)
    }

    // ------------------------------------------------------------------ cues

    private val pending = JSONArray()
    private var pendingEndMs = 0L
    private var cueBatches = 0
    private var cueCount = 0

    private fun deliver(timeMs: Long, payload: ByteArray) {
        if (stopped || payload.isEmpty()) return
        val cues = parseSubRip(String(payload, Charset.forName("UTF-8")), timeMs)
        if (cues.length() == 0) return
        for (i in 0 until cues.length()) {
            pending.put(cues.getJSONArray(i))
            val end = cues.getJSONArray(i).getLong(1)
            if (end > pendingEndMs) pendingEndMs = end
        }
        if (playerPositionMs > 0 && pendingEndMs < playerPositionMs + LOOKAHEAD_MS) flushCues()
    }

    private fun flushCues() {
        if (pending.length() == 0) return
        cueBatches++
        cueCount += pending.length()
        val batch = JSONObject()
            .put("type", "cues")
            .put("ordinal", emitOrdinal)
            .put("cues", pending)
            .put("uptoMs", pendingEndMs)
        emit(batch.toString())
        log("cues batch=$cueBatches total=$cueCount upto=${pendingEndMs}ms")
        while (pending.length() > 0) pending.remove(0)
    }

    /**
     * Player clock, pushed in by [SubtitleExtractor] after every seek, lets the reader hold a
     * small buffer instead of shovelling the whole film into the web page at once.
     */
    @Volatile
    var playerPositionMs: Long = 0

    /**
     * Picks the track the page asked for. LAMPA builds its subtitle menu from an ffprobe dump
     * and drops every stream that has no tags (tracks.js: `parse_subs.filter(a => a.tags)`), so
     * its indices only line up with the subtitle order when every track carries a language tag.
     * Matching on the language and title first, and falling back to the plain position, keeps
     * the selection correct in both cases.
     */
    private fun resolveTrack(ordinal: Int): SubTrack {
        val language = wantedLanguage.trim().lowercase()
        val label = wantedLabel.trim().lowercase()
        if (label.isNotEmpty()) {
            tracks.firstOrNull {
                it.name.trim().lowercase() == label ||
                    (language.isNotEmpty() && it.language.trim().lowercase() == language &&
                        it.name.trim().lowercase().startsWith(label))
            }?.let {
                log("matched ordinal $ordinal by label '$wantedLabel' to track ${it.number}")
                return it
            }
        }
        if (language.isNotEmpty()) {
            val byLanguage = tracks.filter { it.language.trim().lowercase() == language }
            if (byLanguage.size == 1) {
                log("matched ordinal $ordinal by language '$wantedLanguage' to track ${byLanguage[0].number}")
                return byLanguage[0]
            }
        }
        if (ordinal >= 0 && ordinal < tracks.size) {
            log("using ordinal $ordinal as the subtitle position (${tracks.size} tracks)")
            return tracks[ordinal]
        }
        log("ordinal $ordinal is outside 0..${tracks.size - 1}, using the first subtitle track")
        return tracks[0]
    }

    private fun emitTrackList() {
        val array = JSONArray()
        tracks.forEachIndexed { index, track ->
            array.put(
                JSONObject()
                    .put("ordinal", index)
                    .put("language", track.language)
                    .put("label", track.name.ifEmpty { track.language })
                    .put("mime", track.codecId)
                    .put("track", track.number)
            )
        }
        emit(JSONObject().put("type", "tracks").put("tracks", array).toString())
        log("subtitle tracks: $array")
    }

    private fun emitError(message: String) {
        emit(JSONObject().put("type", "error").put("message", message).toString())
    }

    // ------------------------------------------------------------------ io

    private fun open(url: String): InputStream {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Lampa/1.13.1")
            .build()
        val call = CLIENT.newCall(request)
        active = call
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code()}")
        }
        log("opened $url (HTTP ${response.code()}, ${response.body()?.contentLength() ?: -1} bytes)")
        return response.body()?.byteStream() ?: throw IllegalStateException("empty body")
    }

    private data class Header(val id: Long, val size: Long)

    private var position = 0L
    private var segmentStart = 0L

    /**
     * Reads one EBML element id and size. Reads inside a container are bounded by the caller's
     * element offsets; a negative size means "unknown length", which is legal EBML and shows up
     * on live-muxed segments.
     */
    private fun readHeader(stream: InputStream, limit: Long = -1L): Header? {
        if (stopped) return null
        if (limit == 0L) return null
        val id = readVInt(stream)
        val rawSize = readVInt(stream)
        val size = if (rawSize == UNKNOWN_SIZE) -1L else rawSize
        if (id == ID_SEGMENT) segmentStart = position
        return Header(id, size)
    }

    private fun readVInt(stream: InputStream): Long {
        val first = stream.read()
        if (first < 0) throw EOFException("eof")
        position++
        var mask = 0x80
        var length = 1
        while (length <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        var value = (first and (mask - 1)).toLong()
        for (i in 1 until length) {
            val next = stream.read()
            if (next < 0) throw EOFException("truncated variable length integer")
            position++
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun readInt16(stream: InputStream): Long {
        val high = readByte(stream)
        val low = readByte(stream)
        return (((high shl 8) or low).toShort()).toLong()
    }

    private fun readByte(stream: InputStream): Int {
        val value = stream.read()
        if (value < 0) throw EOFException("eof")
        position++
        return value
    }

    private fun readUInt(stream: InputStream, size: Long): Long {
        if (size <= 0) return 0L
        if (size > 8) {
            skip(stream, size)
            return 0L
        }
        var value = 0L
        for (i in 0 until size.toInt()) value = (value shl 8) or readByte(stream).toLong()
        return value
    }

    private fun readString(stream: InputStream, size: Long): String {
        if (size <= 0) return ""
        return String(readBytes(stream, size), Charset.forName("UTF-8")).trim()
    }

    private fun readBytes(stream: InputStream, size: Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        val length = minOf(size, MAX_ELEMENT_BYTES.toLong()).toInt()
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = stream.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("truncated element body")
            position += read
            offset += read
        }
        if (size > length) skip(stream, size - length)
        return buffer
    }

    private fun skip(stream: InputStream, size: Long) {
        if (size <= 0) return
        var remaining = size
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("truncated element body")
            position += read
            remaining -= read
        }
    }

    // ------------------------------------------------------------------ subrip

    /** Turns the SubRip payload of one matroska frame into `[startMs, endMs, text]` cues. */
    private fun parseSubRip(text: String, baseMs: Long): JSONArray {
        val out = JSONArray()
        for (raw in text.split(Regex("\r?\n\r?\n"))) {
            val block = raw.trim()
            if (block.isEmpty()) continue
            val lines = block.split(Regex("\r?\n"))
            val timeLine = lines.indexOfFirst { it.contains("-->") }
            if (timeLine < 0) continue
            val range = lines[timeLine].split("-->")
            if (range.size < 2) continue
            val start = parseTime(range[0].trim())
            val end = parseTime(range[1].trim().substringBefore(' '))
            if (start < 0 || end <= start) continue
            val body = lines.drop(timeLine + 1).joinToString("\n").trim()
            if (body.isEmpty()) continue
            out.put(JSONArray().put(baseMs + start).put(baseMs + end).put(cleanTags(body)))
        }
        return out
    }

    /**
     * SubRip files in the wild carry SSA override codes such as `{\an8}` plus the occasional
     * stray font tag; the page renders cue text through `innerHTML`, so the codes have to go
     * before the text reaches it.
     */
    private fun cleanTags(text: String): String = text
        .replace(Regex("\\{\\\\[^}]*}"), "")
        .replace(Regex("</?font[^>]*>"), "")
        .replace(Regex("\\r"), "")
        .trim()

    private fun parseTime(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        if (parts.size < 3) return -1L
        return try {
            val hours = parts[0].trim().toLong()
            val minutes = parts[1].trim().toLong()
            val seconds = parts[2].trim().toDouble()
            hours * 3_600_000L + minutes * 60_000L + (seconds * 1000.0).toLong()
        } catch (e: Exception) {
            -1L
        }
    }

    companion object {
        private const val TAG = "LampaMkv"
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * OkHttp follows the 302/307 redirects TorrServer answers with before it starts
         * streaming; `HttpURLConnection` drops the request headers on a 307 and then never
         * delivers a byte, so the shared client is worth the dependency.
         */
        private val CLIENT: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        private const val TRACK_SCAN_TIMEOUT_MS = 90_000L
        private const val SCAN_TIMEOUT_MS = 30 * 60 * 1000L
        private const val MAX_ELEMENT_BYTES = 8 * 1024 * 1024
        private const val LOOKAHEAD_MS = 120_000L

        private const val UNKNOWN_SIZE = 0x00FFFFFFFFFFFFFFL

        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEK_HEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEK_ID = 0x53ABL
        private const val ID_SEEK_POSITION = 0x53ACL
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_TRACK_NUMBER = 0xD7L
        private const val ID_TRACK_TYPE = 0x83L
        private const val ID_CODEC_ID = 0x86L
        private const val ID_LANGUAGE = 0x22B59CL
        private const val ID_NAME = 0x536EL
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_TIMESTAMP = 0xE7L
        private const val ID_SIMPLE_BLOCK = 0xA3L
        private const val ID_BLOCK_GROUP = 0xA0L
        private const val ID_BLOCK = 0xA1L

        private const val TYPE_SUBTITLE = 0x11L
    }
}
