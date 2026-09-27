package top.rootu.lampa.helpers

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Embedded subtitle reader for the torrent MKVs LAMPA plays.
 *
 * Chromium only turns in-band media tracks into `HTMLVideoElement.textTracks` when the stream
 * codec is WebVTT (media/filters/ffmpeg_demuxer.cc: `codec_id != AV_CODEC_ID_WEBVTT -> continue`).
 * Every subtitle track inside the torrent MKVs this app plays is `subrip`, so the page always
 * sees `video.textTracks.length === 0` and the LAMPA plugins that switch subtitles by index
 * (`tracks.js`, `pidtor.js`) silently do nothing. TorrServer cannot transcode them either
 * (`/gst/settings` reports `built_in: false`).
 *
 * The first attempt used a headless ExoPlayer for this. It discovered the tracks correctly, but
 * the media3 copy bundled inside the Cefrium runtime never invoked the cue callback, so the
 * reader now parses the container itself in [MkvSubtitleReader]: a track list plus
 * `[startMs, endMs, text]` cues are pushed to the page, which paints them into LAMPA's own
 * subtitle overlay - a path Chromium fully supports.
 */
class SubtitleExtractor(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val emit: (String) -> Unit
) {

    private var reader: MkvSubtitleReader? = null
    private var thread: Thread? = null

    @Volatile
    private var url = ""

    @Volatile
    private var wantedOrdinal = -1

    @Volatile
    private var wantedLanguage = ""

    @Volatile
    private var wantedLabel = ""

    @Volatile
    private var playerPositionMs = 0L

    @Volatile
    private var generation = 0

    /** Creates the reader for [url] and starts collecting track information. */
    fun open(url: String, startPositionMs: Long) {
        if (url.isEmpty()) return
        this.url = url
        playerPositionMs = startPositionMs.coerceAtLeast(0L)
        // The page asks for a track right after it opens the stream, but a session that only
        // reloads the media should still end up with subtitles, so the first track is the default.
        restart(if (wantedOrdinal >= 0) wantedOrdinal else 0, "open")
    }

    /** Selects the subtitle track with the given zero based ordinal (order inside the file). */
    fun select(ordinal: Int, language: String, label: String, positionMs: Long) {
        if (url.isEmpty()) return
        wantedOrdinal = ordinal
        wantedLanguage = language
        wantedLabel = label
        playerPositionMs = positionMs.coerceAtLeast(0L)
        restart(ordinal, "select")
    }

    fun play() = Unit

    fun pause() = Unit

    /** The reader may outrun the player, so it is told where playback currently is. */
    fun seek(positionMs: Long) {
        playerPositionMs = positionMs.coerceAtLeast(0L)
        reader?.playerPositionMs = playerPositionMs
    }

    fun stop() {
        generation++
        reader?.stop()
        reader = null
    }

    fun release() {
        stop()
        wantedOrdinal = -1
        url = ""
    }

    @Synchronized
    private fun restart(ordinal: Int, reason: String) {
        generation++
        val token = generation
        reader?.stop()
        reader = null
        thread?.interrupt()

        val target = url
        val instance = MkvSubtitleReader(emit, wantedLanguage, wantedLabel) { Log.d(TAG, it) }
        instance.playerPositionMs = playerPositionMs
        reader = instance
        Log.d(TAG, "$reason: reading subtitles of $target ordinal=$ordinal at $playerPositionMs ms")

        val worker = Thread({
            instance.run(target, ordinal)
            if (token == generation) Log.d(TAG, "subtitle reader finished")
        }, "lampa-subtitles")
        worker.isDaemon = true
        thread = worker
        worker.start()
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
                    extractor.open(
                        payload.optString("url"),
                        payload.optLong("position", 0L)
                    )
                }

                "subs-select" -> current?.select(
                    payload.optInt("ordinal", 0),
                    payload.optString("language", ""),
                    payload.optString("label", ""),
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
