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
 * HTTP Range and the Cues index let a resumed film start near its playback position without
 * downloading all earlier video. Servers that ignore Range fall back to sequential reads.
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
        val codecId: String,
        val defaultDurationNs: Long
    )

    private val tracks = ArrayList<SubTrack>()
    private var wantedNumber = -1L
    private var emitOrdinal = 0
    private var selectedTrack: SubTrack? = null
    private var timestampScaleNs = 1_000_000L

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
            RangeInput(url).use { stream ->
                if (!readSegment(stream)) return
                if (stopped) return
                if (tracks.isEmpty()) {
                    emitError("no subtitle tracks found")
                    return
                }
                emitTrackList()
                val chosen = resolveTrack(ordinal)
                emitOrdinal = ordinal
                wantedNumber = chosen.number
                selectedTrack = chosen
                if (chosen.codecId != "S_TEXT/UTF8") {
                    emitError("Unsupported subtitle format: ${chosen.codecId}")
                    return
                }
                log("streaming track ${chosen.number} '${chosen.name}' lang=${chosen.language} codec=${chosen.codecId}")
                emit(JSONObject().put("type", "selected").put("ordinal", emitOrdinal).put("mime", chosen.codecId).toString())
                seekToCue(stream)
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
    private var cuesOffset = -1L

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

        // Read metadata before seeking. Info may follow Tracks, and its TimestampScale is
        // needed to interpret both the Cues index and block timestamps.
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
                }

                ID_INFO -> parseInfo(stream, element.size)

                ID_CLUSTER -> {
                    pendingHeader = element
                    return tracks.isNotEmpty()
                }

                else -> skip(stream, element.size)
            }
        }
        return tracks.isNotEmpty()
    }

    /** Collects byte offsets of top level elements, used to jump over the media data. */
    private fun parseSeekHead(stream: InputStream, size: Long) {
        val end = if (size < 0) Long.MAX_VALUE else position + size
        while (!stopped && position < end) {
            val element = readHeader(stream) ?: return
            if (element.id != ID_SEEK) {
                skip(stream, element.size)
                continue
            }
            val entryEnd = if (element.size < 0) Long.MAX_VALUE else position + element.size
            var seekId = -1L
            var seekPosition = -1L
            while (position < entryEnd) {
                val child = readHeader(stream) ?: break
                when (child.id) {
                    ID_SEEK_ID -> seekId = readUInt(stream, child.size)
                    ID_SEEK_POSITION -> seekPosition = readUInt(stream, child.size)
                    else -> skip(stream, child.size)
                }
            }
            // Finish the SeekHead before consuming top-level elements. Jumping here used
            // to skip Info (TimestampScale), and could leave the cursor inside SeekHead.
            if (seekId == ID_CUES && seekPosition >= 0) cuesOffset = segmentStart + seekPosition
        }
    }

    private fun parseInfo(stream: InputStream, size: Long) {
        val end = position + size
        while (!stopped && position < end) {
            val entry = readHeader(stream) ?: return
            if (entry.id == ID_TIMESTAMP_SCALE) {
                timestampScaleNs = readUInt(stream, entry.size)
                require(timestampScaleNs > 0) { "Invalid TimestampScale" }
            } else skip(stream, entry.size)
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
            var defaultDuration = 0L
            val entryEnd = if (entry.size < 0) Long.MAX_VALUE else position + entry.size
            while (!stopped && position < entryEnd) {
                val child = readHeader(stream) ?: break
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUInt(stream, child.size)
                    ID_TRACK_TYPE -> type = readUInt(stream, child.size)
                    ID_CODEC_ID -> codec = readString(stream, child.size)
                    ID_LANGUAGE -> language = readString(stream, child.size)
                    ID_NAME -> name = readString(stream, child.size)
                    ID_DEFAULT_DURATION -> defaultDuration = readUInt(stream, child.size)
                    else -> skip(stream, child.size)
                }
            }
            if (type == TYPE_SUBTITLE && number > 0) {
                tracks.add(SubTrack(number, language, name, codec, defaultDuration))
                log("subtitle track #${number} lang=${language.ifEmpty { "?" }} name='${name}' codec=$codec")
            }
        }
    }

    private fun readClusters(stream: InputStream, segmentSize: Long) {
        while (!stopped) {
            val element = readHeader(stream, if (segmentSize < 0) -1 else segmentStart + segmentSize) ?: return
            when (element.id) {
                ID_CLUSTER -> {
                    val end = if (element.size < 0) Long.MAX_VALUE else position + element.size
                    readCluster(stream, end, 0L)
                }

                ID_INFO -> parseInfo(stream, element.size)
                else -> skip(stream, element.size)
            }
        }
    }

    private fun readCluster(stream: InputStream, end: Long, clusterTime: Long) {
        var base = clusterTime
        while (!stopped && position < end) {
            val element = readHeader(stream) ?: return
            // Unknown-size clusters end at the next segment-level element. Keep its header
            // for the outer loop instead of treating the following cluster as a child.
            if (end == Long.MAX_VALUE && element.id in SEGMENT_ELEMENTS) {
                pendingHeader = element
                flushCues()
                return
            }
            when (element.id) {
                ID_TIMESTAMP -> {
                    base = readUInt(stream, element.size)
                    val timeMs = base * timestampScaleNs / 1_000_000L
                    while (!stopped && timeMs > playerPositionMs + LOOKAHEAD_MS) Thread.sleep(100)
                }
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
        deliverFrames(base + relative, null, readLacedPayload(stream, contentSize, lacing))
    }

    /** BlockGroup frames subtitle data too, usually together with a BlockDuration. */
    private fun readBlockGroup(stream: InputStream, size: Long, base: Long) {
        val end = if (size < 0) Long.MAX_VALUE else position + size
        var relative = 0L
        var frames: List<ByteArray> = emptyList()
        var durationTicks: Long? = null
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
                        frames = readLacedPayload(stream, contentSize, lacing)
                    } else skip(stream, contentSize)
                }

                ID_BLOCK_DURATION -> durationTicks = readUInt(stream, child.size)

                else -> skip(stream, child.size)
            }
        }
        deliverFrames(base + relative, durationTicks, frames)
        flushCues()
    }

    /**
     * Matroska can pack several frames of one track into a single block. Subtitle tracks are
     * usually written one frame per block, but a merged file may lace them, and skipping the
     * lace header instead of decoding it loses every cue but the first.
     */
    private fun readLacedPayload(stream: InputStream, contentSize: Long, lacing: Int): List<ByteArray> {
        require(contentSize >= 0 && contentSize <= MAX_ELEMENT_BYTES) { "Invalid subtitle block size" }
        if (contentSize == 0L) return emptyList()
        if (lacing == 0) {
            return listOf(readBytes(stream, contentSize))
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
                var before = position
                var size = readVInt(stream)
                remaining -= position - before
                sizes[0] = size
                for (i in 1 until frameCount - 1) {
                    before = position
                    val delta = readSignedVInt(stream)
                    remaining -= position - before
                    size += delta
                    sizes[i] = size
                }
            }
        }
        var consumed = 0L
        for (i in 0 until frameCount - 1) consumed += sizes[i]
        sizes[frameCount - 1] = remaining - consumed
        require(sizes.all { it >= 0 } && remaining >= consumed) { "Invalid subtitle lacing" }
        if (lacing == 2) require(remaining % frameCount == 0L) { "Invalid fixed-size lacing" }

        return sizes.map { readBytes(stream, it) }
    }

    private fun deliverFrames(timestamp: Long, durationTicks: Long?, frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        val start = timestamp * timestampScaleNs / 1_000_000L
        val duration = if (durationTicks != null) durationTicks * timestampScaleNs / 1_000_000L
            else (selectedTrack?.defaultDurationNs ?: 0) / 1_000_000L * frames.size
        val each = if (duration > 0) maxOf(1L, duration / frames.size) else 4000L
        frames.forEachIndexed { index, payload -> deliver(start + index * each, each, payload) }
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
        require(length <= 8) { "Invalid EBML lace integer" }
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


    private fun deliver(timeMs: Long, durationMs: Long, payload: ByteArray) {
        if (stopped || payload.isEmpty()) return
        val body = cleanTags(String(payload, Charsets.UTF_8))
        if (body.isBlank()) return
        val end = timeMs + durationMs
        pending.put(JSONArray().put(maxOf(0, timeMs)).put(end).put(body))
        pendingEndMs = maxOf(pendingEndMs, end)
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
            val candidates = tracks.filter { language.isEmpty() || it.language.trim().lowercase() == language }
            val exact = candidates.filter { it.name.trim().lowercase() == label }
            val matches = if (exact.isNotEmpty()) exact else candidates.filter { it.name.trim().lowercase().startsWith(label) }
            val match = tracks.getOrNull(ordinal)?.takeIf { it in matches } ?: matches.firstOrNull()
            match?.let {
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

    private fun seekToCue(stream: RangeInput) {
        if (playerPositionMs <= 0 || cuesOffset < 0 || !stream.supportsRange) return
        val resumeAt = position
        val resumeHeader = pendingHeader
        pendingHeader = null
        stream.seek(cuesOffset)
        position = cuesOffset
        val header = readHeader(stream) ?: throw EOFException("Missing Cues")
        require(header.id == ID_CUES && header.size in 0..MAX_CUES_BYTES) { "Invalid Cues index" }
        val end = position + header.size
        var bestTrackTime = -1L
        var bestTrackOffset = -1L
        while (!stopped && position < end) {
            val point = readHeader(stream) ?: break
            if (point.id != ID_CUE_POINT) { skip(stream, point.size); continue }
            val pointEnd = position + point.size
            var time = -1L
            val offsets = ArrayList<Pair<Long, Long>>()
            while (position < pointEnd) {
                val child = readHeader(stream) ?: break
                when (child.id) {
                    ID_CUE_TIME -> time = readUInt(stream, child.size) * timestampScaleNs / 1_000_000L
                    ID_CUE_TRACK_POSITIONS -> {
                        val entryEnd = position + child.size
                        var track = -1L
                        var offset = -1L
                        while (position < entryEnd) {
                            val entry = readHeader(stream) ?: break
                            when (entry.id) {
                                ID_CUE_TRACK -> track = readUInt(stream, entry.size)
                                ID_CUE_CLUSTER_POSITION -> offset = readUInt(stream, entry.size)
                                else -> skip(stream, entry.size)
                            }
                        }
                        if (offset >= 0) offsets.add(track to (segmentStart + offset))
                    }
                    else -> skip(stream, child.size)
                }
            }
            if (time in 0..playerPositionMs) {
                offsets.forEach { (track, offset) ->
                    if (track == wantedNumber && time > bestTrackTime) { bestTrackTime = time; bestTrackOffset = offset }
                }
            }
        }
        // A video keyframe index cannot show whether an earlier subtitle is still active.
        // Without a cue for this subtitle track, scan from the first cluster and skip media
        // payload through RangeInput. This can take longer, but imposes no duration cutoff.
        val target = if (bestTrackOffset >= 0) bestTrackOffset else resumeAt
        if (bestTrackOffset < 0) pendingHeader = resumeHeader
        stream.seek(target)
        position = target
        log("subtitle seek via Cues to byte $target")
    }

    /** A small random-access HTTP buffer; skipping video payload never downloads that payload. */
    private inner class RangeInput(private val url: String) : InputStream() {
        private var cursor = 0L
        private var bufferStart = 0L
        private var buffer = ByteArray(0)
        private var totalLength = -1L
        private var sequential: InputStream? = null
        private var response: okhttp3.Response? = null
        var supportsRange = true
            private set

        fun seek(target: Long) {
            require(target >= 0) { "Negative stream position" }
            if (supportsRange) cursor = target
            else {
                require(target >= cursor) { "Server does not support seeking" }
                val scratch = ByteArray(BUFFER_SIZE)
                while (cursor < target) {
                    val count = read(scratch, 0, minOf(scratch.size.toLong(), target - cursor).toInt())
                    if (count < 0) throw EOFException("Truncated stream")
                }
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (stopped) throw java.io.InterruptedIOException("Subtitle reading stopped")
            if (sequential != null) {
                val count = sequential!!.read(bytes, offset, length)
                if (count > 0) cursor += count
                return count
            }
            if (totalLength >= 0 && cursor >= totalLength) return -1
            if (cursor < bufferStart || cursor >= bufferStart + buffer.size) fetch()
            if (sequential != null) return read(bytes, offset, length)
            if (buffer.isEmpty()) return -1
            val index = (cursor - bufferStart).toInt()
            val count = minOf(length, buffer.size - index)
            buffer.copyInto(bytes, offset, index, index + count)
            cursor += count
            return count
        }

        private fun fetch() {
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Lampa/1.13.1")
                .header("Range", "bytes=$cursor-${cursor + BUFFER_SIZE - 1}").build()
            val call = CLIENT.newCall(request)
            active = call
            val reply = call.execute()
            if (reply.code() == 200 && cursor == 0L) {
                supportsRange = false
                response = reply
                sequential = BufferedInputStream(reply.body()?.byteStream() ?: throw EOFException("Empty HTTP body"), BUFFER_SIZE)
                return
            }
            reply.use {
                require(reply.code() == 206) { "HTTP ${reply.code()} while seeking subtitles" }
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(reply.header("Content-Range").orEmpty())
                    ?: throw IllegalStateException("Missing Content-Range")
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLong()
                require(start == cursor && end >= start && end - start < BUFFER_SIZE) { "Invalid Content-Range" }
                totalLength = match.groupValues[3].toLongOrNull() ?: -1L
                val input = reply.body()?.byteStream() ?: throw EOFException("Empty HTTP body")
                val chunk = ByteArray((end - start + 1).toInt())
                var count = 0
                while (count < chunk.size) {
                    val got = input.read(chunk, count, chunk.size - count)
                    if (got < 0) throw EOFException("Truncated HTTP range")
                    count += got
                }
                bufferStart = cursor
                buffer = chunk
            }
        }

        override fun close() { response?.close(); sequential = null; active?.cancel() }
    }

    private data class Header(val id: Long, val size: Long)
    private var pendingHeader: Header? = null

    private var position = 0L
    private var segmentStart = 0L

    /**
     * Reads one EBML element id and size. Reads inside a container are bounded by the caller's
     * element offsets; a negative size means "unknown length", which is legal EBML and shows up
     * on live-muxed segments.
     */
    private fun readHeader(stream: InputStream, limit: Long = -1L): Header? {
        if (stopped) return null
        pendingHeader?.let { pendingHeader = null; return it }
        if (limit >= 0 && position >= limit) return null
        val first = stream.read()
        if (first < 0) return null
        position++
        val id = readVIntBody(stream, first, true)
        val rawSize = readVInt(stream)
        val size = if (rawSize == (1L shl (7 * lastVIntLength)) - 1L) -1L else rawSize
        if (id == ID_SEGMENT) segmentStart = position
        return Header(id, size)
    }

    private var lastVIntLength = 0

    private fun readVInt(stream: InputStream): Long {
        val first = stream.read()
        if (first < 0) throw EOFException("eof")
        position++
        return readVIntBody(stream, first, false)
    }

    private fun readVIntBody(stream: InputStream, first: Int, keepMarker: Boolean): Long {
        var mask = 0x80
        var length = 1
        while (length <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        require(length <= if (keepMarker) 4 else 8) { "Invalid EBML integer" }
        lastVIntLength = length
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
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
        if (stream is RangeInput && stream.supportsRange) {
            stream.seek(position + size)
            position += size
            return
        }
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

    /**
     * SubRip files in the wild carry SSA override codes such as `{\an8}` plus the occasional
     * stray font tag; the page renders plain text, so these styling codes are removed
     * before the text reaches it.
     */
    private fun cleanTags(text: String): String = text
        .replace(Regex("\\{\\\\[^}]*\\}"), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?(?:font|b|i|u)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE), "")
        .replace("\r", "")
        .trim()

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
        private const val MAX_ELEMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_CUES_BYTES = 32L * 1024 * 1024
        private const val LOOKAHEAD_MS = 120_000L



        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEK_HEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEK_ID = 0x53ABL
        private const val ID_SEEK_POSITION = 0x53ACL
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
        private const val ID_DEFAULT_DURATION = 0x23E383L
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
        private const val ID_BLOCK_DURATION = 0x9BL
        private const val ID_CUES = 0x1C53BB6BL
        private const val ID_CUE_POINT = 0xBBL
        private const val ID_CUE_TIME = 0xB3L
        private const val ID_CUE_TRACK_POSITIONS = 0xB7L
        private const val ID_CUE_TRACK = 0xF7L
        private const val ID_CUE_CLUSTER_POSITION = 0xF1L

        private const val TYPE_SUBTITLE = 0x11L
        private val SEGMENT_ELEMENTS = setOf(ID_CLUSTER, ID_CUES, ID_INFO, ID_TRACKS,
            ID_SEEK_HEAD, 0x1941A469L, 0x1043A770L, 0x1254C367L)
    }
}
