package top.rootu.lampa.helpers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubtitleCueCacheTest {
    private fun batch(ordinal: Int, start: Long, end: Long, text: String) = JSONObject()
        .put("type", "cues").put("ordinal", ordinal)
        .put("cues", JSONArray().put(JSONArray().put(start).put(end).put(text)))

    @Test fun switchingTracksImmediatelyReplaysCuesAlreadyDownloadedWithVideo() {
        val cache = SubtitleCueCache()
        cache.accept(batch(0, 1000, 5000, "Русский"))
        cache.accept(batch(1, 1000, 5000, "English"))
        assertEquals("Русский", cache.snapshot(0, 2000).getJSONArray(0).getString(2))
        assertEquals("English", cache.snapshot(1, 2000).getJSONArray(0).getString(2))
        assertEquals(0, cache.snapshot(2, 2000).length())
    }

    @Test fun repeatedHttpRangesDoNotDuplicateTextAndSeekingBackReusesCachedCues() {
        val cache = SubtitleCueCache()
        val first = batch(0, 1000, 5000, "One")
        cache.accept(first); cache.accept(first)
        cache.accept(batch(0, 60000, 65000, "Later"))
        assertEquals(1, cache.snapshot(0, 62000).length())
        assertEquals("One", cache.snapshot(0, 2000).getJSONArray(0).getString(2))
    }

    @Test fun boundedCacheDropsOldestCuesAndRejectsInvalidIntervals() {
        val cache = SubtitleCueCache(maxCues = 2)
        cache.accept(batch(0, 1000, 2000, "One"))
        cache.accept(batch(0, 2000, 3000, "Two"))
        cache.accept(batch(0, 3000, 4000, "Three"))
        cache.accept(batch(0, 8000, 7000, "Invalid"))
        val cues = cache.snapshot(0, 0)
        assertEquals(2, cues.length())
        assertEquals("Two", cues.getJSONArray(0).getString(2))
    }

    @Test fun textMemoryIsBoundedEvenWithLongCues() {
        val cache = SubtitleCueCache(maxTextChars = 6)
        cache.accept(batch(0, 1000, 5000, "Four"))
        cache.accept(batch(1, 1000, 5000, "Text"))
        assertEquals(0, cache.snapshot(0, 0).length())
        assertEquals(1, cache.snapshot(1, 0).length())
    }

    @Test fun pluginFilteredMenuMatchesTrackTitleBeforeItsShiftedIndex() {
        val tracks = JSONArray("""[{"ordinal":0,"language":"rus","label":"Forced"},
            {"ordinal":1,"language":"rus","label":"Full"},
            {"ordinal":2,"language":"eng","label":"English"}]""")
        assertEquals(1, SubtitleCueCache.resolveTrack(tracks, 0, "rus", "Full"))
        assertEquals(2, SubtitleCueCache.resolveTrack(tracks, 0, "eng", ""))
        assertEquals(0, SubtitleCueCache.resolveTrack(tracks, 0, "", ""))
    }
}
