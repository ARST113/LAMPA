package top.rootu.lampa.helpers

import org.json.JSONArray
import org.json.JSONObject

/** Text only, shared by all track selections for one media URL. Never stores video bytes. */
class SubtitleCueCache(private val maxCues: Int = 12000, private val maxTextChars: Int = 2_000_000) {
    private data class Cue(val ordinal: Int, val start: Long, val end: Long, val text: String)
    private val cues = LinkedHashMap<Cue, Unit>()
    private var textChars = 0

    fun accept(message: JSONObject) {
        if (message.optString("type") != "cues") return
        val ordinal = message.optInt("ordinal", -1)
        if (ordinal < 0) return
        val batch = message.optJSONArray("cues") ?: return
        for (index in 0 until batch.length()) {
            val data = batch.optJSONArray(index) ?: continue
            val start = data.optLong(0, -1)
            val end = data.optLong(1, -1)
            val text = data.optString(2)
            if (start < 0 || end <= start || text.isBlank() || text.length > 65536) continue
            val cue = Cue(ordinal, start, end, text)
            if (cues.put(cue, Unit) == null) textChars += text.length
            while (cues.size > maxCues || textChars > maxTextChars) {
                val first = cues.keys.first()
                textChars -= first.text.length
                cues.remove(first)
            }
        }
    }

    /** Includes an overlapping cue and a bounded lookahead; backward seeks retain cached text. */
    fun snapshot(ordinal: Int, positionMs: Long): JSONArray {
        val result = JSONArray()
        cues.keys.asSequence().filter {
            it.ordinal == ordinal && it.end > positionMs && it.start <= positionMs + 120000
        }.sortedBy { it.start }.forEach {
            result.put(JSONArray().put(it.start).put(it.end).put(it.text))
        }
        return result
    }

    companion object {
        /** Some plugins omit untagged tracks and therefore shift their menu indices. */
        fun resolveTrack(tracks: JSONArray, ordinal: Int, language: String, label: String): Int {
            val all = (0 until tracks.length()).map { tracks.getJSONObject(it) }
            val lang = language.trim().lowercase()
            val title = label.trim().lowercase()
            val candidates = all.filter { lang.isEmpty() || it.optString("language").trim().lowercase() == lang }
            if (title.isNotEmpty()) {
                val exact = candidates.filter { it.optString("label").trim().lowercase() == title }
                val matches = exact.ifEmpty { candidates.filter { it.optString("label").trim().lowercase().startsWith(title) } }
                (matches.firstOrNull { it.optInt("ordinal") == ordinal } ?: matches.firstOrNull())?.let {
                    return it.optInt("ordinal", ordinal)
                }
            }
            if (lang.isNotEmpty() && candidates.size == 1) return candidates[0].optInt("ordinal", ordinal)
            return ordinal
        }
    }
}
