package top.rootu.lampa.helpers

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
    private var pendingOrdinal = -1
    private var pendingPositionMs = 0L

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
            }

            applyPending()
        }

        override fun onCues(cueGroup: CueGroup) {
            if (selectedOrdinal < 0) return
            val cues = JSONArray()
            for (cue in cueGroup.cues) {
                val text = cue.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val rawStart = cue.startTimeMs
                val rawEnd = cue.endTimeMs
                val start = if (rawStart == C.TIME_UNSET) -1L else rawStart
                val end = if (rawEnd == C.TIME_UNSET) -1L else rawEnd
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
    fun open(url: String) {
        if (url.isEmpty()) return
        release()

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
        instance.playWhenReady = false
    }

    /** Selects the subtitle track with the given zero based ordinal (order inside the file). */
    fun select(ordinal: Int, positionMs: Long) {
        pendingOrdinal = ordinal
        pendingPositionMs = positionMs
        applyPending()
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
        pendingPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun stop() {
        selectedOrdinal = -1
        pendingOrdinal = -1
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
        pendingOrdinal = -1
    }

    private fun applyPending() {
        val instance = player ?: return
        val group = textGroup ?: return
        val ordinal = pendingOrdinal
        if (ordinal < 0 || ordinal >= group.length) return

        selectedOrdinal = ordinal
        pendingOrdinal = -1

        val parameters = instance.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ordinal))
            .build()
        instance.trackSelectionParameters = parameters

        if (pendingPositionMs > 0) {
            instance.seekTo(pendingPositionMs)
            pendingPositionMs = 0L
        }
        instance.playWhenReady = true
    }

    companion object {
        private const val TAG = "LampaSubtitles"

        @Volatile
        private var current: SubtitleExtractor? = null

        /** Handles one request coming from the injected page bridge. */
        fun handle(context: Context, payload: JSONObject, emit: (String) -> Unit) {
            when (payload.optString("type")) {
                "subs-open" -> {
                    val extractor = current ?: SubtitleExtractor(context, emit).also { current = it }
                    extractor.open(payload.optString("url"))
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

