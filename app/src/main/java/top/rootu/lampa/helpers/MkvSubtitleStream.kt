package top.rootu.lampa.helpers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Demuxes bytes already received by the video. Calls and callbacks are synchronous; the
 * owner serializes feed/reset and creates a new instance when the media URL changes.
 * A response may start inside a block, so framing is recovered at a validated Cluster.
 */
class MkvSubtitleStream(private val emit: (String) -> Unit, private val log: (String) -> Unit = {}) {
    internal data class Track(val number: Long, val ordinal: Int, val language: String, val label: String,
                             val codec: String, val defaultNs: Long)
    private data class Header(val id: Long, val size: Long, val bytes: Int)
    private data class Vint(val value: Long, val bytes: Int)
    private data class Block(val track: Track, val time: Long, val lacing: Int, var frames: List<ByteArray> = emptyList())
    private data class Container(val id: Long, val end: Long, var time: Long? = null,
                                 var duration: Long? = null, var block: Block? = null)
    private data class Pending(val header: Header, var remaining: Long = header.size, var block: Block? = null)

    private var tracks = emptyList<Track>()
    private var tracksKnown = false
    private var discoveredTracks = false
    private var discoveredInfo = false
    class Metadata internal constructor(internal val tracks: List<Track>, internal val scaleNs: Long)
    // Only a response that actually read both masters may publish authoritative metadata.
    fun metadata(): Metadata? = if (discoveredTracks && discoveredInfo) Metadata(tracks.toList(), scaleNs) else null
    fun seedMetadata(metadata: Metadata) {
        if (!discoveredTracks) { tracks = metadata.tracks.toList(); tracksKnown = true }
        if (!discoveredInfo) scaleNs = metadata.scaleNs
    }
    private var scaleNs = 1_000_000L
    private val input = Bytes()
    private val containers = ArrayList<Container>()
    private var pending: Pending? = null
    private var skipRemaining = 0L
    private var position = 0L
    private var scanning = true
    private var reportedRecovery = false
    private var emittedCues = 0

    /** Discards incomplete transport data, retaining Tracks and TimestampScale across seeks. */
    fun beginResponse() {
        input.clear()
        containers.clear()
        pending = null
        skipRemaining = 0
        position = 0
        scanning = true
        reportedRecovery = false
    }

    fun feed(bytes: ByteArray) = feed(bytes, 0, bytes.size)
    fun feed(bytes: ByteArray, start: Int, length: Int) {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        var offset = start
        val limit = start + length
        while (offset < limit) {
            drain()
            // Video bodies are discarded directly, without copying or scanning their bytes.
            if (skipRemaining > 0 && input.size == 0) {
                val count = minOf(skipRemaining, (limit - offset).toLong()).toInt()
                skipRemaining -= count
                position += count
                offset += count
                continue
            }
            val count = minOf(limit - offset, 65536, MAX_BUFFER - input.size)
            if (count == 0) { recover(); consume(1); continue }
            input.append(bytes, offset, count)
            offset += count
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val before = position
            val progressed = try {
                when {
                    skipRemaining > 0 -> {
                        val count = minOf(skipRemaining, input.size.toLong()).toInt()
                        consume(count); skipRemaining -= count; count > 0
                    }
                    scanning -> scan()
                    pending != null -> body(pending!!)
                    else -> nextElement()
                }
            } catch (_: IllegalArgumentException) {
                recover()
                if (position == before && input.size > 0) consume(1)
                true
            }
            if (!progressed) return
        }
    }

    private fun recover() {
        containers.clear(); pending = null; skipRemaining = 0; scanning = true
        if (!reportedRecovery) { log("subtitle stream recovering container framing"); reportedRecovery = true }
    }

    private fun consume(count: Int) { input.discard(count); position += count }
    private fun take(count: Int): ByteArray = input.copy(count).also { consume(count) }

    /** Returns only after the candidate contains a valid leading Timestamp. */
    private fun validatedCluster(header: Header): Boolean? {
        var at = header.bytes
        val limit = if (header.size < 0) Long.MAX_VALUE else at.toLong() + header.size
        while (at < 256 && at.toLong() < limit) {
            val child = input.header(at) ?: return null
            val end = at.toLong() + child.bytes + child.size
            if (child.size < 0 || end > limit) return false
            when (child.id) {
                TIMESTAMP -> {
                    if (child.size !in 1..8) return false
                    if (end > input.size) return null
                    return uint(input.copyRange(at + child.bytes, end.toInt())) >= 0
                }
                0xBFL -> if (child.size != 4L) return false // CRC-32
                0xA7L, 0xABL -> if (child.size !in 1..8) return false // Position / PrevSize
                else -> return false
            }
            if (end > input.size) return null
            at = end.toInt()
        }
        return false
    }

    private fun scan(): Boolean {
        if (input.size < 4) return false
        var at = 0
        while (at <= input.size - 4) {
            val magic = input.id4(at)
            if (magic == EBML || magic == CLUSTER) break
            at++
        }
        if (at > 0) { consume(at); return true }
        val header = input.header(0) ?: return false
        if (header.id == EBML) {
            if (header.size !in 1..MAX_METADATA.toLong()) { consume(1); return true }
            val end = header.bytes + header.size.toInt()
            if (input.size < end) return false
            var recognized = false
            elements(input.copyRange(header.bytes, end)) { child, value ->
                if (child.id == 0x4282L) recognized = text(value, 32) in setOf("matroska", "webm")
            }
            if (!recognized) { consume(1); return true }
            consume(end)
            scanning = false
            return true
        }
        when (validatedCluster(header)) {
            null -> return false
            false -> { consume(1); return true }
            true -> {
                consume(header.bytes)
                containers.add(Container(CLUSTER, endAt(header.size)))
                scanning = false
                return true
            }
        }
    }

    private fun nextElement(): Boolean {
        while (containers.isNotEmpty()) {
            val current = containers.last()
            if (current.end < 0 || position < current.end) break
            require(position == current.end)
            finishContainer()
        }
        val header = input.header(0) ?: return false
        if (containers.lastOrNull()?.let { it.id == CLUSTER && it.end < 0 } == true && header.id in SEGMENT_ELEMENTS) {
            finishContainer()
            return true
        }
        val parent = containers.lastOrNull()
        if (parent?.id == CLUSTER && header.id in SEGMENT_ELEMENTS) { recover(); return true }
        if (header.size < 0) require(header.id == SEGMENT || header.id == CLUSTER)
        if (parent != null && parent.end >= 0) {
            require(header.bytes <= parent.end - position)
            if (header.size >= 0) require(header.size <= parent.end - position - header.bytes)
        }
        consume(header.bytes)
        when (header.id) {
            SEGMENT -> { require(containers.isEmpty()); containers.add(Container(SEGMENT, endAt(header.size))) }
            CLUSTER -> { require(containers.none { it.id == CLUSTER }); containers.add(Container(CLUSTER, endAt(header.size))) }
            GROUP -> { require(parent?.id == CLUSTER); containers.add(Container(GROUP, endAt(header.size))) }
            INFO, TRACKS, EBML -> {
                if (header.size > MAX_METADATA) skipRemaining = header.size else pending = Pending(header)
            }
            TIMESTAMP, DURATION -> {
                require(header.size in 1..8)
                pending = Pending(header)
            }
            SIMPLE_BLOCK, BLOCK -> { require(cluster() != null); pending = Pending(header) }
            else -> skipRemaining = header.size
        }
        return true
    }

    private fun endAt(size: Long): Long {
        if (size < 0) return -1
        require(size <= Long.MAX_VALUE - position)
        return position + size
    }

    private fun finishContainer() {
        val current = containers.removeAt(containers.lastIndex)
        if (current.id == GROUP) current.block?.let { deliver(it, current.duration) }
        if (current.id == CLUSTER) current.time?.let {
            emit(JSONObject().put("type", "cluster").put("timeMs", milliseconds(it)).toString())
        }
    }

    private fun cluster(): Container? = containers.lastOrNull { it.id == CLUSTER }

    private fun body(value: Pending): Boolean {
        val id = value.header.id
        if (id == BLOCK || id == SIMPLE_BLOCK) {
            if (value.block == null) {
                val number = input.vint(0) ?: return false
                val prefix = number.bytes + 3
                require(value.remaining >= prefix)
                if (input.size < prefix) return false
                val relative = ((input[number.bytes] shl 8) or input[number.bytes + 1]).toShort().toLong()
                val lacing = (input[number.bytes + 2] shr 1) and 3
                val track = tracks.firstOrNull { it.number == number.value && it.codec == "S_TEXT/UTF8" }
                consume(prefix); value.remaining -= prefix
                if (track == null || value.remaining > MAX_TEXT) {
                    skipRemaining = value.remaining; pending = null; return true
                }
                val time = cluster()?.time ?: throw IllegalArgumentException("Block before Timestamp")
                value.block = Block(track, time + relative, lacing)
            }
            if (input.size.toLong() < value.remaining) return false
            val block = value.block!!
            block.frames = frames(take(value.remaining.toInt()), block.lacing)
            pending = null
            if (id == SIMPLE_BLOCK) deliver(block, null)
            else {
                val group = containers.lastOrNull()
                require(group?.id == GROUP && group.block == null)
                group!!.block = block
            }
            return true
        }
        if (input.size.toLong() < value.remaining) return false
        val bytes = take(value.remaining.toInt())
        pending = null
        when (id) {
            TIMESTAMP -> { val current = cluster(); require(current != null); current.time = uint(bytes) }
            DURATION -> { val current = containers.lastOrNull(); require(current?.id == GROUP); current!!.duration = uint(bytes) }
            INFO -> {
                var foundScale=1_000_000L
                elements(bytes) { child, data -> if (child.id == 0x2AD7B1L) { val scale=uint(data);require(scale>0);foundScale=scale } }
                scaleNs=foundScale;discoveredInfo=true
            }
            TRACKS -> parseTracks(bytes)
        }
        return true
    }

    private fun parseTracks(bytes: ByteArray) {
        val found = ArrayList<Track>()
        elements(bytes) { entry, data ->
            if (entry.id == 0xAEL && found.size < MAX_TRACKS) {
                var number = 0L; var type = 0L; var defaultNs = 0L
                var language = ""; var label = ""; var codec = ""
                elements(data) { field, content -> when (field.id) {
                    0xD7L -> number = uint(content)
                    0x83L -> type = uint(content)
                    0x23E383L -> defaultNs = uint(content)
                    0x22B59CL -> language = text(content, 128)
                    0x536EL -> label = text(content, 512)
                    0x86L -> codec = text(content, 64)
                } }
                if (type == 17L && number > 0) found.add(Track(number, found.size, language, label, codec, defaultNs))
            }
        }
        tracks = found
        tracksKnown = true
        discoveredTracks = true
        val rows = JSONArray()
        tracks.forEach { rows.put(JSONObject().put("ordinal", it.ordinal).put("track", it.number)
            .put("language", it.language).put("label", it.label.ifEmpty { it.language }).put("mime", it.codec)) }
        emit(JSONObject().put("type", "tracks").put("tracks", rows).toString())
    }

    private fun frames(bytes: ByteArray, lacing: Int): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        if (lacing == 0) return listOf(bytes)
        val count = (bytes[0].toInt() and 255) + 1
        var at = 1
        val sizes = LongArray(count)
        when (lacing) {
            1 -> for (index in 0 until count - 1) {
                var next: Int
                do { require(at < bytes.size); next = bytes[at++].toInt() and 255; sizes[index] += next.toLong() } while (next == 255)
            }
            2 -> {
                require((bytes.size - at) % count == 0)
                for (index in 0 until count - 1) sizes[index] = ((bytes.size - at) / count).toLong()
            }
            3 -> if (count > 1) {
                val first = vint(bytes, at) ?: throw IllegalArgumentException("Truncated lace")
                at += first.bytes; sizes[0] = first.value
                for (index in 1 until count - 1) {
                    val delta = vint(bytes, at) ?: throw IllegalArgumentException("Truncated lace")
                    at += delta.bytes
                    sizes[index] = sizes[index - 1] + delta.value - ((1L shl (7 * delta.bytes - 1)) - 1)
                }
            }
        }
        val used = sizes.sum()
        require(sizes.all { it >= 0 && it <= MAX_TEXT } && used <= bytes.size - at)
        sizes[count - 1] = bytes.size - at - used
        return sizes.map { size -> bytes.copyOfRange(at, at + size.toInt()).also { at += size.toInt() } }
    }

    private fun milliseconds(ticks: Long): Long {
        require(ticks >= -32768 && scaleNs <= Long.MAX_VALUE / maxOf(1, kotlin.math.abs(ticks)))
        return ticks * scaleNs / 1_000_000L
    }

    private fun deliver(block: Block, durationTicks: Long?) {
        if (block.frames.isEmpty()) return
        val start = milliseconds(block.time)
        val duration = durationTicks?.let { milliseconds(it) }
        val each = if (duration != null && duration > 0) maxOf(1, duration / block.frames.size)
            else if (block.track.defaultNs > 0) maxOf(1, block.track.defaultNs / 1_000_000) else 4000L
        val rows = JSONArray()
        var end = start
        block.frames.forEachIndexed { index, bytes ->
            val text = clean(String(bytes, Charsets.UTF_8))
            if (text.isNotBlank()) {
                val time = start + index * each
                require(time <= Long.MAX_VALUE - each)
                end = time + each
                rows.put(JSONArray().put(maxOf(0, time)).put(end).put(text))
            }
        }
        if (rows.length() > 0) {
            val before = emittedCues
            emittedCues += rows.length()
            if (before == 0 || before / 100 != emittedCues / 100) log("subtitle stream cues=$emittedCues ordinal=${block.track.ordinal} uptoMs=$end")
            emit(JSONObject().put("type", "cues").put("ordinal", block.track.ordinal).put("cues", rows).put("uptoMs", end).toString())
        }
    }

    private fun clean(text: String): String = text.replace(Regex("\\{\\\\[^}]*\\}"), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?(?:font|b|i|u)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE), "")
        .replace("\r", "").trim()

    private fun text(bytes: ByteArray, limit: Int) = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8).trim()
    private fun uint(bytes: ByteArray): Long {
        require(bytes.size in 1..8)
        var value = 0L
        bytes.forEach { value = (value shl 8) or (it.toInt() and 255).toLong() }
        require(value >= 0)
        return value
    }

    private fun elements(bytes: ByteArray, action: (Header, ByteArray) -> Unit) {
        var at = 0
        while (at < bytes.size) {
            val header = header(at, bytes.size) { bytes[it].toInt() and 255 } ?: throw IllegalArgumentException("Truncated element")
            val body = at + header.bytes
            require(header.size >= 0 && header.size <= bytes.size - body)
            val end = body + header.size.toInt()
            action(header, bytes.copyOfRange(body, end))
            at = end
        }
    }

    private class Bytes {
        private var bytes = ByteArray(4096)
        private var start = 0
        private var end = 0
        val size get() = end - start
        operator fun get(index: Int) = bytes[start + index].toInt() and 255
        fun clear() { start = 0; end = 0 }
        fun discard(count: Int) { start += count; if (start == end) clear() }
        fun copy(count: Int) = copyRange(0, count)
        fun copyRange(from: Int, to: Int) = bytes.copyOfRange(start + from, start + to)
        fun append(value: ByteArray, offset: Int, count: Int) {
            if (end + count > bytes.size) {
                val target = if (size + count <= bytes.size) bytes else ByteArray(minOf(MAX_BUFFER, maxOf(bytes.size * 2, size + count)))
                bytes.copyInto(target, 0, start, end)
                end = size; start = 0; bytes = target
            }
            value.copyInto(bytes, end, offset, offset + count); end += count
        }
        fun id4(at: Int): Long = (get(at).toLong() shl 24) or (get(at + 1).toLong() shl 16) or
            (get(at + 2).toLong() shl 8) or get(at + 3).toLong()
        fun header(at: Int) = header(at, size) { get(it) }
        fun vint(at: Int) = vint(at, size, false) { get(it) }
    }

    companion object {
        private const val MAX_METADATA = 1024 * 1024
        private const val MAX_TEXT = 64 * 1024
        private const val MAX_BUFFER = MAX_METADATA + 16
        private const val MAX_TRACKS = 128
        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val INFO = 0x1549A966L
        private const val TRACKS = 0x1654AE6BL
        private const val CLUSTER = 0x1F43B675L
        private const val TIMESTAMP = 0xE7L
        private const val GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val DURATION = 0x9BL
        private val SEGMENT_ELEMENTS = setOf(CLUSTER, INFO, TRACKS, 0x1C53BB6BL, 0x114D9B74L, 0x1941A469L, 0x1043A770L, 0x1254C367L)

        private fun vint(bytes: ByteArray, at: Int) = vint(at, bytes.size, false) { bytes[it].toInt() and 255 }
        private fun vint(at: Int, available: Int, id: Boolean, byte: (Int) -> Int): Vint? {
            if (at >= available) return null
            val first = byte(at)
            var length = 1; var mask = 0x80
            while (length <= 8 && first and mask == 0) { mask = mask shr 1; length++ }
            require(length <= if (id) 4 else 8)
            if (available - at < length) return null
            var value = (if (id) first else first and (mask - 1)).toLong()
            for (index in 1 until length) value = (value shl 8) or byte(at + index).toLong()
            return Vint(value, length)
        }
        private fun header(at: Int, available: Int, byte: (Int) -> Int): Header? {
            val id = vint(at, available, true, byte) ?: return null
            val size = vint(at + id.bytes, available, false, byte) ?: return null
            return Header(id.value, if (size.value == (1L shl (7 * size.bytes)) - 1) -1 else size.value, id.bytes + size.bytes)
        }
    }
}
