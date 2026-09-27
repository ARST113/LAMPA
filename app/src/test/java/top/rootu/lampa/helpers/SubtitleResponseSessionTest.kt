package top.rootu.lampa.helpers
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SubtitleResponseSessionTest {
    private fun el(id:String, body:ByteArray):ByteArray =
        id.chunked(2).map {it.toInt(16).toByte()}.toByteArray()+byteArrayOf((0x80 or body.size).toByte())+body
    private fun metadata() = el("1A45DFA3",el("4282","matroska".toByteArray()))+
        byteArrayOf(0x18,0x53,0x80.toByte(),0x67,0xff.toByte())+
        el("1654AE6B",el("AE",el("D7",byteArrayOf(2))+el("83",byteArrayOf(17))+el("86","S_TEXT/UTF8".toByteArray())))
    private fun cluster(text:String)=el("1F43B675",el("E7",byteArrayOf(0))+
        el("A0",el("A1",byteArrayOf(0x82.toByte(),0,0,0)+text.toByteArray())+el("9B",byteArrayOf(0x03,0xe8.toByte()))))
    @Test fun interleavedResponseParsersShareOnlyMetadataAndRespectCount() {
        val seed=MkvSubtitleStream({})
        seed.feed(metadata())
        seed.feed(el("1549A966",byteArrayOf()))
        val events=ArrayList<JSONObject>()
        val a=SubtitleResponseSession(1,1,seed.metadata()){events.add(JSONObject(it))}
        val b=SubtitleResponseSession(1,2,seed.metadata()){events.add(JSONObject(it))}
        val one=cluster("one");val two=cluster("two")
        for(i in 0 until maxOf(one.size,two.size)){
            if(i<one.size)a.feed(byteArrayOf(one[i],99,99),1)
            if(i<two.size)b.feed(byteArrayOf(two[i],99,99),1)
        }
        assertEquals(setOf("one","two"),events.filter{it.optString("type")=="cues"}.map{it.getJSONArray("cues").getJSONArray(0).getString(2)}.toSet())
        assertTrue(events.filter{it.optString("type")=="cues"}.all{it.getJSONArray("cues").getJSONArray(0).getLong(1)==1000L})
    }
    @Test fun expiredResponseCannotBorrowNewFilmMetadata() {
        val events=ArrayList<JSONObject>()
        SubtitleExtractor.shutdown()
        SubtitleExtractor.registerSource(1,"http://film.test/one.mkv")
        SubtitleExtractor.begin(10,1,"http://film.test/one.mkv",200,0)
        SubtitleExtractor.data(10,metadata(),metadata().size)
        SubtitleExtractor.registerSource(2,"http://film.test/two.mkv")
        SubtitleExtractor.handle(JSONObject().put("type","subs-open").put("url","http://film.test/two.mkv").put("probe",true).put("session",4)){events.add(JSONObject(it))}
        SubtitleExtractor.data(10,metadata(),metadata().size)
        SubtitleExtractor.begin(11,1,"http://film.test/one.mkv",206,0)
        SubtitleExtractor.data(11,metadata(),metadata().size)
        assertTrue(events.isEmpty())
        SubtitleExtractor.begin(12,2,"http://film.test/two.mkv",200,0)
        SubtitleExtractor.data(12,metadata(),metadata().size)
        assertEquals(1,events.count{it.optString("type")=="tracks"})
        SubtitleExtractor.end(12);SubtitleExtractor.shutdown()
    }
    @Test fun incompleteInfoIsNotSharedAndInheritedScaleCannotBeRepublished() {
        val discover=MkvSubtitleStream({})
        discover.feed(metadata())
        assertNull("Tracks alone are not authoritative metadata",discover.metadata())
        val events=ArrayList<JSONObject>()
        val range=SubtitleResponseSession(1,9,discover.metadata()){events.add(JSONObject(it))}
        discover.feed(el("1549A966",el("2AD7B1",byteArrayOf(0x1e,0x84.toByte(),0x80.toByte()))))
        val validated=discover.metadata()!!
        range.seedMetadata(validated)
        range.feed(cluster("scaled"),cluster("scaled").size)
        assertEquals(2000L,events.last{it.optString("type")=="cues"}.getJSONArray("cues").getJSONArray(0).getLong(1))
        assertNull("Seeded readers cannot overwrite source metadata",range.metadata())
    }
}
