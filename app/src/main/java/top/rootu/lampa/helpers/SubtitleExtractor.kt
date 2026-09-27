package top.rootu.lampa.helpers

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Headless ExoPlayer used purely as an embedded-subtitle reader.
 *
 * Chromium only turns in-band media tracks into `HTMLVideoElement.textTracks` when the stream
 * codec is WebVTT (media/filters/ffmpeg_demuxer.cc: `codec_id != AV_CODEC_ID_WEBVTT -> continue`).
 * Every subtitle track inside the torrent MKVs this app plays is `subrip`, so the page always sees
 * `video.textTracks.length === 0` and the LAMPA plugins that switch subtitles by index
 * (`tracks.js`, `pidtor.js`) silently do nothing.
 *
 * ExoPlayer reads the very same stream itself, understands Matroska + SubRip, and reports parsed
 * cues through [Player.Listener.onCues]. Those cues are forwarded to the page, which paints them
 * into LAMPA's own subtitle overlay вЂ” a path Chromium fully supports.
 */
class SubtitleExtractor(
    private val context: Context,
    private val emit: (String) -> Unit
) {

    private var player: ExoPlayer? = null
    private var selector: DefaultTrackSelector? = null
    private var textGroup: Tracks.Group? = null
    private var reportedCount = -1

    private var selectedOrdinal = -1

    /** Ordinal the page asked for. It survives until the tracks actually exist. */
    private var wantedOrdinal = -1
    private var wantedPositionMs = 0L

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT } ?: return
            if (group.length == 0) return
            textGroup = group

            if (group.length != reportedCount) {
                reportedCount = group.length
                val array = JSONArray()
                for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    array.put(
                        JSONObject()
                            .put("ordinal", index)
                            .put("language", format.language ?: "")
                            .put("label", format.label ?: "")
                            .put("mime", format.sampleMimeType ?: "")
                    )
                }
                emit(JSONObject().put("type", "tracks").put("tracks", array).toString())
                Log.d(TAG, "tracks: " + array.toString())
            }

            applyWanted()
        }

        override fun onCues(cueGroup: CueGroup) {
            if (selectedOrdinal < 0) return
            logTimeFields()
            val cues = JSONArray()
            for (cue in cueGroup.cues) {
                val text = cue.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val start = cueTimeMs(cue, "startTimeMs", "startTimeUs")
                val end = cueTimeMs(cue, "endTimeMs", "endTimeUs")
                cues.put(JSONArray().put(start).put(end).put(text))
            }
            if (cues.length() == 0) return
            emit(
                JSONObject()
                    .put("type", "cues")
                    .put("ordinal", selectedOrdinal)
                    .put("cues", cues)
                    .toString()
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            Log.d(TAG, "state=${stateName(state)}")
            if (state == Player.STATE_READY) applyWanted()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "subtitle reader failed: ${error.errorCodeName} ${error.message}")
            emit(
                JSONObject()
                    .put("type", "error")
                    .put("message", error.errorCodeName + " " + (error.message ?: ""))
                    .toString()
            )
        }
    }

    /** Creates the reader for [url] and starts collecting track information. */
    fun open(url: String, startPositionMs: Long) {
        if (url.isEmpty()) return
        release()
        wantedPositionMs = startPositionMs.coerceAtLeast(0L)

        val rendererLooper = android.os.Looper.getMainLooper()
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = trackSelector.parameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        selector = trackSelector

        val instance = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLooper(rendererLooper)
            .build()
        player = instance
        instance.addListener(listener)
        instance.setMediaItem(MediaItem.fromUri(url))
        instance.prepare()
        if (wantedPositionMs > 0) instance.seekTo(wantedPositionMs)
        instance.playWhenReady = false
        Log.d(TAG, "open url=$url start=$wantedPositionMs")
    }

    /** Selects the subtitle track with the given zero based ordinal (order inside the file). */
    fun select(ordinal: Int, positionMs: Long) {
        wantedOrdinal = ordinal
        wantedPositionMs = positionMs.coerceAtLeast(0L)
        applyWanted()
    }

    fun play() {
        player?.playWhenReady = true
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun seek(positionMs: Long) {
        val instance = player ?: return
        instance.seekTo(positionMs.coerceAtLeast(0L))
        wantedPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun stop() {
        selectedOrdinal = -1
        wantedOrdinal = -1
        player?.playWhenReady = false
    }

    fun release() {
        try {
            player?.removeListener(listener)
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed: ${e.message}")
        }
        player = null
        selector = null
        textGroup = null
        reportedCount = -1
        selectedOrdinal = -1
        wantedOrdinal = -1
        wantedPositionMs = 0L
    }

    /**
     * Applies [wantedOrdinal] as soon as both the player and its text tracks exist.
     * The page asks for a track the moment the menu entry is tapped, which is usually
     * before ExoPlayer has finished opening the stream, so the request has to be
     * remembered rather than dropped.
     */
    private fun applyWanted() {
        val instance = player ?: return
        val group = textGroup ?: return
        val ordinal = wantedOrdinal
        if (ordinal < 0) return
        if (ordinal >= group.length) {
            Log.w(TAG, "subtitle ordinal $ordinal out of range 0..${group.length - 1}")
            emit(
                JSONObject()
                    .put("type", "error")
                    .put("message", "subtitle ordinal $ordinal out of range 0..${group.length - 1}")
                    .toString()
            )
            return
        }

        selectedOrdinal = ordinal

        val parameters = instance.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ordinal))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        instance.trackSelectionParameters = parameters

        if (wantedPositionMs > 0) {
            instance.seekTo(wantedPositionMs)
        }
        instance.playWhenReady = true

        val format = group.getTrackFormat(ordinal)
        logTimeFields()
        Log.d(
            TAG,
            "selected ordinal=$ordinal language=${format.language} label=${format.label} " +
                "mime=${format.sampleMimeType} seek=$wantedPositionMs " + cueTimingProfile()
        )
        emit(
            JSONObject()
                .put("type", "selected")
                .put("ordinal", ordinal)
                .put("language", format.language ?: "")
                .put("mime", format.sampleMimeType ?: "")
                .toString()
        )
    }

    /** Human readable description of how cue timings are being read; used for diagnostics. */
    fun cueTimingProfile(): String {
        val start = startField?.name ?: "none"
        val end = endField?.name ?: "none"
        return "start=$start end=$end"
    }

    companion object {
        private const val TAG = "LampaSubtitles"

        /**
         * The Cefrium SDK bundles its own copy of the media3 classes, and the version it
         * ships exposes the cue timings differently from the media3 artifact declared in
         * build.gradle (`startTimeMs`/`endTimeMs` on recent releases, `startTimeUs`/
         * `endTimeUs` on older ones, sometimes not public at all). Reading them through
         * reflection keeps this class compiling and working against whatever copy ends
         * up on the classpath; the resolved field names are logged once.
         */
        private val startField: java.lang.reflect.Field? by lazy {
            findTimeField("startTimeMs", "startTimeUs", "startTime")
        }
        private val endField: java.lang.reflect.Field? by lazy {
            findTimeField("endTimeMs", "endTimeUs", "endTime")
        }
        private var fieldsLogged = false

        private fun findTimeField(vararg names: String): java.lang.reflect.Field? {
            var type: Class<*>? = Cue::class.java
            while (type != null && type != Any::class.java) {
                for (name in names) {
                    try {
                        val field = type.getDeclaredField(name)
                        field.isAccessible = true
                        return field
                    } catch (_: NoSuchFieldException) {
                    }
                }
                type = type.superclass
            }
            return null
        }

        private fun cueTimeMs(cue: Cue, vararg names: String): Long {
            val field = when {
                names.contains("startTimeMs") -> startField
                names.contains("endTimeMs") -> endField
                else -> null
            }
            if (field == null) return -1L
            return try {
                val value = field.getLong(cue)
                if (value == C.TIME_UNSET) -1L
                else if (field.name.endsWith("Us")) value / 1000L
                else value
            } catch (e: Exception) {
                -1L
            }
        }

        private fun logTimeFields() {
            if (fieldsLogged) return
            fieldsLogged = true
            Log.d(TAG, "cue timing fields: start=${startField?.name} end=${endField?.name}")
        }

        private fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "unknown($state)"
        }

        @Volatile
        private var current: SubtitleExtractor? = null

        /** Handles one request coming from the injected page bridge. */
        fun handle(context: Context, payload: JSONObject, emit: (String) -> Unit) {
            when (payload.optString("type")) {
                "subs-open" -> {
                    val extractor = current ?: SubtitleExtractor(context, emit).also { current = it }
                    extractor.open(
                        payload.optString("url"),
                        payload.optLong("position", 0L)
                    )
                }

                "subs-select" -> current?.select(
                    payload.optInt("ordinal", 0),
                    payload.optLong("position", 0L)
                )

                "subs-play" -> current?.play()

                "subs-pause" -> current?.pause()

                "subs-seek" -> current?.seek(payload.optLong("position", 0L))

                "subs-stop" -> current?.stop()
            }
        }

        fun shutdown() {
            current?.release()
            current = null
        }
    }
}

